# Native hashcat source provenance

Native executable code is built from source with the Android NDK. Generated
`.so`, `.a` and executable files are not committed.

| Component | Revision | Official source archive | SHA-256 |
| --- | --- | --- | --- |
| hashcat | `5108769476e1019ef0900b7b6b21ec39d1cb92a3` | https://codeload.github.com/hashcat/hashcat/tar.gz/5108769476e1019ef0900b7b6b21ec39d1cb92a3 | `f3762cf60f1bd54762be7ae2b65b7e1eeb6938e4fb100585dbe42cfbb5577937` |

Source and Android adapters share `app/src/main/cpp/hashcat/`. Upstream file
headers, licenses and credits are retained. The revision is pinned, with build
version `v7.1.2-mainline-5108769476e1`.

The Android build follows this revision's [BUILD_Android.md](https://github.com/hashcat/hashcat/blob/5108769476e1019ef0900b7b6b21ec39d1cb92a3/BUILD_Android.md),
using the upstream Makefile's `UNAME=Android` branch, plugin ABI, ARM/NEON and
module build rules.

Dual-entry integration changes are in `src/main.c`, `src/folder.c`,
`src/ext_OpenCL.c`, `src/dynloader.c` and `src/hashcat.c`. Startup resource checks
in `src/user_options.c` use WPA modules/kernels instead of fixed MD5/400 checks.
Adapter sources and build scripts are at this directory's root.
`src/affinity.c` uses upstream Android support without a downstream patch.
OpenCL kernels, algorithm modules and ARM64/NEON implementations are unchanged.

Only WPA/WPA2 modules 22000 and 22001 and the wordlist feed are built. Unused
modules, kernels, tools and examples are omitted from the source tree. Kernel
dependencies and all device tuning tables are retained. GNU libiconv and XZ
Utils sources are no longer vendored, compiled or linked. UTF-8 TXT dictionaries
do not need encoding conversion. gzip uses Android's zlib. Optional zstd/OpenSSL
backends retain upstream missing-dependency errors and are not bundled.

Hashcat is MIT licensed. Dependency licenses are in `docs/license_libs/`.
The executable entry point follows AOSP bionic's
`libc/arch-common/bionic/crtbegin.c` and retains its BSD license declaration.

The wordlist-only build does not bundle `hashcat.hcstat2`. Module ELF files and
embedded resource archives are generated in the build directory, without
extracting code from third-party binary releases.
