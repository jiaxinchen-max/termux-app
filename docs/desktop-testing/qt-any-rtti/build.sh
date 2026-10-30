#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
set -eu
compiler="${1:?Usage: build.sh /path/to/aarch64-linux-android24-clang++ output-dir}"
out="${2:?output-dir is required}"
source_dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out"
"$compiler" -O2 -std=c++17 -fvisibility=hidden -shared -fPIC "$source_dir/producer.cpp" -o "$out/producer-old.so"
"$compiler" -O2 -std=c++17 -fvisibility=hidden -shared -fPIC -DTEST_COMPARE_NAMES "$source_dir/producer.cpp" -o "$out/producer-fixed.so"
"$compiler" -O2 -std=c++17 -fvisibility=hidden "$source_dir/check.cpp" -ldl -o "$out/check-any-rtti"
