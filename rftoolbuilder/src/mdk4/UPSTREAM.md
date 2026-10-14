# Vendored MDK4 source

- Upstream: https://github.com/aircrack-ng/mdk4
- Commit: `3e214fc90710c9185f3783783b3b3c6c4e3098c2` (master at import time)
- Source archive: https://codeload.github.com/aircrack-ng/mdk4/zip/3e214fc90710c9185f3783783b3b3c6c4e3098c2
- Source archive SHA-256: `1b6d3e4d51f2ebdc7150ab68ee5dfd5f96fbba5ccfb9dc62d07f77c9763aaa75`
- License: original `COPYING`; all 109 upstream files are unchanged.

The rootfs build compiles a disposable copy of this local source using the
upstream `src/Makefile` target `mdk4`. It does not download MDK4 source at build
time or use a prebuilt MDK4 executable. It uses the Linux Android NDK compiler
and the musl build already produced by `build-pixiewps.sh`.

Fixed Alpine AArch64 development packages supply Linux and library headers,
plus static libnl and libpcap libraries, in a separate build-only directory.
The libnl macros, include paths and libraries are supplied explicitly without
host pkg-config discovery. Only the static AArch64 executable is installed in
rootfs at `/bin/mdk4`; source files and development packages remain outside it.
