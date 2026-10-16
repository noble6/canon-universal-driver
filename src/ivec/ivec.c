/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * IVEC command generation.  The byte layout below (declaration spelling,
 * namespace sets, element order) mirrors what Canon's Windows/Linux drivers
 * put on the wire; printers have been seen to be picky, so keep it exact.
 */
#include "ivec.h"

#include <string.h>
#include <time.h>

#define XML_DECL   "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
#define XML_DECL2  "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
#define NS_IVEC    "xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\""
#define NS_VCN     "xmlns:vcn=\"http://www.canon.com/ns/cmd/2008/07/canon/\""
#define OPEN_CMD   "<cmd " NS_IVEC ">"
#define OPEN_CMDV  "<cmd " NS_IVEC " " NS_VCN ">"
#define PARAMS     "<ivec:param_set servicetype=\"%s\"><ivec:jobID>%s</ivec:jobID>"
#define CLOSE      "</ivec:param_set></ivec:contents></cmd>"

static void timestamp(char out[15]) {
    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    strftime(out, 15, "%Y%m%d%H%M%S", &tm);
}

static void bjl(FILE *f, const char *body);

static int op_svc(FILE *f, int vendor_ns, const char *operation, const char *service, const char *job_id) {
    return fprintf(f, XML_DECL "%s<ivec:contents><ivec:operation>%s</ivec:operation>" PARAMS,
                   vendor_ns ? OPEN_CMDV : OPEN_CMD, operation, service, job_id) < 0 ? -1 : 0;
}

static int op(FILE *f, int vendor_ns, const char *operation, const char *job_id) {
    return op_svc(f, vendor_ns, operation, "print", job_id);
}

static int close_cmd(FILE *f) {
    return fputs(CLOSE, f) < 0 ? -1 : 0;
}

void ivec_job_init(ivec_job_t *job, FILE *out, int protocol, int send_datetime,
                   const char *uuid, const char *host_env) {
    memset(job, 0, sizeof(*job));
    job->out = out;
    job->protocol = protocol == 1 ? 1 : 2;
    job->send_datetime = send_datetime;
    strcpy(job->job_id, job->protocol == 1 ? "00000001" : "00000002");
    if (uuid) {
        const char *p = strncmp(uuid, "urn:uuid:", 9) == 0 ? uuid + 9 : uuid;
        strncpy(job->description, p, sizeof(job->description) - 1);
    }
    strncpy(job->host_env, host_env && *host_env ? host_env : "linux", sizeof(job->host_env) - 1);
}

static int short_start_job(ivec_job_t *job) {
    FILE *f = job->out;
    op(f, 1, "StartJob", job->job_id);
    fputs("<ivec:bidi>0</ivec:bidi><vcn:forcepmdetection>OFF</vcn:forcepmdetection>", f);
    return close_cmd(f);
}

int ivec_start_job(ivec_job_t *job) {
    FILE *f = job->out;
    char now[15];
    timestamp(now);

    if (job->protocol == 1) {
        /* Older engines get their clock set by a separate BJL job first */
        short_start_job(job);
        op(f, 1, "VendorCmd", job->job_id);
        fputs("<vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode>", f);
        close_cmd(f);
        char body[64];
        snprintf(body, sizeof(body), "ControlMode=Common\nSetTime=%s\n", now);
        bjl(f, body);
        ivec_end_job(job);
        return short_start_job(job);
    }

    op(f, 1, "StartJob", job->job_id);
    fprintf(f, "<ivec:bidi>0</ivec:bidi><vcn:forcepmdetection>OFF</vcn:forcepmdetection>"
               "<ivec:jobname/><ivec:username/><ivec:computername/>"
               "<ivec:job_description><![CDATA[%s]]></ivec:job_description>"
               "<vcn:host_environment>%s</vcn:host_environment>",
            job->description, job->host_env);
    close_cmd(f);

    if (job->send_datetime) {
        fprintf(f, XML_DECL2 OPEN_CMD "<ivec:contents><ivec:operation>SetJobConfiguration</ivec:operation>"
                   PARAMS "<ivec:mismatch_mode>none</ivec:mismatch_mode><ivec:datetime>%s</ivec:datetime>",
                "print", job->job_id, now);
        close_cmd(f);
    }
    return ferror(f) ? -1 : 0;
}

int ivec_set_configuration(ivec_job_t *job, const ivec_page_config_t *cfg) {
    FILE *f = job->out;
    op(f, 0, "SetConfiguration", job->job_id);
    fprintf(f, "<ivec:papersize>%s</ivec:papersize><ivec:papertype>%s</ivec:papertype>"
               "<ivec:borderlessprint>%s</ivec:borderlessprint>"
               "<ivec:printcolormode>%s</ivec:printcolormode>"
               "<ivec:duplexprint>%s</ivec:duplexprint>",
            cfg->papersize, cfg->papertype, cfg->borderless ? "ON" : "OFF",
            cfg->monochrome ? "monochrome" : "color", cfg->duplex ? "ON" : "OFF");
    return close_cmd(f);
}

int ivec_send_page(ivec_job_t *job, int more_pages, const unsigned char *pwg, size_t len) {
    FILE *f = job->out;
    op(f, 1, "VendorCmd", job->job_id);
    fprintf(f, "<vcn:ijoperation>SetPageConfiguration</vcn:ijoperation><vcn:nextpage>%s</vcn:nextpage>",
            more_pages ? "ON" : "OFF");
    close_cmd(f);

    op(f, 0, "SendData", job->job_id);
    fprintf(f, "<ivec:format>PWGRaster</ivec:format><ivec:datasize>%zu</ivec:datasize>", len);
    close_cmd(f);

    if (fwrite(pwg, 1, len, f) != len) return -1;
    return ferror(f) ? -1 : 0;
}

int ivec_end_job(ivec_job_t *job) {
    op(job->out, 0, "EndJob", job->job_id);
    close_cmd(job->out);
    return fflush(job->out) == 0 && !ferror(job->out) ? 0 : -1;
}

static void bjl(FILE *f, const char *body) {
    static const unsigned char hdr[] = { 0x1b, '[', 'K', 0x02, 0x00, 0x00, 0x1f };
    fwrite(hdr, 1, sizeof(hdr), f);
    fprintf(f, "BJLSTART\n%sBJLEND\n", body);
}

int ivec_maintenance(FILE *f, int datetime, int host_env, const char *uuid, const char *operation,
                     const char *type, const char *inkgroup) {
    static const char id[] = "00000002";
    const char *desc = uuid && !strncmp(uuid, "urn:uuid:", 9) ? uuid + 9 : (uuid ? uuid : "");
    char now[15];
    timestamp(now);

    /* the vendor namespace is only declared when a vcn: element follows */
    op_svc(f, host_env, "StartJob", "maintenance", id);
    fprintf(f, "<ivec:bidi>0</ivec:bidi><ivec:jobname/><ivec:username/><ivec:computername/>"
               "<ivec:job_description><![CDATA[%s]]></ivec:job_description>", desc);
    if (host_env) fputs("<vcn:host_environment>linux</vcn:host_environment>", f);
    close_cmd(f);
    if (datetime) {
        op_svc(f, 1, "SetJobConfiguration", "maintenance", id);
        fprintf(f, "<ivec:datetime>%s</ivec:datetime>", now);
        close_cmd(f);
    }
    /* the operation's own parameters precede the job id here */
    fprintf(f, XML_DECL2 OPEN_CMD "<ivec:contents><ivec:operation>%s</ivec:operation>"
               "<ivec:param_set servicetype=\"maintenance\">", operation);
    if (inkgroup && *inkgroup) fprintf(f, "<ivec:inkgroup>%s</ivec:inkgroup>", inkgroup);
    if (type && *type) fprintf(f, "<ivec:type>%s</ivec:type>", type);
    fprintf(f, "<ivec:jobID>%s</ivec:jobID>", id);
    close_cmd(f);
    op_svc(f, 0, "EndJob", "maintenance", id);
    close_cmd(f);
    return fflush(f) == 0 && !ferror(f) ? 0 : -1;
}

int ivec_maintenance_bjl(ivec_job_t *job, const char *command) {
    FILE *f = job->out;
    char body[128];

    /* framed like the model's print jobs, minus the V1 clock job */
    if (job->protocol == 1)
        short_start_job(job);
    else
        ivec_start_job(job);
    op(f, 1, "VendorCmd", job->job_id);
    fputs("<vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode>", f);
    close_cmd(f);
    snprintf(body, sizeof(body), "%s\n", command);
    bjl(f, body);
    return ivec_end_job(job);
}
