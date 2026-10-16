/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "status.h"

#include <cups/cups.h>
#include <cups/sidechannel.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

static const char GET_STATUS[] =
    "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\">"
    "<ivec:contents><ivec:operation>GetStatus</ivec:operation><ivec:param_set servicetype=\"print\">"
    "</ivec:param_set></ivec:contents></cmd>";

static struct {
    pthread_t thread;
    int running;
    volatile int stop;
    pthread_mutex_t lock;
    ivec_status_t last;
    time_t last_status;      /* when a status document last arrived */
    int any_data;            /* the back channel has delivered something */
    char reason[48];         /* reason currently set on the printer */
} mon = { .lock = PTHREAD_MUTEX_INITIALIZER };

/* copy the text of the last <tag>value< occurrence */
static int last_value(const char *buf, const char *tag, char *out, size_t outlen) {
    const char *p = buf, *hit = NULL;
    size_t tl = strlen(tag);
    while ((p = strstr(p, tag))) { hit = p; p += tl; }
    if (!hit) return 0;
    hit += tl;
    const char *e = strchr(hit, '<');
    if (!e) return 0;
    size_t n = (size_t)(e - hit) < outlen - 1 ? (size_t)(e - hit) : outlen - 1;
    memcpy(out, hit, n);
    out[n] = 0;
    return 1;
}

int ivec_parse_status(const char *buf, ivec_status_t *out) {
    ivec_status_t st = { "", "", "" };
    if (!last_value(buf, "<ivec:status>", st.status, sizeof(st.status))) return 0;
    last_value(buf, "<ivec:status_detail>", st.detail, sizeof(st.detail));
    if (!last_value(buf, "<ivec:current_support_code>", st.support_code, sizeof(st.support_code)))
        last_value(buf, "<ivec:error_code>", st.support_code, sizeof(st.support_code));
    *out = st;
    return 1;
}

int ivec_status_reason(const ivec_status_t *st, const char **reason, char *msg, int msglen) {
    char code[40] = "";
    if (st->support_code[0] && strcmp(st->support_code, "0"))
        snprintf(code, sizeof(code), " (support code %s)", st->support_code);
    *reason = "";
    if (!strcmp(st->detail, "MediaEmpty")) {
        *reason = "media-empty-error";
        snprintf(msg, msglen, "Out of paper%s. Load paper, then press the printer's Black/Color (Start/OK) button.", code);
    } else if (!strcmp(st->detail, "MediaJam")) {
        *reason = "media-jam-error";
        snprintf(msg, msglen, "Paper jam%s. Remove the paper, then press the printer's Stop/OK button.", code);
    } else if (!strcmp(st->detail, "DoorOpen")) {
        *reason = "door-open-error";
        snprintf(msg, msglen, "A cover is open%s. Close it to continue.", code);
    } else if (!strcmp(st->detail, "MarkerSupplyAttention")) {
        *reason = "marker-supply-low-warning";
        snprintf(msg, msglen, "Check the ink%s.", code);
        return 0;
    } else if (!strcmp(st->status, "stopped") || !strcmp(st->status, "notready") ||
               !strcmp(st->detail, "AttentionRequired")) {
        *reason = "other-error";
        snprintf(msg, msglen, "The printer needs attention%s. Check its lamps or display.", code);
    } else {
        snprintf(msg, msglen, "%s", !strcmp(st->status, "idle") ? "Ready" : "Printing");
        return 0;
    }
    return 1;
}

static void report(const ivec_status_t *st) {
    const char *reason;
    char msg[256];
    int attention = ivec_status_reason(st, &reason, msg, sizeof(msg));

    pthread_mutex_lock(&mon.lock);
    if (strcmp(reason, mon.reason)) {
        if (mon.reason[0]) fprintf(stderr, "STATE: -%s\n", mon.reason);
        if (reason[0]) fprintf(stderr, "STATE: +%s\n", reason);
        snprintf(mon.reason, sizeof(mon.reason), "%s", reason);
        fprintf(stderr, "%s: %s\n", attention ? "WARNING" : "INFO", msg);
    }
    pthread_mutex_unlock(&mon.lock);
}

static void log_raw(const char *what, const char *data, size_t n) {
    char clean[400];
    size_t j = 0;
    for (size_t i = 0; i < n && j < sizeof(clean) - 1; i++)
        clean[j++] = (data[i] >= 32 && data[i] < 127) ? data[i] : '.';
    clean[j] = 0;
    fprintf(stderr, "DEBUG: ivec-%s (%zu bytes): %s\n", what, n, clean);
}

static void *monitor(void *arg) {
    (void)arg;
    static char acc[65536];
    size_t accn = 0;
    char buf[4096], devid[1024] = "", cur[1024];
    int tick = 0;

    while (!mon.stop) {
        ssize_t n = cupsBackChannelRead(buf, sizeof(buf) - 1, 1.0);
        if (n > 0) {
            mon.any_data = 1;
            log_raw("backchannel", buf, (size_t)n);
            if (accn + (size_t)n >= sizeof(acc)) accn = 0;
            memcpy(acc + accn, buf, (size_t)n);
            accn += (size_t)n;
            acc[accn] = 0;
            ivec_status_t st;
            char *end = strstr(acc, "</cmd>") ? acc : NULL;
            if (end && ivec_parse_status(acc, &st)) {
                pthread_mutex_lock(&mon.lock);
                mon.last = st;
                mon.last_status = time(NULL);
                pthread_mutex_unlock(&mon.lock);
                report(&st);
                /* keep only what follows the last complete document */
                char *tail = acc, *p;
                while ((p = strstr(tail, "</cmd>"))) tail = p + 6;
                accn = strlen(tail);
                memmove(acc, tail, accn + 1);
            }
        } else if (n < 0) {
            sleep(1);   /* no back channel (unidirectional queue) */
        }

        /* the device ID answers even while the data pipe is stalled */
        if (++tick % 3 == 0) {
            int len = sizeof(cur) - 1;
            if (cupsSideChannelDoRequest(CUPS_SC_CMD_GET_DEVICE_ID, cur, &len, 2.0) == CUPS_SC_STATUS_OK &&
                len > 0) {
                cur[len] = 0;
                if (strcmp(cur, devid)) {
                    log_raw("device-id", cur, (size_t)len);
                    snprintf(devid, sizeof(devid), "%s", cur);
                }
            }
        }
    }
    return NULL;
}

void ivec_monitor_start(void) {
    /* Only real USB jobs: CUPS sets DEVICE_URI and hands the back channel
     * over on fd 3.  cupsfilter/tests and network queues are left alone. */
    const char *uri = getenv("DEVICE_URI");
    if (getenv("IVEC_NO_MONITOR") || !uri || strncmp(uri, "usb:", 4) || fcntl(3, F_GETFD) < 0) return;
    mon.stop = 0;
    mon.running = pthread_create(&mon.thread, NULL, monitor, NULL) == 0;
}

void ivec_monitor_finish(int timeout) {
    if (!mon.running) return;
    time_t start = time(NULL), asked = 0;

    /* Ask for status between jobs; printers that never answer are left
     * alone after a few seconds. */
    for (;;) {
        time_t now = time(NULL);
        pthread_mutex_lock(&mon.lock);
        ivec_status_t st = mon.last;
        time_t seen = mon.last_status;
        int attention = mon.reason[0] && strcmp(mon.reason, "marker-supply-low-warning");
        pthread_mutex_unlock(&mon.lock);

        if (seen >= start && !strcmp(st.status, "idle") && !attention) break;
        if (!mon.any_data && now - start >= 6) break;
        if (now - start >= timeout) break;
        if (now - asked >= 3) {
            fwrite(GET_STATUS, 1, sizeof(GET_STATUS) - 1, stdout);
            fflush(stdout);
            asked = now;
        }
        sleep(1);
    }

    mon.stop = 1;
    pthread_join(mon.thread, NULL);
    mon.running = 0;
    pthread_mutex_lock(&mon.lock);
    if (mon.reason[0]) fprintf(stderr, "STATE: -%s\n", mon.reason);
    mon.reason[0] = 0;
    pthread_mutex_unlock(&mon.lock);
}
