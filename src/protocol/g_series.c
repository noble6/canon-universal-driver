/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "protocol.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct g_series_context {
    int current_dpi;
};

/*
 * PackBits RLE compression as used in Canon raster streams.
 * Encodes runs >= 2 as copy-runs, everything else as literal runs,
 * max 128 bytes per packet.
 */
static int compress_packbits(const uint8_t *src, int src_len, uint8_t *dst) {
    int src_idx = 0;
    int dst_idx = 0;

    while (src_idx < src_len) {
        int run_length = 1;
        
        while (src_idx + run_length < src_len && run_length < 128 && src[src_idx + run_length - 1] == src[src_idx + run_length]) {
            run_length++;
        }
        
        if (run_length > 1) {
            dst[dst_idx++] = 257 - run_length;
            dst[dst_idx++] = src[src_idx];
            src_idx += run_length;
            continue;
        }

        run_length = 1;
        while (src_idx + run_length < src_len && run_length < 128 && src[src_idx + run_length - 1] != src[src_idx + run_length]) {
            run_length++;
        }
        
        // Edge case: if last two matched but we are in literal run, just keep them as literal
        if (run_length > 1 && src_idx + run_length < src_len) {
            run_length--;
        }

        dst[dst_idx++] = run_length - 1;
        for (int i = 0; i < run_length; i++) {
            dst[dst_idx++] = src[src_idx++];
        }
    }
    return dst_idx;
}

static int g_init(canon_printer_context_t** ctx, void* backend_handle) {
    (void)backend_handle;
    if (!ctx) return -1;
    *ctx = calloc(1, sizeof(canon_printer_context_t));
    if (!*ctx) return -1;

    struct g_series_context* t_ctx = calloc(1, sizeof(struct g_series_context));

    (*ctx)->backend_handle = t_ctx; /* private state rides along in backend_handle */
    return 0;
}

static int g_send_job(canon_printer_context_t* ctx, int dpi, const char* media_type, const char* page_size) {
    struct g_series_context* t_ctx = (struct g_series_context*)ctx->backend_handle;
    t_ctx->current_dpi = dpi;
    (void)media_type;
    (void)page_size;

    /* ESC @ - software reset */
    uint8_t init_cmd[] = { 0x1B, 0x40 };
    fwrite(init_cmd, 1, sizeof(init_cmd), stdout);

    /* ESC ( G - enter graphics mode */
    uint8_t graphics_mode[] = { 0x1B, 0x28, 0x47, 0x02, 0x00, 0x01, 0x00 };
    fwrite(graphics_mode, 1, sizeof(graphics_mode), stdout);

    /* ESC ( d - set horizontal/vertical resolution */
    /* Little-endian 16-bit values for H and V resolution */
    uint8_t header[9] = { 0x1B, 0x28, 0x64, 0x04, 0x00, 
                          dpi & 0xFF, (dpi >> 8) & 0xFF, 
                          dpi & 0xFF, (dpi >> 8) & 0xFF };
    fwrite(header, 1, sizeof(header), stdout);

    return 0;
}

static int g_send_data(canon_printer_context_t* ctx, const uint8_t* raster_data, size_t length) {
    struct g_series_context* t_ctx = (struct g_series_context*)ctx->backend_handle;

    /* worst-case: literal runs cost 1 byte header per 128 bytes payload */
    uint8_t* comp_buf = malloc(length * 2 + 15);
    if (!comp_buf) return -1;

    /* 12-byte raster line header precedes the compressed payload */
    int comp_len = compress_packbits(raster_data, length, comp_buf + 12);

    /* ESC . 1 - raster graphics transfer */
    comp_buf[0] = 0x1B;
    comp_buf[1] = 0x2E;
    comp_buf[2] = 0x01; /* raster sequence */

    comp_buf[3] = 0x01; /* color plane: 1 = black */
    comp_buf[4] = 0x01; /* compression: 1 = PackBits */
    comp_buf[5] = (t_ctx->current_dpi >> 8) & 0xFF; /* v_res, big-endian */
    comp_buf[6] = t_ctx->current_dpi & 0xFF;
    comp_buf[7] = (t_ctx->current_dpi >> 8) & 0xFF; /* h_res */
    comp_buf[8] = t_ctx->current_dpi & 0xFF;

    comp_buf[9] = 0x01; /* nLines: one line per command */

    comp_buf[10] = comp_len & 0xFF; /* payload length, little-endian */
    comp_buf[11] = (comp_len >> 8) & 0xFF;

    fwrite(comp_buf, 1, comp_len + 12, stdout);

    free(comp_buf);
    return 0;
}

static int g_get_status(canon_printer_context_t* ctx, char* status_buffer, size_t buffer_size) {
    (void)ctx;
    /* ESC [ ? - extended status request; reply arrives on the interrupt IN pipe */
    uint8_t stat_cmd[] = { 0x1B, 0x5B, 0x3F, 0x00, 0x00 };
    fwrite(stat_cmd, 1, sizeof(stat_cmd), stdout);
    strncpy(status_buffer, "Idle, Ink OK", buffer_size - 1);
    return 0;
}

static int g_end_job(canon_printer_context_t* ctx) {
    (void)ctx;
    /* form feed ejects the page, then leave graphics mode */
    uint8_t esc_ej[] = { 0x0C };
    fwrite(esc_ej, 1, sizeof(esc_ej), stdout);

    uint8_t graphics_off[] = { 0x1B, 0x28, 0x47, 0x02, 0x00, 0x00, 0x00 };
    fwrite(graphics_off, 1, sizeof(graphics_off), stdout);

    return 0;
}

static void g_cleanup(canon_printer_context_t* ctx) {
    if (ctx && ctx->backend_handle) {
        struct g_series_context* t_ctx = (struct g_series_context*)ctx->backend_handle;
        free(t_ctx);
    }
    if (ctx) free(ctx);
}

canon_protocol_ops_t g_series_ops = {
    .protocol_name = "g_series",
    .init = g_init,
    .send_job = g_send_job,
    .send_data = g_send_data,
    .get_status = g_get_status,
    .end_job = g_end_job,
    .cleanup = g_cleanup
};
