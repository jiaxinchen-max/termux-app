#!/usr/bin/env bash
set -euo pipefail

activity="com.termux/com.termux.app.TermuxActivity"

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
# Start cold: a LocalGamesActivity left over from an earlier check would otherwise
# stay foreground (TermuxActivity is singleTask) and the swipe would land in Games,
# where local_games_button does not exist.
adb shell am force-stop com.termux >/dev/null 2>&1 || true
# This check exercises the TERMINAL-mode drawer entry into Games. In GAMES mode
# TermuxActivity deliberately routes straight to LocalGamesActivity and finishes, so
# the drawer never exists. Both modes coexist; only TERMINAL is in scope here.
mode="$(adb shell run-as com.termux cat shared_prefs/termux_app_experience.xml 2>/dev/null |
  sed -n 's/.*name="mode">\([A-Z]*\)<.*/\1/p' | head -n 1 || true)"
if [[ "${mode:-TERMINAL}" != "TERMINAL" ]]; then
  echo "SKIP: app experience mode is ${mode}; the drawer entry only exists in TERMINAL mode" >&2
  exit 0
fi
adb shell am start -W -n "$activity" >/dev/null
size="$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)/\1 \2/p' | head -n 1)"
read -r width height <<<"$size"
[[ -n "${width:-}" && -n "${height:-}" ]] || {
  echo "FAIL: unable to resolve display size" >&2
  exit 1
}

adb shell input swipe 10 "$((height / 2))" "$((width * 3 / 4))" "$((height / 2))" 1200
# uiautomator can return an empty hierarchy if it dumps before the window settles
# after the cold start; retry briefly rather than misreport the entry as missing.
hierarchy=""
for _ in 1 2 3 4 5 6 7 8; do
  hierarchy="$(adb exec-out uiautomator dump /dev/tty || true)"
  grep -q 'resource-id="com.termux:id/' <<<"$hierarchy" && break
  sleep 1
done
# TERMINAL mode integrates X11, and Games mode has its own X11 under a separate
# preference namespace; the two coexist. When the integrated X11 surface is in front,
# the terminal drawer holding local_games_button is not in the hierarchy at all, so
# report that explicitly instead of dying inside the grep under `set -e`.
if ! grep -q 'resource-id="com.termux:id/local_games_button"' <<<"$hierarchy"; then
  if grep -q 'resource-id="com.termux:id/lorieView"' <<<"$hierarchy"; then
    echo "FAIL: integrated X11 surface is in front; terminal drawer (and its Local Games entry) is not rendered" >&2
  else
    echo "FAIL: Local Games entry is missing from the terminal drawer" >&2
  fi
  exit 1
fi
node="$(printf '%s' "$hierarchy" | tr '<' '\n' |
  grep 'resource-id="com.termux:id/local_games_button"' | head -n 1)"
[[ "$node" == *'clickable="true"'* && "$node" == *'enabled="true"'* ]] || {
  echo "FAIL: Local Games entry is missing or disabled" >&2
  exit 1
}

bounds="$(printf '%s' "$node" |
  sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
read -r left top right bottom <<<"$bounds"
[[ -n "${bottom:-}" ]] || {
  echo "FAIL: Local Games entry bounds are unavailable" >&2
  exit 1
}
adb shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"

stack="$(adb shell dumpsys activity activities)"
# API <=34 prints "mResumedActivity:"; API 35 renamed it to "ResumedActivity:" /
# "topResumedActivity=". Match any of them so the check is not silently version-bound.
grep -Eq '(m|top)?ResumedActivity[:=].*LocalGamesActivity' <<<"$stack" || {
  echo "FAIL: LocalGamesActivity is not resumed" >&2
  exit 1
}
grep -q 'Activities=.*TermuxActivity.*LocalGamesActivity' <<<"$stack" || {
  echo "FAIL: TermuxActivity was not retained in the task" >&2
  exit 1
}

echo "Local Games app entry device check passed."
