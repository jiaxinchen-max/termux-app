/* SPDX-License-Identifier: MIT */
#ifndef TERMUX_USB_GAMEPAD_H
#define TERMUX_USB_GAMEPAD_H
#include <stddef.h>
#include <stdint.h>
#define TUSB_HID_FIELDS 128
struct tusb_gamepad_state { uint32_t sequence, connected, buttons; int16_t axes[8]; uint32_t hat; };
struct tusb_hid_field { uint32_t offset; int32_t minimum, maximum; uint16_t report, bits, kind, index; };
struct tusb_hid_descriptor { size_t count; int report_ids; struct tusb_hid_field fields[TUSB_HID_FIELDS]; };
int tusb_hid_parse(const void *descriptor,size_t length,struct tusb_hid_descriptor *parsed);
int tusb_hid_report(const struct tusb_hid_descriptor *parsed,const void *report,size_t length,struct tusb_gamepad_state *state);
int tusb_gamepad_snapshot(const char *socket_path,struct tusb_gamepad_state *state);
#endif
