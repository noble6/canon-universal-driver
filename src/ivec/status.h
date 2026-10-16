/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef CANON_IVEC_STATUS_H
#define CANON_IVEC_STATUS_H

/*
 * Printer status monitoring for USB queues.
 *
 * Two channels are watched while a job runs:
 *   - the USB back channel (bulk IN), relayed by the CUPS usb backend, on
 *     which IVEC printers send GetStatusResponse documents
 *     (ivec:status / ivec:status_detail / ivec:current_support_code);
 *   - the IEEE-1284 device ID, re-read through the CUPS side channel. This
 *     goes over the USB control pipe, so it still answers while the data
 *     pipe is stalled (paper out, jam, cover open).
 *
 * Conditions are reported to CUPS as standard printer-state-reasons
 * (media-empty-error, media-jam-error, door-open-error, ...) plus a
 * human readable printer-state-message, which desktops show to the user.
 */

typedef struct ivec_status {
    char status[32];        /* idle, processing, stopped, notready, busying, ... */
    char detail[48];        /* MediaEmpty, MediaJam, DoorOpen, ... */
    char support_code[16];  /* Canon support code, e.g. 1000 */
} ivec_status_t;

/* Parse the newest status found in buf (one or more IVEC documents).
 * Returns 1 when a status was found. */
int ivec_parse_status(const char *buf, ivec_status_t *out);

/* Map a status to a CUPS printer-state-reason ("" when none applies) and a
 * message.  Returns 1 when the printer needs the user's attention. */
int ivec_status_reason(const ivec_status_t *st, const char **reason, char *msg, int msglen);

/* Start/stop the background monitor (no-op when there is no back channel). */
void ivec_monitor_start(void);
/* Wait (up to timeout seconds) for the printer to finish the job, keeping
 * the user informed while it is held by an error, then stop the monitor. */
void ivec_monitor_finish(int timeout);

#endif
