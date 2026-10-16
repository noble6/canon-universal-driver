/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <stdio.h>
#include <string.h>
#include "status.h"

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); fails++; } } while (0)

int main(void) {
    ivec_status_t st;
    const char *reason;
    char msg[256];
    const char *resp =
        "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\">"
        "<ivec:contents><ivec:operation>GetStatusResponse</ivec:operation><ivec:param_set servicetype=\"print\">"
        "<ivec:response>OK</ivec:response><ivec:status>stopped</ivec:status><ivec:status_detail>MediaEmpty</ivec:status_detail>"
        "<ivec:current_support_code>1000</ivec:current_support_code></ivec:param_set></ivec:contents></cmd>";

    CHECK(ivec_parse_status(resp, &st));
    CHECK(!strcmp(st.status, "stopped") && !strcmp(st.detail, "MediaEmpty") && !strcmp(st.support_code, "1000"));
    CHECK(ivec_status_reason(&st, &reason, msg, sizeof(msg)) == 1);
    CHECK(!strcmp(reason, "media-empty-error") && strstr(msg, "1000"));

    strcpy(st.detail, "MediaJam");
    CHECK(ivec_status_reason(&st, &reason, msg, sizeof(msg)) == 1 && !strcmp(reason, "media-jam-error"));
    strcpy(st.detail, "DoorOpen");
    CHECK(ivec_status_reason(&st, &reason, msg, sizeof(msg)) == 1 && !strcmp(reason, "door-open-error"));

    /* newest document wins */
    char two[2048];
    snprintf(two, sizeof(two), "%s%s", resp,
             "<cmd><ivec:status>processing</ivec:status><ivec:status_detail></ivec:status_detail></cmd>");
    CHECK(ivec_parse_status(two, &st) && !strcmp(st.status, "processing"));
    CHECK(ivec_status_reason(&st, &reason, msg, sizeof(msg)) == 0 && !reason[0]);

    CHECK(!ivec_parse_status("<cmd>no status here</cmd>", &st));
    if (!fails) printf("PASS status parser\n");
    return fails != 0;
}
