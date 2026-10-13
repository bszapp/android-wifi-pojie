# Vendored Pixiewps source

- Upstream: https://github.com/wiire-a/pixiewps
- Commit: `20eaf62b3fd2afc67b039d1521460b3b7d165842` (master at import time)
- Source archive: https://codeload.github.com/wiire-a/pixiewps/zip/20eaf62b3fd2afc67b039d1521460b3b7d165842
- Source archive SHA-256: `50a39e632ddcd2651ac4b8358fb7326eaf4ede9205a52faeae6f3177ef420730`
- License: original `LICENSE.md`; original source files and license headers are unchanged.

The rootfs build compiles this local source with the upstream `pixiewps` Makefile
target and its default bundled cryptographic implementation. It does not fetch
Pixiewps source or use a prebuilt Pixiewps binary. The existing distfiles download
supplies musl; the existing Linux Android NDK supplies the compiler. An independent,
unmodified musl build produces a static AArch64 executable at `/bin/pixiewps`.
Only the executable is added to rootfs; build tools and sources stay outside rootfs.

