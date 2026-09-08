#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if ! command -v rg >/dev/null 2>&1; then
  echo "FAIL: ripgrep (rg) is required; without it every check below silently passes" >&2
  exit 1
fi

if rg -n "project\\(['\"]:app['\"]\\)" games/build.gradle games/src; then
  echo "FAIL: :games must not depend on :app" >&2
  exit 1
fi

if rg -n "(^|[^a-zA-Z0-9_])com\\.termux\\.app([.;]|$)" games/src -g '*.java' -g '*.kt'; then
  echo "FAIL: :games must not import app implementation packages" >&2
  exit 1
fi

if rg -n "LorieViewRuntimeController|LorieViewRuntimeApi|TermuxScreenView|com\\.termux\\.x11\\.Prefs|com\\.termux\\.x11\\.controller" \
  games/src/main -g '*.java' -g '*.kt' -g '*.xml'; then
  echo "FAIL: Games UI must use the standalone X11SessionView boundary" >&2
  exit 1
fi

if rg -n '^import android\.' \
  games/src/main/java/com/termux/localgames/domain \
  games/src/main/java/com/termux/localgames/data \
  games/src/main/java/com/termux/localgames/runtime; then
  echo "FAIL: games domain, data contracts and runtime contracts must remain Android-free" >&2
  exit 1
fi

echo "Games module boundary check passed."
