#include "../protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void put32(uint8_t *p, uint32_t v) {
    for (unsigned i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (8 * i));
}
int main(void) {
    uint8_t p[WB_PACKET_SIZE] = {0};
    struct wb_request r;
    for (size_t n = 0; n < WB_PACKET_SIZE; ++n) assert(!wb_decode(p, n, &r));
    assert(!wb_decode(NULL, 64, &r)); assert(!wb_decode(p, 64, NULL));
    assert(wb_decode(p, 64, &r) && r.command == WB_EXIT);
    p[0] = WB_LIST_PROCESSES; assert(wb_decode(p, 64, &r));
    for (int code = 0; code < 256; ++code) {
        if (code == 0 || code == 4 || code == 11) continue;
        memset(p, 0, sizeof(p)); p[0] = (uint8_t)code;
        assert(!wb_decode(p, 64, &r));
    }
    memset(p, 0, sizeof(p)); p[0] = WB_EXEC;
    put32(p + 1, 23); put32(p + 5, 8); put32(p + 9, 7);
    memcpy(p + 13, "game.exe--hello", 15);
    assert(wb_decode(p, 64, &r));
    assert(!strcmp(r.name, "game.exe") && !strcmp(r.parameters, "--hello"));
    p[14] = 0; assert(!wb_decode(p, 64, &r)); p[14] = 'a';
    put32(p + 5, UINT32_MAX); assert(!wb_decode(p, 64, &r));
    put32(p + 5, 8); put32(p + 9, 52); assert(!wb_decode(p, 64, &r));
    memset(p, 'a', sizeof(p)); p[0] = WB_EXEC;
    put32(p + 1, 59); put32(p + 5, 51); put32(p + 9, 0);
    assert(wb_decode(p, 64, &r) && strlen(r.name) == 51);
    put32(p + 9, 1); assert(!wb_decode(p, 64, &r));
    memset(p, 'a', sizeof(p)); p[0] = WB_KILL_PROCESS; put32(p + 1, 59);
    assert(wb_decode(p, 64, &r) && strlen(r.name) == 59);
    put32(p + 1, 60); assert(!wb_decode(p, 64, &r));
    memset(p, 0, sizeof(p)); p[0] = WB_SET_AFFINITY;
    put32(p + 1, 9); put32(p + 5, 42); put32(p + 9, 3);
    assert(wb_decode(p, 64, &r) && r.pid == 42 && r.mask == 3);
    put32(p + 5, 0); assert(!wb_decode(p, 64, &r));
    p[13] = 8; put32(p + 1, 17); memcpy(p + 14, "game.exe", 8);
    assert(wb_decode(p, 64, &r) && !strcmp(r.name, "game.exe"));
    put32(p + 9, 0); assert(!wb_decode(p, 64, &r));
    memset(p, 0, sizeof(p)); p[0] = WB_MOUSE; put32(p + 1, 10);
    put32(p + 5, 0x8001); p[9] = 0xfe; p[10] = 0xff; p[11] = 17; p[15] = 1;
    assert(wb_decode(p, 64, &r) && r.dx == -2 && r.dy == 17 && r.feedback == 1);
    p[15] = 2; assert(!wb_decode(p, 64, &r));
    memset(p, 0, sizeof(p)); p[0] = WB_KEYBOARD; p[1] = 65; put32(p + 2, 2);
    assert(wb_decode(p, 64, &r) && r.vkey == 65 && r.flags == 2);
    memset(p, 0, sizeof(p)); p[0] = WB_BRING_TO_FRONT; put32(p + 1, 8);
    memcpy(p + 5, "game.exe", 8); put32(p + 13, 0x12345678); put32(p + 17, 0x90abcdef);
    assert(wb_decode(p, 64, &r) && r.window == UINT64_C(0x90abcdef12345678));
    put32(p + 1, 52); assert(!wb_decode(p, 64, &r));
    wb_process_packet(p, 2, 1, 42, UINT64_C(0x123456789), 5, 1, "game.exe");
    assert(p[0] == 5 && p[1] == 64 && p[5] == 2 && p[7] == 1 && p[9] == 42);
    assert(p[13] == 0x89 && p[17] == 1 && p[21] == 5 && p[25] == 1);
    assert(!strcmp((char *)p + 26, "game.exe"));
    for (int i = 34; i < 64; ++i) assert(p[i] == 0);
    wb_process_packet(p, 1, 0, 1, 0, 1, 0, "123456789012345678901234567890\xe4\xb8\xad");
    assert(p[56] == 0); // No partial three-byte character at byte 31 of the name.
    wb_cursor_packet(p, -2, 321);
    assert(p[0] == 13 && p[1] == 0xfe && p[2] == 0xff && p[3] == 0x41 && p[4] == 1);
    for (int i = 5; i < 64; ++i) assert(p[i] == 0);
    // Every command/length byte value is safe, including names/headers near the upper boundary.
    for (unsigned code = 0; code < 256; ++code) for (unsigned length = 0; length < 256; ++length) {
        memset(p, 0xff, sizeof(p)); p[0] = (uint8_t)code;
        put32(p + 1, length); put32(p + 5, length); put32(p + 9, length); p[13] = (uint8_t)length;
        (void)wb_decode(p, sizeof(p), &r);
    }
    puts("wine-bridge protocol tests passed");
    return 0;
}
