#ifndef WINE_BRIDGE_PROTOCOL_H
#define WINE_BRIDGE_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#define WB_PACKET_SIZE 64
#define WB_SERVER_PORT 7946
#define WB_ANDROID_PORT 7947

enum wb_command {
    WB_EXIT = 0, WB_INIT = 1, WB_EXEC = 2, WB_KILL_PROCESS = 3,
    WB_LIST_PROCESSES = 4, WB_GET_PROCESS = 5, WB_SET_AFFINITY = 6,
    WB_MOUSE = 7, WB_KEYBOARD = 11, WB_BRING_TO_FRONT = 12, WB_CURSOR = 13
};

struct wb_request {
    uint8_t command, vkey, feedback;
    uint32_t pid, mask, flags;
    uint64_t window;
    int16_t dx, dy, wheel;
    char name[WB_PACKET_SIZE], parameters[WB_PACKET_SIZE];
};

int wb_decode(const uint8_t *packet, size_t length, struct wb_request *request);
void wb_process_packet(uint8_t packet[WB_PACKET_SIZE], uint16_t count, uint16_t index,
    uint32_t pid, uint64_t memory, uint32_t affinity, int wow64, const char *name);
void wb_cursor_packet(uint8_t packet[WB_PACKET_SIZE], int16_t x, int16_t y);

#endif
