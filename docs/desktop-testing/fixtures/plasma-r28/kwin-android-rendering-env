#!/data/data/com.termux/files/usr/bin/bash

# Select a renderer for the native Termux/Bionic KWin build. This file is
# sourced by start-plasma and debug-kwin; it intentionally does not enable the
# glibc Vortek broker path.
#
# This is the single source of truth for renderer selection. It probes the
# device, exports the Mesa/libepoxy/Vulkan environment and resolves
# KWIN_ANDROID_GL_MODE to exactly one of: system, zink, llvmpipe.
# kwin_wayland only consumes that value; it does not probe or override it.

kwin_android_use_system_gles()
{
    export KWIN_ANDROID_GL_MODE=system
    export KWIN_COMPOSE=O2ES
    export KWIN_ANDROID_AHB_IMPORT="${KWIN_ANDROID_AHB_IMPORT:-native}"
    export KWIN_ANDROID_CLIENT_BUFFER_IMPORT="${KWIN_ANDROID_CLIENT_BUFFER_IMPORT:-native}"
    export TERMUX_RENDER_AHB_RECEIVE="${TERMUX_RENDER_AHB_RECEIVE:-native}"
    export KWIN_ANDROID_LINUX_DMABUF="${KWIN_ANDROID_LINUX_DMABUF:-off}"
    unset TERMUX_ANDROID_ZINK TERMUX_VULKAN_BROKER_SOCKET
    unset MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER LIBGL_ALWAYS_SOFTWARE
    unset VK_DRIVER_FILES VK_ICD_FILENAMES WRAPPER_VULKAN_PATH MESA_VK_WSI_DEBUG
    unset MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE
    unset LP_NUM_THREADS
}

kwin_android_use_zink()
{
    export KWIN_ANDROID_GL_MODE=zink
    export KWIN_COMPOSE=O2
    export KWIN_ANDROID_AHB_IMPORT="${KWIN_ANDROID_AHB_IMPORT:-dmabuf}"
    export KWIN_ANDROID_CLIENT_BUFFER_IMPORT="${KWIN_ANDROID_CLIENT_BUFFER_IMPORT:-dmabuf}"
    export TERMUX_RENDER_AHB_RECEIVE="${TERMUX_RENDER_AHB_RECEIVE:-dmabuf}"
    export KWIN_ANDROID_LINUX_DMABUF="${KWIN_ANDROID_LINUX_DMABUF:-on}"
    export TERMUX_ANDROID_ZINK=1
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export GALLIUM_DRIVER=zink
    unset LIBGL_ALWAYS_SOFTWARE LP_NUM_THREADS
}

kwin_android_use_llvmpipe()
{
    export KWIN_ANDROID_GL_MODE=llvmpipe
    export KWIN_COMPOSE=O2
    export KWIN_ANDROID_AHB_IMPORT="${KWIN_ANDROID_AHB_IMPORT:-off}"
    export KWIN_ANDROID_CLIENT_BUFFER_IMPORT="${KWIN_ANDROID_CLIENT_BUFFER_IMPORT:-dmabuf}"
    export TERMUX_RENDER_AHB_RECEIVE="${TERMUX_RENDER_AHB_RECEIVE:-native}"
    export KWIN_ANDROID_LINUX_DMABUF="${KWIN_ANDROID_LINUX_DMABUF:-off}"
    export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
    export GALLIUM_DRIVER=llvmpipe
    export LIBGL_ALWAYS_SOFTWARE=1
    export LP_NUM_THREADS="${LP_NUM_THREADS:-4}"
    # llvmpipe lives in Termux Mesa, so libepoxy must load Termux Mesa GL/EGL.
    # TERMUX_ANDROID_ZINK is the libepoxy "use Termux Mesa" switch (not
    # Zink-specific despite the name); without it libepoxy loads the Android
    # system GLES and the llvmpipe override is silently ignored.
    export TERMUX_ANDROID_ZINK=1
    unset TERMUX_VULKAN_BROKER_SOCKET
    unset VK_DRIVER_FILES VK_ICD_FILENAMES WRAPPER_VULKAN_PATH MESA_VK_WSI_DEBUG
}

kwin_android_probe_zink_icd()
{
    local icd="$1"
    local wrapper_path="${2:-}"
    local expected_renderer="${3:-}"
    local output
    local -a probe_command

    if command -v eglinfo >/dev/null 2>&1; then
        probe_command=(eglinfo -p surfaceless -B)
        if command -v timeout >/dev/null 2>&1; then
            probe_command=(timeout "${KWIN_ANDROID_PROBE_TIMEOUT:-10}" "${probe_command[@]}")
        fi
        output="$(
            env -u LIBGL_ALWAYS_SOFTWARE \
                EGL_PLATFORM=surfaceless \
                MESA_LOADER_DRIVER_OVERRIDE=zink \
                GALLIUM_DRIVER=zink \
                TERMUX_ANDROID_ZINK=1 \
                VK_DRIVER_FILES="$icd" \
                VK_ICD_FILENAMES="$icd" \
                WRAPPER_VULKAN_PATH="$wrapper_path" \
                "${probe_command[@]}" 2>&1
        )" || return 1
        printf '%s\n' "$output" | grep -q 'renderer: zink' || return 1
        if printf '%s\n' "$output" | grep -Eqi 'llvmpipe|lavapipe|software rasterizer'; then
            return 1
        fi
        if [ -n "$expected_renderer" ]; then
            printf '%s\n' "$output" | grep -Eqi "$expected_renderer" || return 1
        fi
        return 0
    fi

    if command -v vulkaninfo >/dev/null 2>&1; then
        probe_command=(vulkaninfo --summary)
        if command -v timeout >/dev/null 2>&1; then
            probe_command=(timeout "${KWIN_ANDROID_PROBE_TIMEOUT:-10}" "${probe_command[@]}")
        fi
        output="$(
            VK_DRIVER_FILES="$icd" \
            VK_ICD_FILENAMES="$icd" \
            WRAPPER_VULKAN_PATH="$wrapper_path" \
                "${probe_command[@]}" 2>&1
        )" || return 1
        printf '%s\n' "$output" | grep -Eq 'PHYSICAL_DEVICE_TYPE_(INTEGRATED|DISCRETE|VIRTUAL)_GPU' || return 1
        if [ -n "$expected_renderer" ]; then
            printf '%s\n' "$output" | grep -Eqi "$expected_renderer" || return 1
        fi
        return 0
    fi

    echo "Cannot validate requested Zink mode: eglinfo or vulkaninfo is required" >&2
    return 1
}

kwin_android_configure_icd_zink()
{
    local icd="$1"
    local description="$2"

    kwin_android_use_zink
    export VK_DRIVER_FILES="$icd"
    export VK_ICD_FILENAMES="$icd"
    unset WRAPPER_VULKAN_PATH MESA_VK_WSI_DEBUG TERMUX_VULKAN_BROKER_SOCKET
    ACCELERATION_MODE="$description"
    export ACCELERATION_MODE
}

kwin_android_configure_wrapper_zink()
{
    local wrapper_icd="$1"
    local android_vulkan="$2"

    kwin_android_use_zink
    export VK_DRIVER_FILES="$wrapper_icd"
    export VK_ICD_FILENAMES="$wrapper_icd"
    export WRAPPER_VULKAN_PATH="$android_vulkan"
    export MESA_VK_WSI_DEBUG="${MESA_VK_WSI_DEBUG:-blit}"
    unset TERMUX_VULKAN_BROKER_SOCKET
    ACCELERATION_MODE="Android Vulkan wrapper (Zink)"
    export ACCELERATION_MODE
}

kwin_android_select_rendering()
{
    local arch mode wrapper_icd wrapper_lib android_vulkan requested_icd

    arch="$(uname -m)"
    mode="${KWIN_ANDROID_GL_MODE:-}"
    mode="$(printf '%s' "$mode" | tr '[:upper:]' '[:lower:]')"
    wrapper_icd="${KWIN_ANDROID_WRAPPER_ICD:-$PREFIX/share/vulkan/icd.d/wrapper_icd.${arch}.json}"
    wrapper_lib="${KWIN_ANDROID_WRAPPER_LIBRARY:-$PREFIX/lib/libvulkan_wrapper.so}"
    if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
        android_vulkan="${KWIN_ANDROID_SYSTEM_VULKAN_LIBRARY:-/system/lib64/libvulkan.so}"
    else
        android_vulkan="${KWIN_ANDROID_SYSTEM_VULKAN_LIBRARY:-/system/lib/libvulkan.so}"
    fi

    requested_icd="${VK_DRIVER_FILES:-${VK_ICD_FILENAMES:-}}"
    case "$mode" in
        system)
            kwin_android_use_system_gles
            ACCELERATION_MODE="Android system EGL/GLES (explicit)"
            export ACCELERATION_MODE
            return 0
            ;;
        llvmpipe)
            kwin_android_use_llvmpipe
            ACCELERATION_MODE="Mesa Software (llvmpipe, explicit)"
            export ACCELERATION_MODE
            return 0
            ;;
        zink|turnip|kgsl)
            if [ -z "$requested_icd" ]; then
                requested_icd="${KWIN_ANDROID_TURNIP_ICD:-$PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json}"
            fi
            case "$requested_icd" in
                *vortek*|*/glibc/*)
                    echo "Unsupported non-Bionic Vulkan ICD: $requested_icd" >&2
                    return 1
                    ;;
            esac
            if [ ! -r "$requested_icd" ]; then
                echo "Requested Zink Vulkan ICD is unavailable: $requested_icd" >&2
                return 1
            fi
            if ! kwin_android_probe_zink_icd "$requested_icd" "" ''; then
                echo "Requested Zink Vulkan ICD failed its hardware probe: $requested_icd" >&2
                return 1
            fi
            kwin_android_configure_icd_zink "$requested_icd" "Explicit Vulkan ICD (Zink): $requested_icd"
            return 0
            ;;
        wrapper)
            if [ ! -r "$wrapper_icd" ] || [ ! -r "$wrapper_lib" ] || [ ! -r "$android_vulkan" ]; then
                echo "Requested Android Vulkan wrapper is unavailable" >&2
                return 1
            fi
            if ! kwin_android_probe_zink_icd "$wrapper_icd" "$android_vulkan" ''; then
                echo "Requested Android Vulkan wrapper failed its Zink hardware probe" >&2
                return 1
            fi
            kwin_android_configure_wrapper_zink "$wrapper_icd" "$android_vulkan"
            return 0
            ;;
        *)
            echo "KWIN_ANDROID_GL_MODE must explicitly specify system, zink, turnip, kgsl, wrapper or llvmpipe; got: $mode" >&2
            return 2
            ;;
    esac
}

# Configure native Qt clients only after the compositor has started. Qt's
# default wayland-egl integration otherwise selects Mesa even in system mode.
kwin_android_configure_qt_clients()
{
    case "$KWIN_ANDROID_GL_MODE" in
        system)
            local egl_dir="$PREFIX/lib/qt6/termux-system-egl"
            local plugin="$PREFIX/lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so"
            if [ ! -r "$egl_dir/libEGL.so.1" ] || [ ! -r "$plugin" ]; then
                echo "Requested native Qt system EGL/AHardwareBuffer client integration is unavailable" >&2
                return 1
            fi
            export LD_LIBRARY_PATH="$egl_dir"
            export QT_WAYLAND_CLIENT_BUFFER_INTEGRATION=termux-ahardware-buffer
            export QT_OPENGL=es2
            export QSG_RHI_BACKEND=opengl
            export QT_QUICK_BACKEND=
            export MESA_LOADER_DRIVER_OVERRIDE= GALLIUM_DRIVER= LIBGL_ALWAYS_SOFTWARE=0
            export TERMUX_ANDROID_ZINK=0 __EGL_VENDOR_LIBRARY_FILENAMES= EGL_PLATFORM=
            unset VK_DRIVER_FILES VK_ICD_FILENAMES WRAPPER_VULKAN_PATH MESA_VK_WSI_DEBUG TERMUX_VULKAN_BROKER_SOCKET
            unset MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE LP_NUM_THREADS
            ;;
        zink|llvmpipe)
            local mesa_vendor="$PREFIX/share/glvnd/egl_vendor.d/50_mesa.json"
            if [ ! -r "$mesa_vendor" ]; then
                echo "Requested native Qt Mesa EGL vendor is unavailable: $mesa_vendor" >&2
                return 1
            fi
            export LD_LIBRARY_PATH="$PREFIX/lib"
            export __EGL_VENDOR_LIBRARY_FILENAMES="$mesa_vendor"
            export QT_WAYLAND_CLIENT_BUFFER_INTEGRATION=wayland-egl
            export QT_OPENGL=desktop
            export EGL_PLATFORM=wayland
            export TERMUX_ANDROID_ZINK=1
            export MESA_LOADER_DRIVER_OVERRIDE="$KWIN_ANDROID_GL_MODE" GALLIUM_DRIVER="$KWIN_ANDROID_GL_MODE"
            export QSG_RHI_BACKEND=opengl
            if [ "$KWIN_ANDROID_GL_MODE" = llvmpipe ]; then
                export QT_QUICK_BACKEND=software LIBGL_ALWAYS_SOFTWARE=1
            else
                export QT_QUICK_BACKEND= LIBGL_ALWAYS_SOFTWARE=0
            fi
            ;;
        *)
            echo "Native Qt client mode is not explicit: $KWIN_ANDROID_GL_MODE" >&2
            return 2
            ;;
    esac
    export QT_QPA_PLATFORM=wayland QSG_RENDER_LOOP=basic __EGL_VENDOR_LIBRARY_DIRS=
    # D-Bus activation cannot remove a variable by importing an unset name.
    # Publish every owned key, using an explicit empty value to clear old modes.
    KWIN_ANDROID_QT_CLIENT_ENV_VARS=(KWIN_ANDROID_GL_MODE LD_LIBRARY_PATH QT_QPA_PLATFORM QT_OPENGL
        QT_WAYLAND_CLIENT_BUFFER_INTEGRATION QSG_RHI_BACKEND QT_QUICK_BACKEND QSG_RENDER_LOOP
        EGL_PLATFORM __EGL_VENDOR_LIBRARY_FILENAMES __EGL_VENDOR_LIBRARY_DIRS
        MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER LIBGL_ALWAYS_SOFTWARE TERMUX_ANDROID_ZINK
        VK_DRIVER_FILES VK_ICD_FILENAMES WRAPPER_VULKAN_PATH MESA_VK_WSI_DEBUG TERMUX_VULKAN_BROKER_SOCKET
        MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE LP_NUM_THREADS)
    local variable
    for variable in "${KWIN_ANDROID_QT_CLIENT_ENV_VARS[@]}"; do
        export "$variable=${!variable-}"
    done
    echo "Native Qt client mode: $KWIN_ANDROID_GL_MODE; integration=$QT_WAYLAND_CLIENT_BUFFER_INTEGRATION; RHI=$QSG_RHI_BACKEND; Quick=${QT_QUICK_BACKEND:-rhi}"
}
