/* SPDX-License-Identifier: GPL-2.0-or-later
 * Private native Android/Bionic client-buffer protocol, compatible with KWin.
 * An AHB native_handle is not a Linux dma-buf. Never advertise it as one.
 * Version 1/2 require producer glFinish and wl_buffer.release before reuse.
 */
#include "config.h"
#include "backends/android/meta-wayland-android-buffer.h"
#include "backends/android/meta-backend-android.h"
#include "wayland/meta-wayland-private.h"
#include "cogl/cogl-context-private.h"
#include "ahb-api.h"
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include "termux-ahardware-buffer-v1-server-protocol.h"

static CoglContext *
get_context (MetaWaylandCompositor *compositor)
{
  MetaBackend *backend = meta_context_get_backend (
    meta_wayland_compositor_get_context (compositor));
  return clutter_backend_get_cogl_context (meta_backend_get_clutter_backend (backend));
}

/* A malicious or failed producer must not block the compositor indefinitely.
 * The protocol requires the entire small handle to be queued before the call. */
static AHardwareBuffer *
receive_buffer (int fd)
{
  int type = 0;
  socklen_t size = sizeof (type);
  struct sockaddr_storage address;
  socklen_t address_size = sizeof (address);
  struct timeval timeout = { .tv_usec = 100000 };
  AHardwareBuffer *ahb = NULL;
  if (getsockopt (fd, SOL_SOCKET, SO_TYPE, &type, &size) != 0 ||
      type != SOCK_STREAM ||
      getsockname (fd, (struct sockaddr *) &address, &address_size) != 0 ||
      address.ss_family != AF_UNIX ||
      setsockopt (fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof (timeout)) != 0)
    goto out;
  if (android_ahb_recvHandleFromUnixSocket (fd, &ahb) != 0)
    ahb = NULL;
out:
  close (fd);
  return ahb;
}

static gboolean
valid_buffer (AHardwareBuffer *ahb, AHardwareBuffer_Desc *desc)
{
  android_ahb_describe (ahb, desc);
  return desc->width > 0 && desc->height > 0 &&
    desc->width <= 16384 && desc->height <= 16384 && desc->layers == 1 &&
    (desc->usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) &&
    !(desc->usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) &&
    (desc->format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
     desc->format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM || desc->format == 5);
}

static CoglTexture *
import_texture (MetaWaylandCompositor *compositor, AHardwareBuffer *ahb,
                uint32_t flags, GError **error)
{
  AHardwareBuffer_Desc desc;
  CoglContext *ctx = get_context (compositor);
  EGLDisplay display = cogl_context_get_egl_display (ctx);
  PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get =
    (void *) eglGetProcAddress ("eglGetNativeClientBufferANDROID");
  PFNEGLCREATEIMAGEKHRPROC create = (void *) eglGetProcAddress ("eglCreateImageKHR");
  PFNEGLDESTROYIMAGEKHRPROC destroy = (void *) eglGetProcAddress ("eglDestroyImageKHR");
  EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
  EGLImageKHR image;
  CoglTexture *texture;
  CoglPixelFormat format;
  if (!valid_buffer (ahb, &desc) || !get || !create || !destroy)
    {
      g_set_error_literal (error, G_IO_ERROR, G_IO_ERROR_NOT_SUPPORTED,
                           "Invalid or unsupported native Android client buffer");
      return NULL;
    }
  image = create (display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, get (ahb), attrs);
  if (image == EGL_NO_IMAGE_KHR)
    {
      g_set_error (error, G_IO_ERROR, G_IO_ERROR_FAILED,
                   "Client AHB EGL import failed: 0x%x", eglGetError ());
      return NULL;
    }
  format = (flags & TERMUX_AHARDWARE_BUFFER_MANAGER_V1_BUFFER_FLAGS_OPAQUE) ||
    desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM ?
      COGL_PIXEL_FORMAT_RGB_888 : COGL_PIXEL_FORMAT_RGBA_8888_PRE;
  texture = cogl_texture_2d_new_from_egl_image (ctx, desc.width, desc.height,
    format, image, COGL_EGL_IMAGE_FLAG_NO_GET_DATA, error);
  destroy (display, image);
  return texture;
}

static void
resource_destroy (struct wl_client *client, struct wl_resource *resource)
{
  wl_resource_destroy (resource);
}

static const struct wl_buffer_interface buffer_implementation = {
  .destroy = resource_destroy,
};

static void
create_buffer (struct wl_client *client, struct wl_resource *resource,
               uint32_t id, int32_t socket_fd, uint32_t flags)
{
  MetaWaylandCompositor *compositor = wl_resource_get_user_data (resource);
  AHardwareBuffer *ahb = receive_buffer (socket_fd);
  AHardwareBuffer_Desc desc;
  MetaWaylandBuffer *buffer;
  struct wl_resource *buffer_resource;
  if (!ahb)
    {
      wl_resource_post_error (resource, TERMUX_AHARDWARE_BUFFER_MANAGER_V1_ERROR_INVALID_HANDLE,
                              "No complete AHardwareBuffer handle queued in AF_UNIX socket");
      return;
    }
  if ((flags & ~3u) || !valid_buffer (ahb, &desc))
    {
      android_ahb_release (ahb);
      wl_resource_post_error (resource, TERMUX_AHARDWARE_BUFFER_MANAGER_V1_ERROR_INVALID_BUFFER,
                              "Unsupported AHardwareBuffer geometry, format, usage or flags");
      return;
    }
  buffer_resource = wl_resource_create (client, &wl_buffer_interface, 1, id);
  if (!buffer_resource)
    {
      android_ahb_release (ahb);
      wl_client_post_no_memory (client);
      return;
    }
  buffer = meta_wayland_buffer_from_resource (compositor, buffer_resource);
  buffer->type = META_WAYLAND_BUFFER_TYPE_ANDROID;
  buffer->android.hardware_buffer = ahb;
  buffer->android.flags = flags;
  /* Same bottom-left GPU origin convention as the existing KWin AHB clients. */
  buffer->is_y_inverted = FALSE;
  wl_resource_set_implementation (buffer_resource, &buffer_implementation, buffer, NULL);
}

/* Version 2's one-pixel diagnostic checks the real compositor sampling path.
 * This is explicit test-only readback; normal attach/composition never reads. */
static void
probe_buffer (struct wl_client *client, struct wl_resource *resource,
              int32_t socket_fd, uint32_t token)
{
  MetaWaylandCompositor *compositor = wl_resource_get_user_data (resource);
  AHardwareBuffer *ahb = receive_buffer (socket_fd);
  CoglContext *ctx = get_context (compositor);
  g_autoptr (CoglTexture) source = NULL;
  g_autoptr (CoglTexture) target = NULL;
  g_autoptr (CoglOffscreen) offscreen = NULL;
  g_autoptr (CoglPipeline) pipeline = NULL;
  g_autoptr (GError) error = NULL;
  uint8_t pixel[4];
  uint32_t result = TERMUX_AHARDWARE_BUFFER_MANAGER_V1_CHANNEL_ORDER_UNKNOWN;
  if (!ahb) goto out;
  source = import_texture (compositor, ahb, 1, &error);
  if (!source) goto out;
  target = cogl_texture_2d_new_with_size (ctx, 1, 1);
  offscreen = cogl_offscreen_new_with_texture (target);
  if (!cogl_framebuffer_allocate (COGL_FRAMEBUFFER (offscreen), &error)) goto out;
  pipeline = cogl_pipeline_new (ctx);
  cogl_pipeline_set_layer_texture (pipeline, 0, source);
  cogl_framebuffer_draw_rectangle (COGL_FRAMEBUFFER (offscreen), pipeline, -1, -1, 1, 1);
  if (!cogl_framebuffer_read_pixels (COGL_FRAMEBUFFER (offscreen), 0, 0, 1, 1,
                                     COGL_PIXEL_FORMAT_RGBA_8888, pixel)) goto out;
  if (pixel[0] > 240 && pixel[1] < 15 && pixel[2] < 15)
    result = TERMUX_AHARDWARE_BUFFER_MANAGER_V1_CHANNEL_ORDER_IDENTITY;
  else if (pixel[2] > 240 && pixel[1] < 15 && pixel[0] < 15)
    result = TERMUX_AHARDWARE_BUFFER_MANAGER_V1_CHANNEL_ORDER_SWAP_RED_BLUE;
out:
  if (ahb) android_ahb_release (ahb);
  termux_ahardware_buffer_manager_v1_send_probe_result (resource, token, result);
}

static const struct termux_ahardware_buffer_manager_v1_interface implementation = {
  .destroy = resource_destroy, .create_buffer = create_buffer, .probe_buffer = probe_buffer,
};

static void
bind_manager (struct wl_client *client, void *data, uint32_t version, uint32_t id)
{
  struct wl_resource *resource = wl_resource_create (client,
    &termux_ahardware_buffer_manager_v1_interface, MIN (version, 2), id);
  if (!resource) { wl_client_post_no_memory (client); return; }
  wl_resource_set_implementation (resource, &implementation, data, NULL);
}

void
meta_wayland_android_buffer_init (MetaWaylandCompositor *compositor)
{
  MetaBackend *backend = meta_context_get_backend (
    meta_wayland_compositor_get_context (compositor));
  CoglContext *ctx;
  const char *extensions;
  if (!META_IS_BACKEND_ANDROID (backend) ||
      g_strcmp0 (g_getenv ("MUTTER_ANDROID_GL_MODE"), "system") != 0) return;
  ctx = get_context (compositor);
  extensions = eglQueryString (cogl_context_get_egl_display (ctx), EGL_EXTENSIONS);
  if (!extensions || !strstr (extensions, "EGL_ANDROID_image_native_buffer")) return;
  if (!wl_global_create (compositor->wayland_display,
    &termux_ahardware_buffer_manager_v1_interface, 2, compositor, bind_manager))
    g_error ("Failed to register Android AHardwareBuffer protocol");
  g_message ("Android client AHB protocol v2 enabled: native EGLImage import, CPU upload=off");
}

gboolean
meta_wayland_android_buffer_attach (MetaWaylandBuffer *buffer,
                                    MetaMultiTexture **texture, GError **error)
{
  if (!buffer->android.texture)
    {
      CoglTexture *imported = import_texture (buffer->compositor,
        buffer->android.hardware_buffer, buffer->android.flags, error);
      AHardwareBuffer_Desc desc;
      if (!imported) return FALSE;
      buffer->android.texture = meta_multi_texture_new_simple (imported);
      android_ahb_describe (buffer->android.hardware_buffer, &desc);
      g_message ("Android client AHB imported: %ux%u format=%u, CPU upload=off",
                 desc.width, desc.height, desc.format);
    }
  g_clear_object (texture);
  *texture = g_object_ref (buffer->android.texture);
  return TRUE;
}

CoglSnippet *
meta_wayland_android_buffer_create_snippet (MetaWaylandBuffer *buffer)
{
  CoglSnippet *snippet;
  if (!(buffer->android.flags & TERMUX_AHARDWARE_BUFFER_MANAGER_V1_BUFFER_FLAGS_SWAP_RED_BLUE))
    return NULL;
  snippet = cogl_snippet_new (COGL_SNIPPET_HOOK_TEXTURE_LOOKUP, NULL, NULL);
  cogl_snippet_set_post (snippet, "cogl_texel = cogl_texel.bgra;");
  return snippet;
}

void
meta_wayland_android_buffer_finish (MetaWaylandBuffer *buffer)
{
  /* glFinish before release also covers early buffer replacement before redraw.
   * No explicit native-fence protocol is claimed by this implementation. */
  CoglContext *ctx = get_context (buffer->compositor);
  cogl_context_flush (ctx);
  ctx->glFinish ();
}

void
meta_wayland_android_buffer_clear (MetaWaylandBuffer *buffer)
{
  g_clear_object (&buffer->android.texture);
  if (buffer->android.hardware_buffer)
    android_ahb_release (buffer->android.hardware_buffer);
  buffer->android.hardware_buffer = NULL;
}
