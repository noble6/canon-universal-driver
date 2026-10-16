/* Low-level IVEC USB probe: usbprobe [--clear-halt] [--reset] [file-to-send]
 * Prints what the printer returns on bulk IN for a few seconds. */
#include <libusb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    int clear = 0, reset = 0; const char *file = NULL;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--clear-halt")) clear = 1;
        else if (!strcmp(argv[i], "--reset")) reset = 1;
        else file = argv[i];
    }
    libusb_context *ctx; libusb_init(&ctx);
    libusb_device_handle *h = libusb_open_device_with_vid_pid(ctx, 0x04a9, 0x183b);
    if (!h) { puts("open failed"); return 1; }
    libusb_set_auto_detach_kernel_driver(h, 1);
    int r = libusb_claim_interface(h, 1);
    printf("claim: %s\n", libusb_error_name(r));
    if (clear) printf("clear halt in: %s out: %s\n", libusb_error_name(libusb_clear_halt(h, 0x82)), libusb_error_name(libusb_clear_halt(h, 0x01)));
    if (reset) printf("soft reset: %s\n", libusb_error_name(libusb_control_transfer(h, 0x21, 2, 0, 1, NULL, 0, 5000)));
    unsigned char st[1]; r = libusb_control_transfer(h, 0xA1, 1, 0, 1 << 8, st, 1, 2000);
    printf("port status: r=%d 0x%02x (paper-empty bit 0x20, select 0x10, not-error 0x08)\n", r, r == 1 ? st[0] : 0);
    if (file) {
        FILE *f = fopen(file, "rb"); static unsigned char b[1 << 20]; size_t n = fread(b, 1, sizeof b, f); fclose(f);
        int sent = 0; r = libusb_bulk_transfer(h, 0x01, b, (int)n, &sent, 3000);
        printf("write %zu: %s sent=%d\n", n, libusb_error_name(r), sent);
    }
    for (int i = 0; i < 6; i++) {
        unsigned char buf[8192]; int got = 0;
        r = libusb_bulk_transfer(h, 0x82, buf, sizeof buf, &got, 1000);
        printf("read %d: %s got=%d", i, libusb_error_name(r), got);
        if (got && getenv("FULL")) { buf[got] = 0; puts((char *)buf); }
        if (got) { buf[got] = 0; char *o = strstr((char *)buf, "<ivec:operation>"), *s = strstr((char *)buf, "<ivec:status>"), *d = strstr((char *)buf, "<ivec:status_detail"), *j = strstr((char *)buf, "<ivec:jobID>"); printf(" %.45s | %.30s | %.40s | %.30s", o ? o + 16 : "-", s ? s + 13 : "-", d ? d : "-", j ? j : "-"); }
        puts("");
    }
    libusb_release_interface(h, 1); libusb_close(h); libusb_exit(ctx);
    return 0;
}
