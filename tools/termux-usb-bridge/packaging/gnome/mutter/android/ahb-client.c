/* SPDX-License-Identifier: MIT
 * Native/Bionic GLES client for the private AHB protocol. No wl_shm, CPU map,
 * pixel upload, or readback. The compositor's explicit one-pixel probe is used
 * once to negotiate channel order. Synchronization is glFinish/release.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include "ahb-api.h"
#include <wayland-client.h>
#include <sys/socket.h>
#include <poll.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "termux-ahardware-buffer-v1-client-protocol.h"
#include "xdg-shell-client-protocol.h"

static struct {
  const GLubyte * (*GetString) (GLenum);
  GLenum (*GetError) (void);
  void (*GenTextures) (GLsizei, GLuint *);
  void (*BindTexture) (GLenum, GLuint);
  void (*TexParameteri) (GLenum, GLenum, GLint);
  void (*EGLImageTargetTexture2DOES) (GLenum, GLeglImageOES);
  void (*GenFramebuffers) (GLsizei, GLuint *);
  void (*BindFramebuffer) (GLenum, GLuint);
  void (*FramebufferTexture2D) (GLenum, GLenum, GLenum, GLuint, GLint);
  GLenum (*CheckFramebufferStatus) (GLenum);
  void (*Viewport) (GLint, GLint, GLsizei, GLsizei);
  void (*Disable) (GLenum);
  void (*Enable) (GLenum);
  void (*Scissor) (GLint, GLint, GLsizei, GLsizei);
  void (*ClearColor) (GLfloat, GLfloat, GLfloat, GLfloat);
  void (*Clear) (GLbitfield);
  void (*Finish) (void);
  void (*DeleteFramebuffers) (GLsizei, const GLuint *);
  void (*DeleteTextures) (GLsizei, const GLuint *);
  PFNEGLCREATEIMAGEKHRPROC CreateImageKHR;
  PFNEGLDESTROYIMAGEKHRPROC DestroyImageKHR;
  PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC GetNativeClientBufferANDROID;
} gpu;
#define glGetString gpu.GetString
#define glGetError gpu.GetError
#define glGenTextures gpu.GenTextures
#define glBindTexture gpu.BindTexture
#define glTexParameteri gpu.TexParameteri
#define glEGLImageTargetTexture2DOES gpu.EGLImageTargetTexture2DOES
#define glGenFramebuffers gpu.GenFramebuffers
#define glBindFramebuffer gpu.BindFramebuffer
#define glFramebufferTexture2D gpu.FramebufferTexture2D
#define glCheckFramebufferStatus gpu.CheckFramebufferStatus
#define glViewport gpu.Viewport
#define glDisable gpu.Disable
#define glEnable gpu.Enable
#define glScissor gpu.Scissor
#define glClearColor gpu.ClearColor
#define glClear gpu.Clear
#define glFinish gpu.Finish
#define glDeleteFramebuffers gpu.DeleteFramebuffers
#define glDeleteTextures gpu.DeleteTextures
#define eglCreateImageKHR gpu.CreateImageKHR
#define eglDestroyImageKHR gpu.DestroyImageKHR
#define eglGetNativeClientBufferANDROID gpu.GetNativeClientBufferANDROID

#define WIDTH 480
#define HEIGHT 320
struct buffer {
  AHardwareBuffer *ahb;
  EGLImageKHR image;
  GLuint texture, fbo;
  struct wl_buffer *wl;
  int busy;
};
static struct wl_display *display;
static struct wl_compositor *compositor;
static struct termux_ahardware_buffer_manager_v1 *manager;
static struct xdg_wm_base *wm;
static struct wl_surface *surface;
static struct xdg_surface *xdg;
static struct xdg_toplevel *toplevel;
static EGLDisplay edpy;
static EGLContext context;
static EGLSurface pbuffer;
static struct buffer buffers[2];
static unsigned frames, releases, goal = 120, order;
static int configured, running = 1, frame_pending;
static int diagnostic_mode;

static void fail (const char *message) {
  fprintf (stderr, "AHB_CLIENT_FAIL %s EGL=0x%x GL=0x%x\n", message, eglGetError (), gpu.GetError ? glGetError () : 0);
  exit (1);
}
static int send_buffer (AHardwareBuffer *buffer) {
  int sockets[2];
  if (socketpair (AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) != 0) fail ("socketpair");
  if (android_ahb_sendHandleToUnixSocket (buffer, sockets[0]) != 0) fail ("AHB send");
  close (sockets[0]);
  return sockets[1];
}
static void released (void *data, struct wl_buffer *wl) {
  ((struct buffer *) data)->busy = 0; releases++;
}
static const struct wl_buffer_listener buffer_listener = { .release = released };
static void probe_result (void *data, struct termux_ahardware_buffer_manager_v1 *m,
                          uint32_t token, uint32_t result) {
  if (token != 7) fail ("probe token");
  order = result;
  printf ("AHB_CLIENT_PROBE order=%u\n", order);
}
static const struct termux_ahardware_buffer_manager_v1_listener manager_listener = {
  .probe_result = probe_result,
};
static void ping (void *data, struct xdg_wm_base *base, uint32_t serial) {
  xdg_wm_base_pong (base, serial);
}
static const struct xdg_wm_base_listener wm_listener = { .ping = ping };
static void global (void *data, struct wl_registry *registry, uint32_t id,
                    const char *interface, uint32_t version) {
  if (!strcmp (interface, "wl_compositor"))
    compositor = wl_registry_bind (registry, id, &wl_compositor_interface, version < 4 ? version : 4);
  else if (!strcmp (interface, "xdg_wm_base")) {
    wm = wl_registry_bind (registry, id, &xdg_wm_base_interface, 1);
    xdg_wm_base_add_listener (wm, &wm_listener, NULL);
  } else if (!strcmp (interface, "termux_ahardware_buffer_manager_v1")) {
    manager = wl_registry_bind (registry, id, &termux_ahardware_buffer_manager_v1_interface, 2);
    termux_ahardware_buffer_manager_v1_add_listener (manager, &manager_listener, NULL);
  } else if (!strcmp (interface, "zwp_linux_dmabuf_v1"))
    printf ("LINUX_DMABUF_ADVERTISED version=%u\n", version);
}
static void global_remove (void *data, struct wl_registry *registry, uint32_t id) { }
static const struct wl_registry_listener registry_listener = { global, global_remove };
static void configure (void *data, struct xdg_surface *s, uint32_t serial) {
  xdg_surface_ack_configure (s, serial); configured = 1;
}
static const struct xdg_surface_listener surface_listener = { .configure = configure };
static void top_configure (void *data, struct xdg_toplevel *top, int32_t w,
                           int32_t h, struct wl_array *states) { }
static void top_close (void *data, struct xdg_toplevel *top) { running = 0; }
static const struct xdg_toplevel_listener top_listener = { top_configure, top_close };
static void frame_done (void *data, struct wl_callback *callback, uint32_t time) {
  wl_callback_destroy (callback); frame_pending = 0;
}
static const struct wl_callback_listener frame_listener = { .done = frame_done };

static void init_egl (void) {
  /* Initialize the same private driver namespace before epoxy resolves EGL. */
  const char *(*dispatch) (void) = (void *) dlsym (RTLD_DEFAULT, "mutter_android_system_egl_dispatch");
  if (!dispatch) { fprintf (stderr, "AHB client requires private system EGL preload\n"); exit (1); }
  dispatch ();
  EGLint major, minor, count;
  EGLConfig config;
  const EGLint attrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
    EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE };
  const EGLint ctx[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
  const EGLint size[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
  edpy = eglGetDisplay (EGL_DEFAULT_DISPLAY);
  if (!eglInitialize (edpy, &major, &minor) || !eglBindAPI (EGL_OPENGL_ES_API) ||
      !eglChooseConfig (edpy, attrs, &config, 1, &count) || count != 1) fail ("EGL init");
  context = eglCreateContext (edpy, config, EGL_NO_CONTEXT, ctx);
  pbuffer = eglCreatePbufferSurface (edpy, config, size);
  if (!eglMakeCurrent (edpy, pbuffer, pbuffer, context)) fail ("EGL current");
  gpu.GetString = (void *) eglGetProcAddress ("glGetString");
  if (!gpu.GetString) fail ("glGetString missing");
  gpu.GetError = (void *) eglGetProcAddress ("glGetError");
  if (!gpu.GetError) fail ("glGetError missing");
  gpu.GenTextures = (void *) eglGetProcAddress ("glGenTextures");
  if (!gpu.GenTextures) fail ("glGenTextures missing");
  gpu.BindTexture = (void *) eglGetProcAddress ("glBindTexture");
  if (!gpu.BindTexture) fail ("glBindTexture missing");
  gpu.TexParameteri = (void *) eglGetProcAddress ("glTexParameteri");
  if (!gpu.TexParameteri) fail ("glTexParameteri missing");
  gpu.EGLImageTargetTexture2DOES = (void *) eglGetProcAddress ("glEGLImageTargetTexture2DOES");
  if (!gpu.EGLImageTargetTexture2DOES) fail ("glEGLImageTargetTexture2DOES missing");
  gpu.GenFramebuffers = (void *) eglGetProcAddress ("glGenFramebuffers");
  if (!gpu.GenFramebuffers) fail ("glGenFramebuffers missing");
  gpu.BindFramebuffer = (void *) eglGetProcAddress ("glBindFramebuffer");
  if (!gpu.BindFramebuffer) fail ("glBindFramebuffer missing");
  gpu.FramebufferTexture2D = (void *) eglGetProcAddress ("glFramebufferTexture2D");
  if (!gpu.FramebufferTexture2D) fail ("glFramebufferTexture2D missing");
  gpu.CheckFramebufferStatus = (void *) eglGetProcAddress ("glCheckFramebufferStatus");
  if (!gpu.CheckFramebufferStatus) fail ("glCheckFramebufferStatus missing");
  gpu.Viewport = (void *) eglGetProcAddress ("glViewport");
  if (!gpu.Viewport) fail ("glViewport missing");
  gpu.Disable = (void *) eglGetProcAddress ("glDisable");
  if (!gpu.Disable) fail ("glDisable missing");
  gpu.Enable = (void *) eglGetProcAddress ("glEnable");
  if (!gpu.Enable) fail ("glEnable missing");
  gpu.Scissor = (void *) eglGetProcAddress ("glScissor");
  if (!gpu.Scissor) fail ("glScissor missing");
  gpu.ClearColor = (void *) eglGetProcAddress ("glClearColor");
  if (!gpu.ClearColor) fail ("glClearColor missing");
  gpu.Clear = (void *) eglGetProcAddress ("glClear");
  if (!gpu.Clear) fail ("glClear missing");
  gpu.Finish = (void *) eglGetProcAddress ("glFinish");
  if (!gpu.Finish) fail ("glFinish missing");
  gpu.DeleteFramebuffers = (void *) eglGetProcAddress ("glDeleteFramebuffers");
  if (!gpu.DeleteFramebuffers) fail ("glDeleteFramebuffers missing");
  gpu.DeleteTextures = (void *) eglGetProcAddress ("glDeleteTextures");
  if (!gpu.DeleteTextures) fail ("glDeleteTextures missing");
  gpu.CreateImageKHR = (void *) eglGetProcAddress ("eglCreateImageKHR");
  if (!gpu.CreateImageKHR) fail ("eglCreateImageKHR missing");
  gpu.DestroyImageKHR = (void *) eglGetProcAddress ("eglDestroyImageKHR");
  if (!gpu.DestroyImageKHR) fail ("eglDestroyImageKHR missing");
  gpu.GetNativeClientBufferANDROID = (void *) eglGetProcAddress ("eglGetNativeClientBufferANDROID");
  if (!gpu.GetNativeClientBufferANDROID) fail ("eglGetNativeClientBufferANDROID missing");
  const char *renderer = (const char *) glGetString (GL_RENDERER);
  printf ("AHB_CLIENT_GL vendor=%s renderer=%s version=%s\n", glGetString (GL_VENDOR),
          renderer, glGetString (GL_VERSION));
  if (!renderer || strstr (renderer, "llvmpipe") || strstr (renderer, "softpipe") ||
      strstr (renderer, "SwiftShader") || strstr (renderer, "lavapipe")) fail ("software renderer");
}
static void init_buffer (struct buffer *b, unsigned format) {
  AHardwareBuffer_Desc desc = { .width = WIDTH, .height = HEIGHT, .layers = 1,
    .format = format, .usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                              AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE };
  const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
  if (android_ahb_allocate (&desc, &b->ahb) != 0) fail ("AHB allocate");
  b->image = eglCreateImageKHR (edpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
    eglGetNativeClientBufferANDROID (b->ahb), attrs);
  if (b->image == EGL_NO_IMAGE_KHR) fail ("AHB import");
  glGenTextures (1, &b->texture); glBindTexture (GL_TEXTURE_2D, b->texture);
  glTexParameteri (GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri (GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri (GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri (GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glEGLImageTargetTexture2DOES (GL_TEXTURE_2D, b->image);
  glGenFramebuffers (1, &b->fbo); glBindFramebuffer (GL_FRAMEBUFFER, b->fbo);
  glFramebufferTexture2D (GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, b->texture, 0);
  if (glCheckFramebufferStatus (GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) fail ("AHB framebuffer");
}
static void draw (struct buffer *b, int probe) {
  glBindFramebuffer (GL_FRAMEBUFFER, b->fbo); glViewport (0, 0, WIDTH, HEIGHT);
  glDisable (GL_SCISSOR_TEST); glClearColor (1, 0, 0, 1); glClear (GL_COLOR_BUFFER_BIT);
  if (!probe) {
    glEnable (GL_SCISSOR_TEST);
    glScissor (0, 0, WIDTH, HEIGHT / 2); glClearColor (0, 1, 0, 1); glClear (GL_COLOR_BUFFER_BIT);
    glScissor ((frames * 7) % (WIDTH - 48), 0, 48, HEIGHT);
    glClearColor (0, 0, 1, 1); glClear (GL_COLOR_BUFFER_BIT); glDisable (GL_SCISSOR_TEST);
  }
  glFinish ();
  if (glGetError () != GL_NO_ERROR) fail ("GPU draw");
}
static double seconds (void) {
  struct timespec time; clock_gettime (CLOCK_MONOTONIC, &time);
  return time.tv_sec + time.tv_nsec / 1e9;
}
int main (int argc, char **argv) {
  unsigned format = argc > 1 ? (unsigned) atoi (argv[1]) : 1;
  diagnostic_mode = argc > 2 ? atoi (argv[2]) : 0;
  setbuf (stdout, NULL); init_egl ();
  display = wl_display_connect (NULL); if (!display) fail ("Wayland connect");
  struct wl_registry *registry = wl_display_get_registry (display);
  wl_registry_add_listener (registry, &registry_listener, NULL);
  if (wl_display_roundtrip (display) < 0 || !manager || !wm || !compositor) fail ("required globals");
  for (int n = 0; n < 2; n++) init_buffer (&buffers[n], format);
  draw (&buffers[0], 1);
  int fd = send_buffer (buffers[0].ahb);
  termux_ahardware_buffer_manager_v1_probe_buffer (manager, fd, 7); close (fd);
  if (wl_display_roundtrip (display) < 0 || !order) fail ("compositor sampling probe");
  if (diagnostic_mode) {
    /* Negative protocol tests: invalid flags, non-socket, incomplete handle. */
    if (diagnostic_mode == 1) fd = send_buffer (buffers[0].ahb);
    else if (diagnostic_mode == 2) fd = dup (STDOUT_FILENO);
    else {
      int pair[2]; socketpair (AF_UNIX, SOCK_STREAM, 0, pair);
      fd = pair[1]; /* pair[0] stays open, deliberately queues no handle. */
    }
    struct wl_buffer *bad = termux_ahardware_buffer_manager_v1_create_buffer (
      manager, fd, diagnostic_mode == 1 ? 0x80 : 0); close (fd);
    int result = wl_display_roundtrip (display);
    printf ("AHB_CLIENT_REJECT mode=%d disconnected=%d\n", diagnostic_mode, result < 0);
    return result < 0 ? 0 : 1;
  }
  for (int n = 0; n < 2; n++) {
    fd = send_buffer (buffers[n].ahb);
    buffers[n].wl = termux_ahardware_buffer_manager_v1_create_buffer (manager, fd,
      1 | (order == TERMUX_AHARDWARE_BUFFER_MANAGER_V1_CHANNEL_ORDER_SWAP_RED_BLUE ? 2 : 0));
    close (fd); wl_buffer_add_listener (buffers[n].wl, &buffer_listener, &buffers[n]);
  }
  surface = wl_compositor_create_surface (compositor); xdg = xdg_wm_base_get_xdg_surface (wm, surface);
  xdg_surface_add_listener (xdg, &surface_listener, NULL);
  toplevel = xdg_surface_get_toplevel (xdg);
  xdg_toplevel_add_listener (toplevel, &top_listener, NULL);
  char title[80]; snprintf (title, sizeof title, "Native AHB GPU client (format %u)", format);
  xdg_toplevel_set_title (toplevel, title); xdg_toplevel_set_app_id (toplevel, "mutter-ahb-test");
  wl_surface_commit (surface);
  while (!configured && wl_display_dispatch (display) >= 0) { }
  double deadline = seconds () + 120, finished = 0;
  while (running && seconds () < deadline) {
    if (wl_display_dispatch_pending (display) < 0) fail ("dispatch pending");
    if (!frame_pending && frames < goal) for (int n = 0; n < 2; n++) if (!buffers[n].busy) {
      draw (&buffers[n], 0); buffers[n].busy = 1;
      struct wl_callback *callback = wl_surface_frame (surface);
      wl_callback_add_listener (callback, &frame_listener, NULL); frame_pending = 1;
      wl_surface_attach (surface, buffers[n].wl, 0, 0); wl_surface_damage (surface, 0, 0, WIDTH, HEIGHT);
      wl_surface_commit (surface); frames++;
      if (frames % 30 == 0) printf ("AHB_CLIENT_FRAMES submitted=%u released=%u\n", frames, releases);
      break;
    }
    if (frames == goal && !frame_pending && !finished) {
      printf ("AHB_CLIENT_PASS frames=%u releases=%u CPU_upload=off CPU_readback=off\n", frames, releases);
      finished = seconds ();
    }
    if (finished && seconds () - finished > 90) break;
    if (wl_display_flush (display) < 0) fail ("Wayland flush");
    struct pollfd pollfd = { .fd = wl_display_get_fd (display), .events = POLLIN };
    int ready = poll (&pollfd, 1, 100);
    if (ready > 0 && wl_display_dispatch (display) < 0) fail ("Wayland dispatch");
  }
  xdg_toplevel_destroy (toplevel); xdg_surface_destroy (xdg); wl_surface_destroy (surface);
  wl_display_roundtrip (display);
  for (int n = 0; n < 2; n++) {
    wl_buffer_destroy (buffers[n].wl); glDeleteFramebuffers (1, &buffers[n].fbo);
    glDeleteTextures (1, &buffers[n].texture); eglDestroyImageKHR (edpy, buffers[n].image);
    android_ahb_release (buffers[n].ahb);
  }
  termux_ahardware_buffer_manager_v1_destroy (manager); wl_display_disconnect (display);
  eglMakeCurrent (edpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  eglDestroySurface (edpy, pbuffer); eglDestroyContext (edpy, context); eglTerminate (edpy);
  return frames == goal && finished ? 0 : 1;
}
