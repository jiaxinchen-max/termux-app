/* SPDX-License-Identifier: GPL-2.0-or-later
 * Android output/input backend. Uses termux-render's existing producer API.
 * No KMS, GBM, logind, or framebuffer readback in the hardware output path.
 */
#include "config.h"
#include "backends/android/meta-backend-android.h"
#include "backends/meta-renderer.h"
#include "backends/meta-renderer-view.h"
#include "backends/meta-color-manager-private.h"
#include "backends/meta-color-device.h"
#include "backends/meta-monitor-manager-dummy.h"
#include "backends/meta-input-settings-dummy.h"
#include "backends/meta-cursor-renderer.h"
#include "backends/meta-cursor.h"
#include "backends/meta-stage-private.h"
#include "backends/meta-stage-impl-private.h"
#include "clutter/clutter-mutter.h"
#include "clutter/clutter-event-private.h"
#include "clutter/clutter-input-device-private.h"
#include "cogl/cogl-context-private.h"
#include "cogl/cogl-renderer-private.h"
#include "cogl/cogl-display-private.h"
#include "cogl/winsys/cogl-winsys-egl-private.h"
#include "core/meta-context-private.h"
#include <glib-unix.h>
#include <termux/render/render.h>
#include <termux/render/buffer.h>
#include <android/hardware_buffer.h>
#include <drm_fourcc.h>
#include <xkbcommon/xkbcommon.h>
#include <fcntl.h>
#include <errno.h>
#include <dlfcn.h>
#include <math.h>
#include <sys/socket.h>

struct _MetaBackendAndroid {
  MetaBackend parent;
  int width, height, refresh, input_width, input_height;
  gboolean system;
  LorieBuffer *buffer;
  struct lorie_shared_server_state *state;
  MetaCursorRenderer *cursor;
  MetaInputSettings *settings;
  guint input_source;
  GByteArray *input;
  gsize discard;
};
G_DEFINE_TYPE (MetaBackendAndroid, meta_backend_android, META_TYPE_BACKEND)

/* The keymap describes the actual XKB state owned by the Android seat. */
typedef struct { ClutterKeymap parent; } AndroidKeymap;
typedef ClutterKeymapClass AndroidKeymapClass;
static GType android_keymap_get_type(void);
G_DEFINE_TYPE (AndroidKeymap, android_keymap, CLUTTER_TYPE_KEYMAP)
static ClutterTextDirection keymap_direction (ClutterKeymap *keymap) { return CLUTTER_TEXT_DIRECTION_LTR; }
static void android_keymap_class_init (AndroidKeymapClass *klass) { klass->get_direction = keymap_direction; }
static void android_keymap_init (AndroidKeymap *keymap) { }

typedef struct {
  ClutterSeat parent;
  ClutterInputDevice *pointer, *keyboard, *touch, *pointer_source, *keyboard_source;
  ClutterKeymap *keymap;
  struct xkb_context *xkb_context;
  struct xkb_keymap *xkb_keymap;
  struct xkb_state *xkb_state;
  graphene_point_t pos;
  uint32_t buttons;
  GList *devices;
  GHashTable *touches;
} AndroidSeat;
typedef ClutterSeatClass AndroidSeatClass;
static GType android_seat_get_type(void);
G_DEFINE_TYPE (AndroidSeat, android_seat, CLUTTER_TYPE_SEAT)
static ClutterInputDevice *seat_pointer (ClutterSeat *seat) { return ((AndroidSeat *)seat)->pointer; }
static ClutterInputDevice *seat_keyboard (ClutterSeat *seat) { return ((AndroidSeat *)seat)->keyboard; }
static ClutterKeymap *seat_keymap (ClutterSeat *seat) { return ((AndroidSeat *)seat)->keymap; }
static const GList *seat_devices (ClutterSeat *seat) { return ((AndroidSeat *)seat)->devices; }
static gboolean seat_handle_post (ClutterSeat *seat, const ClutterEvent *event) { return TRUE; }
static void seat_bell (ClutterSeat *seat) { }
static ClutterModifierType seat_mods (AndroidSeat *s) {
  return s->buttons | xkb_state_serialize_mods (s->xkb_state, XKB_STATE_MODS_EFFECTIVE);
}
static gboolean seat_query (ClutterSeat *seat, ClutterInputDevice *device,
                            ClutterEventSequence *sequence, graphene_point_t *coords,
                            ClutterModifierType *mods) {
  AndroidSeat *s = (AndroidSeat *)seat;
  if(coords)*coords = s->pos; if(mods)*mods = seat_mods (s); return TRUE;
}
static void seat_warp (ClutterSeat *seat, int x, int y) { ((AndroidSeat *)seat)->pos = GRAPHENE_POINT_INIT(x,y); }
static void seat_position (ClutterSeat *seat, float x, float y) { ((AndroidSeat *)seat)->pos = GRAPHENE_POINT_INIT(x,y); }
static ClutterVirtualDeviceType seat_virtual_types (ClutterSeat *seat) { return CLUTTER_VIRTUAL_DEVICE_TYPE_NONE; }
static void seat_set_xkb (AndroidSeat *s, const char *layouts, const char *variants,
                          const char *options, const char *model) {
  struct xkb_rule_names names = { .rules="evdev", .model=model ? model : "pc105",
    .layout=layouts && *layouts ? layouts : "us", .variant=variants, .options=options };
  struct xkb_keymap *map = xkb_keymap_new_from_names(s->xkb_context, &names, XKB_KEYMAP_COMPILE_NO_FLAGS);
  if (!map) g_error("Android: failed to compile keyboard map");
  struct xkb_state *state = xkb_state_new(map);
  if (!state) g_error("Android: failed to create keyboard state");
  if (s->xkb_state) xkb_state_unref(s->xkb_state);
  if (s->xkb_keymap) xkb_keymap_unref(s->xkb_keymap);
  s->xkb_keymap=map; s->xkb_state=state;
  g_signal_emit_by_name(s->keymap,"state-changed");
}
static void seat_constructed (GObject *object) {
  AndroidSeat *s=(AndroidSeat *)object;
  G_OBJECT_CLASS(android_seat_parent_class)->constructed(object);
  s->keymap=g_object_new(android_keymap_get_type(),NULL);
  s->xkb_context=xkb_context_new(XKB_CONTEXT_NO_FLAGS);
  seat_set_xkb(s,"us",NULL,NULL,NULL);
  s->pointer=g_object_new(CLUTTER_TYPE_INPUT_DEVICE,"name","Android pointer","seat",s,
    "capabilities",CLUTTER_INPUT_CAPABILITY_POINTER,"device-type",CLUTTER_POINTER_DEVICE,"device-mode",CLUTTER_INPUT_MODE_LOGICAL,"has-cursor",TRUE,NULL);
  s->keyboard=g_object_new(CLUTTER_TYPE_INPUT_DEVICE,"name","Android keyboard","seat",s,
    "capabilities",CLUTTER_INPUT_CAPABILITY_KEYBOARD,"device-type",CLUTTER_KEYBOARD_DEVICE,"device-mode",CLUTTER_INPUT_MODE_LOGICAL,NULL);
  s->touch=g_object_new(CLUTTER_TYPE_INPUT_DEVICE,"name","Android touchscreen","seat",s,
    "capabilities",CLUTTER_INPUT_CAPABILITY_TOUCH,"device-type",CLUTTER_TOUCHSCREEN_DEVICE,"device-mode",CLUTTER_INPUT_MODE_PHYSICAL,NULL);
  s->pointer_source=g_object_new(CLUTTER_TYPE_INPUT_DEVICE,"name","Android mouse source","seat",s,
    "capabilities",CLUTTER_INPUT_CAPABILITY_POINTER,"device-type",CLUTTER_POINTER_DEVICE,
    "device-mode",CLUTTER_INPUT_MODE_PHYSICAL,"has-cursor",TRUE,NULL);
  s->keyboard_source=g_object_new(CLUTTER_TYPE_INPUT_DEVICE,"name","Android keyboard source","seat",s,
    "capabilities",CLUTTER_INPUT_CAPABILITY_KEYBOARD,"device-type",CLUTTER_KEYBOARD_DEVICE,
    "device-mode",CLUTTER_INPUT_MODE_PHYSICAL,NULL);
  s->devices=g_list_append(s->devices,s->pointer_source);
  s->devices=g_list_append(s->devices,s->keyboard_source);
  s->devices=g_list_append(s->devices,s->touch);
  s->touches=g_hash_table_new(g_direct_hash,g_direct_equal);
}
static void seat_dispose (GObject *object) {
  AndroidSeat *s=(AndroidSeat *)object;
  g_clear_object(&s->pointer);g_clear_object(&s->keyboard);g_clear_object(&s->touch);
  g_clear_object(&s->pointer_source);g_clear_object(&s->keyboard_source);
  g_clear_object(&s->keymap);g_clear_pointer(&s->devices,g_list_free);
  g_clear_pointer(&s->touches,g_hash_table_unref);
  g_clear_pointer(&s->xkb_state,xkb_state_unref);
  g_clear_pointer(&s->xkb_keymap,xkb_keymap_unref);
  g_clear_pointer(&s->xkb_context,xkb_context_unref);
  G_OBJECT_CLASS(android_seat_parent_class)->dispose(object);
}
static void android_seat_class_init (AndroidSeatClass *klass) {
  GObjectClass *oc=G_OBJECT_CLASS(klass);oc->constructed=seat_constructed;oc->dispose=seat_dispose;
  klass->get_pointer=seat_pointer;klass->get_keyboard=seat_keyboard;klass->get_keymap=seat_keymap;
  klass->peek_devices=seat_devices;klass->bell_notify=seat_bell;klass->handle_event_post=seat_handle_post;
  klass->warp_pointer=seat_warp;klass->init_pointer_position=seat_position;klass->query_state=seat_query;
  klass->get_supported_virtual_device_types=seat_virtual_types;
}
static void android_seat_init (AndroidSeat *seat) { }

/* Custom Cogl EGL winsys, independent of the X11 and DRM platform paths. */
static gboolean egl_display_setup (CoglDisplay *display,GError **error) { return TRUE; }
static void egl_display_destroy (CoglDisplay *display) { }
static int egl_attributes (CoglDisplay *display,EGLint *a) {
  a[0]=EGL_SURFACE_TYPE;a[1]=EGL_PBUFFER_BIT;return 2;
}
static gboolean egl_choose_config(CoglDisplay *display,EGLint *attrs,EGLConfig *config,GError **error) {
  CoglRendererEGL *r=display->renderer->winsys;EGLint count=0;
  if(!eglChooseConfig(r->edpy,attrs,config,1,&count) || count!=1) {
    g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Android EGL config unavailable: 0x%x",eglGetError());return FALSE;
  }
  return TRUE;
}
static gboolean egl_context_created (CoglDisplay *display,GError **error) {
  CoglRendererEGL *r=display->renderer->winsys;CoglDisplayEGL *d=display->winsys;
  const EGLint attrs[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
  d->dummy_surface=eglCreatePbufferSurface(r->edpy,d->egl_config,attrs);
  if(d->dummy_surface==EGL_NO_SURFACE || !_cogl_winsys_egl_make_current(display,
     d->dummy_surface,d->dummy_surface,d->egl_context)) {
    g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Android EGL pbuffer/current failed: 0x%x",eglGetError());
    return FALSE;
  }
  return TRUE;
}
static void egl_context_cleanup (CoglDisplay *display) {
  CoglRendererEGL *r=display->renderer->winsys;CoglDisplayEGL *d=display->winsys;
  if(d->dummy_surface!=EGL_NO_SURFACE) eglDestroySurface(r->edpy,d->dummy_surface);
  d->dummy_surface=EGL_NO_SURFACE;
}
static gboolean egl_context_init (CoglContext *context,GError **error) { return TRUE; }
static const CoglWinsysEGLVtable android_egl={
  .display_setup=egl_display_setup,.display_destroy=egl_display_destroy,
  .context_created=egl_context_created,.cleanup_context=egl_context_cleanup,
  .context_init=egl_context_init,.add_config_attributes=egl_attributes,.choose_config=egl_choose_config
};
static void renderer_disconnect (CoglRenderer *renderer) {
  CoglRendererEGL *r=renderer->winsys;
  if(r){eglTerminate(r->edpy);g_free(r);renderer->winsys=NULL;}
}
static gboolean renderer_connect (CoglRenderer *renderer,GError **error) {
  if(renderer->driver_id!=COGL_DRIVER_ID_GLES2) {
    g_set_error(error,G_IO_ERROR,G_IO_ERROR_NOT_SUPPORTED,"Android backend requires the Cogl GLES2 driver");return FALSE;
  }
  MetaBackendAndroid *a=META_BACKEND_ANDROID(meta_renderer_get_backend(renderer->custom_winsys_user_data));
  CoglRendererEGL *r=g_new0(CoglRendererEGL,1);renderer->winsys=r;
  r->platform_vtable=&android_egl;r->needs_config=TRUE;
  if(a->system) r->edpy=eglGetDisplay(EGL_DEFAULT_DISPLAY);
  else {
    PFNEGLGETPLATFORMDISPLAYEXTPROC get=(void *)eglGetProcAddress("eglGetPlatformDisplayEXT");
    r->edpy=get ? get(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,NULL) : EGL_NO_DISPLAY;
  }
  if(r->edpy==EGL_NO_DISPLAY || !_cogl_winsys_egl_renderer_connect_common(renderer,error)) {
    if(error && !*error)g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Android EGL display unavailable");
    renderer_disconnect(renderer);return FALSE;
  }
  return TRUE;
}
static const CoglWinsysVtable *android_winsys (CoglRenderer *renderer) {
  static CoglWinsysVtable v;static gsize once;
  if(g_once_init_enter(&once)) {
    v=*_cogl_winsys_egl_get_vtable();v.id=COGL_WINSYS_ID_CUSTOM;v.name="EGL_ANDROID";
    v.renderer_connect=renderer_connect;v.renderer_disconnect=renderer_disconnect;
    g_once_init_leave(&once,1);
  }
  return &v;
}

typedef struct { MetaRenderer parent; EGLImageKHR image; } AndroidRenderer;
typedef MetaRendererClass AndroidRendererClass;
static GType android_renderer_get_type(void);
G_DEFINE_TYPE(AndroidRenderer,android_renderer,META_TYPE_RENDERER)
static CoglRenderer *android_create_cogl (MetaRenderer *renderer) {
  CoglRenderer *r=cogl_renderer_new();
  cogl_renderer_set_driver(r,COGL_DRIVER_ID_GLES2);
  cogl_renderer_set_custom_winsys(r,android_winsys,renderer);return r;
}
static EGLImageKHR import_output (MetaBackendAndroid *a,EGLDisplay dpy,GError **error) {
  const LorieBuffer_Desc *desc=LorieBuffer_description(a->buffer);
  PFNEGLCREATEIMAGEKHRPROC create=(void *)eglGetProcAddress("eglCreateImageKHR");
  if(!create){g_set_error(error,G_IO_ERROR,G_IO_ERROR_NOT_SUPPORTED,"No eglCreateImageKHR");return EGL_NO_IMAGE_KHR;}
  if(a->system) {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get=(void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    AHardwareBuffer *ahb=desc->buffer;
    const EGLint attrs[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
    if(get && ahb)return create(dpy,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,get(ahb),attrs);
  } else {
    typedef int (*DupFd)(const LorieBuffer *);
    DupFd dupfd=(void *)dlsym(RTLD_DEFAULT,"LorieBuffer_dupDmaBufFd");
    int fd=dupfd ? dupfd(a->buffer) : -1;
    if(fd>=0) {
      EGLint attrs[]={EGL_WIDTH,desc->width,EGL_HEIGHT,desc->height,
        EGL_LINUX_DRM_FOURCC_EXT,DRM_FORMAT_XBGR8888,
        EGL_DMA_BUF_PLANE0_FD_EXT,fd,EGL_DMA_BUF_PLANE0_OFFSET_EXT,0,
        EGL_DMA_BUF_PLANE0_PITCH_EXT,desc->stride*4,EGL_NONE};
      EGLImageKHR image=create(dpy,EGL_NO_CONTEXT,EGL_LINUX_DMA_BUF_EXT,NULL,attrs);
      close(fd);return image;
    }
  }
  g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Android output import failed (no CPU fallback), EGL=0x%x",eglGetError());
  return EGL_NO_IMAGE_KHR;
}
static MetaRendererView *android_create_view (MetaRenderer *renderer,MetaLogicalMonitor *logical,
  MetaMonitor *monitor,MetaOutput *output,MetaCrtc *crtc,GError **error) {
  AndroidRenderer *r=(AndroidRenderer *)renderer;
  MetaBackend *backend=meta_renderer_get_backend(renderer);MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  CoglContext *ctx=clutter_backend_get_cogl_context(meta_backend_get_clutter_backend(backend));
  if(r->image!=EGL_NO_IMAGE_KHR) {
    PFNEGLDESTROYIMAGEKHRPROC destroy=(void *)eglGetProcAddress("eglDestroyImageKHR");
    destroy(cogl_context_get_egl_display(ctx),r->image);r->image=EGL_NO_IMAGE_KHR;
  }
  r->image=import_output(a,cogl_context_get_egl_display(ctx),error);
  if(r->image==EGL_NO_IMAGE_KHR)return NULL;
  CoglTexture *tex=COGL_TEXTURE(cogl_texture_2d_new_from_egl_image(ctx,a->width,a->height,
    COGL_PIXEL_FORMAT_RGB_888,r->image,COGL_EGL_IMAGE_FLAG_NO_GET_DATA,error));
  PFNEGLDESTROYIMAGEKHRPROC destroy=(void *)eglGetProcAddress("eglDestroyImageKHR");
  destroy(cogl_context_get_egl_display(ctx),r->image);r->image=EGL_NO_IMAGE_KHR;
  if(!tex)return NULL;
  CoglOffscreen *fb=cogl_offscreen_new_with_texture(tex);g_object_unref(tex);
  if(!cogl_framebuffer_allocate(COGL_FRAMEBUFFER(fb),error)){g_object_unref(fb);return NULL;}
  MetaColorDevice *color=meta_color_manager_get_color_device(meta_backend_get_color_manager(backend),monitor);
  MtkRectangle layout={0,0,a->width,a->height};
  /* The existing termux-render RGBX root consumer interprets X11/BGRA
   * pixels and applies .bgra in its display shader. Match that ABI in one GPU
   * pass; never change the APK or read/map the composed pixels on the CPU. */
  CoglTexture *stage_tex=cogl_texture_2d_new_with_size(ctx,a->width,a->height);
  CoglOffscreen *stage_fb=cogl_offscreen_new_with_texture(stage_tex);
  if(!cogl_framebuffer_allocate(COGL_FRAMEBUFFER(stage_fb),error)) {
    g_object_unref(stage_fb);g_object_unref(stage_tex);g_object_unref(fb);return NULL;
  }
  CoglPipeline *present=cogl_pipeline_new(ctx);
  cogl_pipeline_set_layer_texture(present,0,stage_tex);
  cogl_pipeline_set_layer_filters(present,0,COGL_PIPELINE_FILTER_NEAREST,COGL_PIPELINE_FILTER_NEAREST);
  cogl_pipeline_set_blend(present,"RGBA = ADD(SRC_COLOR, 0)",NULL);
  CoglSnippet *swizzle=cogl_snippet_new(COGL_SNIPPET_HOOK_TEXTURE_LOOKUP,NULL,NULL);
  cogl_snippet_set_post(swizzle,"cogl_texel = cogl_texel.bgra;");
  cogl_pipeline_add_layer_snippet(present,0,swizzle);g_object_unref(swizzle);
  g_object_unref(stage_tex);
  MetaRendererView *view=g_object_new(META_TYPE_RENDERER_VIEW,"name","Android AHB",
    "backend",backend,"stage",meta_backend_get_stage(backend),"crtc",crtc,"color-device",color,"layout",&layout,
    "framebuffer",stage_fb,"scale",1.0f,"refresh-rate",(float)a->refresh,NULL);
  g_object_set_data_full(G_OBJECT(view),"android-output-fb",fb,g_object_unref);
  g_object_set_data_full(G_OBJECT(view),"android-output-pipeline",present,g_object_unref);
  g_object_unref(stage_fb);
  g_message("Android AHB output ready: %dx%d, import=%s, GPU color pass=bgra, CPU readback=off",a->width,a->height,a->system?"native":"dma-buf");
  return view;
}
static void android_renderer_class_init(AndroidRendererClass *c) {
  c->create_cogl_renderer=android_create_cogl;c->create_view=android_create_view;
}
static void android_renderer_init(AndroidRenderer *r) { r->image=EGL_NO_IMAGE_KHR; }

typedef struct { MetaStageImpl parent; } AndroidStage;
typedef MetaStageImplClass AndroidStageClass;
static GType android_stage_get_type(void);
G_DEFINE_TYPE(AndroidStage,android_stage,META_TYPE_STAGE_IMPL)
static GList *stage_views(ClutterStageWindow *stage) {
  MetaBackend *backend=meta_stage_impl_get_backend(META_STAGE_IMPL(stage));
  return meta_renderer_get_views(meta_backend_get_renderer(backend));
}
static void stage_geometry(ClutterStageWindow *stage,MtkRectangle *rect) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(meta_stage_impl_get_backend(META_STAGE_IMPL(stage)));
  *rect=(MtkRectangle){0,0,a->width,a->height};
}
static void stage_redraw(ClutterStageWindow *stage,ClutterStageView *view,ClutterFrame *frame) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(meta_stage_impl_get_backend(META_STAGE_IMPL(stage)));
  lorie_mutex_lock(&a->state->lock,&a->state->lockingPid);
  CLUTTER_STAGE_WINDOW_CLASS(android_stage_parent_class)->redraw_view(stage,view,frame);
  CoglFramebuffer *output_fb=g_object_get_data(G_OBJECT(view),"android-output-fb");
  CoglPipeline *present=g_object_get_data(G_OBJECT(view),"android-output-pipeline");
  /* Cogl offscreens use the opposite vertical sampling origin to the App. */
  cogl_framebuffer_draw_textured_rectangle(output_fb,present,-1,-1,1,1,0,1,1,0);
  cogl_framebuffer_finish(output_fb);
  if(!clutter_frame_has_result(frame))clutter_frame_set_result(frame,CLUTTER_FRAME_RESULT_PENDING_PRESENTED);
  a->state->waitForNextFrame=FALSE;a->state->drawRequested=1;
  if(rendererCond)pthread_cond_signal(rendererCond);
  lorie_mutex_unlock(&a->state->lock,&a->state->lockingPid);
  /* Upstream offscreen views schedule their completion via an idle source.
   * The buffer is submitted before that source can run. This is producer
   * completion, not an Android hardware presentation timestamp. */
}
static void android_stage_class_init(AndroidStageClass *klass) {
  ClutterStageWindowClass *c=CLUTTER_STAGE_WINDOW_CLASS(klass);
  c->get_geometry=stage_geometry;c->get_views=stage_views;c->redraw_view=stage_redraw;
}
static void android_stage_init(AndroidStage *stage) { }

typedef struct { ClutterBackend parent; MetaBackend *backend; } AndroidClutter;
typedef ClutterBackendClass AndroidClutterClass;
static GType android_clutter_get_type(void);
G_DEFINE_TYPE(AndroidClutter,android_clutter,CLUTTER_TYPE_BACKEND)
static CoglRenderer *clutter_renderer(ClutterBackend *c,GError **error) {
  return meta_renderer_create_cogl_renderer(meta_backend_get_renderer(((AndroidClutter *)c)->backend));
}
static ClutterStageWindow *clutter_stage(ClutterBackend *c,ClutterStage *wrapper,GError **error) {
  return g_object_new(android_stage_get_type(),"backend",((AndroidClutter *)c)->backend,"wrapper",wrapper,NULL);
}
static ClutterSeat *clutter_seat(ClutterBackend *c) { return meta_backend_get_default_seat(((AndroidClutter *)c)->backend); }
static gboolean clutter_display_server(ClutterBackend *c) { return TRUE; }
static void android_clutter_class_init(AndroidClutterClass *c) {
  c->get_renderer=clutter_renderer;c->create_stage=clutter_stage;
  c->get_default_seat=clutter_seat;c->is_display_server=clutter_display_server;
}
static void android_clutter_init(AndroidClutter *c) { }

/* Software cursor means the cursor is composited by the same GPU scene. */
typedef struct { MetaCursorRenderer parent; } AndroidCursor;
typedef MetaCursorRendererClass AndroidCursorClass;
static GType android_cursor_get_type(void);
G_DEFINE_TYPE(AndroidCursor,android_cursor,META_TYPE_CURSOR_RENDERER)
static gboolean cursor_update(MetaCursorRenderer *r,MetaCursorSprite *sprite) {
  if(sprite)meta_cursor_sprite_realize_texture(sprite);return FALSE;
}
static void android_cursor_class_init(AndroidCursorClass *c) { c->update_cursor=cursor_update; }
static void android_cursor_init(AndroidCursor *c) { }

static void push_event(ClutterEvent *event) { if(event)_clutter_event_push(event,FALSE); }
static void input_key(AndroidSeat *s,unsigned evcode,gboolean down) {
  unsigned code=evcode+8;uint32_t sym=xkb_state_key_get_one_sym(s->xkb_state,code);
  gunichar unicode=xkb_state_key_get_utf32(s->xkb_state,code);
  xkb_state_update_key(s->xkb_state,code,down?XKB_KEY_DOWN:XKB_KEY_UP);
  ClutterModifierSet mods={xkb_state_serialize_mods(s->xkb_state,XKB_STATE_MODS_DEPRESSED),
    xkb_state_serialize_mods(s->xkb_state,XKB_STATE_MODS_LATCHED),xkb_state_serialize_mods(s->xkb_state,XKB_STATE_MODS_LOCKED)};
  push_event(clutter_event_key_new(down?CLUTTER_KEY_PRESS:CLUTTER_KEY_RELEASE,CLUTTER_EVENT_NONE,
    g_get_monotonic_time(),s->keyboard_source,mods,seat_mods(s),sym,evcode,code,unicode));
}
static void input_unicode(AndroidSeat *s,uint32_t unicode) {
  xkb_keysym_t wanted=xkb_utf32_to_keysym(unicode);
  xkb_layout_index_t layout=xkb_state_serialize_layout(s->xkb_state,XKB_STATE_LAYOUT_EFFECTIVE);
  for(xkb_keycode_t key=xkb_keymap_min_keycode(s->xkb_keymap);key<=xkb_keymap_max_keycode(s->xkb_keymap);key++) {
    for(xkb_level_index_t level=0;level<2;level++) {
      const xkb_keysym_t *symbols;
      int n=xkb_keymap_key_get_syms_by_level(s->xkb_keymap,key,layout,level,&symbols);
      if(n!=1 || symbols[0]!=wanted)continue;
      gboolean shift=level==1 && !xkb_state_mod_name_is_active(s->xkb_state,XKB_MOD_NAME_SHIFT,XKB_STATE_MODS_EFFECTIVE);
      if(shift)input_key(s,KEY_LEFTSHIFT,TRUE);
      input_key(s,key-8,TRUE);input_key(s,key-8,FALSE);
      if(shift)input_key(s,KEY_LEFTSHIFT,FALSE);
      return;
    }
  }
  g_warning("Android Unicode U+%04X has no key in the current XKB layout",unicode);
}
static void input_event(MetaBackendAndroid *a,const lorieEvent *e) {
  AndroidSeat *s=(AndroidSeat *)meta_backend_get_default_seat(META_BACKEND(a));
  if(g_getenv("MUTTER_ANDROID_INPUT_DEBUG"))g_message("Android input type=%u detail=%u down=%u x=%g y=%g",e->type,e->mouse.detail,e->mouse.down,e->mouse.x,e->mouse.y);
  int64_t now=g_get_monotonic_time();
  if(e->type==EVENT_UNICODE)input_unicode(s,e->unicode.code);
  else if(e->type==EVENT_KEY)input_key(s,e->key.key,e->key.state);
  else if(e->type==EVENT_MOUSE) {
    if(!e->mouse.detail) {
      graphene_point_t old=s->pos;
      s->pos.x=CLAMP(e->mouse.relative?s->pos.x+e->mouse.x*a->width/a->input_width:e->mouse.x*a->width/a->input_width,0,a->width-1);
      s->pos.y=CLAMP(e->mouse.relative?s->pos.y+e->mouse.y*a->height/a->input_height:e->mouse.y*a->height/a->input_height,0,a->height-1);
      graphene_point_t delta=GRAPHENE_POINT_INIT(s->pos.x-old.x,s->pos.y-old.y);
      push_event(clutter_event_motion_new(CLUTTER_EVENT_NONE,now,s->pointer_source,NULL,seat_mods(s),s->pos,delta,delta,delta,NULL));
    } else if(e->mouse.detail==4 || e->mouse.detail==5) {
      graphene_point_t delta=GRAPHENE_POINT_INIT(e->mouse.detail==4?e->mouse.x:0,e->mouse.detail==5?e->mouse.y:0);
      push_event(clutter_event_scroll_smooth_new(CLUTTER_EVENT_NONE,now,s->pointer_source,NULL,seat_mods(s),s->pos,delta,CLUTTER_SCROLL_SOURCE_WHEEL,CLUTTER_SCROLL_FINISHED_NONE));
    } else if(e->mouse.detail<=3) {
      unsigned button=e->mouse.detail;uint32_t mask=CLUTTER_BUTTON1_MASK << (button-1);
      if(e->mouse.down)s->buttons|=mask;else s->buttons&=~mask;
      uint32_t evcode=button==1?BTN_LEFT:button==2?BTN_MIDDLE:BTN_RIGHT;
      push_event(clutter_event_button_new(e->mouse.down?CLUTTER_BUTTON_PRESS:CLUTTER_BUTTON_RELEASE,
        CLUTTER_EVENT_NONE,now,s->pointer_source,NULL,seat_mods(s),s->pos,button,evcode,NULL));
    }
  } else if(e->type==EVENT_TOUCH) {
    unsigned type=e->touch.type;
    ClutterEventSequence *seq=GUINT_TO_POINTER((unsigned)e->touch.id+1);
    graphene_point_t pos=GRAPHENE_POINT_INIT((float)e->touch.x*a->width/a->input_width,(float)e->touch.y*a->height/a->input_height);
    if(type==18){g_hash_table_add(s->touches,seq);push_event(clutter_event_touch_new(CLUTTER_TOUCH_BEGIN,CLUTTER_EVENT_NONE,now,s->touch,seq,seat_mods(s),pos));}
    else if(type==19 && g_hash_table_remove(s->touches,seq))push_event(clutter_event_touch_new(CLUTTER_TOUCH_END,CLUTTER_EVENT_NONE,now,s->touch,seq,seat_mods(s),pos));
    else if(type==20 && g_hash_table_contains(s->touches,seq))push_event(clutter_event_touch_new(CLUTTER_TOUCH_UPDATE,CLUTTER_EVENT_NONE,now,s->touch,seq,seat_mods(s),pos));
  }
}
static gboolean input_ready(gint fd,GIOCondition condition,gpointer data) {
  MetaBackendAndroid *a=data;
  if(condition&(G_IO_HUP|G_IO_ERR))g_error("Android display/input connection closed");
  uint8_t buf[4096];ssize_t n;
  while((n=recv(fd,buf,sizeof(buf),MSG_DONTWAIT))>0)g_byte_array_append(a->input,buf,n);
  if(n==0 || (n<0 && errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR))g_error("Android input read failed");
  while(a->input->len) {
    if(a->discard){gsize count=MIN(a->discard,a->input->len);g_byte_array_remove_range(a->input,0,count);a->discard-=count;continue;}
    if(a->input->len<sizeof(lorieEvent))break;
    lorieEvent e;memcpy(&e,a->input->data,sizeof(e));g_byte_array_remove_range(a->input,0,sizeof(e));
    if(e.type==EVENT_CLIPBOARD_SEND)a->discard=e.clipboardSend.count;
    else if(e.type==EVENT_SCREEN_SIZE) {
      a->discard=e.screenSize.name_size;
      /* The negotiated AHB stays fixed until reconnect. The App can resize its
       * Surface independently; map its input coordinate space to our output. */
      if(e.screenSize.width>0 && e.screenSize.height>0) {
        a->input_width=e.screenSize.width;a->input_height=e.screenSize.height;
        g_message("Android input viewport %dx%d mapped to fixed output %dx%d",a->input_width,a->input_height,a->width,a->height);
        /* A recreated App Surface has no frame even when the scene and viewport
         * dimensions are unchanged. Submit the existing scene without input. */
        clutter_actor_queue_redraw(CLUTTER_ACTOR(meta_backend_get_stage(META_BACKEND(a))));
      }
    } else input_event(a,&e);
    if(a->discard>1024*1024)g_error("Android input payload exceeds protocol bound");
  }
  return G_SOURCE_CONTINUE;
}
static gboolean backend_init_basic(MetaBackend *backend,GError **error) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  const char *mode=g_getenv("MUTTER_ANDROID_GL_MODE");
  if(g_strcmp0(mode,"system") && g_strcmp0(mode,"zink")) {
    g_set_error(error,G_IO_ERROR,G_IO_ERROR_INVALID_ARGUMENT,"MUTTER_ANDROID_GL_MODE must explicitly be system or zink");return FALSE;
  }
  a->system=!strcmp(mode,"system");
  g_setenv("TERMUX_RENDER_AHB_RECEIVE",a->system?"native":"dmabuf",TRUE);
  setScreenConfig(a->width,a->height,a->refresh,AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM,LORIEBUFFER_AHARDWAREBUFFER);
  setKeycodeFormat(LORIE_KEYCODE_EVDEV);
  if(connectToRender()!=0){stopEventLoop();g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Cannot connect to existing termux-render display service");return FALSE;}
  a->buffer=get_lorieBuffer();a->state=get_serverState();
  if(!a->buffer || !a->state){g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Display service returned no buffer/state");return FALSE;}
  const LorieBuffer_Desc *desc=LorieBuffer_description(a->buffer);a->width=desc->width;a->height=desc->height;
  a->input_width=a->width;a->input_height=a->height;
  g_autofree char *spec=g_strdup_printf("%dx%d@%d",a->width,a->height,a->refresh);
  g_setenv("MUTTER_DEBUG_DUMMY_MODE_SPECS",spec,TRUE);
  MetaGpu *gpu=g_object_new(META_TYPE_GPU_DUMMY,"backend",backend,NULL);meta_backend_add_gpu(backend,gpu);
  return TRUE;
}
static gboolean backend_init_render(MetaBackend *backend,GError **error) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  CoglContext *ctx=clutter_backend_get_cogl_context(meta_backend_get_clutter_backend(backend));
  const char *renderer=(const char *)ctx->glGetString(GL_RENDERER);
  g_autofree char *lower=renderer?g_ascii_strdown(renderer,-1):NULL;
  if(!lower || strstr(lower,"llvmpipe") || strstr(lower,"lavapipe") || strstr(lower,"softpipe") || strstr(lower,"swiftshader") || strstr(lower,"software rasterizer") ||
     (a->system && strstr(lower,"zink")) || (!a->system && !strstr(lower,"zink"))) {
    g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Requested Android renderer does not match GL_RENDERER=%s",renderer?renderer:"(null)");return FALSE;
  }
  g_message("Android GL_VERSION=%s",ctx->glGetString(GL_VERSION));
  g_message("Android renderer verified: mode=%s, GL_VENDOR=%s, GL_RENDERER=%s",a->system?"system":"zink",ctx->glGetString(GL_VENDOR),renderer);
  return TRUE;
}
static gboolean backend_init_post(MetaBackend *backend,GError **error) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  int fd=get_conn_fd();
  if(fd<0){g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"No Android input fd");return FALSE;}
  if(!meta_renderer_get_views(meta_backend_get_renderer(backend))) {
    g_set_error(error,G_IO_ERROR,G_IO_ERROR_FAILED,"Android output has no usable AHB view");return FALSE;
  }
  /* Keep the forwarding library mapped in this compositor, but do not inject it
   * into applications launched by Shell or D-Bus. */
  const char *preload=g_getenv("LD_PRELOAD");
  if(preload) {
    g_auto(GStrv) entries=g_strsplit_set(preload,": ",-1);
    GString *clean=g_string_new(NULL);
    for(int i=0;entries[i];i++) {
      if(!*entries[i] || g_str_has_suffix(entries[i],"/libmutter-android-system-egl.so"))continue;
      if(clean->len)g_string_append_c(clean,':');g_string_append(clean,entries[i]);
    }
    if(clean->len)g_setenv("LD_PRELOAD",clean->str,TRUE);else g_unsetenv("LD_PRELOAD");
    g_string_free(clean,TRUE);
  }
  a->input_source=g_unix_fd_add(fd,G_IO_IN|G_IO_HUP|G_IO_ERR,input_ready,a);return TRUE;
}
static ClutterBackend *backend_clutter(MetaBackend *backend,ClutterContext *context) {
  AndroidClutter *c=g_object_new(android_clutter_get_type(),"context",context,NULL);c->backend=backend;return CLUTTER_BACKEND(c);
}
static ClutterSeat *backend_seat(MetaBackend *backend,GError **error) {
  AndroidSeat *seat=g_object_new(android_seat_get_type(),"context",meta_backend_get_clutter_context(backend),"name","android",NULL);
  MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  seat->pos=GRAPHENE_POINT_INIT(a->width/2.f,a->height/2.f);
  return CLUTTER_SEAT(seat);
}
static MetaColorManager *backend_color(MetaBackend *backend) {
  return g_object_new(META_TYPE_COLOR_MANAGER,"backend",backend,NULL);
}
static MetaLogicalMonitor *backend_current_monitor(MetaBackend *backend) {
  AndroidSeat *s=(AndroidSeat *)meta_backend_get_default_seat(backend);
  return meta_monitor_manager_get_logical_monitor_at(meta_backend_get_monitor_manager(backend),s->pos.x,s->pos.y);
}
static MetaRenderer *backend_renderer(MetaBackend *backend,GError **error) {
  return g_object_new(android_renderer_get_type(),"backend",backend,NULL);
}
static MetaMonitorManager *backend_monitors(MetaBackend *backend,GError **error) {
  return g_object_new(META_TYPE_MONITOR_MANAGER_DUMMY,"backend",backend,NULL);
}
static MetaCursorRenderer *backend_cursor(MetaBackend *backend,ClutterInputDevice *device) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  if(!a->cursor)a->cursor=g_object_new(android_cursor_get_type(),"backend",backend,"device",device,NULL);
  return a->cursor;
}
static MetaInputSettings *backend_settings(MetaBackend *backend) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(backend);
  if(!a->settings)a->settings=g_object_new(META_TYPE_INPUT_SETTINGS_DUMMY,"backend",backend,NULL);
  return a->settings;
}
static void backend_update_stage(MetaBackend *backend) { meta_stage_rebuild_views(META_STAGE(meta_backend_get_stage(backend))); }
static MetaBackendCapabilities backend_capabilities(MetaBackend *backend) { return META_BACKEND_CAPABILITY_NONE; }
static AndroidSeat *backend_get_seat(MetaBackend *backend) { return (AndroidSeat *)meta_backend_get_default_seat(backend); }
static struct xkb_keymap *backend_keymap(MetaBackend *backend) { return backend_get_seat(backend)->xkb_keymap; }
static xkb_layout_index_t backend_layout(MetaBackend *backend) { return xkb_state_serialize_layout(backend_get_seat(backend)->xkb_state,XKB_STATE_LAYOUT_EFFECTIVE); }
static void backend_set_keymap(MetaBackend *backend,const char *layouts,const char *variants,const char *options,const char *model) {
  seat_set_xkb(backend_get_seat(backend),layouts,variants,options,model);g_signal_emit_by_name(backend,"keymap-changed");
}
static void backend_lock_layout(MetaBackend *backend,guint idx) {
  AndroidSeat *s=backend_get_seat(backend);xkb_state_update_mask(s->xkb_state,0,0,0,0,0,idx);g_signal_emit_by_name(backend,"keymap-layout-group-changed",idx);
}
static void backend_pointer_constraint(MetaBackend *backend,MetaPointerConstraint *constraint) {
  if(constraint)g_warning("Android pointer constraints are not implemented");
}
static void backend_dispose(GObject *object) {
  MetaBackendAndroid *a=META_BACKEND_ANDROID(object);
  g_clear_handle_id(&a->input_source,g_source_remove);g_clear_object(&a->cursor);g_clear_object(&a->settings);
  G_OBJECT_CLASS(meta_backend_android_parent_class)->dispose(object);
  if(a->buffer || a->state){stopEventLoop();a->buffer=NULL;a->state=NULL;}
  g_clear_pointer(&a->input,g_byte_array_unref);
}
static void meta_backend_android_class_init(MetaBackendAndroidClass *klass) {
  G_OBJECT_CLASS(klass)->dispose=backend_dispose;
  MetaBackendClass *c=META_BACKEND_CLASS(klass);
  c->init_basic=backend_init_basic;c->init_render=backend_init_render;c->init_post=backend_init_post;
  c->create_clutter_backend=backend_clutter;c->create_default_seat=backend_seat;
  c->create_renderer=backend_renderer;c->create_monitor_manager=backend_monitors;
  c->create_color_manager=backend_color;c->get_current_logical_monitor=backend_current_monitor;
  c->get_cursor_renderer=backend_cursor;c->get_input_settings=backend_settings;
  c->update_stage=backend_update_stage;c->get_capabilities=backend_capabilities;
  c->get_keymap=backend_keymap;c->get_keymap_layout_group=backend_layout;
  c->set_keymap=backend_set_keymap;c->lock_layout_group=backend_lock_layout;
  c->set_pointer_constraint=backend_pointer_constraint;
}
static void meta_backend_android_init(MetaBackendAndroid *a) {
  a->width=1280;a->height=800;a->refresh=60;a->input=g_byte_array_new();
  const char *w=g_getenv("MUTTER_ANDROID_WIDTH"),*h=g_getenv("MUTTER_ANDROID_HEIGHT");
  if(w)a->width=CLAMP(atoi(w),320,8192);if(h)a->height=CLAMP(atoi(h),240,8192);
}
