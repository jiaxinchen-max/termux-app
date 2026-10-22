#!/usr/bin/env bash
# Prepare a device/AVD so the Games instrumentation suite can run deterministically.
#
# Without this, ActivityScenario failures look like product bugs but are not:
#   - Ungranted storage permissions put GrantPermissionsActivity on top, so the
#     Activity under test never reaches RESUMED ("last lifecycle transition =
#     PAUSED/STARTED"). Seen on API 35; API 30 images often pre-grant these.
#   - A leftover foreground Activity steals window focus, which Espresso reports
#     as RootViewWithoutFocusException in an unrelated test.
#   - Animations left enabled make recreate() time out.
set -euo pipefail

adb get-state >/dev/null

adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell am force-stop com.termux >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$scale" 0 >/dev/null 2>&1 || true
done

# The test package only exists while a run is in flight, so granting to it here
# would fail; the app package is what backs the storage-dependent screens.
for permission in \
  READ_EXTERNAL_STORAGE \
  WRITE_EXTERNAL_STORAGE \
  READ_MEDIA_IMAGES \
  READ_MEDIA_AUDIO \
  READ_MEDIA_VIDEO \
  READ_MEDIA_VISUAL_USER_SELECTED
do
  adb shell pm grant com.termux "android.permission.$permission" >/dev/null 2>&1 || true
done

echo "api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "Device prepared for Games instrumentation."
