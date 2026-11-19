/* Compile on the component build machine for x86_64 and aarch64.
 * Dynamic loading lets the same probe exercise Box64's native wrappers.
 * Tests use fakesink, so no display server or audio device is required.
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <gst/gst.h>

#define CHECK(x) do { if (!(x)) { \
    fprintf(stderr, "probe failed at line %d: %s\n", __LINE__, #x); return 1; \
} } while (0)
#define API(handle, name) \
    __typeof__(&name) p_##name = (__typeof__(&name))dlsym(handle, #name); \
    CHECK(p_##name)

static gpointer (*parent_class_of)(gpointer);
static GstStateChangeReturn (*parent_change_state)(GstElement *, GstStateChange);
static unsigned state_changes;

static GstStateChangeReturn probe_change_state(GstElement *element, GstStateChange transition)
{
    ++state_changes;
    return parent_change_state(element, transition);
}

static void probe_class_init(gpointer klass, gpointer data)
{
    (void)data;
    GstElementClass *parent = parent_class_of(klass);
    parent_change_state = parent->change_state;
    ((GstElementClass *)klass)->change_state = probe_change_state;
}

int main(int argc, char **argv)
{
    CHECK(argc == 2 || argc == 3);
    int video = !strcmp(argv[1], "video");
    int decode = !strcmp(argv[1], "decode");
    int element = !strcmp(argv[1], "element");
    CHECK(video || decode || element || !strcmp(argv[1], "core"));
    CHECK(!decode || argc == 3);
    void *gst = dlopen("libgstreamer-1.0.so.0", RTLD_NOW | RTLD_GLOBAL);
    if (!gst) fprintf(stderr, "GStreamer dlopen: %s\n", dlerror());
    CHECK(gst);
    API(gst, gst_init_check); API(gst, gst_parse_launch);
    API(gst, gst_element_set_state); API(gst, gst_element_get_bus);
    API(gst, gst_bus_timed_pop_filtered); API(gst, gst_message_parse_error);
    API(gst, gst_bin_get_by_name); API(gst, gst_sample_get_buffer);
    API(gst, gst_sample_get_caps); API(gst, gst_caps_get_structure);
    API(gst, gst_structure_get_int); API(gst, gst_buffer_get_size);
    API(gst, gst_buffer_extract); API(gst, gst_mini_object_unref);
    API(gst, gst_object_unref); API(gst, gst_version); API(gst, gst_deinit);
    void *gobject = dlopen("libgobject-2.0.so.0", RTLD_NOW | RTLD_GLOBAL);
    CHECK(gobject);
    API(gobject, g_object_get);
    GError *error = NULL;
    CHECK(p_gst_init_check(NULL, NULL, &error));
    unsigned major, minor, micro, nano;
    p_gst_version(&major, &minor, &micro, &nano);
    if (element) {
        API(gst, gst_element_get_type);
        API(gobject, g_type_register_static); API(gobject, g_type_class_peek_parent);
        API(gobject, g_object_new);
        parent_class_of = p_g_type_class_peek_parent;
        GTypeInfo info = {0};
        info.class_size = sizeof(GstElementClass);
        info.class_init = probe_class_init;
        info.instance_size = sizeof(GstElement);
        GType type = p_g_type_register_static(p_gst_element_get_type(),
            "IdapProbeElement", &info, 0);
        CHECK(type);
        GstElement *custom = p_g_object_new(type, NULL);
        CHECK(custom && parent_change_state);
        CHECK(p_gst_element_set_state(custom, GST_STATE_READY) != GST_STATE_CHANGE_FAILURE);
        CHECK(p_gst_element_set_state(custom, GST_STATE_NULL) != GST_STATE_CHANGE_FAILURE);
        p_gst_object_unref(custom);
        CHECK(state_changes == 2);
        p_gst_deinit();
        printf("{\"mode\":\"element\",\"stateChanges\":%u,\"failures\":0}\n", state_changes);
        return 0;
    }
    char pipeline[4096];
    if (decode) {
        /* The fixture is a builder-controlled absolute path without quotes. */
        CHECK(!strchr(argv[2], '"') && !strchr(argv[2], '\\'));
        CHECK(snprintf(pipeline, sizeof(pipeline),
            "filesrc location=\"%s\" ! avidemux ! avdec_wmv2 ! videoconvert ! "
            "video/x-raw,format=I420 ! fakesink name=verify sync=false", argv[2])
            < (int)sizeof(pipeline));
    } else {
        const char *description = video
            ? "videotestsrc num-buffers=4 pattern=red ! "
              "video/x-raw,format=I420,width=64,height=64,framerate=25/1 ! "
              "videoconvert ! fakesink name=verify sync=false"
            : "fakesrc num-buffers=4 ! identity ! fakesink name=verify sync=false";
        strcpy(pipeline, description);
    }
    GstElement *graph = p_gst_parse_launch(pipeline, &error);
    if (error) fprintf(stderr, "pipeline parse: %s\n", error->message);
    CHECK(graph && !error);
    GstBus *bus = p_gst_element_get_bus(graph);
    CHECK(bus);
    CHECK(p_gst_element_set_state(graph, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE);
    GstMessage *message = p_gst_bus_timed_pop_filtered(bus, 30 * GST_SECOND,
        GST_MESSAGE_EOS | GST_MESSAGE_ERROR);
    CHECK(message);
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
        char *detail = NULL;
        p_gst_message_parse_error(message, &error, &detail);
        fprintf(stderr, "pipeline error: %s (%s)\n",
            error ? error->message : "unknown", detail ? detail : "");
        return 1;
    }
    CHECK(GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS);
    GstElement *sink = p_gst_bin_get_by_name((GstBin *)graph, "verify");
    CHECK(sink);
    GstSample *sample = NULL;
    p_g_object_get(sink, "last-sample", &sample, NULL);
    CHECK(sample);
    GstBuffer *buffer = p_gst_sample_get_buffer(sample);
    CHECK(buffer);
    size_t bytes = p_gst_buffer_get_size(buffer);
    int width = 0, height = 0;
    uint32_t checksum = 2166136261u;
    if (video || decode) {
        GstCaps *caps = p_gst_sample_get_caps(sample);
        CHECK(caps);
        const GstStructure *structure = p_gst_caps_get_structure(caps, 0);
        CHECK(structure && p_gst_structure_get_int(structure, "width", &width)
            && p_gst_structure_get_int(structure, "height", &height));
        CHECK(width == 64 && height == 64 && bytes == 6144);
        uint8_t pixels[6144];
        CHECK(p_gst_buffer_extract(buffer, 0, pixels, sizeof(pixels)) == sizeof(pixels));
        for (size_t i = 0; i < sizeof(pixels); ++i)
            checksum = (checksum ^ pixels[i]) * 16777619u;
    }
    p_gst_mini_object_unref((GstMiniObject *)sample);
    p_gst_object_unref(sink);
    p_gst_mini_object_unref((GstMiniObject *)message);
    CHECK(p_gst_element_set_state(graph, GST_STATE_NULL) != GST_STATE_CHANGE_FAILURE);
    p_gst_object_unref(bus);
    p_gst_object_unref(graph);
    p_gst_deinit();
    printf("{\"mode\":\"%s\",\"gstreamer\":\"%u.%u.%u\",\"width\":%d,"
           "\"height\":%d,\"bytes\":%zu,\"checksum\":%u,\"failures\":0}\n",
           argv[1], major, minor, micro, width, height, bytes, checksum);
    return 0;
}
