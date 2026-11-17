/* Exercise public DirectShow APIs, with Wine's own stream callback objects.
 * Use the generated PCM fixture or an explicitly supplied WMV2 AVI fixture.
 */
#define COBJMACROS
#include <windows.h>
#include <dshow.h>
#include <stdio.h>
#include <string.h>

static void put32(BYTE *p, DWORD n) { memcpy(p, &n, 4); }
static void put16(BYTE *p, WORD n) { memcpy(p, &n, 2); }

static WCHAR cleanup_filename[MAX_PATH];
static volatile LONG cleanup_done;

static void cleanup_state(const char *state)
{
    FILE *file = _wfopen(cleanup_filename, L"wb");
    if (file) {
        fprintf(file, "{\"state\":\"%s\"}\n", state);
        fclose(file);
    }
}

static DWORD WINAPI cleanup_watchdog(void *unused)
{
    (void)unused;
    Sleep(10000);
    if (!InterlockedCompareExchange(&cleanup_done, 1, 0)) {
        cleanup_state("TimedOut");
        /* Diagnostic process only: retain raw 124 separately from playback. */
        TerminateProcess(GetCurrentProcess(), 124);
    }
    return 0;
}

int wmain(int argc, WCHAR **argv)
{
    if (argc != 2 && argc != 3) return 64;
    if (wcslen(argv[1]) + wcslen(L".cleanup.json") >= MAX_PATH) return 64;
    wcscpy(cleanup_filename, argv[1]);
    wcscat(cleanup_filename, L".cleanup.json");
    BOOL video = argc == 3;
    BYTE wave[3244] = {0};
    WCHAR temporary[MAX_PATH], filename[MAX_PATH];
    DWORD written = 0;
    unsigned failures = 0;
    LONG event = 0;
    LONG width = 0, height = 0;
    HRESULT render = E_FAIL, run = E_FAIL, complete = E_FAIL;
    IGraphBuilder *graph = NULL;
    IMediaControl *control = NULL;
    IMediaEvent *events = NULL;
    IBasicVideo *picture = NULL;
    HRESULT initialized = CoInitialize(NULL);
    if (FAILED(initialized)) failures |= 1;
    memcpy(wave, "RIFF", 4); put32(wave + 4, sizeof(wave) - 8);
    memcpy(wave + 8, "WAVEfmt ", 8); put32(wave + 16, 16);
    put16(wave + 20, 1); put16(wave + 22, 2);
    put32(wave + 24, 8000); put32(wave + 28, 32000);
    put16(wave + 32, 4); put16(wave + 34, 16);
    memcpy(wave + 36, "data", 4); put32(wave + 40, 3200);
    if (video) {
        if (wcslen(argv[2]) >= MAX_PATH) return 65;
        wcscpy(filename, argv[2]);
    } else {
    if (!GetTempPathW(MAX_PATH, temporary) ||
        !GetTempFileNameW(temporary, L"dsh", 0, filename)) return 65;
    HANDLE file = CreateFileW(filename, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return 65;
    if (!WriteFile(file, wave, sizeof(wave), &written, NULL) || written != sizeof(wave)) failures |= 2;
    CloseHandle(file);
    }
    if (FAILED(CoCreateInstance(&CLSID_FilterGraph, NULL, CLSCTX_INPROC_SERVER,
                               &IID_IGraphBuilder, (void **)&graph))) failures |= 4;
    else {
        render = IGraphBuilder_RenderFile(graph, filename, NULL);
        if (FAILED(render)) failures |= 8;
        else if (FAILED(IGraphBuilder_QueryInterface(graph, &IID_IMediaControl, (void **)&control)) ||
                 FAILED(IGraphBuilder_QueryInterface(graph, &IID_IMediaEvent, (void **)&events))) failures |= 16;
        else {
            run = IMediaControl_Run(control);
            if (FAILED(run)) failures |= 32;
            else {
                complete = IMediaEvent_WaitForCompletion(events, 30000, &event);
                if (FAILED(complete) || event != EC_COMPLETE) failures |= 64;
                if (video) {
                    if (FAILED(IGraphBuilder_QueryInterface(graph, &IID_IBasicVideo, (void **)&picture)) ||
                        FAILED(IBasicVideo_get_VideoWidth(picture, &width)) ||
                        FAILED(IBasicVideo_get_VideoHeight(picture, &height)) ||
                        width != 64 || height != 64) failures |= 128;
                }
            }
        }
    }
    /* Persist what the public playback APIs actually returned before any COM
     * shutdown. Cleanup can deadlock in the selected upstream Wine version;
     * its independent status and raw process result must remain visible. */
    FILE *result = _wfopen(argv[1], L"wb");
    if (!result) return 65;
    fprintf(result, "{\"bits\":%u,\"render\":%ld,\"run\":%ld,\"complete\":%ld,"
                    "\"event\":%ld,\"width\":%ld,\"height\":%ld,\"failures\":%u}\n",
            (unsigned)(sizeof(void *) * 8), (long)render, (long)run, (long)complete,
            (long)event, (long)width, (long)height, failures);
    fclose(result);
    cleanup_state("Running");
    HANDLE watchdog = CreateThread(NULL, 0, cleanup_watchdog, NULL, 0, NULL);
    if (watchdog) CloseHandle(watchdog);
    else cleanup_state("WatchdogUnavailable");
    if (control) IMediaControl_Stop(control);
    if (events) IMediaEvent_Release(events);
    if (picture) IBasicVideo_Release(picture);
    if (control) IMediaControl_Release(control);
    if (graph) IGraphBuilder_Release(graph);
    if (!video) DeleteFileW(filename);
    if (SUCCEEDED(initialized)) CoUninitialize();
    InterlockedExchange(&cleanup_done, 1);
    cleanup_state("Completed");
    return failures ? 1 : 0;
}
