#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WPA2-PSK 四次握手实验脚本（针对物理网卡 wlan0，目标 SSID: Cindx）
适用于 Linux 系统，需要 root 权限和 scapy。
"""

from __future__ import annotations
import argparse
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import subprocess
import sys
import threading
import time
from typing import Optional

try:
    from scapy.all import (
        Dot11,
        Dot11AssoReq,
        Dot11AssoResp,
        Dot11Auth,
        Dot11Deauth,
        Dot11Disas,
        Dot11Elt,
        LLC,
        RadioTap,
        Raw,
        SNAP,
        conf,
        raw,
        sendp,
        sniff,
    )
    from scapy.layers.eap import EAPOL, EAPOL_KEY
except ImportError as exc:
    raise SystemExit(
        "缺少 Scapy。请安装：python3 -m pip install scapy"
    ) from exc

# 固定目标 SSID
TARGET_SSID = "Cindx"

RSN_EID = 48
ETH_P_EAPOL = 0x888E

RSN_BODY_WPA2_PSK_CCMP = bytes.fromhex(
    "0100"
    "000fac04"
    "0100"
    "000fac04"
    "0100"
    "000fac02"
    "0000"
)

def run_cmd(*args: str) -> subprocess.CompletedProcess[str]:
    """运行系统命令并捕获输出"""
    return subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

def require_root() -> None:
    if os.geteuid() != 0:
        raise SystemExit("请使用 root 权限运行。")

def interface_mac(iface: str) -> str:
    """获取网卡 MAC 地址"""
    path = Path("/sys/class/net") / iface / "address"
    try:
        return path.read_text(encoding="ascii").strip().lower()
    except OSError as exc:
        raise SystemExit(f"无法读取接口 {iface} 的 MAC 地址。") from exc

def pbkdf2_pmk(ssid: str, password: str) -> bytes:
    """根据 SSID 和密码生成 PMK"""
    pwd = password.encode("utf-8")
    ssid_bytes = ssid.encode("utf-8")
    if not (8 <= len(pwd) <= 63):
        raise SystemExit("WPA2-PSK 口令按 UTF-8 编码后必须为 8～63 字节。")
    if not (1 <= len(ssid_bytes) <= 32):
        raise SystemExit("SSID 按 UTF-8 编码后必须为 1～32 字节。")
    return hashlib.pbkdf2_hmac("sha1", pwd, ssid_bytes, 4096, 32)

def mac_bytes(mac: str) -> bytes:
    """将 MAC 地址字符串转换为 6 字节的 bytes"""
    try:
        value = bytes.fromhex(mac.replace(":", ""))
    except ValueError as exc:
        raise SystemExit(f"非法 MAC 地址：{mac}") from exc
    if len(value) != 6:
        raise SystemExit(f"非法 MAC 地址：{mac}")
    return value

def prf_sha1_512(key: bytes, label: bytes, data: bytes) -> bytes:
    """WPA PRF-512 函数"""
    out = bytearray()
    counter = 0
    while len(out) < 64:
        out.extend(
            hmac.new(key, label + b"\x00" + data + bytes([counter]), hashlib.sha1).digest()
        )
        counter += 1
    return bytes(out[:64])

def derive_ptk(
    pmk: bytes,
    ap_mac: str,
    sta_mac: str,
    anonce: bytes,
    snonce: bytes,
) -> bytes:
    """推导 PTK"""
    aa = mac_bytes(ap_mac)
    spa = mac_bytes(sta_mac)
    data = min(aa, spa) + max(aa, spa) + min(anonce, snonce) + max(anonce, snonce)
    return prf_sha1_512(pmk, b"Pairwise key expansion", data)

def mic_hmac_sha1_128(kck: bytes, eapol_bytes_with_zero_mic: bytes) -> bytes:
    """计算 EAPOL-Key MIC"""
    return hmac.new(kck, eapol_bytes_with_zero_mic, hashlib.sha1).digest()[:16]

def freq_to_channel(f: int) -> int:
    """频率转信道（简单映射）"""
    if f == 2484:
        return 14
    if 2412 <= f <= 2472:
        return (f - 2407) // 5
    if 4910 <= f <= 4980:
        return (f - 4000) // 5
    if 5000 <= f <= 5895:
        return (f - 5000) // 5
    if f == 5935:
        return 2
    if 5955 <= f <= 7115:
        return (f - 5950) // 5
    return 1

def scan_target_ap(iface: str, target_ssid: str) -> tuple[str, int, int]:
    """扫描目标 SSID，返回 (BSSID, freq, channel)"""
    print(f"[*] 扫描网络寻找 SSID：{target_ssid}")
    # 确保网卡已启用
    run_cmd("ip", "link", "set", iface, "up")
    time.sleep(2)

    res = run_cmd("iw", "dev", iface, "scan")
    if res.returncode != 0:
        # 某些驱动需要触发扫描
        run_cmd("iw", "dev", iface, "scan", "trigger")
        time.sleep(3)
        res = run_cmd("iw", "dev", iface, "scan", "dump")
        if res.returncode != 0:
            raise SystemExit(f"扫描失败：{res.stderr.strip()}")

    bss_blocks = res.stdout.split("BSS ")
    target_bssid = None
    target_freq = None
    target_channel = None

    for block in bss_blocks[1:]:
        lines = [line.strip() for line in block.splitlines()]
        bssid = lines[0].split("(")[0].strip().lower()
        freq = None
        ssid = None
        channel = None

        for line in lines:
            if line.startswith("freq:"):
                try:
                    freq = int(line.split(":")[1].strip())
                except ValueError:
                    pass
            elif line.startswith("SSID:"):
                ssid = line.split("SSID:", 1)[1].strip()
            elif "DS Parameter set: channel" in line:
                try:
                    channel = int(line.split()[-1])
                except ValueError:
                    pass

        if ssid == target_ssid and bssid and freq:
            target_bssid = bssid
            target_freq = freq
            target_channel = channel if channel else freq_to_channel(freq)
            break

    if not target_bssid or not target_freq:
        raise SystemExit(f"未找到目标 SSID：{target_ssid}")

    print(f"[+] 找到目标 AP：BSSID={target_bssid}, 频率={target_freq}MHz, 信道={target_channel}")
    return target_bssid, target_freq, target_channel

def kill_interfering_processes() -> None:
    """结束可能干扰的进程（NetworkManager、wpa_supplicant 等）"""
    for proc in ["wpa_supplicant", "NetworkManager", "dhclient", "dhcpcd"]:
        run_cmd("pkill", proc)
    time.sleep(1)

def set_monitor_mode(iface: str, freq: int, channel: int) -> None:
    """将网卡设置为 monitor 模式并锁定信道"""
    print("[*] 正在切换到 monitor 模式并锁定信道...")

    # 先结束冲突进程
    kill_interfering_processes()

    # 关闭网卡
    if run_cmd("ip", "link", "set", iface, "down").returncode != 0:
        raise SystemExit(f"无法关闭接口 {iface}")
    # 设置类型为 monitor
    res = run_cmd("iw", "dev", iface, "set", "type", "monitor")
    if res.returncode != 0:
        # 尝试使用 airmon-ng 风格
        # 某些系统可能需要先执行 iw dev wlan0 interface add wlan0mon type monitor
        raise SystemExit(f"无法将 {iface} 设置为 monitor 模式：{res.stderr.strip()}")
    # 启用网卡
    if run_cmd("ip", "link", "set", iface, "up").returncode != 0:
        raise SystemExit(f"无法启用接口 {iface}")

    # 锁定频率
    if run_cmd("iw", "dev", iface, "set", "freq", str(freq), "HT20").returncode != 0:
        if run_cmd("iw", "dev", iface, "set", "channel", str(channel), "HT20").returncode != 0:
            raise SystemExit("无法锁定信道")
    print(f"[+] monitor 模式已就绪，锁定频率 {freq} MHz / 信道 {channel}")

def next_seq(state: dict[str, int]) -> int:
    """生成下一个序列号"""
    state["seq"] = (state["seq"] + 1) & 0xFFF
    return state["seq"] << 4

def authenticate(iface: str, sta: str, bssid: str, seq_state: dict[str, int]) -> None:
    """802.11 开放系统认证"""
    req = (
        RadioTap()
        / Dot11(
            type=0,
            subtype=11,
            addr1=bssid,
            addr2=sta,
            addr3=bssid,
            SC=next_seq(seq_state),
        )
        / Dot11Auth(algo=0, seqnum=1, status=0)
    )
    print("[*] 发送 Open System Authentication request")
    sendp(req, iface=iface, verbose=False)

    def match(pkt) -> bool:
        return (
            pkt.haslayer(Dot11Auth)
            and pkt.haslayer(Dot11)
            and pkt[Dot11].addr1
            and pkt[Dot11].addr1.lower() == sta
            and pkt[Dot11].addr2
            and pkt[Dot11].addr2.lower() == bssid
            and int(pkt[Dot11Auth].seqnum) == 2
        )

    packets = sniff(iface=iface, timeout=2.0, lfilter=match, count=1, store=True)
    if not packets:
        raise SystemExit("未收到 Authentication response。")
    status = int(packets[0][Dot11Auth].status)
    if status != 0:
        raise SystemExit(f"Authentication 被拒绝，status={status}")
    print("[+] 802.11 Authentication 成功")

def associate(
    iface: str,
    sta: str,
    bssid: str,
    ssid: str,
    seq_state: dict[str, int],
) -> bytes:
    """802.11 关联请求，返回 RSN IE 字节串"""
    rsn_ie = bytes([RSN_EID, len(RSN_BODY_WPA2_PSK_CCMP)]) + RSN_BODY_WPA2_PSK_CCMP

    req = (
        RadioTap()
        / Dot11(
            type=0,
            subtype=0,
            addr1=bssid,
            addr2=sta,
            addr3=bssid,
            SC=next_seq(seq_state),
        )
        / Dot11AssoReq(cap=0x0431, listen_interval=10)
        / Dot11Elt(ID=0, info=ssid.encode("utf-8"))
        / Dot11Elt(ID=1, info=bytes.fromhex("82848b960c121824"))
        / Dot11Elt(ID=50, info=bytes.fromhex("3048606c"))
        / Dot11Elt(ID=RSN_EID, info=RSN_BODY_WPA2_PSK_CCMP)
    )
    print("[*] 发送 Association Request（WPA2-PSK/CCMP）")
    sendp(req, iface=iface, verbose=False)

    def match(pkt) -> bool:
        return (
            pkt.haslayer(Dot11AssoResp)
            and pkt.haslayer(Dot11)
            and pkt[Dot11].addr1
            and pkt[Dot11].addr1.lower() == sta
            and pkt[Dot11].addr2
            and pkt[Dot11].addr2.lower() == bssid
        )

    packets = sniff(iface=iface, timeout=2.0, lfilter=match, count=1, store=True)
    if not packets:
        raise SystemExit("未收到 Association Response。")
    status = int(packets[0][Dot11AssoResp].status)
    if status != 0:
        raise SystemExit(f"Association 被拒绝，status={status}")
    aid = int(packets[0][Dot11AssoResp].AID)
    print(f"[+] 802.11 Association 成功，AID={aid & 0x3FFF}")
    return rsn_ie

def build_m2_eapol(
    descriptor_type: int,
    descriptor_version: int,
    key_length: int,
    replay_counter: int,
    snonce: bytes,
    rsn_ie: bytes,
    kck: bytes,
    eapol_version: int,
) -> bytes:
    """构造 EAPOL-Key 消息 2/4"""
    if descriptor_version != 2:
        raise ValueError(
            f"本实验脚本只实现 descriptor version 2，收到 version={descriptor_version}"
        )

    key = EAPOL_KEY(
        key_descriptor_type=descriptor_type,
        key_descriptor_type_version=descriptor_version,
        key_type=1,
        install=0,
        key_ack=0,
        has_key_mic=1,
        secure=0,
        error=0,
        request=0,
        encrypted_key_data=0,
        key_length=key_length,
        key_replay_counter=replay_counter,
        key_nonce=snonce,
        key_iv=b"\x00" * 16,
        key_rsc=b"\x00" * 8,
        key_id=b"\x00" * 8,
        key_mic=b"\x00" * 16,
        key_data_length=len(rsn_ie),
        key_data=rsn_ie,
    )
    eapol = EAPOL(version=eapol_version, type=3) / key
    zero_mic_bytes = raw(eapol)
    key.key_mic = mic_hmac_sha1_128(kck, zero_mic_bytes)
    return raw(EAPOL(version=eapol_version, type=3) / key)

def wrap_m2_80211(
    bssid: str,
    sta: str,
    eapol_bytes: bytes,
    seq_state: dict[str, int],
):
    """将 EAPOL M2 封装为完整的 802.11 数据帧"""
    return (
        RadioTap()
        / Dot11(
            type=2,
            subtype=0,
            FCfield="to-DS",
            addr1=bssid,
            addr2=sta,
            addr3=bssid,
            SC=next_seq(seq_state),
        )
        / LLC(dsap=0xAA, ssap=0xAA, ctrl=3)
        / SNAP(OUI=0, code=ETH_P_EAPOL)
        / Raw(load=eapol_bytes)
    )

class Experiment:
    """四次握手实验控制器"""

    def __init__(
        self,
        iface: str,
        ssid: str,
        password: str,
        sta: str,
        bssid: str,
        rsn_ie: bytes,
        repeat_interval: float,
        seq_state: dict[str, int],
    ) -> None:
        self.iface = iface
        self.ssid = ssid
        self.password = password
        self.sta = sta
        self.bssid = bssid
        self.rsn_ie = rsn_ie
        self.repeat_interval = repeat_interval
        self.seq_state = seq_state

        self.pmk = pbkdf2_pmk(ssid, password)
        self.snonce = secrets.token_bytes(32)

        self.lock = threading.Lock()
        self.latest_m2 = None
        self.latest_rc: Optional[int] = None
        self.stop_repeating = threading.Event()
        self.finished = threading.Event()
        self.m3_seen = False
        self.m1_count = 0
        self.m2_count = 0

    def send_latest_m2(self, reason: str) -> None:
        with self.lock:
            frame = self.latest_m2
            rc = self.latest_rc
        if frame is None:
            return
        sendp(frame, iface=self.iface, verbose=False)
        self.m2_count += 1
        print(
            f"[TX] EAPOL-Key 2/4  rc={rc}  "
            f"发送次数={self.m2_count}  原因={reason}"
        )

    def repeat_worker(self) -> None:
        while not self.stop_repeating.wait(self.repeat_interval):
            self.send_latest_m2("定时重复")

    def handle_m1(self, pkt) -> None:
        key = pkt[EAPOL_KEY]
        self.m1_count += 1

        descriptor_type = int(key.key_descriptor_type)
        descriptor_version = int(key.key_descriptor_type_version)
        replay_counter = int(key.key_replay_counter)
        key_length = int(key.key_length or 16)
        anonce = bytes(key.key_nonce)
        eapol_version = int(pkt[EAPOL].version)

        if len(anonce) != 32:
            print(f"[!] 忽略异常 M1：ANonce 长度={len(anonce)}")
            return

        ptk = derive_ptk(
            self.pmk,
            self.bssid,
            self.sta,
            anonce,
            self.snonce,
        )
        kck = ptk[:16]

        try:
            eapol_m2 = build_m2_eapol(
                descriptor_type=descriptor_type,
                descriptor_version=descriptor_version,
                key_length=key_length,
                replay_counter=replay_counter,
                snonce=self.snonce,
                rsn_ie=self.rsn_ie,
                kck=kck,
                eapol_version=eapol_version,
            )
        except ValueError as exc:
            print(f"[!] {exc}")
            return

        frame = wrap_m2_80211(
            self.bssid,
            self.sta,
            eapol_m2,
            self.seq_state,
        )
        with self.lock:
            self.latest_m2 = frame
            self.latest_rc = replay_counter

        print(
            f"[RX] EAPOL-Key 1/4  rc={replay_counter}  "
            f"M1次数={self.m1_count}"
        )
        self.send_latest_m2("响应 M1")

    def handle_m3(self, pkt) -> None:
        key = pkt[EAPOL_KEY]
        replay_counter = int(key.key_replay_counter)
        if not self.m3_seen:
            self.m3_seen = True
            self.stop_repeating.set()
            print(
                f"[RX] 收到 EAPOL-Key 3/4，rc={replay_counter}。"
                "M2 已被 AP 接受；按实验要求不发送 M4、不安装密钥。"
            )
        else:
            print(f"[RX] AP 重发 EAPOL-Key 3/4，rc={replay_counter}；继续忽略。")

    def handle_control(self, pkt) -> None:
        if pkt.haslayer(Dot11Deauth):
            reason = int(pkt[Dot11Deauth].reason)
            print(f"[RX] Deauthentication，reason={reason}")
            self.finished.set()
        elif pkt.haslayer(Dot11Disas):
            reason = int(pkt[Dot11Disas].reason)
            print(f"[RX] Disassociation，reason={reason}")
            self.finished.set()

    def packet_handler(self, pkt) -> None:
        if not pkt.haslayer(Dot11):
            return

        d = pkt[Dot11]
        addr1 = (d.addr1 or "").lower()
        addr2 = (d.addr2 or "").lower()

        if addr1 != self.sta or addr2 != self.bssid:
            return

        if pkt.haslayer(Dot11Deauth) or pkt.haslayer(Dot11Disas):
            self.handle_control(pkt)
            return

        if not pkt.haslayer(EAPOL) or not pkt.haslayer(EAPOL_KEY):
            return

        key = pkt[EAPOL_KEY]
        pairwise = int(key.key_type) == 1
        ack = int(key.key_ack) == 1
        mic = int(key.has_key_mic) == 1
        install = int(key.install) == 1

        if pairwise and ack and not mic and not install:
            self.handle_m1(pkt)
        elif pairwise and ack and mic and install:
            self.handle_m3(pkt)

    def run(self) -> None:
        worker = threading.Thread(target=self.repeat_worker, daemon=True)
        worker.start()

        print(
            "[*] 开始监听四次握手。收到 M1 后立即发 M2；"
            f"未收到 M3 时每 {self.repeat_interval:.3f}s 重复最近的 M2。"
        )
        try:
            sniff(
                iface=self.iface,
                prn=self.packet_handler,
                store=False,
                stop_filter=lambda _pkt: self.finished.is_set(),
            )
        except KeyboardInterrupt:
            print("\n[*] 用户终止实验。")
        finally:
            self.stop_repeating.set()
            worker.join(timeout=2.0)

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=f"WPA2-PSK 四次握手实验（目标: {TARGET_SSID}）"
    )
    parser.add_argument("password", help="WPA2-PSK 口令")
    parser.add_argument("--iface", default="wlan0", help="网络接口，默认 wlan0")
    parser.add_argument(
        "--repeat-interval",
        type=float,
        default=1.0,
        help="未收到 M3 时重复最近 M2 的间隔，必须 >= 0.1 秒",
    )
    return parser.parse_args()

def main() -> int:
    args = parse_args()
    require_root()

    if args.repeat_interval < 0.1:
        raise SystemExit("--repeat-interval 不能低于 0.1 秒。")

    conf.verb = 0

    sta = interface_mac(args.iface)
    print(f"[*] STA MAC：{sta}")

    # 固定目标 SSID
    bssid, freq, channel = scan_target_ap(args.iface, TARGET_SSID)

    set_monitor_mode(args.iface, freq, channel)

    seq_state = {"seq": secrets.randbelow(4096)}
    authenticate(args.iface, sta, bssid, seq_state)
    rsn_ie = associate(args.iface, sta, bssid, TARGET_SSID, seq_state)

    experiment = Experiment(
        iface=args.iface,
        ssid=TARGET_SSID,
        password=args.password,
        sta=sta,
        bssid=bssid,
        rsn_ie=rsn_ie,
        repeat_interval=args.repeat_interval,
        seq_state=seq_state,
    )
    experiment.run()
    return 0

if __name__ == "__main__":
    sys.exit(main())