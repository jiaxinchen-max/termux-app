/* SPDX-License-Identifier: GPL-2.0-or-later */
#pragma once
#include "wayland/meta-wayland-buffer.h"
void meta_wayland_android_buffer_init (MetaWaylandCompositor *compositor);
gboolean meta_wayland_android_buffer_attach (MetaWaylandBuffer *buffer,
                                             MetaMultiTexture **texture,
                                             GError **error);
CoglSnippet *meta_wayland_android_buffer_create_snippet (MetaWaylandBuffer *buffer);
void meta_wayland_android_buffer_finish (MetaWaylandBuffer *buffer);
void meta_wayland_android_buffer_clear (MetaWaylandBuffer *buffer);
