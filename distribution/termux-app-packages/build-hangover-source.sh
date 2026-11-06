#!/bin/bash
# Run inside the dedicated Debian 13 amd64 chroot, never in the host checkout.
set -euo pipefail
export LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive
export PATH=/opt/llvm-mingw-20260826-ucrt-ubuntu-22.04-x86_64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# Without /proc/self/exe, glibc cannot reliably resolve LLVM's $ORIGIN rpath.
# Explicitly expose only this pinned toolchain's host shared libraries.
export LD_LIBRARY_PATH=/opt/llvm-mingw-20260826-ucrt-ubuntu-22.04-x86_64/lib
export SOURCE_DATE_EPOCH=1787940812
export DEB_BUILD_OPTIONS=parallel=16
export CROSSDEBUG=pdb CROSSCFLAGS='-O2 -g'
jobs=${HANGOVER_BUILD_JOBS:-16}
[[ "$jobs" =~ ^[1-9][0-9]?$ ]] || { echo 'Invalid build jobs' >&2; exit 1; }
test "$(dpkg --print-architecture)" = amd64
test "$(. /etc/os-release; echo "$VERSION_CODENAME")" = trixie
test -d /work/hangover/wine
test ! -e /work/BUILD_COMPLETE
mkdir -p /work/logs /work/identity
trap 'code=$?; printf "%s\n" "$code" > /work/BUILD_EXIT_CODE' EXIT
dpkg --add-architecture arm64
apt-get update
# Match the upstream Debian 13 foundation. Both native tools and target headers
# are explicit; no package or executable is taken from the Ubuntu host.
apt-get install -y --no-install-recommends \
  build-essential binutils-aarch64-linux-gnu gcc-aarch64-linux-gnu \
  libc6-dev-arm64-cross libstdc++6-arm64-cross \
  ca-certificates python3 git curl xz-utils clang llvm lld \
  flex bison nasm gettext make automake autoconf pkg-config libtool \
  dpkg-dev fakeroot debhelper dh-make cmake ninja-build \
  libfreetype6-dev libfontconfig-dev libx11-dev libxinerama-dev \
  libglib2.0-dev libltdl-dev libxcb1-dev \
  libglib2.0-dev:arm64 libfreetype6-dev:arm64 libltdl-dev:arm64 \
  libxcb1-dev:arm64 libx11-dev:arm64 libepoxy-dev:arm64 libsdl2-dev:arm64 \
  libxcursor-dev:arm64 libxi-dev:arm64 libxshmfence-dev:arm64 \
  libxxf86vm-dev:arm64 libxrandr-dev:arm64 libxrender-dev:arm64 \
  libxfixes-dev:arm64 libxcomposite-dev:arm64 libdbus-1-dev:arm64 \
  libsane-dev:arm64 libusb-1.0-0-dev:arm64 libv4l-dev:arm64 \
  libpulse-dev:arm64 libgstreamer1.0-dev:arm64 \
  libgstreamer-plugins-base1.0-dev:arm64 libgstreamer-plugins-bad1.0-dev:arm64 \
  libudev-dev:arm64 libunwind-dev:arm64 libfontconfig1-dev:arm64 \
  libkrb5-dev:arm64 libvulkan-dev:arm64 libgl-dev:arm64 \
  libgnutls28-dev:arm64 libcups2-dev:arm64 libxkbcommon-dev:arm64 \
  libxkbregistry-dev:arm64 libwayland-dev:arm64 \
  libasound2-dev:arm64 libncurses-dev:arm64 \
  libgphoto2-dev:arm64 libosmesa6-dev:arm64 libglu1-mesa-dev:arm64 \
  unixodbc-dev:arm64
dpkg-query -W -f='${binary:Package}\t${Version}\t${Architecture}\n' > /work/identity/packages.tsv
clang --version > /work/identity/clang.txt
aarch64-linux-gnu-gcc --version > /work/identity/gcc.txt
ld.lld --version > /work/identity/linker.txt
llvm-readobj --version > /work/identity/readobj.txt
llvm-pdbutil --version > /work/identity/pdbutil.txt
# Exercise CodeView/PDB before spending time on the complete Wine build.
printf '__declspec(dllexport) int answer(void) { return 42; }\n' > /work/identity/pdb-probe.c
aarch64-w64-mingw32-clang -nostdlib -shared -gcodeview -O2 -g /work/identity/pdb-probe.c \
  -Wl,--entry,answer,--pdb=/work/identity/pdb-probe.pdb -o /work/identity/pdb-probe.dll
llvm-readobj --coff-debug-directory /work/identity/pdb-probe.dll > /work/identity/pdb-probe-pe.txt
llvm-pdbutil dump --summary /work/identity/pdb-probe.pdb > /work/identity/pdb-probe-summary.txt
cd /work/hangover
test "$(git rev-parse HEAD)" = 56e385b7ff490edd281b288f420677093563560b
test "$(git -C wine rev-parse HEAD)" = 3ea3a14941b6a122b892c84141ce9662c008081e
git submodule status > /work/identity/submodules.txt
# A separate native tools build is required by the upstream cross recipe.
if ! test -d ../wine64; then cp -a wine ../wine64; fi
mkdir -p ../wine64/amd64
cd ../wine64/amd64
../../hangover/wine/configure --enable-win64 --disable-tests > /work/logs/native-configure.log 2>&1
make -j"$jobs" __tooldeps__ > /work/logs/native-tools.log 2>&1
make -j"$jobs" -C nls >> /work/logs/native-tools.log 2>&1
cd /work/hangover/wine
test ! -e debian
cp -a ../.packaging/debian13/wine/debian debian
# Keep the upstream rules, install paths, feature selection and ELF dh_strip.
# Its relative tools path expects a sibling wine64 directory.
ln -s /work/wine64 /work/hangover/wine64
python3 - <<'PY'
from pathlib import Path
rules = Path('debian/rules')
text = rules.read_text()
text = text.replace('export DEB_CFLAGS_MAINT_STRIP', 'export CROSSDEBUG = pdb\nexport CROSSCFLAGS = -O2 -g\nexport DEB_CFLAGS_MAINT_STRIP', 1)
text = text.replace('--disable-tests ' + chr(92) + chr(10),
                    '--disable-tests ' + chr(92) + chr(10) +
                    '\t\t\t\t--without-opencl ' + chr(92) + chr(10) +
                    '\t\t\t\t--without-pcap ' + chr(92) + chr(10), 1)
rules.write_text(text)
changelog = Path('debian/changelog')
text = changelog.read_text()
text = text.replace(text.splitlines()[0], 'hangover-wine (11.16~trixie+idap.pdb2) unstable; urgency=medium', 1)
changelog.write_text(text)
PY
cp debian/rules /work/identity/debian-rules
cp debian/changelog /work/identity/debian-changelog
dpkg-buildpackage -d -b -a arm64 -us -uc -ui -j"$jobs" > /work/logs/wine-build.log 2>&1
cp config.log /work/identity/target-config.log
find /work/hangover -maxdepth 1 -name '*.deb' -exec sha256sum '{}' + > /work/identity/debs.sha256
test -s /work/identity/debs.sha256
# This marker only means compilation and Debian packaging finished. Symbol and
# runtime validation are separate gates; no catalog or device is changed.
printf 'BuiltNotValidated\n' > /work/BUILD_COMPLETE
