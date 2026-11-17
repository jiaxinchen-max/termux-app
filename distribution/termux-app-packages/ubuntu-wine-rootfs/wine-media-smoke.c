/* Exercise the actual Wine PE -> Unix -> FFmpeg demux path in an isolated prefix.
 * The exported winedmo interface is pinned to the selected Wine components.
 * Codec decoding is covered separately by media-smoke.c; this checks integration.
 */
#define COBJMACROS
#include <windows.h>
#include <mediaobj.h>
#include <stdio.h>
#include <string.h>

struct stream {
    LONG (__cdecl *seek)(struct stream *, UINT64 *);
    LONG (__cdecl *read)(struct stream *, BYTE *, ULONG *);
    BYTE wave[3244];
    ULONG position;
};
struct demuxer { UINT64 handle; };
struct buffer { IMediaBuffer iface; ULONG length; BYTE bytes[8192]; };

static HRESULT WINAPI query(IMediaBuffer *iface, REFIID id, void **out)
{ (void)iface; (void)id; *out = NULL; return E_NOINTERFACE; }
static ULONG WINAPI ref(IMediaBuffer *iface) { (void)iface; return 1; }
static HRESULT WINAPI length(IMediaBuffer *iface, DWORD n)
{
    struct buffer *b = (struct buffer *)iface;
    if (n > sizeof(b->bytes)) return E_INVALIDARG;
    b->length = n;
    return S_OK;
}
static HRESULT WINAPI capacity(IMediaBuffer *iface, DWORD *n)
{ (void)iface; *n = sizeof(((struct buffer *)0)->bytes); return S_OK; }
static HRESULT WINAPI data(IMediaBuffer *iface, BYTE **out, DWORD *n)
{
    struct buffer *b = (struct buffer *)iface;
    if (out) *out = b->bytes;
    if (n) *n = b->length;
    return S_OK;
}
static IMediaBufferVtbl vtable = {query, ref, ref, length, capacity, data};
static LONG __cdecl seek_stream(struct stream *s, UINT64 *position)
{
    if (*position > sizeof(s->wave)) return (LONG)0xc000000d;
    s->position = (ULONG)*position;
    return 0;
}
static LONG __cdecl read_stream(struct stream *s, BYTE *out, ULONG *size)
{
    ULONG remaining = sizeof(s->wave) - s->position;
    if (*size > remaining) *size = remaining;
    memcpy(out, s->wave + s->position, *size);
    s->position += *size;
    return 0;
}
static void put32(BYTE *p, ULONG n) { memcpy(p, &n, 4); }
static void put16(BYTE *p, WORD n) { memcpy(p, &n, 2); }

int wmain(int argc, WCHAR **argv)
{
    typedef LONG (__cdecl *check_fn)(const char *);
    typedef LONG (__cdecl *create_fn)(const WCHAR *, struct stream *, UINT64,
                                      INT64 *, UINT *, WCHAR *, struct demuxer *);
    typedef LONG (__cdecl *read_fn)(struct demuxer, UINT *, DMO_OUTPUT_DATA_BUFFER *, UINT *);
    typedef LONG (__cdecl *destroy_fn)(struct demuxer *);
    if (argc != 2) return 64;
    unsigned failures = 0, packet_bytes = 0;
    UINT streams = 0;
    HMODULE library = LoadLibraryW(L"winedmo.dll");
    check_fn check = library ? (check_fn)(void *)GetProcAddress(library, "winedmo_demuxer_check") : NULL;
    create_fn create = library ? (create_fn)(void *)GetProcAddress(library, "winedmo_demuxer_create") : NULL;
    read_fn read = library ? (read_fn)(void *)GetProcAddress(library, "winedmo_demuxer_read") : NULL;
    destroy_fn destroy = library ? (destroy_fn)(void *)GetProcAddress(library, "winedmo_demuxer_destroy") : NULL;
    if (!check || !create || !read || !destroy) failures |= 1;
    else {
        if (check("audio/wav") || check("video/x-ms-asf") || !check("application/idap-unsupported"))
            failures |= 2;
        struct stream input = {seek_stream, read_stream, {0}, 0};
        memcpy(input.wave, "RIFF", 4); put32(input.wave + 4, sizeof(input.wave) - 8);
        memcpy(input.wave + 8, "WAVEfmt ", 8); put32(input.wave + 16, 16);
        put16(input.wave + 20, 1); put16(input.wave + 22, 2);
        put32(input.wave + 24, 8000); put32(input.wave + 28, 32000);
        put16(input.wave + 32, 4); put16(input.wave + 34, 16);
        memcpy(input.wave + 36, "data", 4); put32(input.wave + 40, 3200);
        for (unsigned i = 0; i < 3200; i++) input.wave[44 + i] = (BYTE)(i * 7);
        INT64 duration = 0;
        WCHAR mime[256] = {0};
        struct demuxer demux = {0};
        if (create(L"fixture.wav", &input, sizeof(input.wave), &duration, &streams, mime, &demux))
            failures |= 4;
        else {
            if (streams != 1 || duration <= 0 || !demux.handle) failures |= 8;
            struct buffer output = {{&vtable}, 0, {0}};
            for (unsigned i = 0; i < 16 && packet_bytes < 3200; i++) {
                DMO_OUTPUT_DATA_BUFFER sample = {0};
                sample.pBuffer = &output.iface;
                UINT index = 99, size = 0;
                if (read(demux, &index, &sample, &size)) { failures |= 16; break; }
                if (index != 0 || size != output.length || packet_bytes + size > 3200) {
                    failures |= 16; break;
                }
                for (unsigned j = 0; j < size; j++)
                    if (output.bytes[j] != (BYTE)((packet_bytes + j) * 7)) failures |= 32;
                packet_bytes += size;
            }
            if (packet_bytes != 3200) failures |= 16;
            if (destroy(&demux) || demux.handle) failures |= 64;
        }
    }
    FILE *result = _wfopen(argv[1], L"wb");
    if (!result) return 65;
    fprintf(result, "{\"bits\":%u,\"winedmoLoaded\":%s,\"streamCount\":%u,"
                    "\"packetBytes\":%u,\"failures\":%u}\n",
            (unsigned)(sizeof(void *) * 8), library ? "true" : "false", streams, packet_bytes, failures);
    fclose(result);
    if (library) FreeLibrary(library);
    return failures ? 1 : 0;
}
