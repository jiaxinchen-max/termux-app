/* -*- mode: C; c-file-style: "gnu"; indent-tabs-mode: nil; -*- */

/*
 * Copyright (C) 2016 Red Hat
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 * Written by:
 *     Jonas Ådahl <jadahl@gmail.com>
 */

/**
 * MetaClutterBackendAndroid:
 *
 * A native backend which renders using EGL.
 *
 * MetaClutterBackendAndroid is the #ClutterBackend which is used by the native
 * (as opposed to the X) backend. It creates a stage with #MetaStageAndroid and
 * renders using the #CoglRenderer.
 *
 * Note that MetaClutterBackendAndroid is something different than a
 * #MetaBackendAndroid. The former is a #ClutterBackend implementation, while
 * the latter is a #MetaBackend implementation.
 */

#include "config.h"

#include "backends/android/meta-clutter-backend-android.h"

#include <glib-object.h>

#include "backends/meta-backend-private.h"
#include "backends/meta-cursor-xcursor.h"
#include "backends/meta-renderer.h"
#include "backends/android/meta-backend-android.h"
#include "backends/android/meta-sprite-android.h"
#include "clutter/clutter.h"
#include "core/bell.h"
#include "meta/meta-backend.h"

struct _MetaClutterBackendAndroid
{
  ClutterBackend parent;

  MetaBackend *backend;

  gboolean disposed;

  GHashTable *touch_sprites;
  GHashTable *stylus_sprites;
  ClutterSprite *pointer_sprite;
  ClutterKeyFocus *key_focus;
};

G_DEFINE_TYPE (MetaClutterBackendAndroid, meta_clutter_backend_android,
               CLUTTER_TYPE_BACKEND)

static CoglRenderer *
meta_clutter_backend_android_get_renderer (ClutterBackend  *clutter_backend,
                                          GError         **error)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);
  MetaBackend *backend = clutter_backend_android->backend;
  MetaRenderer *renderer = meta_backend_get_renderer (backend);

  return meta_renderer_create_cogl_renderer (renderer);
}

static ClutterStageWindow *
meta_clutter_backend_android_create_stage (ClutterBackend  *clutter_backend,
                                          ClutterStage    *wrapper,
                                          GError         **error)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);

  return meta_backend_android_create_stage_window (clutter_backend_android->backend, wrapper);
}

static ClutterSeat *
meta_clutter_backend_android_get_default_seat (ClutterBackend *clutter_backend)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);
  MetaBackend *backend = clutter_backend_android->backend;

  return meta_backend_get_default_seat (backend);
}

static gboolean
meta_clutter_backend_android_is_display_server (ClutterBackend *clutter_backend)
{
  return TRUE;
}

static ClutterSprite *
create_sprite (ClutterBackend     *clutter_backend,
               ClutterStage       *stage,
               const ClutterEvent *for_event)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);
  MetaBackend *backend = clutter_backend_android->backend;
  ClutterInputDevice *device, *sprite_device = NULL;
  ClutterEventSequence *sequence = NULL;
  ClutterSpriteRole role;

  device = clutter_event_get_source_device (for_event);

  if (clutter_event_get_event_sequence (for_event))
    role = CLUTTER_SPRITE_ROLE_TOUCHPOINT;
  else if (clutter_input_device_get_capabilities (device) ==
           CLUTTER_INPUT_CAPABILITY_TABLET_TOOL)
    role = CLUTTER_SPRITE_ROLE_TABLET;
  else
    role = CLUTTER_SPRITE_ROLE_POINTER;

  if (role == CLUTTER_SPRITE_ROLE_TABLET)
    sprite_device = device;
  else if (role == CLUTTER_SPRITE_ROLE_TOUCHPOINT)
    sequence = clutter_event_get_event_sequence (for_event);

  return g_object_new (META_TYPE_SPRITE_ANDROID,
                       "backend", backend,
                       "stage", stage,
                       "sprite-device", sprite_device,
                       "sequence", sequence,
                       "role", role,
                       NULL);
}

static ClutterSprite *
ensure_sprite (ClutterBackend     *clutter_backend,
               ClutterStage       *stage,
               const ClutterEvent *for_event,
               GHashTable         *ht,
               gpointer            key)
{
  ClutterSprite *sprite;

  sprite = g_hash_table_lookup (ht, key);
  if (!sprite)
    {
      sprite = create_sprite (clutter_backend, stage, for_event);
      g_hash_table_insert (ht, key, sprite);
    }

  return sprite;
}

static void
ensure_pointer_sprite (ClutterBackend *clutter_backend)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);

  if (!clutter_backend_android->pointer_sprite)
    {
      MetaBackend *backend = clutter_backend_android->backend;
      ClutterStage *stage = CLUTTER_STAGE (meta_backend_get_stage (backend));

      clutter_backend_android->pointer_sprite =
        g_object_new (META_TYPE_SPRITE_ANDROID,
                      "backend", backend,
                      "stage", stage,
                      "role", CLUTTER_SPRITE_ROLE_POINTER,
                      NULL);
    }
}

static ClutterSprite *
meta_clutter_backend_android_get_sprite (ClutterBackend     *clutter_backend,
                                        ClutterStage       *stage,
                                        const ClutterEvent *for_event)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);
  ClutterInputDevice *source_device;
  ClutterEventSequence *event_sequence;
  ClutterInputDeviceType device_type;

  if (clutter_backend_android->disposed)
    return NULL;

  event_sequence = clutter_event_get_event_sequence (for_event);
  if (event_sequence)
    {
      return ensure_sprite (clutter_backend, stage, for_event,
                            clutter_backend_android->touch_sprites,
                            event_sequence);
    }

  source_device = clutter_event_get_source_device (for_event);
  device_type = clutter_input_device_get_device_type (source_device);

  if (device_type == CLUTTER_TABLET_DEVICE ||
      device_type == CLUTTER_PEN_DEVICE ||
      device_type == CLUTTER_ERASER_DEVICE)
    {
      return ensure_sprite (clutter_backend, stage, for_event,
                            clutter_backend_android->stylus_sprites,
                            source_device);
    }

  if (device_type == CLUTTER_POINTER_DEVICE ||
      device_type == CLUTTER_TOUCHPAD_DEVICE)
    {
      if (!clutter_backend_android->pointer_sprite)
        {
          clutter_backend_android->pointer_sprite =
            create_sprite (clutter_backend, stage, for_event);
        }

      return clutter_backend_android->pointer_sprite;
    }

  return NULL;
}

static ClutterSprite *
meta_clutter_backend_android_get_pointer_sprite (ClutterBackend *clutter_backend,
                                                ClutterStage   *stage)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);

  ensure_pointer_sprite (clutter_backend);

  return clutter_backend_android->pointer_sprite;
}

static void
meta_clutter_backend_android_destroy_sprite (ClutterBackend *clutter_backend,
                                            ClutterSprite  *sprite)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);

  meta_backend_android_remove_cursor_renderer (clutter_backend_android->backend, sprite);

  g_object_ref (sprite);
  g_hash_table_remove (clutter_backend_android->touch_sprites,
                       clutter_sprite_get_sequence (sprite));
  g_hash_table_remove (clutter_backend_android->stylus_sprites,
                       clutter_sprite_get_sprite_device (sprite));

  if (clutter_backend_android->pointer_sprite == sprite)
    g_clear_object (&clutter_backend_android->pointer_sprite);
  g_object_unref (sprite);
}

static gboolean
meta_clutter_backend_android_foreach_sprite (ClutterBackend               *clutter_backend,
                                            ClutterStage                 *stage,
                                            ClutterStageInputForeachFunc  func,
                                            gpointer                      user_data)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);
  GHashTableIter iter;
  ClutterSprite *sprite;

  if (clutter_backend_android->pointer_sprite)
    {
      if (!func (stage, clutter_backend_android->pointer_sprite, user_data))
        return FALSE;
    }

  g_hash_table_iter_init (&iter, clutter_backend_android->stylus_sprites);
  while (g_hash_table_iter_next (&iter, NULL, (gpointer*) &sprite))
    {
      if (!func (stage, sprite, user_data))
        return FALSE;
    }

  g_hash_table_iter_init (&iter, clutter_backend_android->touch_sprites);
  while (g_hash_table_iter_next (&iter, NULL, (gpointer*) &sprite))
    {
      if (!func (stage, sprite, user_data))
        return FALSE;
    }

  return TRUE;
}

static ClutterKeyFocus *
meta_clutter_backend_android_get_key_focus (ClutterBackend *clutter_backend,
                                           ClutterStage   *stage)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);

  if (clutter_backend_android->disposed)
    return NULL;

  if (!clutter_backend_android->key_focus)
    {
      clutter_backend_android->key_focus =
        g_object_new (CLUTTER_TYPE_KEY_FOCUS,
                      "stage", stage,
                      NULL);
    }

  return clutter_backend_android->key_focus;
}

static ClutterCursor *
meta_clutter_backend_android_get_cursor (ClutterBackend    *clutter_backend,
                                        ClutterCursorType  cursor_type)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (clutter_backend);

  return meta_backend_get_cursor (clutter_backend_android->backend, cursor_type);
}

static void
meta_clutter_backend_android_constructed (GObject *object)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (object);

  G_OBJECT_CLASS (meta_clutter_backend_android_parent_class)->constructed (object);

  clutter_backend_android->touch_sprites =
    g_hash_table_new_full (NULL, NULL, NULL, g_object_unref);
  clutter_backend_android->stylus_sprites =
    g_hash_table_new_full (NULL, NULL, NULL, g_object_unref);
}

static void
meta_clutter_backend_android_dispose (GObject *object)
{
  MetaClutterBackendAndroid *clutter_backend_android =
    META_CLUTTER_BACKEND_ANDROID (object);

  clutter_backend_android->disposed = TRUE;

  g_clear_object (&clutter_backend_android->pointer_sprite);
  g_clear_object (&clutter_backend_android->key_focus);
  g_clear_pointer (&clutter_backend_android->touch_sprites, g_hash_table_unref);
  g_clear_pointer (&clutter_backend_android->stylus_sprites, g_hash_table_unref);

  G_OBJECT_CLASS (meta_clutter_backend_android_parent_class)->dispose (object);
}

static void
meta_clutter_backend_android_init (MetaClutterBackendAndroid *clutter_backend_android)
{
}

static void
meta_clutter_backend_android_class_init (MetaClutterBackendAndroidClass *klass)
{
  GObjectClass *object_class = G_OBJECT_CLASS (klass);
  ClutterBackendClass *clutter_backend_class = CLUTTER_BACKEND_CLASS (klass);

  object_class->constructed = meta_clutter_backend_android_constructed;
  object_class->dispose = meta_clutter_backend_android_dispose;

  clutter_backend_class->get_renderer = meta_clutter_backend_android_get_renderer;
  clutter_backend_class->create_stage = meta_clutter_backend_android_create_stage;
  clutter_backend_class->get_default_seat = meta_clutter_backend_android_get_default_seat;
  clutter_backend_class->is_display_server = meta_clutter_backend_android_is_display_server;
  clutter_backend_class->get_sprite = meta_clutter_backend_android_get_sprite;
  clutter_backend_class->get_pointer_sprite = meta_clutter_backend_android_get_pointer_sprite;
  clutter_backend_class->destroy_sprite = meta_clutter_backend_android_destroy_sprite;
  clutter_backend_class->foreach_sprite = meta_clutter_backend_android_foreach_sprite;
  clutter_backend_class->get_key_focus = meta_clutter_backend_android_get_key_focus;
  clutter_backend_class->get_cursor = meta_clutter_backend_android_get_cursor;
}

MetaClutterBackendAndroid *
meta_clutter_backend_android_new (MetaBackend    *backend,
                                 ClutterContext *context)
{
  MetaClutterBackendAndroid *clutter_backend_android;

  clutter_backend_android = g_object_new (META_TYPE_CLUTTER_BACKEND_ANDROID,
                                         "context", context,
                                         NULL);
  clutter_backend_android->backend = backend;

  return clutter_backend_android;
}
