#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK_DIR=$ROOT_DIR/build/pixiewps
MUSL_SOURCE=$WORK_DIR/musl-source
PIXIEWPS_SOURCE=$WORK_DIR/source
TOOLCHAIN=${ANDROID_NDK_DIR:?ANDROID_NDK_DIR is required}/toolchains/llvm/prebuilt/linux-x86_64/bin
JOBS=${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)}

export PIXIEWPS_CLANG=$TOOLCHAIN/clang
export PIXIEWPS_MUSL_PREFIX=$WORK_DIR/musl
PIXIEWPS_BUILTINS=$("$PIXIEWPS_CLANG" --target=aarch64-linux-musl --rtlib=compiler-rt --print-libgcc-file-name)
if [ ! -f "$PIXIEWPS_BUILTINS" ]; then
  PIXIEWPS_BUILTINS=$(find "$TOOLCHAIN/../lib/clang" -name 'libclang_rt.builtins-aarch64-android.a' | sort | head -n 1)
fi
export PIXIEWPS_BUILTINS
[ -f "$PIXIEWPS_BUILTINS" ] || { echo 'Missing AArch64 compiler builtins' >&2; exit 1; }

mkdir -p "$MUSL_SOURCE" "$WORK_DIR/musl-build" "$PIXIEWPS_SOURCE"
tar -xzf "$ROOT_DIR/distfiles/musl-1.2.6.tar.gz" -C "$MUSL_SOURCE" --strip-components=1
# Build from a disposable copy, keeping the vendored source tree untouched.
cp -R "$ROOT_DIR/src/pixiewps/." "$PIXIEWPS_SOURCE/"
cd "$WORK_DIR/musl-build"
"$MUSL_SOURCE/configure" --prefix="$PIXIEWPS_MUSL_PREFIX" --target=aarch64-linux-musl \
  CC="$PIXIEWPS_CLANG --target=aarch64-linux-musl --rtlib=compiler-rt" \
  AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" LIBCC="$PIXIEWPS_BUILTINS"
make -j"$JOBS"
make install
make -C "$PIXIEWPS_SOURCE" -j"$JOBS" pixiewps \
  CC="sh $ROOT_DIR/pixiewps-cc.sh" CFLAGS='-O3 -fPIE' CPPFLAGS='-D_GNU_SOURCE'
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$PIXIEWPS_SOURCE/pixiewps"
"$TOOLCHAIN/llvm-readelf" -h "$PIXIEWPS_SOURCE/pixiewps" | grep -q 'Machine:.*AArch64'
if "$TOOLCHAIN/llvm-readelf" -l -d "$PIXIEWPS_SOURCE/pixiewps" | grep -Eq 'INTERP|NEEDED'; then
  echo 'pixiewps must be a standalone static executable' >&2
  exit 1
fi
install -m 755 "$PIXIEWPS_SOURCE/pixiewps" "${1:?rootfs stage directory is required}/bin/pixiewps"

