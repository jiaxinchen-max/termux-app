/* Build as aarch64 and x86_64 Linux. The latter exercises Box64's FFmpeg 4
 * wrappers. This checks a synthetic WMV2 frame, not Wine media integration.
 * Link only libdl; versioned FFmpeg libraries are opened from the candidate.
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "media check failed at %d\n", __LINE__); return 1; } } while (0)
#define API(h, n) __typeof__(&n) p_##n = (__typeof__(&n))dlsym(h, #n); CHECK(p_##n)

int main(int argc, char **argv) {
    CHECK(argc == 4);
    int encode = !strcmp(argv[1], "encode");
    CHECK(encode || !strcmp(argv[1], "decode"));
    void *util = dlopen("libavutil.so.56", RTLD_NOW | RTLD_GLOBAL);
    void *codec = dlopen("libavcodec.so.58", RTLD_NOW | RTLD_GLOBAL);
    void *format = dlopen("libavformat.so.58", RTLD_NOW | RTLD_GLOBAL);
    if (!util || !codec || !format) fprintf(stderr, "%s\n", dlerror());
    CHECK(util && codec && format);
    API(codec, avcodec_find_encoder); API(codec, avcodec_find_decoder);
    API(codec, avcodec_alloc_context3); API(codec, avcodec_free_context);
    API(codec, avcodec_open2); API(codec, avcodec_send_frame);
    API(codec, avcodec_receive_packet); API(codec, avcodec_send_packet);
    API(codec, avcodec_receive_frame); API(codec, av_packet_alloc);
    API(codec, av_packet_free); API(codec, av_new_packet);
    API(util, av_frame_alloc); API(util, av_frame_free);
    API(util, av_frame_get_buffer); API(util, av_mallocz);
    API(format, avformat_version);
    CHECK((p_avformat_version() >> 16) == 58);
    const AVCodec *implementation = encode ? p_avcodec_find_encoder(AV_CODEC_ID_WMV2)
                                          : p_avcodec_find_decoder(AV_CODEC_ID_WMV2);
    CHECK(implementation);
    AVCodecContext *context = p_avcodec_alloc_context3(implementation);
    AVFrame *frame = p_av_frame_alloc();
    AVPacket *packet = p_av_packet_alloc();
    CHECK(context && frame && packet);
    context->width = context->height = 64;
    uint32_t extra = 0, bytes = 0;
    if (encode) {
        context->time_base = (AVRational){1, 25};
        context->pix_fmt = AV_PIX_FMT_YUV420P;
        context->bit_rate = 100000;
        context->gop_size = 1;
        CHECK(p_avcodec_open2(context, implementation, NULL) == 0);
        frame->format = context->pix_fmt;
        frame->width = frame->height = 64;
        CHECK(p_av_frame_get_buffer(frame, 32) == 0);
        for (int y = 0; y < 64; ++y)
            for (int x = 0; x < 64; ++x) frame->data[0][y * frame->linesize[0] + x] = (x * 3 + y * 2) & 255;
        for (int y = 0; y < 32; ++y) {
            memset(frame->data[1] + y * frame->linesize[1], 90, 32);
            memset(frame->data[2] + y * frame->linesize[2], 180, 32);
        }
        CHECK(p_avcodec_send_frame(context, frame) == 0);
        CHECK(p_avcodec_receive_packet(context, packet) == 0);
        FILE *output = fopen(argv[2], "wb"); CHECK(output);
        extra = context->extradata_size; bytes = packet->size;
        CHECK(extra <= 4096 && bytes > 0 && bytes <= 1048576);
        CHECK(fwrite(&extra, 4, 1, output) == 1 && fwrite(&bytes, 4, 1, output) == 1);
        CHECK(!extra || fwrite(context->extradata, 1, extra, output) == extra);
        CHECK(fwrite(packet->data, 1, bytes, output) == bytes && fclose(output) == 0);
    } else {
        FILE *input = fopen(argv[2], "rb"); CHECK(input);
        CHECK(fread(&extra, 4, 1, input) == 1 && fread(&bytes, 4, 1, input) == 1);
        CHECK(extra <= 4096 && bytes > 0 && bytes <= 1048576);
        if (extra) {
            context->extradata = p_av_mallocz(extra + AV_INPUT_BUFFER_PADDING_SIZE);
            context->extradata_size = extra; CHECK(context->extradata);
            CHECK(fread(context->extradata, 1, extra, input) == extra);
        }
        CHECK(p_av_new_packet(packet, bytes) == 0);
        CHECK(fread(packet->data, 1, bytes, input) == bytes && fgetc(input) == EOF);
        fclose(input);
        CHECK(p_avcodec_open2(context, implementation, NULL) == 0);
        CHECK(p_avcodec_send_packet(context, packet) == 0);
        CHECK(p_avcodec_receive_frame(context, frame) == 0);
        CHECK(frame->width == 64 && frame->height == 64 && frame->format == AV_PIX_FMT_YUV420P);
    }
    uint32_t checksum = 2166136261u;
    if (!encode) for (int plane = 0; plane < 3; ++plane) {
        int size = plane ? 32 : 64;
        for (int y = 0; y < size; ++y) for (int x = 0; x < size; ++x)
            checksum = (checksum ^ frame->data[plane][y * frame->linesize[plane] + x]) * 16777619u;
    }
    p_av_packet_free(&packet); p_av_frame_free(&frame); p_avcodec_free_context(&context);
    FILE *result = fopen(argv[3], "wb"); CHECK(result);
    CHECK(fprintf(result, "{\"codec\":\"wmv2\",\"mode\":\"%s\",\"width\":64,\"height\":64,\"checksum\":%u,\"failures\":0}\n",
                  encode ? "encode" : "decode", checksum) > 0);
    CHECK(fclose(result) == 0);
    return 0;
}
