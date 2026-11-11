#include "protocol.h"
#include <string.h>

static uint16_t u16(const uint8_t *p) {
    return (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
}
static uint32_t u32(const uint8_t *p) {
    return (uint32_t)u16(p) | ((uint32_t)u16(p + 2) << 16);
}
static uint64_t u64(const uint8_t *p) {
    return (uint64_t)u32(p) | ((uint64_t)u32(p + 4) << 32);
}
static void put16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8);
}
static void put32(uint8_t *p, uint32_t v) {
    put16(p, (uint16_t)v); put16(p + 2, (uint16_t)(v >> 16));
}
static void put64(uint8_t *p, uint64_t v) {
    put32(p, (uint32_t)v); put32(p + 4, (uint32_t)(v >> 32));
}
static int string_field(char *out, const uint8_t *packet, size_t offset,
                        size_t size, int allow_empty) {
    if ((!allow_empty && !size) || offset > WB_PACKET_SIZE || size >= WB_PACKET_SIZE
        || size > WB_PACKET_SIZE - offset || memchr(packet + offset, 0, size)) return 0;
    memcpy(out, packet + offset, size);
    out[size] = 0;
    return 1;
}

int wb_decode(const uint8_t *p, size_t length, struct wb_request *r) {
    uint32_t a, b;
    if (!p || !r || length != WB_PACKET_SIZE) return 0;
    memset(r, 0, sizeof(*r));
    r->command = p[0];
    switch (r->command) {
    case WB_EXIT: case WB_LIST_PROCESSES:
        return 1;
    case WB_EXEC:
        a = u32(p + 5); b = u32(p + 9);
        if (a > 51 || b > 51 || a + b > 51 || u32(p + 1) != a + b + 8) return 0;
        return string_field(r->name, p, 13, a, 0)
            && string_field(r->parameters, p, 13 + a, b, 1);
    case WB_KILL_PROCESS:
        return string_field(r->name, p, 5, u32(p + 1), 0);
    case WB_SET_AFFINITY:
        r->pid = u32(p + 5); r->mask = u32(p + 9);
        a = p[13];
        if (!r->mask || u32(p + 1) != 9 + a || (r->pid && a)) return 0;
        return string_field(r->name, p, 14, a, r->pid != 0);
    case WB_MOUSE:
        // The Java sender declares ten payload bytes, then appends this feedback byte.
        if (u32(p + 1) != 10 || p[15] > 1) return 0;
        r->flags = u32(p + 5); r->dx = (int16_t)u16(p + 9);
        r->dy = (int16_t)u16(p + 11); r->wheel = (int16_t)u16(p + 13);
        r->feedback = p[15];
        return 1;
    case WB_KEYBOARD:
        // Keyboard messages have no four-byte payload-size header.
        r->vkey = p[1]; r->flags = u32(p + 2);
        return 1;
    case WB_BRING_TO_FRONT:
        a = u32(p + 1);
        if (a > WB_PACKET_SIZE - 13 || !string_field(r->name, p, 5, a, 1)) return 0;
        r->window = u64(p + 5 + a);
        return a != 0 || r->window != 0;
    default:
        return 0;
    }
}

void wb_process_packet(uint8_t p[WB_PACKET_SIZE], uint16_t count, uint16_t index,
    uint32_t pid, uint64_t memory, uint32_t affinity, int wow64, const char *name) {
    size_t length = strlen(name);
    if (length > 31) {
        length = 31;
        // Do not split a UTF-8 code point at the fixed Java name-field boundary.
        while (length && (((unsigned char)name[length] & 0xc0) == 0x80)) --length;
    }
    memset(p, 0, WB_PACKET_SIZE);
    p[0] = WB_GET_PROCESS;
    put32(p + 1, WB_PACKET_SIZE); put16(p + 5, count); put16(p + 7, index);
    put32(p + 9, pid); put64(p + 13, memory); put32(p + 21, affinity);
    p[25] = wow64 != 0;
    memcpy(p + 26, name, length);
}

void wb_cursor_packet(uint8_t p[WB_PACKET_SIZE], int16_t x, int16_t y) {
    memset(p, 0, WB_PACKET_SIZE);
    p[0] = WB_CURSOR; put16(p + 1, (uint16_t)x); put16(p + 3, (uint16_t)y);
}
