#!/bin/sh
set -eu
set -x

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR=$ROOT_DIR/build
OUT_DIR=$BUILD_DIR/out
DIST_DIR=$ROOT_DIR/distfiles
ROOTFS_STAGE=$BUILD_DIR/rootfs-stage
ROOTFS_ARCHIVE=$OUT_DIR/rootfs.tar.xz
APP_ASSETS_DIR=$ROOT_DIR/../app/src/main/assets

die() {
  echo "build.sh: $*" >&2
  exit 1
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"
}

need_tool tar
need_tool xz
need_tool chmod
need_tool cp
need_tool ln
need_tool rm
need_tool mkdir

for required in \
  "$DIST_DIR/alpine/alpine-minirootfs-3.23.4-aarch64.tar.gz" \
  "$DIST_DIR/alpine/dbus-libs-1.16.2-r1.apk" \
  "$DIST_DIR/alpine/gdbm-1.26-r0.apk" \
  "$DIST_DIR/alpine/libbz2-1.0.8-r6.apk" \
  "$DIST_DIR/alpine/libcrypto3-3.5.7-r0.apk" \
  "$DIST_DIR/alpine/libexpat-2.8.2-r0.apk" \
  "$DIST_DIR/alpine/libffi-3.5.2-r0.apk" \
  "$DIST_DIR/alpine/libgcc-15.2.0-r2.apk" \
  "$DIST_DIR/alpine/libncursesw-6.5_p20251123-r0.apk" \
  "$DIST_DIR/alpine/libnl3-3.11.0-r0.apk" \
  "$DIST_DIR/alpine/libpanelw-6.5_p20251123-r0.apk" \
  "$DIST_DIR/alpine/libssl3-3.5.7-r0.apk" \
  "$DIST_DIR/alpine/libstdc++-15.2.0-r2.apk" \
  "$DIST_DIR/alpine/mpdecimal-4.0.1-r0.apk" \
  "$DIST_DIR/alpine/musl-1.2.5-r23.apk" \
  "$DIST_DIR/alpine/ncurses-terminfo-base-6.5_p20251123-r0.apk" \
  "$DIST_DIR/alpine/pcsc-lite-libs-2.4.0-r1.apk" \
  "$DIST_DIR/alpine/python3-3.12.13-r0.apk" \
  "$DIST_DIR/alpine/py3-cparser-2.22-r1.apk" \
  "$DIST_DIR/alpine/py3-cffi-1.17.1-r1.apk" \
  "$DIST_DIR/alpine/py3-cryptography-46.0.7-r0.apk" \
  "$DIST_DIR/alpine/py3-wcwidth-0.2.13-r1.apk" \
  "$DIST_DIR/alpine/libpcap-1.10.5-r1.apk" \
  "$DIST_DIR/alpine/readline-8.3.1-r0.apk" \
  "$DIST_DIR/alpine/sqlite-libs-3.51.2-r0.apk" \
  "$DIST_DIR/alpine/tcpdump-4.99.5-r1.apk" \
  "$DIST_DIR/alpine/iw-6.17-r0.apk" \
  "$DIST_DIR/alpine/wpa_supplicant-2.11-r3.apk" \
  "$DIST_DIR/alpine/libmnl-1.0.5-r2.apk" \
  "$DIST_DIR/alpine/ethtool-6.15-r0.apk" \
  "$DIST_DIR/alpine/wireless-tools-libs-30_pre9-r5.apk" \
  "$DIST_DIR/alpine/wireless-tools-30_pre9-r5.apk" \
  "$DIST_DIR/alpine/pcre-8.45-r4.apk" \
  "$DIST_DIR/alpine/pcre2-10.47-r0.apk" \
  "$DIST_DIR/alpine/grep-3.12-r0.apk" \
  "$DIST_DIR/alpine/sqlite-3.51.2-r0.apk" \
  "$DIST_DIR/alpine/aircrack-ng-1.7-r3.apk" \
  "$DIST_DIR/alpine/scapy-2.6.1-r0.apk" \
  "$DIST_DIR/alpine/py3-pyroute2-0.9.5-r0.apk" \
  "$DIST_DIR/alpine/xz-libs-5.8.3-r0.apk" \
  "$DIST_DIR/alpine/zlib-1.3.2-r0.apk"; do
  [ -f "$required" ] || die "missing distfile: $required"
done

rm -rf "$BUILD_DIR"
mkdir -p "$OUT_DIR"

mkdir -p "$ROOTFS_STAGE"
tar -xzf "$DIST_DIR/alpine/alpine-minirootfs-3.23.4-aarch64.tar.gz" -C "$ROOTFS_STAGE"

for pkg in \
  dbus-libs-1.16.2-r1.apk \
  gdbm-1.26-r0.apk \
  libbz2-1.0.8-r6.apk \
  libcrypto3-3.5.7-r0.apk \
  libexpat-2.8.2-r0.apk \
  libffi-3.5.2-r0.apk \
  libgcc-15.2.0-r2.apk \
  libncursesw-6.5_p20251123-r0.apk \
  libnl3-3.11.0-r0.apk \
  libpanelw-6.5_p20251123-r0.apk \
  libssl3-3.5.7-r0.apk \
  libstdc++-15.2.0-r2.apk \
  mpdecimal-4.0.1-r0.apk \
  musl-1.2.5-r23.apk \
  ncurses-terminfo-base-6.5_p20251123-r0.apk \
  pcsc-lite-libs-2.4.0-r1.apk \
  python3-3.12.13-r0.apk \
  py3-cparser-2.22-r1.apk \
  py3-cffi-1.17.1-r1.apk \
  py3-cryptography-46.0.7-r0.apk \
  py3-wcwidth-0.2.13-r1.apk \
  libpcap-1.10.5-r1.apk \
  readline-8.3.1-r0.apk \
  sqlite-libs-3.51.2-r0.apk \
  tcpdump-4.99.5-r1.apk \
  iw-6.17-r0.apk \
  wpa_supplicant-2.11-r3.apk \
  libmnl-1.0.5-r2.apk \
  ethtool-6.15-r0.apk \
  wireless-tools-libs-30_pre9-r5.apk \
  wireless-tools-30_pre9-r5.apk \
  pcre-8.45-r4.apk \
  pcre2-10.47-r0.apk \
  grep-3.12-r0.apk \
  sqlite-3.51.2-r0.apk \
  aircrack-ng-1.7-r3.apk \
  scapy-2.6.1-r0.apk \
  py3-pyroute2-0.9.5-r0.apk \
  xz-libs-5.8.3-r0.apk \
  zlib-1.3.2-r0.apk; do
  tar -xzf "$DIST_DIR/alpine/$pkg" -C "$ROOTFS_STAGE"
done

rm -f \
  "$ROOTFS_STAGE"/.PKGINFO \
  "$ROOTFS_STAGE"/.INSTALL \
  "$ROOTFS_STAGE"/.pre-install \
  "$ROOTFS_STAGE"/.post-install \
  "$ROOTFS_STAGE"/.pre-upgrade \
  "$ROOTFS_STAGE"/.post-upgrade \
  "$ROOTFS_STAGE"/.trigger \
  "$ROOTFS_STAGE"/.SIGN.*

mkdir -p "$ROOTFS_STAGE/proc" "$ROOTFS_STAGE/sys" "$ROOTFS_STAGE/dev" "$ROOTFS_STAGE/dev/pts"
mkdir -p \
  "$ROOTFS_STAGE/etc" \
  "$ROOTFS_STAGE/run/wpa_supplicant" \
  "$ROOTFS_STAGE/var/run/wpa_supplicant" \
  "$ROOTFS_STAGE/wlantool" \
  "$ROOTFS_STAGE/usr/local/bin"
# Copy the entire tool directory so future scripts are packaged automatically.
cp -R "$ROOT_DIR/src/wlantool/." "$ROOTFS_STAGE/wlantool/"
rm -rf "$ROOTFS_STAGE/wlantool/__pycache__"
: > "$ROOTFS_STAGE/wlantool/vulnwsc.txt"
chmod 755 "$ROOTFS_STAGE/wlantool/scan.py"
ln -sf /usr/bin/python3 "$ROOTFS_STAGE/usr/local/bin/python"
cat > "$ROOTFS_STAGE/etc/wpa_supplicant.conf" <<'EOF'
ctrl_interface=/var/run/wpa_supplicant
ctrl_interface_group=0
update_config=1
EOF
chmod 600 "$ROOTFS_STAGE/etc/wpa_supplicant.conf"

tar \
  --format=gnu \
  --sort=name \
  --mtime='1970-01-01 00:00Z' \
  --numeric-owner \
  --owner=0 \
  --group=0 \
  -cf - \
  -C "$ROOTFS_STAGE" \
  . | xz -T0 -9e -c > "$ROOTFS_ARCHIVE"

mkdir -p "$APP_ASSETS_DIR"
rm -f \
  "$APP_ASSETS_DIR/rootfs.tar" \
  "$APP_ASSETS_DIR/rootfs.tar.gz" \
  "$APP_ASSETS_DIR/rootfs.tar.xz"
cp "$ROOTFS_ARCHIVE" "$APP_ASSETS_DIR/rootfs.tar.xz"

echo "build finished"
echo "rootfs         : $ROOTFS_ARCHIVE"
echo "synced assets  : $APP_ASSETS_DIR/rootfs.tar.xz"
