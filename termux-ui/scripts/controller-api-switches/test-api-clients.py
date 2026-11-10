#!/usr/bin/env python3
"""Exercise the real patched DLL receive branches with deterministic UDP responses.

Usage: test-api-clients.py <patched Wine source> <output directory>
Windows entry points are stubbed; this verifies API selection, not a device run.
"""
from pathlib import Path
import subprocess
import sys

source, output = map(Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
def block(text, start):
    begin = text.index(start)
    opening = text.index('{', begin)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[begin:end]

dinput = block((source/'dlls/dinput/gamepad.c').read_text(), 'static BOOL get_gamepad_request(')
xinput = block((source/'dlls/xinput1_3/main.c').read_text(), 'if (buffer[0] == REQUEST_CODE_GET_GAMEPAD)')
program = r'''
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <arpa/inet.h>
#define BOOL int
#define TRUE 1
#define FALSE 0
#define BUFFER_SIZE 64
#define CLIENT_PORT 7947
#define SOCKET_ERROR -1
#define REQUEST_CODE_GET_GAMEPAD 8
#define MAPPER_TYPE_XINPUT 1
#define MAPPER_TYPE_STANDARD 0
static int server_sock, connected_gamepad_id;
static char mapper_type;
static unsigned char response[64];
static int fixture_sendto(int sock, const char *buffer, int size, int flags,
                         const struct sockaddr *addr, int addrsize) {
    assert(size == 64 && buffer[0] == 8 && buffer[1] == 0 && buffer[2] == 1);
    return size;
}
static int fixture_recvfrom(int sock, char *buffer, int size, int flags, void *addr, int addrsize) {
    memcpy(buffer, response, 64);
    return 64;
}
#define sendto fixture_sendto
#define recvfrom fixture_recvfrom
'''+dinput+r'''
static struct {int id, connected, crit;} controller;
static int started, start_event;
#define EnterCriticalSection(x) ((void)0)
#define LeaveCriticalSection(x) ((void)0)
#define SetEvent(x) ((void)0)
static void controller_init(void) { controller.connected = TRUE; }
static void xinput_response(char *buffer) {
'''+xinput+r'''
}
int main(void) {
    int cases = 0;
    for (int x = 0; x <= 1; x++) for (int d = 0; d <= 1; d++)
    for (int mapper = 0; mapper <= 1; mapper++) for (int present = 0; present <= 1; present++) {
        int id = present ? 123 : 0;
        int length = 3;
        char name[64] = {0};
        memset(response, 0, sizeof response);
        response[0] = 8;
        memcpy(response + 1, &id, sizeof id);
        response[5] = (mapper ? 2 : 1) | (x ? 4 : 0) | (d ? 8 : 0);
        memcpy(response + 6, &length, sizeof length);
        memcpy(response + 10, "Pad", 3);
        connected_gamepad_id = 999;
        assert(get_gamepad_request(TRUE, name) == (d && present));
        assert(connected_gamepad_id == (d && present ? id : 0));
        if (d && present) { assert(mapper_type == mapper); assert(!strcmp(name, "Pad")); }
        controller.id = 999;
        controller.connected = !(x && present);
        xinput_response((char *)response);
        assert(controller.connected == (x && present));
        assert(controller.id == (x && present ? id : 0));
        cases++;
    }
    printf("%d response scenarios passed (both real DLL API branches).\n", cases);
}
'''
c = output/'api-clients.c'
c.write_text(program)
exe = output/'api-clients'
# The upstream wire code intentionally uses unaligned int reads from byte offset 1.
subprocess.run(['gcc', '-std=c99', '-O1', '-g', '-fsanitize=address,undefined',
                '-fno-sanitize=alignment', str(c), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)

creation = block((source/'dlls/dinput/gamepad.c').read_text(), 'HRESULT gamepad_create_device(')
creation_program = r'''
#include <assert.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
typedef int HRESULT;
typedef int GUID;
typedef uintptr_t DWORD_PTR;
typedef struct {int unused;} IDirectInputDevice8W;
typedef struct {int dwSize, dwHeaderSize, dwObj, dwHow;} DIPROPHEADER;
struct dinput {int dwVersion;};
struct instance {int dwDevType;};
struct debug_info {DWORD_PTR Spare[1];};
struct base {
    IDirectInputDevice8W IDirectInputDevice8W_iface;
    struct {struct debug_info *DebugInfo;} crit;
    void *read_event;
    struct instance instance;
    struct {int dwDevType, dwFirmwareRevision, dwHardwareRevision;} caps;
    int dwCoopLevel;
};
struct gamepad {struct base base;};
static const GUID GUID_Joystick = 1;
static const int gamepad_vtbl;
static struct debug_info debug;
static HRESULT enum_status;
static int format_calls, releases;
#define TRACE(...) ((void)0)
#define DIERR_DEVICENOTREG (-1)
#define E_OUTOFMEMORY (-2)
#define DI_OK 0
#define TRUE 1
#define FALSE 0
#define DIPH_DEVICE 0
#define DISCL_NONEXCLUSIVE 1
#define DISCL_BACKGROUND 2
#define FAILED(x) ((x) < 0)
#define CreateEventW(...) NULL
#define gamepad_enum_objects(...) ((void)0)
static int IsEqualGUID(const GUID *a, const GUID *b) {return *a == *b;}
static void dinput_device_init(struct base *base, const int *vtbl, const GUID *guid, struct dinput *input) {
    base->crit.DebugInfo = &debug;
}
static HRESULT gamepad_enum_device(int type, int flags, struct instance *instance, int version) {
    instance->dwDevType = 4;
    return enum_status;
}
static HRESULT dinput_device_init_device_format(IDirectInputDevice8W *iface) {
    format_calls++;
    return DI_OK;
}
static void IDirectInputDevice_Release(IDirectInputDevice8W *iface) {releases++; free(iface);}
'''+creation+r'''
int main(void) {
    struct dinput input = {0x0800};
    IDirectInputDevice8W *out;
    enum_status = DIERR_DEVICENOTREG;
    out = (void *)1;
    assert(gamepad_create_device(&input, &GUID_Joystick, &out) == DIERR_DEVICENOTREG);
    assert(out == NULL && format_calls == 0 && releases == 1);
    enum_status = DI_OK;
    assert(gamepad_create_device(&input, &GUID_Joystick, &out) == DI_OK);
    assert(out != NULL && format_calls == 1);
    IDirectInputDevice_Release(out);
    GUID keyboard = 2;
    out = (void *)1;
    assert(gamepad_create_device(&input, &keyboard, &out) == DIERR_DEVICENOTREG);
    assert(out == NULL && format_calls == 1 && releases == 2);
    puts("3 real DirectInput creation scenarios passed (enum failure, success, unrelated GUID).");
}
'''
c = output/'api-create-device.c'
c.write_text(creation_program)
exe = output/'api-create-device'
subprocess.run(['gcc', '-std=c99', '-O1', '-g', '-fsanitize=address,undefined',
                str(c), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
