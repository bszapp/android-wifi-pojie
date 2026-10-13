#!/bin/sh
set -eu

: "${PIXIEWPS_CLANG:?}" "${PIXIEWPS_MUSL_PREFIX:?}" "${PIXIEWPS_BUILTINS:?}"
for arg in "$@"; do
  case "$arg" in
    -c|-E|-S|-M|-MM)
      exec "$PIXIEWPS_CLANG" --target=aarch64-linux-musl -nostdinc \
        -isystem "$PIXIEWPS_MUSL_PREFIX/include" "$@"
      ;;
  esac
done

exec "$PIXIEWPS_CLANG" --target=aarch64-linux-musl -nostdinc \
  -isystem "$PIXIEWPS_MUSL_PREFIX/include" -fuse-ld=lld -nostdlib -static-pie \
  -Wl,-z,now -Wl,-z,relro "$PIXIEWPS_MUSL_PREFIX/lib/rcrt1.o" \
  "$PIXIEWPS_MUSL_PREFIX/lib/crti.o" "$@" -L"$PIXIEWPS_MUSL_PREFIX/lib" \
  -lc "$PIXIEWPS_BUILTINS" "$PIXIEWPS_MUSL_PREFIX/lib/crtn.o"

