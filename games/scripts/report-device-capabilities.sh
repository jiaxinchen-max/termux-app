#!/usr/bin/env bash
set -euo pipefail

adb get-state >/dev/null
model="$(adb shell getprop ro.product.model | tr -d '\r')"
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
inputs="$(adb shell getevent -lp | sed -n 's/^[[:space:]]*name:[[:space:]]*"\(.*\)"/\1/p')"

echo "model=$model"
echo "api=$api"
echo "abi=$abi"
echo "input_devices=$(printf '%s' "$inputs" | paste -sd ',' -)"
if grep -Eqi 'gamepad|joystick|controller|xbox|dualshock|dualsense' <<<"$inputs"; then
  echo "physical_controller_candidate=detected"
else
  echo "physical_controller_candidate=not_detected"
fi
