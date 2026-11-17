/* Cross-compile as PE32 and PE32+; run only in isolated candidate prefixes.
 * The audio check exercises WinMM through a test PulseAudio null sink.
 * It does not certify physical speakers or GPU acceleration.
 */
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <mmsystem.h>
#include <stdio.h>
#include <wchar.h>

int wmain(int argc, wchar_t **argv) {
    if (argc != 2) return 64;
    unsigned failures = 0;
    wchar_t path[2048];
    if (wcslen(argv[1]) > 1900) return 64;
    swprintf(path, 2048, L"%ls.\x4e2d\x6587.txt", argv[1]);
    const wchar_t payload[] = L"Ubuntu 26.04 Wine \x4e2d\x6587\x6587\x4ef6";
    wchar_t readback[128] = {0};
    DWORD written = 0, count = 0;
    HANDLE file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, 0, NULL,
                              CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) failures |= 1;
    else {
        if (!WriteFile(file, payload, sizeof(payload), &written, NULL) ||
            written != sizeof(payload)) failures |= 1;
        SetFilePointer(file, 0, NULL, FILE_BEGIN);
        if (!ReadFile(file, readback, sizeof(readback), &count, NULL) ||
            count != sizeof(payload) || memcmp(readback, payload, count)) failures |= 1;
        CloseHandle(file);
    }

    HWND window = CreateWindowExW(0, L"STATIC", L"Ubuntu Wine \x4e2d\x6587\x9a8c\x8bc1",
                                  WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                                  40, 40, 480, 240, NULL, NULL, GetModuleHandleW(NULL), NULL);
    BOOL chinese = FALSE;
    if (!window) failures |= 2;
    else {
        HDC dc = GetDC(window);
        HFONT font = CreateFontW(24, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                 DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                 DEFAULT_QUALITY, DEFAULT_PITCH, L"Noto Sans CJK SC");
        HGDIOBJ previous = font && dc ? SelectObject(dc, font) : NULL;
        WORD glyphs[2] = {0xffff, 0xffff};
        chinese = dc && GetGlyphIndicesW(dc, L"\x4e2d\x6587", 2, glyphs,
                                         GGI_MARK_NONEXISTING_GLYPHS) != GDI_ERROR &&
                  glyphs[0] != 0xffff && glyphs[1] != 0xffff;
        if (!chinese) failures |= 4;
        RECT rect = {10, 10, 450, 180};
        if (!dc || !DrawTextW(dc, payload, -1, &rect, DT_LEFT)) failures |= 2;
        GdiFlush();
        if (previous) SelectObject(dc, previous);
        if (font) DeleteObject(font);
        if (dc) ReleaseDC(window, dc);
        DWORD until = GetTickCount() + 1500;
        MSG msg;
        while ((LONG)(until - GetTickCount()) > 0) {
            while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
                TranslateMessage(&msg); DispatchMessageW(&msg);
            }
            Sleep(10);
        }
        DestroyWindow(window);
    }

    WAVEFORMATEX format = {WAVE_FORMAT_PCM, 2, 44100, 176400, 4, 16, 0};
    HWAVEOUT output = NULL;
    MMRESULT audio = waveOutOpen(&output, WAVE_MAPPER, &format, 0, 0, CALLBACK_NULL);
    if (audio != MMSYSERR_NOERROR) failures |= 8;
    else {
        char samples[17640] = {0};
        WAVEHDR header = {0}; header.lpData = samples; header.dwBufferLength = sizeof(samples);
        audio = waveOutPrepareHeader(output, &header, sizeof(header));
        if (audio == MMSYSERR_NOERROR) {
            audio = waveOutWrite(output, &header, sizeof(header));
            DWORD until = GetTickCount() + 5000;
            while (audio == MMSYSERR_NOERROR && !(header.dwFlags & WHDR_DONE) &&
                   (LONG)(until - GetTickCount()) > 0) Sleep(10);
            if (!(header.dwFlags & WHDR_DONE)) failures |= 8;
            waveOutReset(output);
            waveOutUnprepareHeader(output, &header, sizeof(header));
        }
        if (audio != MMSYSERR_NOERROR) failures |= 8;
        waveOutClose(output);
    }
    FILE *result = _wfopen(argv[1], L"wb");
    if (!result) return 65;
    fprintf(result, "{\"bits\":%u,\"unicodeFile\":%s,\"window\":%s,"
                    "\"chineseGlyphs\":%s,\"winmmNullSink\":%s,\"failures\":%u}\n",
            (unsigned)(sizeof(void *) * 8), failures & 1 ? "false" : "true",
            failures & 2 ? "false" : "true", chinese ? "true" : "false",
            failures & 8 ? "false" : "true", failures);
    fclose(result);
    return failures;
}
