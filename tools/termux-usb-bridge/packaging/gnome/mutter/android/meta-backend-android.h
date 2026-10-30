/* SPDX-License-Identifier: GPL-2.0-or-later */
#pragma once
#include "backends/meta-backend-private.h"
#define META_TYPE_BACKEND_ANDROID (meta_backend_android_get_type ())
G_DECLARE_FINAL_TYPE (MetaBackendAndroid, meta_backend_android, META, BACKEND_ANDROID, MetaBackend)
