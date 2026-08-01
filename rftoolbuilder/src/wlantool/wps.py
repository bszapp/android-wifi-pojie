#!/usr/bin/env python3
# wps_connect.py
import argparse
import os
import re
import subprocess
import sys
import threading
import time

MAC_RE = re.compile(r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
PIN_RE = re.compile(r"^\d{8}$")

HEXDUMP_INLINE_RE = re.compile(r'hexdump(?:_key)?\(len=(\d+)\):\s*(.+)$')
HEXDUMP_ASCII_HEADER_RE = re.compile(r'hexdump_ascii(?:_key)?\(len=(\d+)\):\s*$')
HEXLINE_RE = re.compile(r'^\s*[0-9A-Fa-f]+:\s+((?:[0-9A-Fa-f]{2}\s+){1,16})')


class CredentialReader(threading.Thread):
    def __init__(self, proc):
        super().__init__(daemon=True)
        self.proc = proc
        self.psk = None
        self._collecting = False
        self._need_len = 0
        self._hexparts = []

    def run(self):
        for raw in self.proc.stdout:
            line = raw.rstrip('\n')
            if self.psk:
                continue
            if 'Network Key' in line and 'hexdump' in line:
                m = HEXDUMP_INLINE_RE.search(line)
                if m:
                    self._finish(re.sub(r'\s+', '', m.group(2)), int(m.group(1)))
                    continue
                m2 = HEXDUMP_ASCII_HEADER_RE.search(line)
                if m2:
                    self._collecting = True
                    self._need_len = int(m2.group(1))
                    self._hexparts = []
                continue
            if self._collecting:
                m3 = HEXLINE_RE.match(line)
                if m3:
                    self._hexparts.append(re.sub(r'\s+', '', m3.group(1)))
                    if sum(len(p) for p in self._hexparts) // 2 >= self._need_len:
                        self._finish(''.join(self._hexparts), self._need_len)
                else:
                    self._collecting = False

    def _finish(self, hexstr, need_len):
        hexstr = hexstr[:need_len * 2]
        try:
            self.psk = bytes.fromhex(hexstr).decode('utf-8', errors='replace')
        except ValueError:
            self.psk = None
        self._collecting = False


def run_cli(cmd, check=True):
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(f"[!] 命令失败: {' '.join(cmd)}\n{result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout.strip()


def parse_status(text):
    info = {}
    for line in text.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            info[k] = v
    return info


def kill_old_supplicant(iface, timeout=5):
    """确保上一次遗留的 wpa_supplicant 进程真正退出，而不是发完信号就当作完事"""
    pattern = f"wpa_supplicant.*-i {iface}"
    subprocess.run(["pkill", "-f", pattern], capture_output=True)

    waited = 0.0
    while waited < timeout:
        result = subprocess.run(["pgrep", "-f", pattern], capture_output=True)
        if result.returncode != 0:   # pgrep 找不到匹配进程时返回非0，说明已经死透
            return
        time.sleep(0.3)
        waited += 0.3

    # 超时还没死，强制 kill -9 兜底
    print("[*] 旧进程未及时退出，发送 SIGKILL 强制结束...")
    subprocess.run(["pkill", "-9", "-f", pattern], capture_output=True)
    time.sleep(0.5)


def clear_stale_socket(ctrl_dir, iface):
    """无论旧进程是否清理干净，主动清掉可能残留的 socket 文件，避免假活"""
    sock_path = os.path.join(ctrl_dir, iface)
    if os.path.exists(sock_path):
        try:
            os.remove(sock_path)
            print(f"[*] 已清除残留的控制接口文件: {sock_path}")
        except Exception as e:
            print(f"[!] 清除残留文件失败: {e}", file=sys.stderr)


def wait_ctrl_ready(iface, proc, timeout=15):
    """
    不再只检查 socket 文件是否存在，而是真实发起一次 wpa_cli ping 往返，
    并同时检测 wpa_supplicant 进程是否已提前退出（如驱动初始化失败）
    """
    waited = 0.0
    interval = 0.3
    while waited < timeout:
        if proc.poll() is not None:
            return False, "wpa_supplicant 进程已提前退出（可能是网卡被占用或驱动初始化失败，检查 dmesg / logcat）"
        result = subprocess.run(["wpa_cli", "-i", iface, "ping"], capture_output=True, text=True)
        if result.returncode == 0 and "PONG" in result.stdout:
            return True, ""
        time.sleep(interval)
        waited += interval
    return False, "等待控制接口就绪超时"


def main():
    parser = argparse.ArgumentParser(description="WPS PBC/PIN 连接工具(仅限自有/授权设备)")
    parser.add_argument("-k", "--iface", required=True)
    parser.add_argument("-mac", help="目标 MAC，--pbc 模式可省略(=any)，-pin 模式必填")

    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--pbc", action="store_true")
    mode.add_argument("-pin", help="已知8位WPS PIN，免按钮")

    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    iface = args.iface
    ctrl_dir = "/run/wpa_supplicant"

    if args.mac and not MAC_RE.match(args.mac):
        print("[!] -mac 格式错误"); sys.exit(1)
    if args.pin:
        if not PIN_RE.match(args.pin):
            print("[!] -pin 必须是8位数字"); sys.exit(1)
        if not args.mac:
            print("[!] -pin 模式必须指定 -mac"); sys.exit(1)

    print(f"[*] 接口: {iface}")

    # 1) 确保旧进程真正退出
    kill_old_supplicant(iface)
    # 2) 主动清理可能残留的控制socket
    os.makedirs(ctrl_dir, exist_ok=True)
    clear_stale_socket(ctrl_dir, iface)

    run_cli(["ip", "link", "set", iface, "up"])

    cmd = ["wpa_supplicant", "-K", "-dd", "-Dnl80211", "-i", iface, "-C", ctrl_dir]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, bufsize=1)

    # 3) 真实ping测试，而不是只看文件存不存在
    ready, err = wait_ctrl_ready(iface, proc)
    if not ready:
        print(f"[!] {err}")
        proc.terminate()
        sys.exit(1)

    reader = CredentialReader(proc)
    reader.start()

    if args.pin:
        print(f"[*] 使用已知 PIN 与目标 {args.mac} 进行 WPS 握手...")
        run_cli(["wpa_cli", "-i", iface, "wps_pin", args.mac, args.pin])
    else:
        if args.mac:
            print(f"[*] 已启动 WPS PBC，目标 BSSID={args.mac}，请按下该路由器的 WPS 按钮...")
            run_cli(["wpa_cli", "-i", iface, "wps_pbc", args.mac])
        else:
            print("[*] 已启动 WPS PBC(any)，请在限定时间内按下路由器的 WPS 按钮...")
            run_cli(["wpa_cli", "-i", iface, "wps_pbc"])

    state, elapsed, interval = "", 0, 2
    while elapsed < args.timeout:
        status_text = run_cli(["wpa_cli", "-i", iface, "status"], check=False)
        info = parse_status(status_text)
        state = info.get("wpa_state", "")
        if state == "COMPLETED":
            break
        time.sleep(interval)
        elapsed += interval

    if state != "COMPLETED":
        print("[!] 连接超时或失败")
        proc.terminate()
        sys.exit(1)

    for _ in range(10):
        if reader.psk:
            break
        time.sleep(0.3)

    status_text = run_cli(["wpa_cli", "-i", iface, "status"])
    info = parse_status(status_text)
    bssid = info.get("bssid", "")
    essid = info.get("ssid", "")

    print("\n[*] 连接成功：")
    print(f"BSSID: {bssid}")
    print(f"ESSID: {essid}")
    print(f"WPA密码(PSK): {reader.psk if reader.psk else '(未捕获到)'}")
    print("[*] wpa_supplicant 仍在后台保持连接，如需断开请手动 pkill wpa_supplicant")


if __name__ == "__main__":
    if os.geteuid() != 0:
        print("[!] 请使用 root 权限运行")
        sys.exit(1)
    main()