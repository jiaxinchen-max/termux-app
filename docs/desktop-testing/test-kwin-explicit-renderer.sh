#!/usr/bin/env bash
set -eu
helper="${1:?Usage: test-kwin-explicit-renderer.sh /path/to/android-rendering-env.sh}"
fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT
mkdir -p "$fixtures/bin" "$fixtures/share/vulkan/icd.d" "$fixtures/lib"
touch "$fixtures/share/vulkan/icd.d/freedreno_icd.aarch64.json" "$fixtures/share/vulkan/icd.d/wrapper_icd.aarch64.json" "$fixtures/lib/libvulkan_wrapper.so" "$fixtures/libvulkan.so"
cat > "$fixtures/bin/eglinfo" <<'EOF'
#!/bin/sh
printf '%s\n' "${TEST_PROBE_OUTPUT:-renderer: zink (Turnip Adreno)}"
exit "${TEST_PROBE_STATUS:-0}"
EOF
chmod +x "$fixtures/bin/eglinfo"
export PREFIX="$fixtures" PATH="$fixtures/bin:$PATH"
export KWIN_ANDROID_SYSTEM_VULKAN_LIBRARY="$fixtures/libvulkan.so"
export KWIN_ANDROID_WRAPPER_ICD="$fixtures/share/vulkan/icd.d/wrapper_icd.aarch64.json"
source "$helper"
checks=0
check() { "$@"; checks=$((checks+1)); }
select_mode() { ( unset VK_DRIVER_FILES VK_ICD_FILENAMES; export KWIN_ANDROID_GL_MODE="$1"; kwin_android_select_rendering; [ "$KWIN_ANDROID_GL_MODE" = "$2" ]; [ "${MESA_LOADER_DRIVER_OVERRIDE:-}" = "$3" ]; ); }
reject_mode() { ( unset VK_DRIVER_FILES VK_ICD_FILENAMES; export KWIN_ANDROID_GL_MODE="$1"; if kwin_android_select_rendering; then echo "unexpected success: $1" >&2; exit 1; fi; ); }
check select_mode system system ''
check select_mode llvmpipe llvmpipe llvmpipe
check select_mode zink zink zink
check select_mode wrapper zink zink
check reject_mode auto
check reject_mode ''
check reject_mode invalid
# Turnip failure cannot continue with wrapper or system, although both files exist.
export TEST_PROBE_STATUS=1
check reject_mode zink
check reject_mode wrapper
unset TEST_PROBE_STATUS
export TEST_PROBE_OUTPUT='renderer: zink (llvmpipe)'
check reject_mode zink
unset TEST_PROBE_OUTPUT
rm "$fixtures/share/vulkan/icd.d/freedreno_icd.aarch64.json"
check reject_mode zink
# Native Qt clients have a separate explicit integration from the compositor.
mkdir -p "$fixtures/lib/qt6/termux-system-egl" "$fixtures/lib/qt6/plugins/wayland-graphics-integration-client" "$fixtures/share/glvnd/egl_vendor.d"
touch "$fixtures/lib/qt6/termux-system-egl/libEGL.so.1" "$fixtures/lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so" "$fixtures/share/glvnd/egl_vendor.d/50_mesa.json"
client_system() { (
    export KWIN_ANDROID_GL_MODE=system QT_QUICK_BACKEND=software LD_LIBRARY_PATH=/stale
    export VK_DRIVER_FILES=/stale MESA_GL_VERSION_OVERRIDE=4.6 LP_NUM_THREADS=99
    kwin_android_configure_qt_clients
    [ "$LD_LIBRARY_PATH" = "$PREFIX/lib/qt6/termux-system-egl" ] && [ "$QT_WAYLAND_CLIENT_BUFFER_INTEGRATION" = termux-ahardware-buffer ] && [ "$QT_OPENGL" = es2 ] && [ "$QT_QUICK_BACKEND" = '' ] && [ "$QSG_RHI_BACKEND" = opengl ] && [ "$TERMUX_ANDROID_ZINK" = 0 ] && [ "$VK_DRIVER_FILES" = '' ] && [ "$MESA_GL_VERSION_OVERRIDE" = '' ] && [ "$LP_NUM_THREADS" = '' ]
); }
client_mesa() { (
    export KWIN_ANDROID_GL_MODE="$1" LIBGL_ALWAYS_SOFTWARE=1
    kwin_android_configure_qt_clients
    [ "$MESA_LOADER_DRIVER_OVERRIDE" = "$1" ] && [ "$GALLIUM_DRIVER" = "$1" ] && [ "$LIBGL_ALWAYS_SOFTWARE" = "$3" ] && [ "$EGL_PLATFORM" = wayland ] && [ "$LD_LIBRARY_PATH" = "$PREFIX/lib" ] && [ "$QT_WAYLAND_CLIENT_BUFFER_INTEGRATION" = wayland-egl ] && [ "$QT_OPENGL" = desktop ] && [ "$QT_QUICK_BACKEND" = "$2" ] && [ "$QSG_RHI_BACKEND" = opengl ]
); }
client_missing() { (
    export KWIN_ANDROID_GL_MODE=system
    if kwin_android_configure_qt_clients; then exit 1; fi
); }
client_activation_clear() { (
    export KWIN_ANDROID_GL_MODE=system MESA_GL_VERSION_OVERRIDE=4.6 VK_DRIVER_FILES=/stale QT_QUICK_BACKEND=software
    kwin_android_configure_qt_clients
    for variable in "${KWIN_ANDROID_QT_CLIENT_ENV_VARS[@]}"; do [ "${!variable+x}" = x ] || return 1; done
    for variable in VK_DRIVER_FILES VK_ICD_FILENAMES WRAPPER_VULKAN_PATH MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE LP_NUM_THREADS QT_QUICK_BACKEND; do
        [ "${!variable}" = '' ] || return 1
    done
); }
check client_activation_clear
check client_system
check client_mesa zink '' 0
check client_mesa llvmpipe software 1
rm "$fixtures/lib/qt6/termux-system-egl/libEGL.so.1"
check client_missing

printf 'PASS: %s strict renderer checks\n' "$checks"
