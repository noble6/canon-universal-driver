/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef CANON_IVEC_H
#define CANON_IVEC_H

#include <stdio.h>
#include <stddef.h>

/*
 * IVEC is the job-control protocol used by Canon PIXMA/MAXIFY inkjets
 * since ~2013: a sequence of small XML command documents written
 * back-to-back on the data channel, with page images sent as PWG
 * Raster blobs right after each SendData command.
 *
 * Two job framings exist in the field:
 *   V1: a throw-away "set clock" job (StartJob, ModeShift, BJL SetTime,
 *       EndJob) followed by the real job with a short StartJob.
 *   V2: a single job whose StartJob carries host information, followed by
 *       SetJobConfiguration (clock) when the model reports a datetime
 *       capability.
 */

typedef struct ivec_job {
    FILE *out;
    int protocol;           /* 1 or 2 */
    int send_datetime;      /* V2 only: emit SetJobConfiguration */
    char job_id[9];         /* "00000001" (V1) or "00000002" (V2) */
    char description[64];   /* job uuid without the urn:uuid: prefix */
    char host_env[32];      /* "linux" */
} ivec_job_t;

typedef struct ivec_page_config {
    const char *papersize;  /* PWG self-describing name, e.g. iso_a4_210x297mm */
    const char *papertype;  /* stationery, photographic, custom-media-type-canon-N, ... */
    int borderless;
    int monochrome;
    int duplex;
    const char *printquality;  /* best, fast_draft, ... or NULL to leave it to the printer */
} ivec_page_config_t;

void ivec_job_init(ivec_job_t *job, FILE *out, int protocol, int send_datetime,
                   const char *uuid, const char *host_env);
int ivec_start_job(ivec_job_t *job);
int ivec_set_configuration(ivec_job_t *job, const ivec_page_config_t *cfg);
int ivec_send_page(ivec_job_t *job, int more_pages, const unsigned char *pwg, size_t len);
int ivec_end_job(ivec_job_t *job);

/* Maintenance job: operation Cleaning / RollerCleaning / TestPrint with its
 * capability "type" (regular, deep, choke, roller, platen, nozzle_check, ...)
 * and optional ink group. */
int ivec_maintenance(FILE *out, int datetime, int host_env, const char *uuid, const char *operation,
                     const char *type, const char *inkgroup);

/* Older engines: maintenance as a BJL line such as "@Cleaning=1ALL". */
int ivec_maintenance_bjl(ivec_job_t *job, const char *command);

#endif
