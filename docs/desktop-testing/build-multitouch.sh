#!/usr/bin/env bash
set -euo pipefail
if [ "$#" != 5 ]; then
    echo 'Usage: build-multitouch.sh /path/javac /path/d8 /path/android.jar output-dir min-api' >&2
    exit 2
fi
javac_path="$1"; d8_path="$2"; android_jar="$3"; output="$4"; min_api="$5"
[ -x "$javac_path" ] && [ -x "$d8_path" ] && [ -f "$android_jar" ] || { echo 'Explicit compiler inputs are unavailable.' >&2; exit 1; }
case "$min_api" in ''|*[!0-9]*) echo 'min-api must be numeric' >&2; exit 2;; esac
source_dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
work="$(mktemp -d "$output/mt-build.XXXXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"$javac_path" -source 8 -target 8 -classpath "$android_jar" -d "$work/classes" "$source_dir/MultiTouch.java"
"$d8_path" --min-api "$min_api" --lib "$android_jar" --output "$work/mt.jar" "$work/classes/"*.class
mv "$work/mt.jar" "$output/mt.jar"
python3 - "$source_dir/MultiTouch.java" "$output/mt.jar" "$javac_path" "$d8_path" "$android_jar" "$min_api" <<'PY'
import hashlib,json,pathlib,sys
source,jar,javac,d8,android,api=sys.argv[1:]
meta={'source_sha256':hashlib.sha256(pathlib.Path(source).read_bytes()).hexdigest(),'jar_sha256':hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest(),'javac':javac,'d8':d8,'android_jar':android,'min_api':int(api)}
pathlib.Path(jar+'.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta,indent=2))
PY
