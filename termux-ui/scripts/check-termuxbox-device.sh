#!/usr/bin/env bash
set -euo pipefail

activity="com.termux/com.termux.app.activities.TermuxBoxActivity"

adb get-state >/dev/null
cleanup() {
  status=$?
  adb shell am force-stop com.termux >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  exit "$status"
}
# Leaving this Activity resumed steals window focus from any Espresso run that
# follows, which surfaces as RootViewWithoutFocusException in an unrelated test.
trap cleanup EXIT
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell am force-stop com.termux >/dev/null 2>&1 || true
adb shell am start -W -n "$activity" --es termux_box_initial_section containers >/dev/null
containers="$(adb exec-out uiautomator dump /dev/tty)"
grep -q 'resource-id="com.termux:id/termux_box_toolbar".*enabled="true"' <<<"$containers" || {
  echo "FAIL: TermuxBox toolbar is unavailable" >&2
  exit 1
}
grep -q 'resource-id="com.termux:id/termux_box_container_scroll".*enabled="true"' <<<"$containers" || {
  echo "FAIL: TermuxBox container section is unavailable" >&2
  exit 1
}

# TermuxBoxActivity is singleTask and has no onNewIntent, so EXTRA_INITIAL_SECTION
# is ignored when the Activity is already running. Restart cold to switch sections.
adb shell am force-stop com.termux >/dev/null 2>&1 || true
adb shell am start -W -n "$activity" --es termux_box_initial_section packages >/dev/null
packages="$(adb exec-out uiautomator dump /dev/tty)"
grep -Eq 'text="(Packages|软件包)".*enabled="true"' <<<"$packages" || {
  echo "FAIL: TermuxBox packages section is unavailable" >&2
  exit 1
}

stack="$(adb shell dumpsys activity activities)"
# API <=34 prints "mResumedActivity:"; API 35 renamed it to "ResumedActivity:" /
# "topResumedActivity=". Match any of them so the check is not silently version-bound.
grep -Eq '(m|top)?ResumedActivity[:=].*TermuxBoxActivity' <<<"$stack" || {
  echo "FAIL: TermuxBoxActivity is not resumed" >&2
  exit 1
}

echo "TermuxBox containers and packages device check passed."
