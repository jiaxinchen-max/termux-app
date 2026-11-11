/* Real Win32 test program: logs argv, window/input events and creates descendants. */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdlib.h>
#include <wchar.h>

static const wchar_t *log_path, *stop_path;

static void event(const char *format, ...) {
    char line[4096];
    DWORD written;
    va_list args;
    HANDLE file;
    va_start(args, format);
    int size = vsnprintf(line, sizeof(line) - 2, format, args);
    va_end(args);
    if (size < 0 || size > (int)sizeof(line) - 2) ExitProcess(90);
    line[size++] = '\n';
    file = CreateFileW(log_path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                        NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) ExitProcess(91);
    if (!WriteFile(file, line, (DWORD)size, &written, NULL) || written != (DWORD)size) ExitProcess(92);
    CloseHandle(file);
}

static void wide_event(const char *name, const wchar_t *value) {
    char utf8[3072];
    if (!WideCharToMultiByte(CP_UTF8, 0, value, -1, utf8, sizeof(utf8), NULL, NULL)) ExitProcess(93);
    event("%s=%s", name, utf8);
}

static int spawn(const wchar_t *mode) {
    wchar_t executable[1024], command[4096];
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    GetModuleFileNameW(NULL, executable, 1024);
    swprintf(command, 4096, L"\"%ls\" \"%ls\" \"%ls\" %ls", executable, log_path, stop_path, mode);
    startup.cb = sizeof(startup);
    if (!CreateProcessW(executable, command, NULL, NULL, FALSE, 0, NULL, NULL, &startup, &process)) return 94;
    event("spawn=%lu", process.dwProcessId);
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    return 0;
}

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_TIMER:
        if (GetFileAttributesW(stop_path) != INVALID_FILE_ATTRIBUTES) DestroyWindow(window);
        return 0;
    case WM_KEYDOWN: event("key-down=%lu", (DWORD)wparam); break;
    case WM_KEYUP: event("key-up=%lu", (DWORD)wparam); break;
    case WM_LBUTTONDOWN: event("mouse-down"); break;
    case WM_LBUTTONUP: event("mouse-up"); break;
    case WM_MOUSEWHEEL: event("wheel=%d", (short)HIWORD(wparam)); break;
    case WM_SIZE: event("size=%lu", (DWORD)wparam); break;
    case WM_DESTROY: event("closed=%lu", GetCurrentProcessId()); PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

int wmain(int argc, wchar_t **argv) {
    wchar_t directory[1024];
    if (argc < 4) return 95;
    log_path = argv[1]; stop_path = argv[2];
    event("pid=%lu", GetCurrentProcessId());
    DWORD_PTR affinity, system_affinity;
    if (!GetProcessAffinityMask(GetCurrentProcess(), &affinity, &system_affinity)) return 98;
    event("affinity=%llu", (unsigned long long)affinity);
    for (int i = 3; i < argc; ++i) {
        char key[32]; snprintf(key, sizeof(key), "arg%d", i - 3); wide_event(key, argv[i]);
    }
    if (GetCurrentDirectoryW(1024, directory)) wide_event("cwd", directory);
    if (!wcscmp(argv[3], L"check-gone") && argc == 5) {
        DWORD pid = (DWORD)wcstoul(argv[4], NULL, 10);
        HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
        if (!process) return GetLastError() == ERROR_INVALID_PARAMETER ? 0 : 99;
        DWORD state = WaitForSingleObject(process, 0);
        CloseHandle(process);
        return state == WAIT_OBJECT_0 ? 0 : 99;
    }
    if (!wcscmp(argv[3], L"launcher")) return spawn(L"relay");
    if (!wcscmp(argv[3], L"relay")) return spawn(L"child");
    if (!wcscmp(argv[3], L"exit47")) return 47;
    WNDCLASSW type = {0};
    MSG message;
    type.lpfnWndProc = window_proc; type.hInstance = GetModuleHandleW(NULL);
    type.lpszClassName = L"WineBridgeProbe";
    if (!RegisterClassW(&type)) return 96;
    HWND window = CreateWindowW(type.lpszClassName, L"wine-bridge probe", WS_OVERLAPPEDWINDOW,
                                100, 100, 500, 350, NULL, NULL, type.hInstance, NULL);
    if (!window) return 97;
    event("hwnd=%llu", (unsigned long long)(uintptr_t)window);
    ShowWindow(window, !wcscmp(argv[3], L"window") ? SW_SHOWMINIMIZED : SW_SHOWNORMAL);
    SetTimer(window, 1, 50, NULL);
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message); DispatchMessageW(&message);
    }
    return 0;
}
