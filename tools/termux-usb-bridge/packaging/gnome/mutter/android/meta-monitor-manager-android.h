/* -*- mode: C; c-file-style: "gnu"; indent-tabs-mode: nil; -*- */

/*
 * Copyright (C) 2001 Havoc Pennington
 * Copyright (C) 2003 Rob Adams
 * Copyright (C) 2004-2006 Elijah Newren
 * Copyright (C) 2013 Red Hat Inc.
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
 */

#pragma once

#include "backends/meta-crtc.h"
#include "backends/meta-gpu.h"
#include "backends/meta-monitor-manager-private.h"
#include "backends/meta-output.h"

#define META_TYPE_OUTPUT_ANDROID (meta_output_android_get_type ())
G_DECLARE_FINAL_TYPE (MetaOutputAndroid, meta_output_android,
                      META, OUTPUT_ANDROID,
                      MetaOutput)

#define META_TYPE_CRTC_ANDROID (meta_crtc_android_get_type ())
G_DECLARE_FINAL_TYPE (MetaCrtcAndroid, meta_crtc_android,
                      META, CRTC_ANDROID,
                      MetaCrtc)

#define META_TYPE_MONITOR_MANAGER_ANDROID (meta_monitor_manager_android_get_type ())
G_DECLARE_FINAL_TYPE (MetaMonitorManagerAndroid, meta_monitor_manager_android,
                      META, MONITOR_MANAGER_ANDROID, MetaMonitorManager)

#define META_TYPE_GPU_ANDROID (meta_gpu_android_get_type ())
G_DECLARE_FINAL_TYPE (MetaGpuAndroid, meta_gpu_android, META, GPU_ANDROID, MetaGpu)
