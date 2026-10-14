#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK_DIR=$ROOT_DIR/build/mdk4
MDK4_SOURCE=$WORK_DIR/source
TOOLCHAIN=${ANDROID_NDK_DIR:?ANDROID_NDK_DIR is required}/toolchains/llvm/prebuilt/linux-x86_64/bin

export MDK4_CLANG=$TOOLCHAIN/clang
export MDK4_MUSL_PREFIX=$ROOT_DIR/build/pixiewps/musl
export MDK4_DEPS=$WORK_DIR/deps
MDK4_BUILTINS=$("$MDK4_CLANG" --target=aarch64-linux-musl --rtlib=compiler-rt --print-libgcc-file-name)
if [ ! -f "$MDK4_BUILTINS" ]; then
  MDK4_BUILTINS=$(find "$TOOLCHAIN/../lib/clang" -name 'libclang_rt.builtins-aarch64-android.a' | sort | head -n 1)
fi
export MDK4_BUILTINS
[ -f "$MDK4_BUILTINS" ] || { echo 'Missing AArch64 compiler builtins' >&2; exit 1; }
[ -f "$MDK4_MUSL_PREFIX/lib/rcrt1.o" ] || { echo 'Build pixiewps musl before mdk4' >&2; exit 1; }

mkdir -p "$MDK4_SOURCE" "$MDK4_DEPS"
# Keep the original vendored files untouched, as in the pixiewps build.
cp -R "$ROOT_DIR/src/mdk4/." "$MDK4_SOURCE/"
for pkg in \
  libnl3-dev-3.11.0-r0.apk \
  libnl3-static-3.11.0-r0.apk \
  libpcap-dev-1.10.7-r0.apk \
  linux-headers-6.16.12-r0.apk; do
  tar -xzf "$ROOT_DIR/distfiles/alpine/$pkg" -C "$MDK4_DEPS"
done

# Supply the target libnl configuration explicitly; do not probe host libraries.
# libnl=false only skips common.mak's pkg-config discovery. The target macros and
# static libraries below enable libnl in the upstream build.
CFLAGS='-O3 -fPIE -fcommon -Wno-error=int-conversion -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 -D_REVISION=mdk4-v2 -DCONFIG_LIBNL -DCONFIG_LIBNL30' \
make -C "$MDK4_SOURCE/src" -j1 mdk4 \
  CC="sh $ROOT_DIR/mdk4-cc.sh" AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" \
  OSNAME=Linux libnl=false \
  CPPFLAGS="-I$MDK4_DEPS/usr/include/libnl3" \
  LDFLAGS="-L$MDK4_DEPS/usr/lib" \
  LIBS="-lm -L$MDK4_SOURCE/src/osdep -losdep -lnl-genl-3 -lnl-3"
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$MDK4_SOURCE/src/mdk4"
"$TOOLCHAIN/llvm-readelf" -h "$MDK4_SOURCE/src/mdk4" | grep -q 'Machine:.*AArch64'
if "$TOOLCHAIN/llvm-readelf" -l -d "$MDK4_SOURCE/src/mdk4" | grep -Eq 'INTERP|NEEDED'; then
  echo 'mdk4 must be a standalone static executable' >&2
  exit 1
fi
install -m 755 "$MDK4_SOURCE/src/mdk4" "${1:?rootfs stage directory is required}/bin/mdk4"
