/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef CANON_PROTOCOL_H
#define CANON_PROTOCOL_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/**
 * Common Protocol Interface for Canon Universal Driver
 * 
 * Each supported printer family (G, E, TS, MG, iP, imageCLASS)
 * implements this struct to handle specifics of job creation,
 * raster transmission, and status checking.
 */

typedef struct canon_printer_context {
    void* backend_handle; // USB or Net backend handle
    int max_dpi;
    bool is_color;
    // Additional state info here
} canon_printer_context_t;

typedef struct canon_protocol_ops {
    const char* protocol_name;

    // Initialize the protocol and allocate context
    int (*init)(canon_printer_context_t** ctx, void* backend_handle);

    // Send beginning of job commands (header, resolution, media type)
    int (*send_job)(canon_printer_context_t* ctx, int dpi, const char* media_type, const char* page_size);

    // Send compressed or raw raster data
    int (*send_data)(canon_printer_context_t* ctx, const uint8_t* raster_data, size_t length);

    // Query printer for ink levels, errors, paper status
    int (*get_status)(canon_printer_context_t* ctx, char* status_buffer, size_t buffer_size);

    // Cap off the job to tell the printer to eject the page
    int (*end_job)(canon_printer_context_t* ctx);

    // Free context and cleanup
    void (*cleanup)(canon_printer_context_t* ctx);

} canon_protocol_ops_t;

// Exported ops for different series
extern canon_protocol_ops_t g_series_ops;
extern canon_protocol_ops_t e_series_ops;
extern canon_protocol_ops_t ip_series_ops;
extern canon_protocol_ops_t mg_series_ops;
extern canon_protocol_ops_t tr_ts_series_ops;
extern canon_protocol_ops_t imageclass_series_ops;

#endif // CANON_PROTOCOL_H
