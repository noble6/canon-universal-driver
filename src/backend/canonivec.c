/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * canonivec - CUPS USB backend for Canon IVEC inkjets.
 *
 * Why not the stock usb backend: Canon printers stall the data pipe while
 * waiting for paper.  The stock backend then sits in an endless bulk write,
 * ignores SIGTERM and cannot reset the printer, so cancelling a job leaves
 * the printer waiting until its Stop button is pressed.  This backend writes
 * with short timeouts, reads the printer's status messages the whole time,
 * and on cancel sends the printer-class soft reset Canon's own software
 * uses, which aborts the job on the printer.
 *
 * URI: canonivec://Canon/<model>?serial=<serial>
 */
#define _GNU_SOURCE
#include <cups/backend.h>
#include <cups/cups.h>
#include <errno.h>
#include <fcntl.h>
#include <libusb.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "status.h"

#define CANON_VID     0x04a9
#define CHUNK         (64 * 1024)
#define WRITE_TIMEOUT 1000   /* ms; short so cancel stays responsive */

typedef struct {
    libusb_device_handle *h;
    int config;              /* configuration index */
    int iface, alt;
    unsigned char ep_out, ep_in;
    char serial[128];
    char device_id[1024];
} printer_t;

static volatile sig_atomic_t g_cancel;
static char g_job_id[16] = "00000002";
static char g_service[16] = "print";

/* remember the job id and service type the stream is using, for CancelJob
 * (only looked for in the command part; PWG data rarely contains '<ivec') */
static void note_job(const unsigned char *data, int len) {
    static const char id_tag[] = "<ivec:jobID>", svc_tag[] = "servicetype=\"";
    const char *d = (const char *)data, *end = d + len, *q;
    if ((q = memmem(d, len, id_tag, sizeof(id_tag) - 1)) && end - q >= (long)sizeof(id_tag) - 1 + 8) {
        memcpy(g_job_id, q + sizeof(id_tag) - 1, 8);
        g_job_id[8] = 0;
    }
    if ((q = memmem(d, len, svc_tag, sizeof(svc_tag) - 1))) {
        q += sizeof(svc_tag) - 1;
        int n = 0;
        while (n < (int)sizeof(g_service) - 1 && q + n < end && q[n] != '"') n++;
        memcpy(g_service, q, n);
        g_service[n] = 0;
    }
}

static volatile int g_reader_stop;
static volatile int g_printer_cancel;   /* job ended with the printer's Stop button */
static printer_t g_prn;

static void on_term(int sig) {
    (void)sig;
    g_cancel = 1;
}

/* ---- USB helpers ---- */

static int find_printer_iface(libusb_device *dev, printer_t *p) {
    struct libusb_config_descriptor *conf;
    if (libusb_get_active_config_descriptor(dev, &conf) != 0 &&
        libusb_get_config_descriptor(dev, 0, &conf) != 0)
        return -1;
    int found = -1;
    for (int i = 0; i < conf->bNumInterfaces && !(found == 0 && p->ep_in); i++) {
        for (int a = 0; a < conf->interface[i].num_altsetting; a++) {
            const struct libusb_interface_descriptor *d = &conf->interface[i].altsetting[a];
            if (d->bInterfaceClass != LIBUSB_CLASS_PRINTER || d->bInterfaceSubClass != 1) continue;
            unsigned char out = 0, in = 0;
            for (int e = 0; e < d->bNumEndpoints; e++) {
                const struct libusb_endpoint_descriptor *ep = &d->endpoint[e];
                if ((ep->bmAttributes & 3) != LIBUSB_TRANSFER_TYPE_BULK) continue;
                if (ep->bEndpointAddress & LIBUSB_ENDPOINT_IN) { if (!in) in = ep->bEndpointAddress; }
                else if (!out) out = ep->bEndpointAddress;
            }
            if (!out) continue;
            /* take the first usable setting, but upgrade to a bidirectional one */
            if (found == 0 && (p->ep_in || !in)) continue;
            p->config = conf->bConfigurationValue - 1;
            p->iface = d->bInterfaceNumber;
            p->alt = d->bAlternateSetting;
            p->ep_out = out;
            p->ep_in = in;
            found = 0;
        }
    }
    libusb_free_config_descriptor(conf);
    return found;
}

static int read_device_id(printer_t *p) {
    unsigned char buf[1024];
    int n = libusb_control_transfer(p->h, LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                    0, p->config, (p->iface << 8) | p->alt, buf, sizeof(buf), 5000);
    if (n < 2) return -1;
    int len = (buf[0] << 8) | buf[1];
    if (len > n || len < 2) len = n;
    len -= 2;
    if (len >= (int)sizeof(p->device_id)) len = sizeof(p->device_id) - 1;
    memcpy(p->device_id, buf + 2, len);
    p->device_id[len] = 0;
    return 0;
}

static void field(const char *id, const char *key, char *out, size_t outlen) {
    out[0] = 0;
    const char *p = strstr(id, key);
    if (!p) return;
    p += strlen(key);
    size_t n = strcspn(p, ";");
    if (n >= outlen) n = outlen - 1;
    memcpy(out, p, n);
    out[n] = 0;
}

static void uri_escape(const char *in, char *out, size_t outlen) {
    size_t j = 0;
    for (; *in && j + 4 < outlen; in++) {
        unsigned char c = (unsigned char)*in;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || strchr("-_.~", c))
            out[j++] = c;
        else
            j += snprintf(out + j, outlen - j, "%%%02X", c);
    }
    out[j] = 0;
}

static void uri_unescape(char *s) {
    char *o = s;
    for (; *s; s++) {
        if (*s == '%' && s[1] && s[2]) {
            char hex[3] = { s[1], s[2], 0 };
            *o++ = (char)strtol(hex, NULL, 16);
            s += 2;
        } else {
            *o++ = *s;
        }
    }
    *o = 0;
}

/* Open the first Canon IVEC printer matching model/serial (NULL = any).
 * With list set, print discovery lines instead. */
static int scan(libusb_context *ctx, const char *want_model, const char *want_serial, int list, printer_t *out) {
    libusb_device **devs;
    ssize_t n = libusb_get_device_list(ctx, &devs);
    int found = -1;
    for (ssize_t i = 0; i < n && found < 0; i++) {
        struct libusb_device_descriptor dd;
        if (libusb_get_device_descriptor(devs[i], &dd) || dd.idVendor != CANON_VID) continue;
        printer_t p = { 0 };
        if (find_printer_iface(devs[i], &p) || libusb_open(devs[i], &p.h)) continue;
        if (dd.iSerialNumber)
            libusb_get_string_descriptor_ascii(p.h, dd.iSerialNumber, (unsigned char *)p.serial, sizeof(p.serial));
        char mdl[128] = "", cmd[256] = "";
        if (read_device_id(&p) == 0) {
            field(p.device_id, "MDL:", mdl, sizeof(mdl));
            field(p.device_id, "CMD:", cmd, sizeof(cmd));
        }
        int ivec = strstr(cmd, "IVEC") != NULL;
        if (list && ivec) {
            char em[256], es[256];
            uri_escape(mdl, em, sizeof(em));
            uri_escape(p.serial, es, sizeof(es));
            printf("direct canonivec://Canon/%s?serial=%s \"Canon %s\" \"Canon %s (IVEC open driver)\" \"%s\" \"\"\n",
                   em, es, mdl, mdl, p.device_id);
        } else if (!list && ivec && (!want_model || !strcmp(want_model, mdl)) &&
                   (!want_serial || !*want_serial || !strcmp(want_serial, p.serial))) {
            *out = p;
            found = 0;
            continue;
        }
        libusb_close(p.h);
    }
    libusb_free_device_list(devs, 1);
    return found;
}

/* ---- status reader ---- */

/* An error state followed directly by idle (no processing in between)
 * means the user pressed Stop on the printer. */
static void watch_for_printer_cancel(void) {
    static int in_error;
    ivec_status_t st;
    if (!ivec_status_latest(&st)) return;
    const char *reason;
    char msg[256];
    if (ivec_status_reason(&st, &reason, msg, sizeof(msg))) in_error = 1;
    else if (!strcmp(st.status, "idle")) { if (in_error) g_printer_cancel = 1; in_error = 0; }
    else in_error = 0;
}

static void *reader(void *arg) {
    printer_t *p = arg;
    unsigned char buf[4096];
    /* IVEC_RAW_LOG=file keeps a copy of everything the printer sends */
    const char *raw = getenv("IVEC_RAW_LOG");
    FILE *rawf = raw ? fopen(raw, "ab") : NULL;
    while (!g_reader_stop) {
        int got = 0;
        int r = libusb_bulk_transfer(p->h, p->ep_in, buf, sizeof(buf), &got, 500);
        if (got > 0) {
            if (rawf) { fwrite(buf, 1, got, rawf); fflush(rawf); }
            ivec_status_feed((const char *)buf, (size_t)got);
            watch_for_printer_cancel();
            /* a full buffer means the document continues; otherwise pace the
             * polling, the printer answers every read immediately */
            if (got < (int)sizeof(buf)) usleep(700000);
        }
        else if (r != 0 && r != LIBUSB_ERROR_TIMEOUT)
            usleep(200000);
    }
    if (rawf) fclose(rawf);
    return NULL;
}

/* write everything, a second at a time, so cancel is noticed */
static int write_all(printer_t *p, const unsigned char *data, int len) {
    while (len > 0 && !g_cancel && !g_printer_cancel) {
        int sent = 0;
        int r = libusb_bulk_transfer(p->h, p->ep_out, (unsigned char *)data, len, &sent, WRITE_TIMEOUT);
        data += sent;
        len -= sent;
        if (r == LIBUSB_ERROR_TIMEOUT) continue;      /* printer busy or waiting for the user */
        if (r == LIBUSB_ERROR_PIPE) { libusb_clear_halt(p->h, p->ep_out); continue; }
        if (r != 0) {
            fprintf(stderr, "ERROR: USB write failed: %s\n", libusb_error_name(r));
            return -1;
        }
    }
    return (g_cancel || g_printer_cancel) ? -1 : 0;
}

static void cancel_on_printer(printer_t *p);
static void ensure_listening(printer_t *p);

static void soft_reset(printer_t *p) {
    /* USB printer class SOFT_RESET, addressed to the interface (what Canon's tools send) */
    int r = libusb_control_transfer(p->h, LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                    2, 0, p->iface, NULL, 0, 5000);
    fprintf(stderr, "DEBUG: soft reset -> %s\n", r < 0 ? libusb_error_name(r) : "ok");
}

/* Abort the job on the printer.  Observed on a G3010:
 *   - a soft reset alone drops the queued data but the printer keeps
 *     waiting (stopped/MediaEmpty);
 *   - CancelJob ends the job and clears the error, but afterwards the
 *     printer discards everything it receives until the next soft reset.
 * So: reset, CancelJob, then reset again. */
static void cancel_on_printer(printer_t *p) {
    char cmd[512];
    int sent;
    fprintf(stderr, "INFO: Cancelling job %s on the printer\n", g_job_id);
    soft_reset(p);
    snprintf(cmd, sizeof(cmd),
             "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\">"
             "<ivec:contents><ivec:operation>CancelJob</ivec:operation><ivec:param_set servicetype=\"%s\">"
             "<ivec:jobID>%s</ivec:jobID></ivec:param_set></ivec:contents></cmd>", g_service, g_job_id);
    int r = libusb_bulk_transfer(p->h, p->ep_out, (unsigned char *)cmd, (int)strlen(cmd), &sent, 5000);
    fprintf(stderr, "DEBUG: CancelJob %s/%s -> %s\n", g_service, g_job_id, r ? libusb_error_name(r) : "sent");
    sleep(2);
    soft_reset(p);
}

/* A printer left in "discard" mode (see above) answers nothing; make sure it
 * is listening before sending a job. */
static void ensure_listening(printer_t *p) {
    if (!p->ep_in) return;
    unsigned char buf[4096];
    int sent, got = 0;
    libusb_bulk_transfer(p->h, p->ep_out, (unsigned char *)IVEC_GET_STATUS, (int)strlen(IVEC_GET_STATUS), &sent, 2000);
    for (int i = 0; i < 3 && got == 0; i++)
        libusb_bulk_transfer(p->h, p->ep_in, buf, sizeof(buf), &got, 700);
    if (got > 0) {
        ivec_status_feed((const char *)buf, (size_t)got);
    } else {
        fprintf(stderr, "DEBUG: printer not answering, resetting it\n");
        soft_reset(p);
    }
}

/* After the last byte: wait until the printer has finished, so errors on
 * the last page still reach the user and the job is not marked done early.
 * The whole job usually fits in the printer's buffer, and the printer keeps
 * reporting "idle" for several seconds before it starts, so only trust
 * "idle" once it has been busy (or after 30 s without any activity). */
static void wait_for_printer(printer_t *p, int has_in) {
    if (!has_in) return;
    time_t start = time(NULL), asked = 0;
    int busy = 0;
    while (!g_cancel && !g_printer_cancel) {
        ivec_status_t st;
        time_t seen = ivec_status_latest(&st), now = time(NULL);
        const char *reason;
        char msg[256];
        int attention = seen ? ivec_status_reason(&st, &reason, msg, sizeof(msg)) : 0;
        int idle = seen >= start && !strcmp(st.status, "idle") && !attention;
        if (seen >= start && strcmp(st.status, "idle")) busy = 1;
        if (idle && (busy || now - start >= 30)) break;
        if (!seen && now - start >= 30) break;           /* printer never reports: assume done */
        if (!attention && now - start >= 900) break;
        if (now - asked >= 3) {
            int sent;
            libusb_bulk_transfer(p->h, p->ep_out, (unsigned char *)IVEC_GET_STATUS,
                                 (int)strlen(IVEC_GET_STATUS), &sent, WRITE_TIMEOUT);
            asked = now;
        }
        sleep(1);
    }
}

int main(int argc, char *argv[]) {
    setbuf(stderr, NULL);
    libusb_context *ctx;
    if (libusb_init(&ctx) != 0) {
        fprintf(stderr, "ERROR: Unable to initialise libusb\n");
        return CUPS_BACKEND_FAILED;
    }

    if (argc == 1) {
        printer_t dummy;
        scan(ctx, NULL, NULL, 1, &dummy);
        libusb_exit(ctx);
        return CUPS_BACKEND_OK;
    }
    if (argc < 6 || argc > 7) {
        fprintf(stderr, "Usage: %s job-id user title copies options [file]\n", argv[0]);
        return CUPS_BACKEND_FAILED;
    }

    /* canonivec://Canon/<model>?serial=<serial> */
    const char *uri = getenv("DEVICE_URI") ? getenv("DEVICE_URI") : argv[0];
    char model[256] = "", serial[256] = "";
    const char *m = strstr(uri, "://Canon/");
    if (m) {
        snprintf(model, sizeof(model), "%s", m + 9);
        char *q = strchr(model, '?');
        if (q) *q = 0;
        uri_unescape(model);
        const char *s = strstr(uri, "serial=");
        if (s) {
            snprintf(serial, sizeof(serial), "%s", s + 7);
            serial[strcspn(serial, "&")] = 0;
            uri_unescape(serial);
        }
    }

    struct sigaction sa = { 0 };
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    int fd = 0;
    if (argc == 7 && (fd = open(argv[6], O_RDONLY)) < 0) {
        fprintf(stderr, "ERROR: Unable to open %s: %s\n", argv[6], strerror(errno));
        return CUPS_BACKEND_FAILED;
    }

    /* wait for the printer to show up */
    printer_t *p = &g_prn;
    int announced = 0;
    while (scan(ctx, *model ? model : NULL, serial, 0, p) != 0) {
        if (g_cancel) { libusb_exit(ctx); return CUPS_BACKEND_OK; }
        if (!announced++) {
            fprintf(stderr, "STATE: +offline-report\n");
            fprintf(stderr, "INFO: Waiting for the printer (%s) to be switched on or connected\n", model);
        }
        sleep(5);
    }
    if (announced) fprintf(stderr, "STATE: -offline-report\n");

    libusb_set_auto_detach_kernel_driver(p->h, 1);
    int r = libusb_claim_interface(p->h, p->iface);
    if (r == 0 && p->alt) r = libusb_set_interface_alt_setting(p->h, p->iface, p->alt);
    if (r != 0) {
        fprintf(stderr, "ERROR: Unable to claim the printer interface: %s\n", libusb_error_name(r));
        libusb_close(p->h);
        libusb_exit(ctx);
        return CUPS_BACKEND_RETRY;
    }
    fprintf(stderr, "DEBUG: canonivec %s serial %s iface %d alt %d out 0x%02x in 0x%02x\n",
            model, p->serial, p->iface, p->alt, p->ep_out, p->ep_in);
    fprintf(stderr, "INFO: Printing\n");

    ensure_listening(p);

    pthread_t rt;
    int have_reader = p->ep_in && pthread_create(&rt, NULL, reader, p) == 0;

    unsigned char *buf = malloc(CHUNK);
    int rc = CUPS_BACKEND_OK;
    ssize_t n;
    while (!g_cancel && (n = read(fd, buf, CHUNK)) != 0) {
        if (n < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "ERROR: Unable to read print data: %s\n", strerror(errno));
            rc = CUPS_BACKEND_FAILED;
            break;
        }
        note_job(buf, (int)n);
        if (write_all(p, buf, (int)n) != 0) {
            if (!g_cancel && !g_printer_cancel) rc = CUPS_BACKEND_FAILED;
            break;
        }
    }
    free(buf);

    if (g_cancel) {
        cancel_on_printer(p);
    } else if (rc == CUPS_BACKEND_OK) {
        wait_for_printer(p, have_reader);
        if (g_cancel) cancel_on_printer(p);
    }

    if (g_printer_cancel && !g_cancel) {
        /* drop whatever is still queued so the next job starts clean */
        fprintf(stderr, "INFO: Job cancelled at the printer\n");
        soft_reset(p);
        rc = CUPS_BACKEND_CANCEL;
    }

    g_reader_stop = 1;
    if (have_reader) pthread_join(rt, NULL);
    ivec_status_clear();
    libusb_release_interface(p->h, p->iface);
    libusb_close(p->h);
    libusb_exit(ctx);
    if (fd) close(fd);
    return rc;
}
