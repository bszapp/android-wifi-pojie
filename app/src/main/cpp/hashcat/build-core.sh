#!/bin/sh
# SPDX-License-Identifier: MIT
set -eu

SOURCE=$1
WORK=$2
ADAPTER=$3
CC=$4
CXX=$5
AR=$6
API=$7
MAKE=$8
VERSION=$9
STRIP=${10}

mkdir -p "$WORK/hashcat"
# Only the build workspace is populated; vendored upstream source stays clean.
cp -a "$SOURCE/." "$WORK/hashcat/"
cd "$WORK/hashcat"
# Keep the upstream Android branch, ARM/NEON rules and plugin ABI rules.
# Only replace host-specific compiler targets and bionic linker libraries.
NATIVE_FLAGS="-fPIC -ffunction-sections -fdata-sections -D_GNU_SOURCE -DWLANTOOL_ANDROID_HASHCAT -DWLANTOOL_HASHCAT_WPA_ONLY -march=armv8-a -mtune=generic -I$ADAPTER"
NATIVE_LINK="-ldl -lm -lz -llog -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
MODULES='src/modules/module_22000.c src/modules/module_22001.c'
CFLAGS="$NATIVE_FLAGS" "$MAKE" -s -j8 UNAME=Android PRODUCTION=1 VERSION_TAG="$VERSION" \
  CC="$CC --target=aarch64-linux-android$API" \
  CXX="$CXX --target=aarch64-linux-android$API" AR="$AR" \
  LFLAGS_NATIVE="$NATIVE_LINK" \
  IS_AARCH64=1 IS_ARM=1 MAINTAINER_MODE=1 SHARED=1 \
  HASHCAT_LIBRARY_NATIVE=libhashcat.so COMPTIME=0 ENABLE_BRAIN=0 \
  MODULES_SRC="$MODULES" BRIDGES_SRC= FEEDS_SRC=src/feeds/feed_wordlist.c \
  LIBRARY_LFLAGS_NATIVE='-shared -Wl,-soname,libhashcat.so -Wl,-Bsymbolic-functions' \
  obj/combined.NATIVE.a modules bridges feeds
python3 "$ADAPTER/pack_resources.py" "$WORK/hashcat" "$WORK/embedded" "$STRIP"
