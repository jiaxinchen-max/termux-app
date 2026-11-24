#!/data/data/com.termux/files/usr/bin/bash
set -eu
operation="$1"
export REPO_OS_OVERRIDE=linux
check_java() {
  test -x "$JAVA_HOME/bin/java" || { echo 'Missing JDK. Prepare tools or select an installed JDK.'; return 1; }
  local version
  version=$("$JAVA_HOME/bin/java" -version 2>&1)
  echo "$version"
  [[ "$version" =~ version\ \"(17|18|19|20|21)\. ]] || { echo 'This Gradle 8.7 template needs JDK 17–21. Select a compatible JDK.'; return 1; }
}
check_android() {
  check_java || return 1
  test -f "$ANDROID_HOME/platforms/android-34/android.jar" || { echo 'Missing SDK platform android-34'; return 1; }
  test -f "$ANDROID_HOME/build-tools/34.0.0/package.xml" || { echo 'Missing SDK build-tools 34.0.0'; return 1; }
  test -x "$IDE_AAPT2" || { echo 'Missing native aapt2 (Termux aapt2 package)'; return 1; }
  "$IDE_AAPT2" version
}
case "$operation" in
prepare)
  missing=()
  if ! check_java; then
    test "${IDE_CUSTOM_JAVA:-0}" != 1 || { echo 'The selected custom JDK is incompatible. Change JAVA_HOME in Tool paths.'; exit 1; }
    missing+=(openjdk-17)
  fi
  for package in aapt2 curl unzip; do
    case "$package" in aapt2) executable="$IDE_AAPT2";; *) executable="$PREFIX/bin/$package";; esac
    if ! test -x "$executable"; then missing+=("$package"); fi
  done
  if test "${#missing[@]}" -gt 0 || ! test -f "$ANDROID_HOME/platforms/android-34/android.jar" || ! test -f "$ANDROID_HOME/build-tools/34.0.0/package.xml"; then
    available=$(df -Pk "$HOME" | awk 'END { print $4 }')
    test "$available" -ge 1228800 || { echo 'At least 1.2 GiB free space is required to prepare Android tools.'; exit 1; }
  fi
  if test "${#missing[@]}" -gt 0; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}"
  else echo 'All required Termux packages already installed; skipping apt downloads.'; fi
  # Verify the native package before spending time downloading SDK components.
  test -x "$IDE_AAPT2" || { echo 'Missing native aapt2 after package installation (Termux aapt2 package).'; exit 1; }
  "$IDE_AAPT2" version
  # Resolve again after installation; existing compatible custom JDKs are retained.
  if ! check_java; then
    for jdk in "$PREFIX/lib/jvm/java-17-openjdk" "$PREFIX/lib/jvm/java-21-openjdk" "$PREFIX/opt/openjdk-17" "$PREFIX/opt/openjdk"; do
      if test -x "$jdk/bin/java"; then export JAVA_HOME="$jdk"; break; fi
    done
    if ! test -x "$JAVA_HOME/bin/java" && test -x "$PREFIX/bin/java"; then
      java_binary=$(readlink -f "$PREFIX/bin/java")
      export JAVA_HOME="$(dirname "$(dirname "$java_binary")")"
    fi
    export PATH="$JAVA_HOME/bin:$PREFIX/bin:/system/bin"
    check_java
  fi
  if test -f "$ANDROID_HOME/platforms/android-34/android.jar" && test -f "$ANDROID_HOME/build-tools/34.0.0/package.xml"; then
    check_android
    echo 'Existing compatible SDK reused; no SDK download required.'
    exit 0
  fi
  manager=''
  for tool in "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager "$ANDROID_HOME/tools/bin/sdkmanager"; do
    if test -f "$tool"; then manager="$tool"; break; fi
  done
  if test -z "$manager"; then
    cache="$HOME/.cache/termux-ide"
    mkdir -p "$cache" "$ANDROID_HOME/cmdline-tools"
    archive="$cache/commandlinetools-linux-11479570_latest.zip"
    checksum=9673a4b9bdfc49a26022b047af0b8a91da74ed951ffd7dbe04181bce36980e6d
    if ! echo "$checksum  $archive" | sha256sum -c - >/dev/null 2>&1; then
      curl --fail --location --retry 3 --output "$archive.part" https://dl.google.com/android/repository/commandlinetools-linux-11479570_latest.zip
      echo "$checksum  $archive.part" | sha256sum -c -
      mv "$archive.part" "$archive"
    fi
    unpack=$(mktemp -d "$cache/unpack.XXXXXXXX")
    trap 'rm -rf -- "$unpack"' EXIT
    unzip -q "$archive" -d "$unpack"
    test ! -e "$ANDROID_HOME/cmdline-tools/13.0" || { echo 'Incomplete cmdline-tools/13.0 exists; choose another SDK path or repair it.'; exit 1; }
    mv "$unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/13.0"
    manager="$ANDROID_HOME/cmdline-tools/13.0/bin/sdkmanager"
  fi
  # User explicitly accepts SDK licenses in the preparation dialog.
  yes | bash "$manager" --sdk_root="$ANDROID_HOME" --licenses
  packages=()
  test -f "$ANDROID_HOME/platforms/android-34/android.jar" || packages+=('platforms;android-34')
  test -f "$ANDROID_HOME/build-tools/34.0.0/package.xml" || packages+=('build-tools;34.0.0')
  if test "${#packages[@]}" -gt 0; then
    yes | bash "$manager" --sdk_root="$ANDROID_HOME" "${packages[@]}"
  else echo 'SDK components already installed; skipping SDK downloads.'; fi
  check_android
  echo 'Android Java tools ready. NDK/externalNativeBuild is not verified.'
  ;;
check)
  result=0
  check_android || result=1
  if command -v clang >/dev/null; then
    probe=$(mktemp -d "$TMPDIR/ide-native.XXXXXXXX")
    trap 'rm -rf -- "$probe"' EXIT
    printf 'int main(void) { return 0; }\n' > "$probe/main.c"
    if clang "$probe/main.c" -o "$probe/test" && "$probe/test"; then
      echo 'Native C compile and execution: passed.'
    else echo 'Native C compile or execution: failed.'; result=1; fi
  else echo 'Clang is not installed; native C was not tested.'; fi
  if command -v clang++ >/dev/null; then
    cpp=$(mktemp -d "$TMPDIR/ide-cpp.XXXXXXXX")
    printf '#include <iostream>\nint main() { std::cout << "C++ native probe passed\\n"; }\n' > "$cpp/main.cpp"
    if clang++ "$cpp/main.cpp" -o "$cpp/test" && "$cpp/test"; then
      echo 'Native C++ compile and execution: passed.'
    else echo 'Native C++ compile or execution: failed.'; result=1; fi
    rm -rf -- "$cpp"
  else echo 'Clang++ is not installed; native C++ was not tested.'; fi
  command -v cmake || true
  command -v ninja || true
  echo 'Gradle externalNativeBuild/NDK ABI and API compatibility is NOT verified.'
  exit "$result"
  ;;
build)
  check_java
  test -x "$IDE_AAPT2" || { echo 'Install native aapt2 first (Prepare tools).'; exit 1; }
  test -d "$ANDROID_HOME/platforms" || { echo 'Android SDK not found.'; exit 1; }
  test -f gradlew || { echo 'Project has no Gradle Wrapper (gradlew).'; exit 1; }
  # Project controls its own Wrapper version. Never substitute a global Gradle.
  bash ./gradlew --no-daemon --console=plain --max-workers=2 --no-watch-fs \
    "-Pandroid.aapt2FromMavenOverride=$IDE_AAPT2" "-Dorg.gradle.java.home=$JAVA_HOME" assembleDebug
  ;;
*) echo 'Unknown IDE operation'; exit 2 ;;
esac
