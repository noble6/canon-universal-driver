/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <stdio.h>
#include <stdlib.h>
#include <cups/cups.h>
#include <cups/raster.h>
#include "protocol.h"

/**
 * canonfilter - CUPS raster filter for Canon printers.
 *
 * Consumes a CUPS raster stream on stdin (or from a spool file) and
 * emits device commands for the target printer through the active
 * protocol backend.
 */

int main(int argc, char *argv[]) {
    /* CUPS filters are invoked as: filter job-id user title copies options [filename] */
    if (argc < 6 || argc > 7) {
        fprintf(stderr, "ERROR: canonfilter job-id user title copies options [file]\n");
        return 1;
    }

    FILE* input = stdin;
    if (argc == 7) {
        input = fopen(argv[6], "rb");
        if (!input) {
            fprintf(stderr, "ERROR: Unable to open input file %s\n", argv[6]);
            return 1;
        }
    }

    cups_raster_t *ras = cupsRasterOpen(fileno(input), CUPS_RASTER_READ);
    if (!ras) {
        fprintf(stderr, "ERROR: Failed to open CUPS raster stream.\n");
        if (input != stdin) fclose(input);
        return 1;
    }

    /* single protocol family implemented so far; selection by model comes later */
    canon_protocol_ops_t *ops = &g_series_ops;
    canon_printer_context_t *ctx = NULL;

    if (ops->init(&ctx, NULL) < 0) {
        fprintf(stderr, "ERROR: Failed to init protocol ops (is printer connected?)\n");
        cupsRasterClose(ras);
        if (input != stdin) fclose(input);
        return 1;
    }

    cups_page_header2_t header;
    while (cupsRasterReadHeader2(ras, &header)) {
        fprintf(stderr, "INFO: page %ux%dpi\n", header.HWResolution[0], header.HWResolution[1]);

        ops->send_job(ctx, header.HWResolution[0], header.MediaType, (const char*)header.PageSize);

        int bytes_per_line = header.cupsBytesPerLine;
        uint8_t *line_buffer = malloc(bytes_per_line);
        if (!line_buffer) {
            fprintf(stderr, "ERROR: Out of memory for raster line buffer.\n");
            ops->cleanup(ctx);
            cupsRasterClose(ras);
            if (input != stdin) fclose(input);
            return 1;
        }

        for (unsigned int y = 0; y < header.cupsHeight; y++) {
            if (cupsRasterReadPixels(ras, line_buffer, bytes_per_line) == 0) {
                break;
            }
            ops->send_data(ctx, line_buffer, bytes_per_line);
        }

        free(line_buffer);
        ops->end_job(ctx); /* eject per page */
    }

    ops->cleanup(ctx);
    cupsRasterClose(ras);
    if (input != stdin) {
        fclose(input);
    }

    return 0;
}
