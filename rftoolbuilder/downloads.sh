#!/bin/sh
set -eu
set -x

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_DIR=$ROOT_DIR/distfiles
PROOT_REV=58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939

die() {
  echo "downloads.sh: $*" >&2
  exit 1
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"
}

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
    return 0
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{print $NF}'
    return 0
  fi
  die "need sha256sum, shasum, or openssl"
}

fetch() {
  url=$1
  sha=${2:-}
  rel=$3
  dst=$DIST_DIR/$rel
  tmp=$dst.part

  mkdir -p "$(dirname "$dst")"

  if [ -f "$dst" ]; then
    if [ -z "$sha" ] || [ "$sha" = "-" ]; then
      return 0
    fi
    have=$(hash_file "$dst")
    if [ "$have" = "$sha" ]; then
      return 0
    fi
    rm -f "$dst"
  fi

  rm -f "$tmp"
  curl \
    --fail \
    --location \
    --retry 3 \
    --retry-delay 2 \
    --show-error \
    --output "$tmp" \
    "$url"

  if [ -n "$sha" ] && [ "$sha" != "-" ]; then
    have=$(hash_file "$tmp")
    [ "$have" = "$sha" ] || die "sha256 mismatch for $rel: expected $sha got $have"
  fi
  mv "$tmp" "$dst"
}

need_tool curl
need_tool awk
need_tool mkdir
need_tool mv
need_tool rm

fetch \
  "$(printf 'https://codeload.github.com/%s/%s/tar.gz/%s' "$(printf '\164\145\162\155\165\170')" proot "$PROOT_REV")" \
  "0e132e306214adba900479d3262058f179577856f375d786d3e062498ca957fd" \
  "proot-android-compat-$PROOT_REV.tar.gz"

while IFS='|' read -r rel sha url; do
  [ -n "$rel" ] || continue
  fetch "$url" "$sha" "$rel"
done <<'EOF'
musl-1.2.6.tar.gz|d585fd3b613c66151fc3249e8ed44f77020cb5e6c1e635a616d3f9f82460512a|https://musl.libc.org/releases/musl-1.2.6.tar.gz
talloc-2.4.3.tar.gz|dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd|https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
zlib-1.3.1.tar.gz|9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23|https://zlib.net/fossils/zlib-1.3.1.tar.gz
alpine/alpine-minirootfs-3.23.4-aarch64.tar.gz|9250667a8affac8f1e98086392f80f43f086626701e9bce33398eb9b6c0bd64c|https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.4-aarch64.tar.gz
alpine/gdbm-1.26-r0.apk|d8981e56b4c16722424ef642313bb710e42cc7181f204e14856a1f21887adf17|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/gdbm-1.26-r0.apk
alpine/dbus-libs-1.16.2-r1.apk|7d8f2b7bb25430d440d36ade181cd77d11e0ef4172a5a57e767985ca33670fb3|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/dbus-libs-1.16.2-r1.apk
alpine/libbz2-1.0.8-r6.apk|ccd478f4d5346a25911d4ee977690e2695e0ca3acf3e6b1a8c1300b2f4311e2f|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libbz2-1.0.8-r6.apk
alpine/libcrypto3-3.5.7-r0.apk|9420d61c8d6b4ce84bdd660fc0957f94284185de6418eb3ba50bfe4f91504b76|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libcrypto3-3.5.7-r0.apk
alpine/libexpat-2.8.2-r0.apk|0dc69b24b3903d3099dfe176edcc06ba4a1d81bdc72400d29017437fb8c17122|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libexpat-2.8.2-r0.apk
alpine/libffi-3.5.2-r0.apk|ebf430b246a6b1167350431e2af59a09f4edbc42dc3e03f3b9d74800ba5f9e7f|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libffi-3.5.2-r0.apk
alpine/libgcc-15.2.0-r2.apk|eaaafda78fde1c904e1741680ddea91649f051e29a343152c8a4327605704b0f|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libgcc-15.2.0-r2.apk
alpine/libncursesw-6.5_p20251123-r0.apk|7fe332c8af8b97579e89df56466f83f4abda5040a18e1842c07d684320bbef4b|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libncursesw-6.5_p20251123-r0.apk
alpine/libnl3-3.11.0-r0.apk|47bf42b751721d62447978b5486d1b675a56dbbe0e07b9bb66036eb3287323f7|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libnl3-3.11.0-r0.apk
alpine/libpanelw-6.5_p20251123-r0.apk|d2d6c6eaf1ef0894ae777adb36e063c9fe1f08eba8d318163073557d901cdcd3|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libpanelw-6.5_p20251123-r0.apk
alpine/libssl3-3.5.7-r0.apk|390912a004788643e73cf2c1ad8959d16369d7de19c45dd5fddf072a67f4ff9e|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libssl3-3.5.7-r0.apk
alpine/libstdc++-15.2.0-r2.apk|10d72e25f6fcc0f3d9fdd801c9bdaed81d6e836aa2b65b63f25d2d97f860a7d1|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libstdc++-15.2.0-r2.apk
alpine/mpdecimal-4.0.1-r0.apk|65f85194314576a50e0d3755ed42726c0ce37c846d2f22f558ea81a5ceb8278f|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/mpdecimal-4.0.1-r0.apk
alpine/musl-1.2.5-r23.apk|6a3edd924ead1fad88a69e28c5775809af3026b322f58428001cd02fedc5299e|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/musl-1.2.5-r23.apk
alpine/ncurses-terminfo-base-6.5_p20251123-r0.apk|6952a6b39abaf7bbc498cb085f0f59bf23619b53b3f7328b08fe50c0198a2bd4|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/ncurses-terminfo-base-6.5_p20251123-r0.apk
alpine/pcsc-lite-libs-2.4.0-r1.apk|8b436b649abc801ff52c943d5248e1e4085599193c3340a458ff32417325bab4|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/pcsc-lite-libs-2.4.0-r1.apk
alpine/python3-3.12.13-r0.apk|ede3fca8b8339f8f4854a3b2a42fe7e1cc9594cca2951019a4c023546410e029|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/python3-3.12.13-r0.apk
alpine/py3-wcwidth-0.2.13-r1.apk|debb55542573c7db5e0f3162cffda6c1f616f0df1f086fa7bda65dc82be7f6df|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/py3-wcwidth-0.2.13-r1.apk
alpine/libpcap-1.10.5-r1.apk|8ef83f428101a4cd8fa11f39987c4016e8b78f62ec5c79867482fe42025ed755|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libpcap-1.10.5-r1.apk
alpine/readline-8.3.1-r0.apk|70d288a6c3d8daf19b10fb220120d2ebd6f154011c96ca5bc84923b658cb21f7|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/readline-8.3.1-r0.apk
alpine/sqlite-libs-3.51.2-r0.apk|0264d50e8ae451804bc0ae2833f18a465a743443bc4ff4e166b50233a1b0cda4|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/sqlite-libs-3.51.2-r0.apk
alpine/tcpdump-4.99.5-r1.apk|cd078660c053520e4dc1866276706d2f95c7862980e85fe833233429aaedf479|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/tcpdump-4.99.5-r1.apk
alpine/iw-6.17-r0.apk|aa98f90f196d1dab15a749215bff4aed48b75c61bc64ff23495e8052979c32da|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/iw-6.17-r0.apk
alpine/wpa_supplicant-2.11-r3.apk|aea1caa2a0bfd07314886ddfeb66d76daddce6af38b0baa17d1718d7596bb558|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/wpa_supplicant-2.11-r3.apk
alpine/libmnl-1.0.5-r2.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libmnl-1.0.5-r2.apk
alpine/ethtool-6.15-r0.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/ethtool-6.15-r0.apk
alpine/wireless-tools-libs-30_pre9-r5.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/wireless-tools-libs-30_pre9-r5.apk
alpine/wireless-tools-30_pre9-r5.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/wireless-tools-30_pre9-r5.apk
alpine/pcre-8.45-r4.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/pcre-8.45-r4.apk
alpine/pcre2-10.47-r0.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/pcre2-10.47-r0.apk
alpine/grep-3.12-r0.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/grep-3.12-r0.apk
alpine/sqlite-3.51.2-r0.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/sqlite-3.51.2-r0.apk
alpine/aircrack-ng-1.7-r3.apk||https://dl-cdn.alpinelinux.org/alpine/v3.23/community/aarch64/aircrack-ng-1.7-r3.apk
alpine/xz-libs-5.8.3-r0.apk|6123d4fc5be222318236887639047b844214f609e75abee6b4e6528f44d76be4|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/xz-libs-5.8.3-r0.apk
alpine/zlib-1.3.2-r0.apk|ecda4cc94fd18f90182f1d3a615889df5e0db9cf78926d11627dd23e06d2e6e8|https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/zlib-1.3.2-r0.apk
EOF
