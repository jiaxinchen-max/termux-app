/* SPDX-License-Identifier: MIT */
#ifndef TUSB_CAMERA_FRAME_H
#define TUSB_CAMERA_FRAME_H
#include <stddef.h>
#include <stdint.h>
enum tusb_camera_format { TUSB_CAMERA_YUYV, TUSB_CAMERA_MJPEG };
struct tusb_camera_decoder { void *jpeg; };
/* Decode exactly the negotiated dimensions into tightly packed RGB24.
 * Reject short input/stride and JPEG headers that disagree with UVC metadata.
 * A failed compressed decode may change output; publish only on success. */
int tusb_camera_decode(struct tusb_camera_decoder *, enum tusb_camera_format,
    const void *, size_t, unsigned, unsigned, size_t, void *, size_t);
void tusb_camera_decoder_close(struct tusb_camera_decoder *);
#endif
