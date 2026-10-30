/* SPDX-License-Identifier: MIT */
#include "termux_usb_gamepad.h"
#include "ipc.h"
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include "gamepad_wire.h"
int tusb_gamepad_snapshot(const char *path,struct tusb_gamepad_state *state) {
    if(!state){errno=EINVAL;return -1;}
    int fd=tusb_ipc_connect(path);if(fd<0)return -1;unsigned char bytes[48];
    int r=tusb_ipc_read(fd,bytes,sizeof(bytes)),e=errno;close(fd);errno=e;if(r)return -1;
    if(gp_decode(bytes,state)){errno=EPROTO;return -1;}return 0;
}
