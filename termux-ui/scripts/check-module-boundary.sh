#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if ! command -v rg >/dev/null 2>&1; then
  echo "FAIL: ripgrep (rg) is required; without it every check below silently passes" >&2
  exit 1
fi

if rg -n "project\\(['\"]:app['\"]\\)" termux-ui/build.gradle termux-ui/src; then
  echo "FAIL: :termux-ui must not depend on :app" >&2
  exit 1
fi

if rg -n "(^|[^a-zA-Z0-9_])com\\.termux\\.app([.;]|$)" termux-ui/src -g '*.java' -g '*.kt'; then
  echo "FAIL: :termux-ui must not import app implementation packages" >&2
  exit 1
fi

if rg -n "LorieViewRuntimeController|LorieViewRuntimeApi|TermuxScreenView|com\\.termux\\.x11\\.Prefs|com\\.termux\\.x11\\.controller" \
  termux-ui/src/main -g '*.java' -g '*.kt' -g '*.xml'; then
  echo "FAIL: Games UI must use the standalone X11SessionView boundary" >&2
  exit 1
fi

if rg -n '^import android\.' \
  termux-ui/src/main/java/com/termux/ui/domain \
  termux-ui/src/main/java/com/termux/ui/data \
  termux-ui/src/main/java/com/termux/ui/runtime; then
  echo "FAIL: games domain, data contracts and runtime contracts must remain Android-free" >&2
  exit 1
fi

echo "Games module boundary check passed."
