/* Wine-side process/input bridge. Wire format is defined by the Android caller. */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <winsock2.h>
#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>
#include <shellapi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <errno.h>
#include "protocol.h"

#define COMPLETION_JOB 1
#define COMPLETION_STOP 2
#define COMPLETION_FAILURE 3

struct bridge {
    SOCKET socket;
    HANDLE completion;
    volatile LONG stopping;
    const wchar_t *directory;
};

static void failure(const char *operation) {
    fprintf(stderr, "wine-bridge: %s failed (Windows error %lu)\n", operation, GetLastError());
    fflush(stderr);
}

static wchar_t *from_utf8(const char *text) {
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, NULL, 0);
    wchar_t *result;
    if (!count) return NULL;
    result = calloc((size_t)count, sizeof(*result));
    if (!result) return NULL;
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, result, count)) {
        free(result); return NULL;
    }
    return result;
}

static int send_packet(struct bridge *bridge, const uint8_t *packet,
                        const struct sockaddr_in *destination) {
    if (sendto(bridge->socket, (const char *)packet, WB_PACKET_SIZE, 0,
               (const struct sockaddr *)destination, sizeof(*destination)) == SOCKET_ERROR) {
        fprintf(stderr, "wine-bridge: send failed (%d)\n", WSAGetLastError());
        return 0;
    }
    return 1;
}

static DWORD process_id(const wchar_t *name) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    PROCESSENTRY32W entry = {0};
    DWORD pid = 0;
    if (snapshot == INVALID_HANDLE_VALUE) { failure("process snapshot"); return 0; }
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) do {
        if (!_wcsicmp(entry.szExeFile, name)) { pid = entry.th32ProcessID; break; }
    } while (Process32NextW(snapshot, &entry));
    CloseHandle(snapshot);
    return pid;
}

struct process_record {
    DWORD pid, affinity;
    uint64_t memory;
    BOOL wow64;
    char name[4 * MAX_PATH];
};

static void list_processes(struct bridge *bridge, const struct sockaddr_in *peer) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    PROCESSENTRY32W entry = {0};
    struct process_record *records = NULL;
    size_t count = 0, capacity = 0;
    uint8_t packet[WB_PACKET_SIZE];
    if (snapshot == INVALID_HANDLE_VALUE) { failure("list snapshot"); return; }
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) do {
        HANDLE process = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, entry.th32ProcessID);
        PROCESS_MEMORY_COUNTERS memory = {0};
        DWORD_PTR mask = 0, system_mask = 0;
        BOOL wow64 = FALSE;
        struct process_record *record;
        if (!process) continue; // An exited/inaccessible process cannot supply a live record.
        memory.cb = sizeof(memory);
        if (!GetProcessMemoryInfo(process, &memory, sizeof(memory))
            || !GetProcessAffinityMask(process, &mask, &system_mask)
            || !IsWow64Process(process, &wow64)) {
            CloseHandle(process); continue;
        }
        CloseHandle(process);
        if (count == UINT16_MAX) { fprintf(stderr, "wine-bridge: process list exceeds wire capacity\n"); break; }
        if (count == capacity) {
            size_t next_capacity = capacity ? capacity * 2 : 32;
            struct process_record *next = realloc(records, next_capacity * sizeof(*records));
            if (!next) { fprintf(stderr, "wine-bridge: process list allocation failed\n"); goto done; }
            records = next; capacity = next_capacity;
        }
        record = &records[count];
        memset(record, 0, sizeof(*record));
        if (!WideCharToMultiByte(CP_UTF8, 0, entry.szExeFile, -1,
                                record->name, sizeof(record->name), NULL, NULL)) continue;
        record->pid = entry.th32ProcessID; record->memory = memory.WorkingSetSize;
        record->affinity = (DWORD)mask; record->wow64 = wow64;
        ++count;
    } while (!InterlockedCompareExchange(&bridge->stopping, 0, 0) && Process32NextW(snapshot, &entry));
    for (size_t index = 0; index < count; ++index) {
        struct process_record *record = &records[index];
        wb_process_packet(packet, (uint16_t)count, (uint16_t)index, record->pid,
            record->memory, record->affinity, record->wow64, record->name);
        if (!send_packet(bridge, packet, peer)) break;
    }
done:
    free(records);
    CloseHandle(snapshot);
}

struct window_search { DWORD pid; HWND result; };
static BOOL CALLBACK find_window(HWND window, LPARAM value) {
    struct window_search *search = (struct window_search *)value;
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (pid == search->pid && IsWindowVisible(window) && !GetWindow(window, GW_OWNER)
        && !(GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW)) {
        search->result = window; return FALSE;
    }
    return TRUE;
}

static void execute(struct bridge *bridge, const struct wb_request *request,
                    const struct sockaddr_in *peer) {
    wchar_t *name = NULL, *parameters = NULL;
    HANDLE process = NULL;
    DWORD pid;
    switch (request->command) {
    case WB_EXIT:
        PostQueuedCompletionStatus(bridge->completion, 0, COMPLETION_STOP, NULL);
        return;
    case WB_LIST_PROCESSES:
        list_processes(bridge, peer);
        return;
    case WB_MOUSE: {
        POINT position;
        uint8_t packet[WB_PACKET_SIZE];
        DWORD dx = request->flags & MOUSEEVENTF_ABSOLUTE ? (uint16_t)request->dx : (DWORD)(LONG)request->dx;
        DWORD dy = request->flags & MOUSEEVENTF_ABSOLUTE ? (uint16_t)request->dy : (DWORD)(LONG)request->dy;
        mouse_event(request->flags, dx, dy,
                    (DWORD)(LONG)request->wheel, 0);
        if (request->feedback) {
            if (!GetCursorPos(&position)) { failure("cursor position"); return; }
            wb_cursor_packet(packet, (int16_t)position.x, (int16_t)position.y);
            send_packet(bridge, packet, peer);
        }
        return;
    }
    case WB_KEYBOARD:
        keybd_event(request->vkey, 0, request->flags, 0);
        return;
    default:
        name = from_utf8(request->name);
        if (!name) { fprintf(stderr, "wine-bridge: invalid UTF-8 name or allocation failure\n"); return; }
    }
    switch (request->command) {
    case WB_EXEC: {
        SHELLEXECUTEINFOW info = {0};
        parameters = from_utf8(request->parameters);
        if (!parameters) { fprintf(stderr, "wine-bridge: invalid UTF-8 parameters\n"); break; }
        info.cbSize = sizeof(info); info.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI;
        info.lpVerb = L"open"; info.lpFile = name; info.lpParameters = parameters;
        info.lpDirectory = bridge->directory; info.nShow = SW_SHOWNORMAL;
        if (!ShellExecuteExW(&info)) failure("execute program");
        if (info.hProcess) CloseHandle(info.hProcess);
        break;
    }
    case WB_KILL_PROCESS:
        pid = process_id(name);
        if (!pid) { fprintf(stderr, "wine-bridge: process not found for termination\n"); break; }
        process = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
        if (!process || !TerminateProcess(process, 1)) failure("terminate process");
        break;
    case WB_SET_AFFINITY:
        pid = request->pid ? request->pid : process_id(name);
        if (!pid) { fprintf(stderr, "wine-bridge: process not found for affinity\n"); break; }
        process = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
        if (!process || !SetProcessAffinityMask(process, request->mask)) failure("set affinity");
        break;
    case WB_BRING_TO_FRONT: {
        HWND window = (HWND)(uintptr_t)request->window;
        if (!window) {
            struct window_search search = {process_id(name), NULL};
            if (search.pid) EnumWindows(find_window, (LPARAM)&search);
            window = search.result;
        }
        if (!window || !IsWindow(window)) { fprintf(stderr, "wine-bridge: target window not found\n"); break; }
        ShowWindow(window, SW_RESTORE);
        SwitchToThisWindow(window, TRUE);
        break;
    }
    default:
        break;
    }
    if (process) CloseHandle(process);
    free(parameters); free(name);
}

static DWORD WINAPI serve(void *argument) {
    struct bridge *bridge = argument;
    uint8_t packet[WB_PACKET_SIZE + 1];
    while (!InterlockedCompareExchange(&bridge->stopping, 0, 0)) {
        struct sockaddr_in peer;
        int peer_size = sizeof(peer);
        int size = recvfrom(bridge->socket, (char *)packet, sizeof(packet), 0,
                            (struct sockaddr *)&peer, &peer_size);
        struct wb_request request;
        if (size == SOCKET_ERROR) {
            int error = WSAGetLastError();
            if (InterlockedCompareExchange(&bridge->stopping, 0, 0)) break;
            if (error == WSAEMSGSIZE) { fprintf(stderr, "wine-bridge: oversized datagram rejected\n"); continue; }
            fprintf(stderr, "wine-bridge: receive failed (%d)\n", error);
            PostQueuedCompletionStatus(bridge->completion, 0, COMPLETION_FAILURE, NULL);
            break;
        }
        if (peer.sin_family != AF_INET || peer.sin_addr.s_addr != htonl(INADDR_LOOPBACK)
            || peer.sin_port != htons(WB_ANDROID_PORT)) continue;
        if (!wb_decode(packet, (size_t)size, &request)) {
            fprintf(stderr, "wine-bridge: invalid request rejected (%d bytes)\n", size);
            continue;
        }
        execute(bridge, &request, &peer);
    }
    return 0;
}

/* Quote every argv element by the Windows C runtime rules, including trailing backslashes. */
static wchar_t *command_line(int count, wchar_t **arguments) {
    size_t capacity = 1, at = 0;
    wchar_t *line;
    for (int i = 0; i < count; ++i) {
        size_t length = wcslen(arguments[i]);
        if (length > 16382 || capacity + 2 * length + 3 > 32767) return NULL;
        capacity += 2 * length + 3;
    }
    line = calloc(capacity, sizeof(*line));
    if (!line) return NULL;
    for (int i = 0; i < count; ++i) {
        const wchar_t *p = arguments[i];
        if (i) line[at++] = L' ';
        line[at++] = L'"';
        while (*p) {
            size_t slashes = 0;
            while (*p == L'\\') { ++slashes; ++p; }
            if (!*p || *p == L'"') slashes *= 2;
            while (slashes--) line[at++] = L'\\';
            if (*p == L'"') line[at++] = L'\\';
            if (*p) line[at++] = *p++;
        }
        line[at++] = L'"';
    }
    return line;
}

int wmain(int argc, wchar_t **argv) {
    struct bridge bridge = {INVALID_SOCKET, NULL, 0, NULL};
    WSADATA wsa;
    HANDLE job = NULL, thread = NULL;
    PROCESS_INFORMATION child = {0};
    STARTUPINFOW startup = {0};
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    JOBOBJECT_ASSOCIATE_COMPLETION_PORT association = {0};
    struct sockaddr_in address = {0}, android = {0};
    uint8_t init[WB_PACKET_SIZE] = {WB_INIT};
    DWORD affinity = 0, result = 3;
    int target = 1, winsock = 0;
    wchar_t *line = NULL;
    while (target < argc && (!wcscmp(argv[target], L"/dir") || !wcscmp(argv[target], L"/affinity"))) {
        if (target + 1 >= argc) goto usage;
        if (!wcscmp(argv[target], L"/dir")) bridge.directory = argv[target + 1];
        else {
            wchar_t *end;
            unsigned long long value;
            errno = 0;
            value = wcstoull(argv[target + 1], &end, 16);
            if (!argv[target + 1][0] || *end || errno || !value || value > UINT32_MAX) goto usage;
            affinity = (DWORD)value;
        }
        target += 2;
    }
    if (target >= argc) goto usage;
    line = command_line(argc - target, argv + target);
    if (!line) { fprintf(stderr, "wine-bridge: command line too long or allocation failed\n"); return 2; }
    job = CreateJobObjectW(NULL, NULL);
    if (!job) { failure("create job"); goto cleanup; }
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        failure("set job limits"); goto cleanup;
    }
    bridge.completion = CreateIoCompletionPort(INVALID_HANDLE_VALUE, NULL, 0, 1);
    if (!bridge.completion) { failure("create job completion port"); goto cleanup; }
    association.CompletionKey = (void *)COMPLETION_JOB;
    association.CompletionPort = bridge.completion;
    if (!SetInformationJobObject(job, JobObjectAssociateCompletionPortInformation, &association, sizeof(association))) {
        failure("associate job completion"); goto cleanup;
    }
    if (WSAStartup(MAKEWORD(2, 2), &wsa)) { fprintf(stderr, "wine-bridge: WSAStartup failed\n"); goto cleanup; }
    winsock = 1;
    bridge.socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (bridge.socket == INVALID_SOCKET) { failure("create UDP socket"); goto cleanup; }
    // Exclusive ownership: a second bridge must fail, never steal an active session's packets.
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(WB_SERVER_PORT);
    if (bind(bridge.socket, (const struct sockaddr *)&address, sizeof(address)) == SOCKET_ERROR) {
        fprintf(stderr, "wine-bridge: bind 127.0.0.1:%d failed (%d)\n", WB_SERVER_PORT, WSAGetLastError()); goto cleanup;
    }
    thread = CreateThread(NULL, 0, serve, &bridge, 0, NULL);
    if (!thread) { failure("create server thread"); goto cleanup; }
    android.sin_family = AF_INET; android.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    android.sin_port = htons(WB_ANDROID_PORT);
    if (!send_packet(&bridge, init, &android)) goto cleanup;
    startup.cb = sizeof(startup);
    if (!CreateProcessW(argv[target], line, NULL, NULL, FALSE, CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT,
                        NULL, bridge.directory, &startup, &child)) {
        failure("start target"); result = 4; goto cleanup;
    }
    if (!AssignProcessToJobObject(job, child.hProcess)) { failure("assign target job"); goto startup_failure; }
    // Wine can accept Job affinity limits without applying them. Set the suspended
    // root process explicitly; descendants inherit its process affinity normally.
    if (affinity && !SetProcessAffinityMask(child.hProcess, affinity)) {
        failure("set target affinity"); goto startup_failure;
    }
    if (ResumeThread(child.hThread) == (DWORD)-1) { failure("resume target"); goto startup_failure; }
    CloseHandle(child.hThread); child.hThread = NULL;
    for (;;) {
        DWORD message;
        ULONG_PTR key;
        OVERLAPPED *value;
        if (!GetQueuedCompletionStatus(bridge.completion, &message, &key, &value, INFINITE)) {
            failure("wait for job completion"); break;
        }
        if (key == COMPLETION_STOP) { result = 0; break; }
        if (key == COMPLETION_FAILURE) break;
        if (key == COMPLETION_JOB && message == JOB_OBJECT_MSG_ACTIVE_PROCESS_ZERO) {
            if (!GetExitCodeProcess(child.hProcess, &result)) { failure("get target exit code"); result = 3; }
            break;
        }
    }
    goto cleanup;
startup_failure:
    // Assignment may have failed; terminate the still-suspended target explicitly.
    TerminateProcess(child.hProcess, 3);
cleanup:
    InterlockedExchange(&bridge.stopping, 1);
    if (bridge.socket != INVALID_SOCKET) {
        shutdown(bridge.socket, SD_BOTH);
        closesocket(bridge.socket);
    }
    if (thread) { WaitForSingleObject(thread, INFINITE); CloseHandle(thread); }
    if (child.hThread) CloseHandle(child.hThread);
    if (child.hProcess) CloseHandle(child.hProcess);
    if (job) CloseHandle(job);
    if (bridge.completion) CloseHandle(bridge.completion);
    if (winsock) WSACleanup();
    free(line);
    return (int)result;
usage:
    fprintf(stderr, "Usage: wine-bridge.exe [/dir DIRECTORY] [/affinity HEXMASK] PROGRAM [ARGUMENTS...]\n");
    return 2;
}
