/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * rastertocanonivec - CUPS filter for Canon IVEC inkjets (PIXMA/MAXIFY,
 * roughly 2013 onwards: G, E, TS, TR, MG, MB, GM, GX, iB ... series).
 *
 * Input:  8-bit RGB CUPS raster rendered by gstoraster from our PPD.
 * Output: IVEC command stream with one PWG Raster blob per page.
 *
 * Per-model knowledge is carried in the PPD:
 *   *cnivecProtocol: "1" | "2"
 *   *cnivecDateTime: "True"            (V2: send SetJobConfiguration clock)
 *   *cnivecColorMode: "monochrome"     (mono-only models)
 *   *cnivecPaperSize <PageSize>: "<pwg-name> <printable-w> <printable-h>"
 *   *cnivecMediaType <MediaType>: "<ivec papertype>"
 *   *cnivecRotateBack: "True"          (long-edge duplex: turn odd pages 180 degrees)
 *   *cnivecOptimization: "True"        (gray pages as 1-channel; colour jobs
 *                                       without colour go out as monochrome)
 */
#include <cups/cups.h>
#include <cups/raster.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "ivec.h"
#include "status.h"

#define MAX_SIZES 96
#define MAX_MEDIA 48

typedef struct {
    char name[48];        /* PPD PageSize keyword */
    char pwg[64];
    int print_w, print_h; /* printable area in device pixels */
    float width, length;  /* points */
    int borderless;
} size_entry_t;

typedef struct {
    char name[48];
    char ivec[64];
} media_entry_t;

typedef struct {
    int protocol;
    int datetime;
    int mono_only;
    int rotate_back;
    int optimization;
    char default_size[48];
    char default_media[48];
    int default_gray;
    size_entry_t sizes[MAX_SIZES];
    int nsizes;
    media_entry_t media[MAX_MEDIA];
    int nmedia;
} model_t;

static volatile sig_atomic_t canceled;

static void on_term(int sig) {
    (void)sig;
    canceled = 1;
}

/* ---- PPD parsing (only the bits we need; avoids the deprecated ppd API) ---- */

static char *unquote(char *s) {
    char *q = strchr(s, '"');
    if (!q) return s;
    char *e = strrchr(q + 1, '"');
    if (e) *e = 0;
    return q + 1;
}

static int load_model(const char *path, model_t *m) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    memset(m, 0, sizeof(*m));
    m->protocol = 2;

    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = 0;
        char key[48], opt[48];
        if (sscanf(line, "*cnivecPaperSize %47[^:]:", opt) == 1 && m->nsizes < MAX_SIZES) {
            size_entry_t *s = &m->sizes[m->nsizes];
            if (sscanf(unquote(line), "%63s %d %d %f %f", s->pwg, &s->print_w, &s->print_h,
                       &s->width, &s->length) == 5) {
                snprintf(s->name, sizeof(s->name), "%s", opt);
                s->borderless = strstr(opt, ".bl") != NULL;
                m->nsizes++;
            }
        } else if (sscanf(line, "*cnivecMediaType %47[^:]:", opt) == 1 && m->nmedia < MAX_MEDIA) {
            media_entry_t *e = &m->media[m->nmedia++];
            snprintf(e->name, sizeof(e->name), "%s", opt);
            snprintf(e->ivec, sizeof(e->ivec), "%s", unquote(line));
        } else if (sscanf(line, "*%47[^:]:", key) == 1) {
            char *val = unquote(line + strlen(key) + 2);
            if (!strcmp(key, "cnivecProtocol")) m->protocol = atoi(val);
            else if (!strcmp(key, "cnivecDateTime")) m->datetime = !strcasecmp(val, "True");
            else if (!strcmp(key, "cnivecColorMode")) m->mono_only = !strcasecmp(val, "monochrome");
            else if (!strcmp(key, "cnivecRotateBack")) m->rotate_back = !strcasecmp(val, "True");
            else if (!strcmp(key, "cnivecOptimization")) m->optimization = !strcasecmp(val, "True");
            else if (!strcmp(key, "DefaultPageSize")) snprintf(m->default_size, sizeof(m->default_size), "%s", line + 18);
            else if (!strcmp(key, "DefaultMediaType")) snprintf(m->default_media, sizeof(m->default_media), "%s", line + 19);
            else if (!strcmp(key, "DefaultCNGrayscale")) m->default_gray = !strcasecmp(line + 21, "True");
            else if (!strcmp(key, "DefaultColorModel")) m->default_gray = !strcasecmp(line + 20, "Gray");
        }
    }
    fclose(f);
    return m->nsizes > 0 ? 0 : -1;
}

/* The rendered page tells us which size was chosen: match geometry, and
 * treat a full-page imageable area as the borderless variant. */
static const size_entry_t *match_size(const model_t *m, const cups_page_header2_t *h) {
    int borderless = h->ImagingBoundingBox[0] == 0 && h->ImagingBoundingBox[1] == 0 &&
                     h->ImagingBoundingBox[2] == h->PageSize[0] &&
                     h->ImagingBoundingBox[3] == h->PageSize[1];
    const size_entry_t *any = NULL;
    for (int i = 0; i < m->nsizes; i++) {
        const size_entry_t *s = &m->sizes[i];
        if (abs((int)(s->width + 0.5f) - (int)h->PageSize[0]) > 1 ||
            abs((int)(s->length + 0.5f) - (int)h->PageSize[1]) > 1)
            continue;
        if (s->borderless == borderless) return s;
        if (!any) any = s;
    }
    if (any) return any;
    for (int i = 0; i < m->nsizes; i++)
        if (!strcmp(m->sizes[i].name, m->default_size)) return &m->sizes[i];
    return NULL;
}

static const char *media_for(const model_t *m, const char *name) {
    for (int i = 0; i < m->nmedia; i++)
        if (!strcasecmp(m->media[i].name, name)) return m->media[i].ivec;
    for (int i = 0; i < m->nmedia; i++)
        if (!strcasecmp(m->media[i].name, m->default_media)) return m->media[i].ivec;
    return "stationery";
}

static int want_gray(const model_t *m, int nopts, cups_option_t *opts) {
    const char *v;
    if (m->mono_only) return 1;
    if ((v = cupsGetOption("ColorModel", nopts, opts))) return !strcasecmp(v, "Gray");
    if ((v = cupsGetOption("print-color-mode", nopts, opts))) return !strcasecmp(v, "monochrome");
    if ((v = cupsGetOption("CNGrayscale", nopts, opts))) return !strcasecmp(v, "True");   /* Canon PPD name */
    return m->default_gray;
}

/* ---- PWG page generation ---- */

typedef struct {
    unsigned char *data;
    size_t len, cap;
} membuf_t;

static ssize_t membuf_write(void *ctx, unsigned char *buf, size_t len) {
    membuf_t *b = ctx;
    if (b->len + len > b->cap) {
        size_t cap = b->cap ? b->cap : 1 << 20;
        while (cap < b->len + len) cap *= 2;
        unsigned char *p = realloc(b->data, cap);
        if (!p) return -1;
        b->data = p;
        b->cap = cap;
    }
    memcpy(b->data + b->len, buf, len);
    b->len += len;
    return (ssize_t)len;
}

/* Nearest source index for destination i when stretching n_src samples
 * across n_dst, endpoints pinned, halves rounded up. */
static long src_index(long i, long n_src, long n_dst) {
    if (n_dst <= 1) return 0;
    return (2 * i * (n_src - 1) + (n_dst - 1)) / (2 * (n_dst - 1));
}

static void to_rgb(const unsigned char *in, unsigned char *out, unsigned w, const cups_page_header2_t *h) {
    if (h->cupsNumColors == 3) {
        memcpy(out, in, (size_t)w * 3);
        return;
    }
    for (unsigned x = 0; x < w; x++) {
        unsigned char v = h->cupsColorSpace == CUPS_CSPACE_K ? 255 - in[x] : in[x];
        out[3 * x] = out[3 * x + 1] = out[3 * x + 2] = v;
    }
}

/* Canon's luminance for 1-channel pages (Rec. 709 weights, truncated) */
static void to_gray(const unsigned char *rgb, unsigned char *out, long w) {
    for (long x = 0; x < w; x++, rgb += 3)
        out[x] = (unsigned char)((2126 * rgb[0] + 7152 * rgb[1] + 722 * rgb[2]) / 10000);
}

static int has_color(const unsigned char *rgb, long w) {
    for (long x = 0; x < w; x++, rgb += 3)
        if (rgb[0] != rgb[1] || rgb[1] != rgb[2]) return 1;
    return 0;
}

/* Canon's own driver fills the PWG ImageBox words (cupsInteger[3..6])
 * with points * dpi, without the /72 libcups applies.  Firmware has only
 * ever been fed those values over IVEC, so reproduce them. */
static void patch_image_box(membuf_t *b, const cups_page_header2_t *h) {
    if (b->len < 4 + 1796) return;
    unsigned char *p = b->data + 4 + 452 + 3 * 4;
    for (int i = 0; i < 4; i++) {
        uint32_t v = (uint32_t)(h->cupsImagingBBox[i] * h->HWResolution[i & 1]);
        p[4 * i] = v >> 24; p[4 * i + 1] = v >> 16; p[4 * i + 2] = v >> 8; p[4 * i + 3] = v;
    }
}

static cups_raster_t *open_pwg(membuf_t *b, const cups_page_header2_t *h, long w, long ht, int gray) {
    cups_page_header2_t oh = *h;
    oh.cupsWidth = w;
    oh.cupsHeight = ht;
    oh.cupsNumColors = gray ? 1 : 3;
    oh.cupsBitsPerPixel = gray ? 8 : 24;
    oh.cupsBytesPerLine = w * oh.cupsNumColors;
    oh.cupsColorSpace = gray ? CUPS_CSPACE_SW : CUPS_CSPACE_SRGB;
    oh.cupsInteger[0] = 1;          /* TotalPageCount: every page is its own stream */
    oh.cupsInteger[7] = 0xffffff;   /* AlternatePrimary: white */
    oh.cupsInteger[14] = 0;
    oh.cupsInteger[15] = 0;
    cups_raster_t *r = cupsRasterOpenIO(membuf_write, b, CUPS_RASTER_WRITE_PWG);
    if (r && !cupsRasterWriteHeader2(r, &oh)) {
        cupsRasterClose(r);
        r = NULL;
    }
    return r;
}

/* Render one page into a colour and/or a gray PWG stream.  *colorful is set
 * when any printed pixel is not neutral. */
static int render_page(cups_raster_t *in, const cups_page_header2_t *h, const size_entry_t *size,
                       int rotate, membuf_t *color, membuf_t *gray, int *colorful) {
    if (h->cupsBitsPerColor != 8 || h->cupsColorOrder != CUPS_ORDER_CHUNKED ||
        (h->cupsNumColors != 3 && h->cupsNumColors != 1)) {
        fprintf(stderr, "ERROR: Unsupported raster format (%u bpc, %u colors)\n",
                h->cupsBitsPerColor, h->cupsNumColors);
        return -1;
    }

    long in_w = h->cupsWidth, in_h = h->cupsHeight;
    long out_w = size->print_w, out_h = size->print_h;
    long dst_w, dst_h, ofs_w, ofs_h;

    /* fit inside the printable area, keep aspect, centre */
    if ((double)out_w / in_w < (double)out_h / in_h) {
        dst_w = out_w;
        dst_h = (long)((double)in_h * out_w / in_w + 0.5);
        ofs_w = 0;
        ofs_h = (out_h - dst_h) / 2;
    } else {
        dst_w = (long)((double)in_w * out_h / in_h + 0.5);
        dst_h = out_h;
        ofs_w = (out_w - dst_w) / 2;
        ofs_h = 0;
    }

    cups_raster_t *cr = color ? open_pwg(color, h, out_w, out_h, 0) : NULL;
    cups_raster_t *gr = gray ? open_pwg(gray, h, out_w, out_h, 1) : NULL;
    size_t obpl = (size_t)out_w * 3;
    unsigned char *inl = malloc(h->cupsBytesPerLine);
    unsigned char *rgb = malloc((size_t)in_w * 3);
    unsigned char *g = malloc(out_w);
    unsigned char *rows = malloc(obpl * (rotate ? (size_t)out_h : 1));
    int rc = 0;
    if ((color && !cr) || (gray && !gr) || !inl || !rgb || !g || !rows) rc = -1;

    long have = -1;   /* last source row read */
    for (long y = 0; y < out_h && rc == 0 && !canceled; y++) {
        unsigned char *o = rows + (rotate ? (size_t)y * obpl : 0);
        memset(o, 255, obpl);
        if (y >= ofs_h && y < ofs_h + dst_h) {
            long want = src_index(y - ofs_h, in_h, dst_h);
            while (have < want) {
                if (cupsRasterReadPixels(in, inl, h->cupsBytesPerLine) == 0) { rc = -1; break; }
                have++;
            }
            to_rgb(inl, rgb, in_w, h);
            unsigned char *d = o + ofs_w * 3;
            for (long x = 0; x < dst_w; x++) {
                const unsigned char *s = rgb + src_index(x, in_w, dst_w) * 3;
                d[3 * x] = s[0]; d[3 * x + 1] = s[1]; d[3 * x + 2] = s[2];
            }
            if (colorful && !*colorful) *colorful = has_color(d, dst_w);
        }
        if (rotate) continue;
        if (cr && cupsRasterWritePixels(cr, o, obpl) == 0) rc = -1;
        if (gr) { to_gray(o, g, out_w); if (cupsRasterWritePixels(gr, g, out_w) == 0) rc = -1; }
    }

    /* drain unread input rows so the next page header lines up */
    while (rc == 0 && ++have < in_h)
        if (cupsRasterReadPixels(in, inl, h->cupsBytesPerLine) == 0) rc = -1;

    /* 180 degrees: last row first, each row mirrored */
    for (long y = out_h - 1; rotate && rc == 0 && y >= 0; y--) {
        unsigned char *r = rows + (size_t)y * obpl;
        for (long a = 0, b = out_w - 1; a < b; a++, b--)
            for (int c = 0; c < 3; c++) {
                unsigned char t = r[3 * a + c];
                r[3 * a + c] = r[3 * b + c];
                r[3 * b + c] = t;
            }
        if (cr && cupsRasterWritePixels(cr, r, obpl) == 0) rc = -1;
        if (gr) { to_gray(r, g, out_w); if (cupsRasterWritePixels(gr, g, out_w) == 0) rc = -1; }
    }

    free(inl); free(rgb); free(g); free(rows);
    if (cr) cupsRasterClose(cr);
    if (gr) cupsRasterClose(gr);
    if (rc == 0) {
        if (color) patch_image_box(color, h);
        if (gray) patch_image_box(gray, h);
    }
    return rc;
}

int main(int argc, char *argv[]) {
    if (argc < 6 || argc > 7) {
        fprintf(stderr, "Usage: %s job-id user title copies options [file]\n", argv[0]);
        return 1;
    }

    struct sigaction sa = { 0 };
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);

    const char *ppd = getenv("PPD");
    model_t *m = calloc(1, sizeof(model_t));
    if (!ppd || !m || load_model(ppd, m) != 0) {
        fprintf(stderr, "ERROR: Unable to read IVEC model data from PPD %s\n", ppd ? ppd : "(none)");
        return 1;
    }

    cups_option_t *opts = NULL;
    int nopts = cupsParseOptions(argv[5], 0, &opts);

    int fd = 0;
    if (argc == 7 && (fd = open(argv[6], O_RDONLY)) < 0) {
        fprintf(stderr, "ERROR: Unable to open %s: %s\n", argv[6], strerror(errno));
        return 1;
    }
    cups_raster_t *in = cupsRasterOpen(fd, CUPS_RASTER_READ);
    if (!in) {
        fprintf(stderr, "ERROR: Unable to read raster data\n");
        return 1;
    }

    ivec_job_t job;
    ivec_job_init(&job, stdout, m->protocol, m->datetime,
                  cupsGetOption("job-uuid", nopts, opts), "linux");
    int gray_req = want_gray(m, nopts, opts);
    ivec_monitor_start();
    /* "Optimization" models get 1-channel pages for grayscale jobs, and in
     * colour mode the whole job is held back until we know whether any page
     * actually contains colour. */
    int hold = m->optimization && !gray_req;

    membuf_t *color = NULL, *gray = NULL;   /* one per page */
    int npages = 0, cap = 0, colorful = 0, job_gray = 1, rc = 0, started = 0;
    ivec_page_config_t cfg = { 0 };
    cups_page_header2_t h;

    while (!canceled && cupsRasterReadHeader2(in, &h)) {
        const size_entry_t *size = match_size(m, &h);
        if (!size) {
            fprintf(stderr, "ERROR: Page size %ux%u is not supported by this printer\n",
                    h.PageSize[0], h.PageSize[1]);
            rc = 1;
            break;
        }
        if (npages == 0) {
            cfg.papersize = size->pwg;
            cfg.papertype = media_for(m, h.MediaType);
            cfg.borderless = size->borderless;
            cfg.monochrome = gray_req;
            cfg.duplex = h.Duplex != 0;
        }
        if (npages == cap) {
            cap = cap ? cap * 2 : 8;
            color = realloc(color, cap * sizeof(*color));
            gray = realloc(gray, cap * sizeof(*gray));
            if (!color || !gray) { rc = 1; break; }
        }
        membuf_t *c = &color[npages], *g = &gray[npages];
        memset(c, 0, sizeof(*c));
        memset(g, 0, sizeof(*g));
        npages++;

        fprintf(stderr, "INFO: Processing page %d\n", npages);
        int rotate = m->rotate_back && h.Duplex && !h.Tumble && npages % 2 == 1;
        int want_color = !(m->optimization && gray_req);
        int want_gray = m->optimization && (gray_req || (hold && job_gray));
        colorful = 0;
        if (render_page(in, &h, size, rotate, want_color ? c : NULL, want_gray ? g : NULL,
                        hold ? &colorful : NULL) != 0) {
            fprintf(stderr, "ERROR: Failed to convert page %d\n", npages);
            rc = 1;
            break;
        }
        if (colorful) job_gray = 0;

        if (hold) continue;
        /* streaming: send the previous page now that we know another follows */
        if (!started) {
            if (ivec_start_job(&job) || ivec_set_configuration(&job, &cfg)) { rc = 1; break; }
            started = 1;
        }
        if (npages > 1) {
            membuf_t *p = want_color ? &color[npages - 2] : &gray[npages - 2];
            if (ivec_send_page(&job, 1, p->data, p->len)) { rc = 1; break; }
            free(color[npages - 2].data); free(gray[npages - 2].data);
            color[npages - 2].data = gray[npages - 2].data = NULL;
        }
        fprintf(stderr, "PAGE: %d 1\n", npages);
    }

    if (rc == 0 && npages == 0) {
        fprintf(stderr, "ERROR: No pages found\n");
        rc = 1;
    } else if (rc == 0 && hold) {
        cfg.monochrome = job_gray;
        fprintf(stderr, "INFO: Job is %s\n", job_gray ? "grayscale only" : "colour");
        if (ivec_start_job(&job) || ivec_set_configuration(&job, &cfg)) rc = 1;
        started = 1;
        for (int i = 0; i < npages && rc == 0; i++) {
            membuf_t *p = job_gray ? &gray[i] : &color[i];
            if (ivec_send_page(&job, i + 1 < npages, p->data, p->len)) rc = 1;
            else fprintf(stderr, "PAGE: %d 1\n", i + 1);
        }
    } else if (rc == 0) {
        membuf_t *p = (m->optimization && gray_req) ? &gray[npages - 1] : &color[npages - 1];
        if (ivec_send_page(&job, 0, p->data, p->len)) rc = 1;
    }
    fprintf(stderr, "DEBUG: IVEC v%d %s %s%s %s%s, %d page(s)\n", m->protocol, cfg.papersize ? cfg.papersize : "-",
            cfg.papertype ? cfg.papertype : "-", cfg.borderless ? " borderless" : "",
            cfg.monochrome ? "mono" : "color", cfg.duplex ? " duplex" : "", npages);
    /* always close a started job so the printer does not wait forever */
    if (started && ivec_end_job(&job)) rc = 1;
    /* stay around while the printer works through the job, so paper-out and
     * similar conditions reach the desktop */
    ivec_monitor_finish(canceled ? 0 : 3600);

    for (int i = 0; i < npages; i++) { free(color[i].data); free(gray[i].data); }
    free(color);
    free(gray);
    cupsRasterClose(in);
    cupsFreeOptions(nopts, opts);
    free(m);
    return canceled ? 0 : rc;
}
