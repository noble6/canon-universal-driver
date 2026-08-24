/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <stdio.h>
#include <stdlib.h>
#include <libusb-1.0/libusb.h>

/*
 * libusb transport backend for the Canon Universal Driver.
 * Opens the printer by VID/PID and exchanges data over bulk endpoints.
 */

struct usb_backend_ctx {
    libusb_context *usb_ctx;
    libusb_device_handle *dev_handle;
    int endpoint_in;
    int endpoint_out;
};

void* usb_backend_open(uint16_t vid, uint16_t pid) {
    struct usb_backend_ctx *ctx = calloc(1, sizeof(struct usb_backend_ctx));
    if (!ctx) return NULL;

    if (libusb_init(&ctx->usb_ctx) < 0) {
        free(ctx);
        return NULL;
    }

    ctx->dev_handle = libusb_open_device_with_vid_pid(ctx->usb_ctx, vid, pid);
    if (!ctx->dev_handle) {
        fprintf(stderr, "Failed to open USB device %04x:%04x\n", vid, pid);
        libusb_exit(ctx->usb_ctx);
        free(ctx);
        return NULL;
    }

    /* printer class devices expose the printing interface at index 0 */
    if (libusb_claim_interface(ctx->dev_handle, 0) < 0) {
        fprintf(stderr, "Failed to claim USB interface\n");
        libusb_close(ctx->dev_handle);
        libusb_exit(ctx->usb_ctx);
        free(ctx);
        return NULL;
    }

    /* bulk OUT/IN pair used by Canon print engines */
    ctx->endpoint_in = 0x81;
    ctx->endpoint_out = 0x01;

    return ctx;
}

int usb_backend_write(void* handle, const unsigned char* data, int length) {
    struct usb_backend_ctx *ctx = (struct usb_backend_ctx*)handle;
    int actual_length;
    int r = libusb_bulk_transfer(ctx->dev_handle, ctx->endpoint_out, (unsigned char*)data, length, &actual_length, 2000);
    if (r == 0 && actual_length == length) return 0;
    return -1;
}

void usb_backend_close(void* handle) {
    struct usb_backend_ctx *ctx = (struct usb_backend_ctx*)handle;
    if (ctx) {
        libusb_release_interface(ctx->dev_handle, 0);
        libusb_close(ctx->dev_handle);
        libusb_exit(ctx->usb_ctx);
        free(ctx);
    }
}
