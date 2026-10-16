/* Ask Canon's library how each cnijfilter2 PPD media type / page size name
 * translates into the IVEC keyword sent to the printer, for one model. */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include "cnclcmdutils.h"
#include "cnclcmdutilsdef.h"
#include "cncl_paramtbl.h"

static int (*get)(const char *, const char *, int *, uint8_t **);
static int (*setcfg)(CNCL_P_SETTINGSPTR, char *, long, void *, long, char *, long *);

static void val(const char *x, const char *tag, char *out) {
    char t[64];
    snprintf(t, sizeof t, "<ivec:%s>", tag);
    const char *p = strstr(x, t);
    if (!p) { strcpy(out, "-"); return; }
    p += strlen(t);
    const char *e = strchr(p, '<');
    memcpy(out, p, e - p);
    out[e - p] = 0;
}

int main(int argc, char **argv) {
    void *h = dlopen("libcnbpcnclapicom2.so", RTLD_LAZY);
    if (!h || argc != 2) return 1;
    get = dlsym(h, "CNCL_GetStringWithTagFromFile");
    setcfg = dlsym(h, "CNCL_GetSetConfigurationCommand");
    uint8_t *cap = NULL;
    int n = get(argv[1], "CNIJ-IVEC-CAPABILITY", (int *)1, &cap);
    char buf[8192], a[256];
    long w;
    CNCL_P_SETTINGS s = { 0, CNCL_PSET_SIZE_A4, CNCL_PSET_MEDIA_PLAIN, CNCL_PSET_BORDERLESS_OFF,
                          CNCL_PSET_COLORMODE_COLOR, CNCL_PSET_DUPLEX_OFF };
    for (const MapTbl *m = mediatypeTbl; m->optName; m++) {
        CNCL_P_SETTINGS t = s;
        t.mediatype = m->optNum;
        w = 0;
        int r = setcfg(&t, "00000002", sizeof buf, cap, n, buf, &w);
        buf[w > 0 ? w : 0] = 0;
        val(buf, "papertype", a);
        if (r == 0) printf("media %s %s\n", m->optName, a);
    }
    for (const MapTbl *m = papersizeTbl; m->optName; m++) {
        CNCL_P_SETTINGS t = s;
        t.papersize = m->optNum;
        t.borderlessprint = strstr(m->optName, ".bl") ? CNCL_PSET_BORDERLESS_ON : CNCL_PSET_BORDERLESS_OFF;
        w = 0;
        int r = setcfg(&t, "00000002", sizeof buf, cap, n, buf, &w);
        buf[w > 0 ? w : 0] = 0;
        val(buf, "papersize", a);
        if (r == 0) printf("size %s %s\n", m->optName, a);
    }
    return 0;
}
