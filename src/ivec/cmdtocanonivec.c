/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * cmdtocanonivec - CUPS command-file filter for Canon IVEC inkjets.
 *
 * Standard CUPS commands:
 *   Clean [all]            regular head cleaning, all inks
 *   PrintSelfTestPage      nozzle check pattern
 *   PrintAlignmentPage     print head alignment
 * Extended (only what the model's capability lists):
 *   com.canon.Cleaning <regular|deep|choke> [all|group1|group2]
 *   com.canon.RollerCleaning <roller|platen>
 *   com.canon.TestPrint <nozzle_check|half_auto_registration|...>
 *
 * PPD keys: *cnivecMaintStyle "ivec"|"bjl", *cnivecMaintDateTime,
 *           *cnivecMaint <Operation>.<type>: "<inkgroups>"
 */
#include <cups/cups.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "ivec.h"

#define MAX_CAPS 32

typedef struct {
    char op[32], type[48], groups[64];
} maint_cap_t;

typedef struct {
    int bjl;
    int datetime;
    int host_env;
    int protocol;           /* print framing, used by BJL-style maintenance */
    int print_datetime;
    maint_cap_t caps[MAX_CAPS];
    int ncaps;
} maint_model_t;

static void load(const char *path, maint_model_t *m) {
    memset(m, 0, sizeof(*m));
    m->protocol = 2;
    m->host_env = 1;
    FILE *f = path ? fopen(path, "r") : NULL;
    if (!f) return;
    char line[512], op[32], type[48], val[64];
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "*cnivecMaint %31[^.].%47[^:]: \"%63[^\"]\"", op, type, val) >= 2 && m->ncaps < MAX_CAPS) {
            maint_cap_t *c = &m->caps[m->ncaps++];
            snprintf(c->op, sizeof(c->op), "%s", op);
            snprintf(c->type, sizeof(c->type), "%s", type);
            snprintf(c->groups, sizeof(c->groups), "%s", strchr(line, '"') && strchr(line, '"')[1] != '"' ? val : "");
        } else if (sscanf(line, "*cnivecMaintStyle: \"%63[^\"]\"", val) == 1) {
            m->bjl = !strcasecmp(val, "bjl");
        } else if (sscanf(line, "*cnivecMaintDateTime: \"%63[^\"]\"", val) == 1) {
            m->datetime = !strcasecmp(val, "True");
        } else if (sscanf(line, "*cnivecMaintHostEnv: \"%63[^\"]\"", val) == 1) {
            m->host_env = !strcasecmp(val, "True");
        } else if (sscanf(line, "*cnivecProtocol: \"%63[^\"]\"", val) == 1) {
            m->protocol = atoi(val);
        } else if (sscanf(line, "*cnivecDateTime: \"%63[^\"]\"", val) == 1) {
            m->print_datetime = !strcasecmp(val, "True");
        }
    }
    fclose(f);
}

static const maint_cap_t *find(const maint_model_t *m, const char *op, const char *type) {
    for (int i = 0; i < m->ncaps; i++)
        if (!strcmp(m->caps[i].op, op) && !strcmp(m->caps[i].type, type)) return &m->caps[i];
    return NULL;
}

static int group_ok(const maint_cap_t *c, const char *group) {
    if (!group || !*group || !*c->groups) return !group || !*group || !strcmp(group, "all");
    char buf[64];
    snprintf(buf, sizeof(buf), "%s", c->groups);
    for (char *t = strtok(buf, ","); t; t = strtok(NULL, ","))
        if (!strcmp(t, group)) return 1;
    return 0;
}

static int run(const maint_model_t *m, const char *uuid, char *cmd) {
    char *argv[4] = { 0 };
    int n = 0;
    for (char *t = strtok(cmd, " \t"); t && n < 4; t = strtok(NULL, " \t")) argv[n++] = t;
    if (!n) return 0;

    const char *op = NULL, *type = NULL, *group = NULL;
    if (!strcasecmp(argv[0], "Clean")) {
        op = "Cleaning"; type = "regular"; group = "all";
    } else if (!strcasecmp(argv[0], "PrintSelfTestPage")) {
        op = "TestPrint"; type = "nozzle_check";
    } else if (!strcasecmp(argv[0], "PrintAlignmentPage") || !strcasecmp(argv[0], "com.canon.autoalignment")) {
        static const char *pref[] = { "auto_registration", "half_auto_registration", "regi_check" };
        op = "TestPrint";
        for (unsigned i = 0; i < sizeof(pref) / sizeof(*pref) && !type; i++)
            if (find(m, op, pref[i])) type = pref[i];
        if (!type) type = "auto_registration";
    } else if (!strncmp(argv[0], "com.canon.", 10) && n >= 2) {
        op = argv[0] + 10; type = argv[1]; group = n >= 3 ? argv[2] : (strcmp(op, "Cleaning") ? NULL : "all");
    } else {
        fprintf(stderr, "ERROR: Unsupported maintenance command \"%s\"\n", argv[0]);
        return -1;
    }

    if (m->bjl) {
        /* older engines only take the two classic BJL maintenance lines */
        const char *line = NULL;
        if (!strcmp(op, "Cleaning") && !strcmp(type, "regular") && (!group || !strcmp(group, "all")))
            line = "@Cleaning=1ALL";
        else if (!strcmp(op, "TestPrint") && !strcmp(type, "nozzle_check"))
            line = "@TestPrint=NozzleCheck";
        if (!line) {
            fprintf(stderr, "ERROR: %s %s is not available on this printer\n", op, type);
            return -1;
        }
        fprintf(stderr, "INFO: Sending %s\n", line);
        ivec_job_t job;
        ivec_job_init(&job, stdout, m->protocol, m->print_datetime, uuid, "linux");
        return ivec_maintenance_bjl(&job, line);
    }

    if (m->ncaps) {
        const maint_cap_t *c = find(m, op, type);
        if (!c || !group_ok(c, group)) {
            fprintf(stderr, "ERROR: %s %s%s%s is not available on this printer\n", op, type,
                    group ? " " : "", group ? group : "");
            return -1;
        }
    }
    fprintf(stderr, "INFO: Sending %s %s%s%s\n", op, type, group ? " " : "", group ? group : "");
    return ivec_maintenance(stdout, m->datetime, m->host_env, uuid, op, type, strcmp(op, "Cleaning") ? NULL : group);
}

int main(int argc, char *argv[]) {
    if (argc < 6 || argc > 7) {
        fprintf(stderr, "Usage: %s job-id user title copies options [file]\n", argv[0]);
        return 1;
    }
    maint_model_t m;
    load(getenv("PPD"), &m);

    cups_option_t *opts = NULL;
    int nopts = cupsParseOptions(argv[5], 0, &opts);
    const char *uuid = cupsGetOption("job-uuid", nopts, opts);

    FILE *in = argc == 7 ? fopen(argv[6], "r") : stdin;
    if (!in) {
        fprintf(stderr, "ERROR: Unable to open %s: %s\n", argv[6], strerror(errno));
        return 1;
    }

    char line[256];
    int rc = 0, sent = 0;
    while (fgets(line, sizeof(line), in)) {
        line[strcspn(line, "\r\n")] = 0;
        if (!line[0] || line[0] == '#') continue;
        if (run(&m, uuid, line) != 0) { rc = 1; break; }
        sent++;
    }
    if (!sent && !rc) {
        fprintf(stderr, "ERROR: No maintenance command found\n");
        rc = 1;
    }
    if (in != stdin) fclose(in);
    cupsFreeOptions(nopts, opts);
    return rc;
}
