#!/bin/sh
set -eu

: "${MDK4_CLANG:?}" "${MDK4_MUSL_PREFIX:?}" "${MDK4_BUILTINS:?}" "${MDK4_DEPS:?}"
for arg in "$@"; do
  case "$arg" in
    -c|-E|-S|-M|-MM)
      exec "$MDK4_CLANG" --target=aarch64-linux-musl -nostdinc \
        -isystem "$MDK4_MUSL_PREFIX/include" -isystem "$MDK4_DEPS/usr/include" "$@"
      ;;
  esac
done

exec "$MDK4_CLANG" --target=aarch64-linux-musl -nostdinc \
  -isystem "$MDK4_MUSL_PREFIX/include" -isystem "$MDK4_DEPS/usr/include" \
  -fuse-ld=lld -nostdlib -static-pie -Wl,-z,now -Wl,-z,relro \
  "$MDK4_MUSL_PREFIX/lib/rcrt1.o" "$MDK4_MUSL_PREFIX/lib/crti.o" \
  "$@" -L"$MDK4_MUSL_PREFIX/lib" -lc "$MDK4_BUILTINS" "$MDK4_MUSL_PREFIX/lib/crtn.o"
