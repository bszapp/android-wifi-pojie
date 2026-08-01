#!/system/bin/sh

# 用法：
#   sh wifi_monitor.sh
#   sh wifi_monitor.sh wlan0

IFACE="${1:-wlan0}"
TMPDIR="${TMPDIR:-/data/local/tmp}"
SCAN_RAW="$TMPDIR/iw_scan_raw.$$"
SCAN_LIST="$TMPDIR/iw_scan_list.$$"
SCAN_ERR="$TMPDIR/iw_scan_error.$$"

cleanup() {
    rm -f "$SCAN_RAW" "$SCAN_LIST" "$SCAN_ERR"
}

trap cleanup EXIT HUP INT TERM

die() {
    echo "[!] $*" >&2
    exit 1
}

# 检查 root
[ "$(id -u)" = "0" ] || die "请使用 root 权限运行"

command -v iw >/dev/null 2>&1 || die "没有找到 iw 命令"
command -v ip >/dev/null 2>&1 || die "没有找到 ip 命令"
command -v awk >/dev/null 2>&1 || die "没有找到 awk 命令"

iw dev "$IFACE" info >/dev/null 2>&1 ||
    die "没有找到无线接口：$IFACE"

echo "[*] 无线接口：$IFACE"
echo "[*] 准备扫描周围网络……"

# 确保 Android Wi-Fi 框架已启用，便于扫描
svc wifi enable >/dev/null 2>&1
sleep 2

ip link set "$IFACE" up >/dev/null 2>&1

# 扫描
if ! iw dev "$IFACE" scan >"$SCAN_RAW" 2>"$SCAN_ERR"; then
    echo "[!] 扫描失败：" >&2
    cat "$SCAN_ERR" >&2
    echo >&2
    echo "请确认：" >&2
    echo "  1. $IFACE 当前支持扫描" >&2
    echo "  2. 接口没有处于 monitor 模式" >&2
    echo "  3. 驱动支持 nl80211 扫描" >&2
    exit 1
fi

# 生成格式：
# 编号<TAB>BSSID<TAB>信道<TAB>频率<TAB>SSID
awk '
function freq_to_channel(f) {
    # 2.4 GHz
    if (f == 2484)
        return 14

    if (f >= 2412 && f <= 2472)
        return int((f - 2407) / 5)

    # 部分 4.9 GHz 公共安全频段
    if (f >= 4910 && f <= 4980)
        return int((f - 4000) / 5)

    # 5 GHz
    if (f >= 5000 && f <= 5895)
        return int((f - 5000) / 5)

    # 6 GHz 特殊信道 2
    if (f == 5935)
        return 2

    # 6 GHz
    if (f >= 5955 && f <= 7115)
        return int((f - 5950) / 5)

    return "?"
}

function output_bss(    ch) {
    if (bssid == "" || freq == "")
        return

    ch = channel
    if (ch == "")
        ch = freq_to_channel(freq)

    if (ssid == "")
        ssid = "<hidden>"

    number++
    printf "%d\t%s\t%s\t%s\t%s\n", \
           number, bssid, ch, freq, ssid
}

/^BSS[ \t]/ {
    output_bss()

    bssid = $2
    sub(/\(.*/, "", bssid)

    ssid = ""
    freq = ""
    channel = ""
    next
}

/^[ \t]*freq:/ {
    freq = $2
    next
}

/^[ \t]*SSID:/ {
    line = $0
    sub(/^[ \t]*SSID:[ \t]*/, "", line)
    ssid = line
    next
}

/^[ \t]*DS Parameter set: channel/ {
    channel = $NF
    next
}

END {
    output_bss()
}
' "$SCAN_RAW" >"$SCAN_LIST"

COUNT="$(wc -l <"$SCAN_LIST" | tr -d ' ')"

[ -n "$COUNT" ] || COUNT=0
[ "$COUNT" -gt 0 ] 2>/dev/null || die "没有扫描到可用网络"

echo
printf "%-5s %-17s %-8s %-7s %s\n" \
    "编号" "BSSID" "信道" "频率" "ESSID"

printf "%-5s %-17s %-8s %-7s %s\n" \
    "----" "-----------------" "------" "------" "----------------"

awk -F '	' '
{
    ssid = $5
    for (i = 6; i <= NF; i++)
        ssid = ssid FS $i

    printf "%-5s %-17s %-8s %-7s %s\n", \
           $1, $2, $3, $4, ssid
}
' "$SCAN_LIST"

echo
printf "请选择网络编号 [1-%s]：" "$COUNT"
IFS= read -r CHOICE

case "$CHOICE" in
    ""|*[!0-9]*)
        die "输入必须是数字"
        ;;
esac

[ "$CHOICE" -ge 1 ] 2>/dev/null &&
[ "$CHOICE" -le "$COUNT" ] 2>/dev/null ||
    die "编号超出范围：$CHOICE"

SELECTED="$(
    awk -F '	' -v number="$CHOICE" '
        $1 == number {
            print
            exit
        }
    ' "$SCAN_LIST"
)"

[ -n "$SELECTED" ] || die "无法读取所选网络"

BSSID="$(printf '%s\n' "$SELECTED" | cut -f2)"
CHANNEL="$(printf '%s\n' "$SELECTED" | cut -f3)"
FREQ="$(printf '%s\n' "$SELECTED" | cut -f4)"
ESSID="$(printf '%s\n' "$SELECTED" | cut -f5-)"

echo
echo "[*] 已选择："
echo "    ESSID   : $ESSID"
echo "    BSSID   : $BSSID"
echo "    Channel : $CHANNEL"
echo "    Freq    : ${FREQ} MHz"
echo
echo "[*] 正在关闭系统 Wi-Fi 并切换 monitor 模式……"

# 保留原命令中的服务处理流程
svc wifi disable >/dev/null 2>&1

setprop ctl.restart wificond >/dev/null 2>&1
setprop ctl.restart vendor.wifi_hal_legacy >/dev/null 2>&1

start wificond >/dev/null 2>&1
start vendor.wifi_hal_legacy >/dev/null 2>&1

sleep 1

pkill wpa_supplicant >/dev/null 2>&1
pkill -f wpa_supplicant >/dev/null 2>&1

sleep 1

set_monitor_mode() {
    ip link set "$IFACE" down || return 1

    iw dev "$IFACE" set type monitor || return 1

    ip link set "$IFACE" up || return 1

    # 优先按频率锁定，避免跨频段信道编号冲突
    if iw dev "$IFACE" set freq "$FREQ" HT20 2>/dev/null; then
        return 0
    fi

    # 部分驱动只接受 channel
    if iw dev "$IFACE" set channel "$CHANNEL" HT20 2>/dev/null; then
        return 0
    fi

    # 6 GHz 或不接受 HT20 参数的驱动
    if iw dev "$IFACE" set freq "$FREQ" 2>/dev/null; then
        return 0
    fi

    return 1
}

if ! set_monitor_mode; then
    echo "[!] Wi-Fi 服务可能仍在占用接口，停止服务后重试……"

    setprop ctl.stop wificond >/dev/null 2>&1
    setprop ctl.stop vendor.wifi_hal_legacy >/dev/null 2>&1

    stop wificond >/dev/null 2>&1
    stop vendor.wifi_hal_legacy >/dev/null 2>&1

    pkill wpa_supplicant >/dev/null 2>&1
    pkill -f wpa_supplicant >/dev/null 2>&1

    sleep 1

    set_monitor_mode ||
        die "无法进入 monitor 模式或无法设置所选信道"
fi

echo
echo "[+] 已进入 monitor 模式"
echo "[+] 已锁定 Channel $CHANNEL / ${FREQ} MHz"
echo
iw dev
#嗯，这里不太合适，交给app吧
#echo
#iw dev "$IFACE" info
#tcpdump -i wlan0 -e -w handshake.pcap
#aircrack-ng -w passwords.txt -e "$ESSID" handshake.pcap