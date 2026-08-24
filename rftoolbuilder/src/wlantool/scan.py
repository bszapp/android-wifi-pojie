#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Scan nearby Wi-Fi networks through Linux nl80211 and emit JSON Lines."""

import argparse
import codecs
import errno
import fcntl
import json
import os
import queue
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from typing import Callable, Dict, Iterable, List, Optional, Tuple


NETLINK_GENERIC = 16
SOL_NETLINK = 270
NETLINK_ADD_MEMBERSHIP = 1

NLMSG_NOOP = 1
NLMSG_ERROR = 2
NLMSG_DONE = 3
NLM_F_REQUEST = 0x01
NLM_F_ACK = 0x04
NLM_F_DUMP = 0x300
NLM_F_DUMP_INTR = 0x10

GENL_ID_CTRL = 0x10
CTRL_CMD_GETFAMILY = 3
CTRL_ATTR_FAMILY_ID = 1
CTRL_ATTR_FAMILY_NAME = 2
CTRL_ATTR_MCAST_GROUPS = 7
CTRL_ATTR_MCAST_GRP_NAME = 1
CTRL_ATTR_MCAST_GRP_ID = 2

NL80211_CMD_GET_SCAN = 32
NL80211_CMD_TRIGGER_SCAN = 33
NL80211_CMD_NEW_SCAN_RESULTS = 34
NL80211_CMD_SCAN_ABORTED = 35

NL80211_ATTR_IFINDEX = 3
NL80211_ATTR_SCAN_SSIDS = 45
NL80211_ATTR_GENERATION = 46
NL80211_ATTR_BSS = 47

NL80211_BSS_BSSID = 1
NL80211_BSS_FREQUENCY = 2
NL80211_BSS_CAPABILITY = 5
NL80211_BSS_INFORMATION_ELEMENTS = 6
NL80211_BSS_SIGNAL_MBM = 7
NL80211_BSS_SIGNAL_UNSPEC = 8
NL80211_BSS_SEEN_MS_AGO = 10
NL80211_BSS_BEACON_IES = 11
NL80211_BSS_LAST_SEEN_BOOTTIME = 15

NLA_F_NESTED = 1 << 15
NLA_TYPE_MASK = 0x3FFF

WLAN_CAPABILITY_PRIVACY = 1 << 4
WLAN_CAPABILITY_ESS = 1 << 0
WLAN_CAPABILITY_IBSS = 1 << 1
WLAN_EID_SSID = 0
WLAN_EID_RSN = 48
WLAN_EID_VENDOR_SPECIFIC = 221
WPA_VENDOR_TYPE = b"\x00\x50\xf2\x01"
WPS_VENDOR_TYPE = b"\x00\x50\xf2\x04"

WPS_ATTR_DEVICE_NAME = 0x1011
WPS_ATTR_MODEL_NAME = 0x1023
WPS_ATTR_MODEL_NUMBER = 0x1024
WPS_ATTR_AP_SETUP_LOCKED = 0x1057

SCAN_TIMEOUT_SECONDS = 20.0
MIN_SCAN_DURATION_SECONDS = 3.0
NETLINK_TIMEOUT_SECONDS = 5.0
SCAN_REFRESH_SECONDS = 0.25
FRESHNESS_TOLERANCE_NS = 2_000_000
SIOCGIFFLAGS = 0x8913
IFF_UP = 0x1

DISABLED_ERRNOS = {
    errno.ENETDOWN,
    errno.ENODEV,
    errno.ENXIO,
}


class ScanError(RuntimeError):
    pass


class ScanTooFrequent(ScanError):
    pass


class InterfaceDisabled(ScanError):
    pass


MAC_ADDRESS_RE = re.compile(r"^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
WPS_PIN_RE = re.compile(r"^\d{8}$")


class SinglePinWpsConnector:
    """Run one registrar-side WPS transaction with a caller-provided PIN."""

    def __init__(self, interface: str, bssid: str, pin: str, timeout: float):
        self.interface = interface
        self.bssid = bssid.upper()
        self.pin = pin
        self.timeout = timeout
        self.tempdir = None
        self.tempconf = None
        self.reply_socket_path = None
        self.reply_socket = None
        self.wpas = None
        self.output_queue = queue.Queue()
        self.last_power = "0"
        self.essid = ""
        self.password = ""
        self.status = ""
        self.failure_message = ""

    def log(self, content: str) -> None:
        emit_json({"type": "log", "content": content})

    def indicator_log(self, level: str, content: str) -> None:
        self.log("[{}] [{}] {}".format(level, self.last_power, content))

    def start_supplicant(self) -> None:
        self.tempdir = tempfile.mkdtemp(prefix="wlantool-wps-")
        with tempfile.NamedTemporaryFile(
            mode="w",
            suffix=".conf",
            delete=False,
        ) as config:
            config.write(
                "ctrl_interface={}\nctrl_interface_group=root\nupdate_config=1\n".format(
                    self.tempdir
                )
            )
            self.tempconf = config.name

        self.reply_socket_path = os.path.join(self.tempdir, "client.sock")
        self.reply_socket = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.reply_socket.bind(self.reply_socket_path)
        self.reply_socket.settimeout(5.0)

        self.log("[*] Running wpa_supplicant…")
        self.wpas = subprocess.Popen(
            [
                "wpa_supplicant",
                "-K",
                "-d",
                "-Dnl80211,wext,hostapd,wired",
                "-i{}".format(self.interface),
                "-c{}".format(self.tempconf),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            bufsize=1,
        )
        threading.Thread(target=self._read_output, daemon=True).start()

        control_path = os.path.join(self.tempdir, self.interface)
        deadline = time.monotonic() + 15.0
        while not os.path.exists(control_path):
            if self.wpas.poll() is not None:
                raise RuntimeError("wpa_supplicant returned an error")
            if time.monotonic() >= deadline:
                raise RuntimeError("等待 wpa_supplicant 控制接口超时")
            time.sleep(0.1)
        self.control_path = control_path

    def _read_output(self) -> None:
        try:
            for raw in self.wpas.stdout:
                self.output_queue.put(raw.rstrip("\n"))
        finally:
            self.output_queue.put(None)

    def send_and_receive(self, command: str) -> str:
        self.reply_socket.sendto(command.encode("utf-8"), self.control_path)
        response, _address = self.reply_socket.recvfrom(4096)
        return response.decode("utf-8", errors="replace").strip()

    def send_only(self, command: str) -> None:
        self.reply_socket.sendto(command.encode("utf-8"), self.control_path)

    @staticmethod
    def extract_hex(line: str) -> str:
        parts = line.split(":", 3)
        if len(parts) < 3:
            return ""
        return parts[2].replace(" ", "").upper()

    @staticmethod
    def decode_debug_ssid(line: str) -> str:
        if "SSID" not in line:
            return ""
        escaped = "'".join(line.split("'")[1:-1])
        if not escaped:
            return ""
        return codecs.decode(escaped, "unicode-escape").encode("latin1").decode(
            "utf-8",
            errors="replace",
        )

    def handle_output(self, line: str) -> None:
        self.log(line)
        if line.startswith("WPS: "):
            if "Building Message M" in line:
                raw_number = line.split("Building Message M", 1)[1].replace("D", "")
                number = int(raw_number.split()[0])
                self.indicator_log("*", "Sending WPS Message M{}…".format(number))
            elif "Received M" in line:
                number = int(line.split("Received M", 1)[1].split()[0])
                self.indicator_log("*", "Received WPS Message M{}".format(number))
                if number == 5:
                    self.log("[+] The first half of the PIN is valid")
            elif "Received WSC_NACK" in line:
                self.status = "failed"
                self.failure_message = "wrong PIN code"
                self.indicator_log("*", "Received WSC NACK")
                self.log("[-] Error: wrong PIN code")
            elif "Enrollee Nonce" in line and "hexdump" in line:
                self.log("[P] E-Nonce: {}".format(self.extract_hex(line)))
            elif "DH own Public Key" in line and "hexdump" in line:
                self.log("[P] PKR: {}".format(self.extract_hex(line)))
            elif "DH peer Public Key" in line and "hexdump" in line:
                self.log("[P] PKE: {}".format(self.extract_hex(line)))
            elif "AuthKey" in line and "hexdump" in line:
                self.log("[P] AuthKey: {}".format(self.extract_hex(line)))
            elif "E-Hash1" in line and "hexdump" in line:
                self.log("[P] E-Hash1: {}".format(self.extract_hex(line)))
            elif "E-Hash2" in line and "hexdump" in line:
                self.log("[P] E-Hash2: {}".format(self.extract_hex(line)))
            elif "Network Key" in line and "hexdump" in line:
                encoded = self.extract_hex(line)
                try:
                    self.password = bytes.fromhex(encoded).decode(
                        "utf-8",
                        errors="replace",
                    )
                except ValueError:
                    self.password = ""
                if self.password:
                    self.status = "success"
        elif ": State: " in line and "-> SCANNING" in line:
            self.status = "scanning"
            self.indicator_log("*", "Scanning…")
        elif "WPS-FAIL" in line:
            self.status = "failed"
            self.failure_message = "wpa_supplicant returned WPS-FAIL"
            self.log("[-] wpa_supplicant returned WPS-FAIL")
        elif "Trying to authenticate with" in line:
            self.status = "authenticating"
            decoded = self.decode_debug_ssid(line)
            if decoded:
                self.essid = decoded
            self.indicator_log("*", "Authenticating…")
        elif "Authentication response" in line:
            self.indicator_log("*", "Authenticated")
        elif "Trying to associate with" in line:
            self.status = "associating"
            decoded = self.decode_debug_ssid(line)
            if decoded:
                self.essid = decoded
            self.indicator_log("*", "Associating with AP…")
        elif "Associated with" in line and self.interface in line:
            associated_bssid = line.split()[-1].upper()
            if self.essid:
                self.indicator_log(
                    "+",
                    "Associated with {} (ESSID: {})".format(
                        associated_bssid,
                        self.essid,
                    ),
                )
            else:
                self.indicator_log("+", "Associated with {}".format(associated_bssid))
        elif "EAPOL: txStart" in line:
            self.status = "eapol_start"
            self.indicator_log("*", "Sending EAPOL Start…")
        elif "EAP entering state IDENTITY" in line:
            self.indicator_log("*", "Received Identity Request")
        elif "using real identity" in line:
            self.indicator_log("*", "Sending Identity Response…")
        elif self.bssid.lower() in line.lower() and "level=" in line:
            self.last_power = line.split("level=", 1)[1].split()[0]
            if "noise=" in line:
                noise = line.split("noise=", 1)[1].split()[0]
                self.log(
                    "[i] Current signal: {}, noise: {}".format(
                        self.last_power,
                        noise,
                    )
                )
            else:
                self.log("[i] Current signal: {}".format(self.last_power))

    def run(self) -> bool:
        try:
            self.start_supplicant()
            self.log("[*] Trying PIN '{}'…".format(self.pin))
            command = "WPS_REG {} {}".format(self.bssid, self.pin)
            response = self.send_and_receive(command)
            if "OK" not in response:
                message = (
                    "wpa_supplicant 未启用 WPS 协议支持"
                    if response == "UNKNOWN COMMAND"
                    else "WPS_REG 返回 {}".format(response or "空响应")
                )
                emit_json({"type": "failure", "message": message})
                return False

            deadline = time.monotonic() + self.timeout
            while time.monotonic() < deadline:
                remaining = max(0.05, deadline - time.monotonic())
                try:
                    line = self.output_queue.get(timeout=min(0.5, remaining))
                except queue.Empty:
                    if self.wpas.poll() is not None:
                        emit_json(
                            {
                                "type": "failure",
                                "message": "wpa_supplicant 进程已退出",
                            }
                        )
                        return False
                    continue
                if line is None:
                    emit_json(
                        {
                            "type": "failure",
                            "message": "wpa_supplicant 输出已结束",
                        }
                    )
                    return False
                self.handle_output(line)
                if self.status == "success":
                    self.log("[+] WPS PIN: '{}'".format(self.pin))
                    self.log("[+] WPA PSK: '{}'".format(self.password))
                    self.log("[+] AP SSID: '{}'".format(self.essid))
                    emit_json(
                        {
                            "type": "success",
                            "ssid": self.essid,
                            "password": self.password,
                            "mac": self.bssid,
                        }
                    )
                    return True
                if self.status == "failed":
                    emit_json(
                        {
                            "type": "failure",
                            "message": self.failure_message or "WPS transaction failed",
                        }
                    )
                    return False

            emit_json({"type": "failure", "message": "WPS 单 PIN 连接超时"})
            return False
        except Exception as error:
            emit_json({"type": "failure", "message": str(error)})
            return False
        finally:
            self.cleanup()

    def cleanup(self) -> None:
        if self.reply_socket is not None:
            if hasattr(self, "control_path"):
                try:
                    self.send_only("WPS_CANCEL")
                except (OSError, AttributeError):
                    pass
            self.reply_socket.close()
        if self.wpas is not None and self.wpas.poll() is None:
            self.wpas.terminate()
            try:
                self.wpas.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                self.wpas.kill()
        if self.tempdir is not None:
            shutil.rmtree(self.tempdir, ignore_errors=True)
        if self.tempconf is not None:
            try:
                os.remove(self.tempconf)
            except FileNotFoundError:
                pass


def emit_json(payload: dict) -> None:
    print(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        flush=True,
    )


def align4(length: int) -> int:
    return (length + 3) & ~3


def pack_attr(attr_type: int, payload: bytes, nested: bool = False) -> bytes:
    encoded_type = attr_type | (NLA_F_NESTED if nested else 0)
    length = 4 + len(payload)
    return (
        struct.pack("=HH", length, encoded_type)
        + payload
        + b"\x00" * (align4(length) - length)
    )


def pack_u32_attr(attr_type: int, value: int) -> bytes:
    return pack_attr(attr_type, struct.pack("=I", value))


def parse_attrs(payload: bytes) -> Dict[int, List[bytes]]:
    attrs: Dict[int, List[bytes]] = {}
    offset = 0
    while offset + 4 <= len(payload):
        length, raw_type = struct.unpack_from("=HH", payload, offset)
        if length < 4 or offset + length > len(payload):
            break
        attr_type = raw_type & NLA_TYPE_MASK
        attrs.setdefault(attr_type, []).append(payload[offset + 4:offset + length])
        offset += align4(length)
    return attrs


def first_attr(attrs: Dict[int, List[bytes]], attr_type: int) -> Optional[bytes]:
    values = attrs.get(attr_type)
    return values[0] if values else None


def iter_netlink_messages(data: bytes) -> Iterable[Tuple[int, int, int, bytes]]:
    offset = 0
    while offset + 16 <= len(data):
        length, message_type, flags, sequence, _pid = struct.unpack_from(
            "=IHHII", data, offset
        )
        if length < 16 or offset + length > len(data):
            break
        yield message_type, flags, sequence, data[offset + 16:offset + length]
        offset += align4(length)


class Nl80211Scanner:
    def __init__(self, interface: str):
        self.interface = interface
        try:
            self.ifindex = socket.if_nametoindex(interface)
        except OSError as error:
            raise InterfaceDisabled(
                f"无线接口 {interface} 不存在或已被移除: {error}"
            ) from error
        self._ensure_interface_up()
        self.sequence = int(time.monotonic() * 1_000_000_000) & 0xFFFFFFFF or 1
        self.command_socket = self._open_socket(NETLINK_TIMEOUT_SECONDS)
        self.family_id, self.scan_group_id = self._resolve_nl80211()

    def _ensure_interface_up(self) -> None:
        ifname = self.interface.encode("utf-8")
        if len(ifname) > 15:
            raise InterfaceDisabled(
                f"无线接口名称过长，无法读取状态: {self.interface}"
            )
        request = struct.pack("256s", ifname)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as control_socket:
            try:
                response = fcntl.ioctl(
                    control_socket.fileno(),
                    SIOCGIFFLAGS,
                    request,
                )
            except OSError as error:
                raise InterfaceDisabled(
                    f"无法读取无线接口 {self.interface} 的链路状态: {error}"
                ) from error
        flags = struct.unpack_from("H", response, 16)[0]
        if not flags & IFF_UP:
            raise InterfaceDisabled(
                f"无线接口 {self.interface} 当前为 DOWN，IFF_UP 标志未设置"
            )

    @staticmethod
    def _open_socket(timeout: float) -> socket.socket:
        sock = socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, NETLINK_GENERIC)
        sock.bind((0, 0))
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
        sock.settimeout(timeout)
        return sock

    def close(self) -> None:
        self.command_socket.close()

    def _next_sequence(self) -> int:
        self.sequence = (self.sequence + 1) & 0xFFFFFFFF
        if self.sequence == 0:
            self.sequence = 1
        return self.sequence

    @staticmethod
    def _build_message(
        sock: socket.socket,
        family_id: int,
        command: int,
        flags: int,
        sequence: int,
        attrs: bytes,
    ) -> bytes:
        version = 1 if family_id == GENL_ID_CTRL else 0
        generic_header = struct.pack("=BBH", command, version, 0)
        length = 16 + len(generic_header) + len(attrs)
        netlink_header = struct.pack(
            "=IHHII",
            length,
            family_id,
            flags,
            sequence,
            sock.getsockname()[0],
        )
        return netlink_header + generic_header + attrs

    def _send(
        self,
        sock: socket.socket,
        family_id: int,
        command: int,
        flags: int,
        attrs: bytes,
    ) -> int:
        sequence = self._next_sequence()
        message = self._build_message(
            sock, family_id, command, flags, sequence, attrs
        )
        sock.sendto(message, (0, 0))
        return sequence

    @staticmethod
    def _raise_netlink_error(payload: bytes) -> None:
        if len(payload) < 4:
            raise ScanError("内核返回了损坏的 Netlink 错误消息")
        error_code = struct.unpack_from("=i", payload)[0]
        if error_code:
            code = -error_code
            raise OSError(code, os.strerror(code))

    def _receive_single(self, sock: socket.socket, sequence: int) -> bytes:
        while True:
            try:
                data = sock.recv(1024 * 1024)
            except socket.timeout as error:
                raise ScanError("等待 Netlink 响应超时") from error
            for message_type, _flags, message_sequence, payload in iter_netlink_messages(data):
                if message_sequence != sequence:
                    continue
                if message_type == NLMSG_ERROR:
                    self._raise_netlink_error(payload)
                    continue
                if message_type in (NLMSG_NOOP, NLMSG_DONE):
                    continue
                return payload

    def _receive_ack(self, sock: socket.socket, sequence: int) -> None:
        while True:
            try:
                data = sock.recv(1024 * 1024)
            except socket.timeout as error:
                raise ScanError("等待扫描触发确认超时") from error
            for message_type, _flags, message_sequence, payload in iter_netlink_messages(data):
                if message_sequence != sequence:
                    continue
                if message_type == NLMSG_ERROR:
                    self._raise_netlink_error(payload)
                    return

    def _resolve_nl80211(self) -> Tuple[int, int]:
        attrs = pack_attr(CTRL_ATTR_FAMILY_NAME, b"nl80211\x00")
        sequence = self._send(
            self.command_socket,
            GENL_ID_CTRL,
            CTRL_CMD_GETFAMILY,
            NLM_F_REQUEST,
            attrs,
        )
        payload = self._receive_single(self.command_socket, sequence)
        if len(payload) < 4:
            raise ScanError("nl80211 family 响应不完整")
        family_attrs = parse_attrs(payload[4:])
        family_data = first_attr(family_attrs, CTRL_ATTR_FAMILY_ID)
        groups_data = first_attr(family_attrs, CTRL_ATTR_MCAST_GROUPS)
        if family_data is None or len(family_data) < 2:
            raise ScanError("内核没有返回 nl80211 family id")
        family_id = struct.unpack_from("=H", family_data)[0]
        scan_group_id = 0
        if groups_data is not None:
            for group_entries in parse_attrs(groups_data).values():
                for group_entry in group_entries:
                    group = parse_attrs(group_entry)
                    name_data = first_attr(group, CTRL_ATTR_MCAST_GRP_NAME)
                    id_data = first_attr(group, CTRL_ATTR_MCAST_GRP_ID)
                    if name_data is None or id_data is None or len(id_data) < 4:
                        continue
                    name = name_data.rstrip(b"\x00").decode("ascii", errors="replace")
                    if name == "scan":
                        scan_group_id = struct.unpack_from("=I", id_data)[0]
                        break
        if not scan_group_id:
            raise ScanError("内核没有提供 nl80211 scan 事件组")
        return family_id, scan_group_id

    @staticmethod
    def _drain_socket(sock: socket.socket) -> None:
        sock.setblocking(False)
        try:
            while True:
                sock.recv(1024 * 1024)
        except BlockingIOError:
            pass
        finally:
            sock.settimeout(SCAN_TIMEOUT_SECONDS)

    def _poll_scan_event(self, event_socket: socket.socket, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            event_socket.settimeout(remaining)
            try:
                data = event_socket.recv(1024 * 1024)
            except socket.timeout:
                return False
            for message_type, _flags, _sequence, payload in iter_netlink_messages(data):
                if message_type != self.family_id or len(payload) < 4:
                    continue
                command = payload[0]
                if command not in (NL80211_CMD_NEW_SCAN_RESULTS, NL80211_CMD_SCAN_ABORTED):
                    continue
                attrs = parse_attrs(payload[4:])
                event_ifindex = first_attr(attrs, NL80211_ATTR_IFINDEX)
                if event_ifindex is not None and len(event_ifindex) >= 4:
                    if struct.unpack_from("=I", event_ifindex)[0] != self.ifindex:
                        continue
                if command == NL80211_CMD_SCAN_ABORTED:
                    raise ScanError(f"内核中止了 {self.interface} 扫描")
                return True

    def _trigger_scan(self) -> None:
        wildcard_ssid = pack_attr(1, b"")
        attrs = (
            pack_u32_attr(NL80211_ATTR_IFINDEX, self.ifindex)
            + pack_attr(NL80211_ATTR_SCAN_SSIDS, wildcard_ssid, nested=True)
        )
        sequence = self._send(
            self.command_socket,
            self.family_id,
            NL80211_CMD_TRIGGER_SCAN,
            NLM_F_REQUEST | NLM_F_ACK,
            attrs,
        )
        try:
            self._receive_ack(self.command_socket, sequence)
        except OSError as error:
            if error.errno == errno.EBUSY:
                raise ScanTooFrequent() from error
            if error.errno in DISABLED_ERRNOS:
                raise InterfaceDisabled(
                    f"无线接口 {self.interface} 无法扫描: {error}"
                ) from error
            raise

    def _dump_scan_once(self) -> Tuple[List[bytes], Optional[int]]:
        attrs = pack_u32_attr(NL80211_ATTR_IFINDEX, self.ifindex)
        sequence = self._send(
            self.command_socket,
            self.family_id,
            NL80211_CMD_GET_SCAN,
            NLM_F_REQUEST | NLM_F_DUMP,
            attrs,
        )
        entries: List[bytes] = []
        generations = set()
        interrupted = False
        while True:
            try:
                data = self.command_socket.recv(1024 * 1024)
            except socket.timeout as error:
                raise ScanError("读取 nl80211 扫描结果超时") from error
            done = False
            for message_type, flags, message_sequence, payload in iter_netlink_messages(data):
                if message_sequence != sequence:
                    continue
                interrupted = interrupted or bool(flags & NLM_F_DUMP_INTR)
                if message_type == NLMSG_ERROR:
                    self._raise_netlink_error(payload)
                    continue
                if message_type == NLMSG_DONE:
                    done = True
                    continue
                if message_type != self.family_id or len(payload) < 4:
                    continue
                attrs = parse_attrs(payload[4:])
                generation = first_attr(attrs, NL80211_ATTR_GENERATION)
                if generation is not None and len(generation) >= 4:
                    generations.add(struct.unpack_from("=I", generation)[0])
                entries.extend(attrs.get(NL80211_ATTR_BSS, []))
            if done:
                if interrupted or len(generations) > 1:
                    raise ScanError("扫描结果在读取期间发生变化")
                generation = next(iter(generations)) if generations else None
                return entries, generation

    def _dump_scan(self) -> Tuple[List[bytes], Optional[int]]:
        last_error: Optional[Exception] = None
        for _attempt in range(2):
            try:
                return self._dump_scan_once()
            except ScanError as error:
                last_error = error
        raise last_error or ScanError("无法读取扫描结果")

    @staticmethod
    def _boottime_ns() -> int:
        try:
            return int(time.clock_gettime(7) * 1_000_000_000)
        except (AttributeError, OSError):
            return int(time.monotonic() * 1_000_000_000)

    @classmethod
    def _observation_times(
        cls,
        entries: List[bytes],
        sampled_at_ns: int,
    ) -> Dict[bytes, int]:
        observations: Dict[bytes, int] = {}
        for payload in entries:
            attrs = parse_attrs(payload)
            bssid = first_attr(attrs, NL80211_BSS_BSSID)
            if bssid is None or len(bssid) != 6:
                continue
            last_seen = first_attr(attrs, NL80211_BSS_LAST_SEEN_BOOTTIME)
            if last_seen is not None and len(last_seen) >= 8:
                observations[bssid] = struct.unpack_from("=Q", last_seen)[0]
                continue
            seen_ms_ago = first_attr(attrs, NL80211_BSS_SEEN_MS_AGO)
            if seen_ms_ago is not None and len(seen_ms_ago) >= 4:
                age_ns = struct.unpack_from("=I", seen_ms_ago)[0] * 1_000_000
                observations[bssid] = sampled_at_ns - age_ns
        return observations

    @classmethod
    def _results_refreshed(
        cls,
        before_entries: List[bytes],
        before_generation: Optional[int],
        before_sample_ns: int,
        after_entries: List[bytes],
        after_generation: Optional[int],
        after_sample_ns: int,
    ) -> bool:
        if (
            before_generation is not None
            and after_generation is not None
            and before_generation != after_generation
        ):
            return True

        before = cls._observation_times(before_entries, before_sample_ns)
        after = cls._observation_times(after_entries, after_sample_ns)
        if not before_entries and after_entries:
            return True

        comparable = False
        for bssid, observed_at in after.items():
            previous = before.get(bssid)
            if previous is None:
                return True
            comparable = True
            if observed_at > previous + FRESHNESS_TOLERANCE_NS:
                return True

        has_generation_evidence = (
            before_generation is not None and after_generation is not None
        )
        if comparable or has_generation_evidence:
            return False

        # Some old drivers expose neither generation nor observation time.
        # Their result freshness cannot be established without guessing.
        return True

    @classmethod
    def _fresh_entries(
        cls,
        before_entries: List[bytes],
        before_generation: Optional[int],
        before_sample_ns: int,
        current_entries: List[bytes],
        current_generation: Optional[int],
        current_sample_ns: int,
    ) -> List[bytes]:
        before_times = cls._observation_times(before_entries, before_sample_ns)
        current_times = cls._observation_times(current_entries, current_sample_ns)
        before_bssids = set()
        for payload in before_entries:
            bssid = first_attr(parse_attrs(payload), NL80211_BSS_BSSID)
            if bssid is not None and len(bssid) == 6:
                before_bssids.add(bssid)

        fresh: List[bytes] = []
        for payload in current_entries:
            bssid = first_attr(parse_attrs(payload), NL80211_BSS_BSSID)
            if bssid is None or len(bssid) != 6:
                continue
            if bssid not in before_bssids:
                fresh.append(payload)
                continue
            previous = before_times.get(bssid)
            observed_at = current_times.get(bssid)
            if (
                previous is not None
                and observed_at is not None
                and observed_at > previous + FRESHNESS_TOLERANCE_NS
            ):
                fresh.append(payload)

        has_observation_times = bool(before_times) or bool(current_times)
        generation_changed = (
            before_generation is not None
            and current_generation is not None
            and before_generation != current_generation
        )
        if not fresh and generation_changed and not has_observation_times:
            return current_entries
        return fresh

    def scan(
        self,
        on_started: Callable[[], None],
        on_update: Callable[[List[bytes]], None],
    ) -> List[bytes]:
        event_socket = self._open_socket(SCAN_TIMEOUT_SECONDS)
        try:
            event_socket.setsockopt(
                SOL_NETLINK,
                NETLINK_ADD_MEMBERSHIP,
                struct.pack("=I", self.scan_group_id),
            )
            self._drain_socket(event_socket)
            before_entries, before_generation = self._dump_scan()
            before_sample_ns = self._boottime_ns()
            self._drain_socket(event_socket)
            self._trigger_scan()
            on_started()

            started_at = time.monotonic()
            deadline = started_at + SCAN_TIMEOUT_SECONDS
            next_refresh = started_at + SCAN_REFRESH_SECONDS
            scan_completed = False
            while True:
                now = time.monotonic()
                if now >= deadline:
                    raise ScanError(f"扫描 {self.interface} 超时")
                wait_time = min(next_refresh - now, deadline - now)
                if scan_completed:
                    if wait_time > 0:
                        time.sleep(wait_time)
                elif self._poll_scan_event(event_socket, max(0.0, wait_time)):
                    scan_completed = True
                now = time.monotonic()
                if now >= next_refresh:
                    current_entries, current_generation = self._dump_scan()
                    current_sample_ns = self._boottime_ns()
                    on_update(
                        self._fresh_entries(
                            before_entries,
                            before_generation,
                            before_sample_ns,
                            current_entries,
                            current_generation,
                            current_sample_ns,
                        )
                    )
                    completed_at = time.monotonic()
                    while next_refresh <= completed_at:
                        next_refresh += SCAN_REFRESH_SECONDS
                    now = completed_at
                if scan_completed and now - started_at >= MIN_SCAN_DURATION_SECONDS:
                    break
        finally:
            event_socket.close()

        after_entries, after_generation = self._dump_scan()
        after_sample_ns = self._boottime_ns()
        return self._fresh_entries(
            before_entries,
            before_generation,
            before_sample_ns,
            after_entries,
            after_generation,
            after_sample_ns,
        )


def parse_information_elements(data: bytes) -> List[Tuple[int, bytes]]:
    elements: List[Tuple[int, bytes]] = []
    offset = 0
    while offset + 2 <= len(data):
        element_id = data[offset]
        length = data[offset + 1]
        offset += 2
        if offset + length > len(data):
            break
        elements.append((element_id, data[offset:offset + length]))
        offset += length
    return elements


def parse_wps_attributes(data: bytes) -> Dict[int, List[bytes]]:
    attributes: Dict[int, List[bytes]] = {}
    offset = 0
    while offset + 4 <= len(data):
        attr_type, length = struct.unpack_from(">HH", data, offset)
        offset += 4
        if offset + length > len(data):
            break
        attributes.setdefault(attr_type, []).append(data[offset:offset + length])
        offset += length
    return attributes


def decode_wps_text(value: Optional[bytes]) -> str:
    if not value:
        return ""
    return value.rstrip(b"\x00").decode("utf-8", errors="replace")


def parse_bss(payload: bytes) -> Optional[dict]:
    attrs = parse_attrs(payload)
    bssid_data = first_attr(attrs, NL80211_BSS_BSSID)
    if bssid_data is None or len(bssid_data) != 6:
        return None

    ie_blobs = attrs.get(NL80211_BSS_INFORMATION_ELEMENTS, [])
    ie_blobs += attrs.get(NL80211_BSS_BEACON_IES, [])
    elements: List[Tuple[int, bytes]] = []
    for blob in ie_blobs:
        elements.extend(parse_information_elements(blob))

    ssid = ""
    has_rsn = False
    has_wpa = False
    wps_data: List[bytes] = []
    for element_id, value in elements:
        if element_id == WLAN_EID_SSID and not ssid and value:
            ssid = value.decode("utf-8", errors="replace")
        elif element_id == WLAN_EID_RSN:
            has_rsn = True
        elif element_id == WLAN_EID_VENDOR_SPECIFIC and len(value) >= 4:
            if value.startswith(WPA_VENDOR_TYPE):
                has_wpa = True
            elif value.startswith(WPS_VENDOR_TYPE):
                wps_data.append(value[4:])

    capability_data = first_attr(attrs, NL80211_BSS_CAPABILITY)
    capability = (
        struct.unpack_from("=H", capability_data)[0]
        if capability_data is not None and len(capability_data) >= 2
        else 0
    )
    capabilities = []
    if has_rsn:
        capabilities.append("WPA2")
    if has_wpa:
        capabilities.append("WPA")
    if not has_rsn and not has_wpa and capability & WLAN_CAPABILITY_PRIVACY:
        capabilities.append("WEP")
    if capability & WLAN_CAPABILITY_ESS:
        capabilities.append("ESS")
    if capability & WLAN_CAPABILITY_IBSS:
        capabilities.append("IBSS")
    if wps_data:
        capabilities.append("WPS")

    signal_data = first_attr(attrs, NL80211_BSS_SIGNAL_MBM)
    if signal_data is not None and len(signal_data) >= 4:
        level = int(struct.unpack_from("=i", signal_data)[0] / 100.0)
    else:
        signal_unspec = first_attr(attrs, NL80211_BSS_SIGNAL_UNSPEC)
        level = (
            int(signal_unspec[0] / 2.0 - 110)
            if signal_unspec
            else -100
        )

    wps_attrs: Dict[int, List[bytes]] = {}
    for blob in wps_data:
        for attr_type, values in parse_wps_attributes(blob).items():
            wps_attrs.setdefault(attr_type, []).extend(values)

    def first_wps(attr_type: int) -> Optional[bytes]:
        values = wps_attrs.get(attr_type)
        return values[0] if values else None

    locked_data = first_wps(WPS_ATTR_AP_SETUP_LOCKED)
    locked = bool(locked_data and int.from_bytes(locked_data, "big"))
    frequency_data = first_attr(attrs, NL80211_BSS_FREQUENCY)
    frequency = (
        struct.unpack_from("=I", frequency_data)[0]
        if frequency_data is not None and len(frequency_data) >= 4
        else 0
    )
    timestamp_data = first_attr(attrs, NL80211_BSS_LAST_SEEN_BOOTTIME)
    if timestamp_data is not None and len(timestamp_data) >= 8:
        timestamp_us = struct.unpack_from("=Q", timestamp_data)[0] // 1_000
    else:
        seen_ms_data = first_attr(attrs, NL80211_BSS_SEEN_MS_AGO)
        seen_ms = (
            struct.unpack_from("=I", seen_ms_data)[0]
            if seen_ms_data is not None and len(seen_ms_data) >= 4
            else 0
        )
        timestamp_us = max(0, Nl80211Scanner._boottime_ns() // 1_000 - seen_ms * 1_000)

    return {
        "BSSID": ":".join(f"{byte:02X}" for byte in bssid_data),
        "SSID": ssid,
        "capabilities": "".join(f"[{value}]" for value in capabilities),
        "level": level,
        "frequency": frequency,
        "timestamp": timestamp_us,
        "channelWidth": 0,
        "centerFreq0": frequency,
        "centerFreq1": 0,
        "wps": {
            "supported": bool(wps_data),
            "locked": locked,
            "model": decode_wps_text(first_wps(WPS_ATTR_MODEL_NAME)),
            "modelNumber": decode_wps_text(first_wps(WPS_ATTR_MODEL_NUMBER)),
            "deviceName": decode_wps_text(first_wps(WPS_ATTR_DEVICE_NAME)),
        },
    }


def wifi_state_event(networks: List[dict], scanning: bool) -> dict:
    return {
        "action": "update_wifi_state",
        "state": {
            "type": "enabled",
            "scanning": scanning,
            "wifilist": networks,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="WiFi Scanner — scan nearby networks through nl80211",
        epilog="Example: %(prog)s -i wlan0",
    )
    parser.add_argument(
        "-i",
        "--interface",
        default="wlan0",
        help="Name of the wireless interface to use (default: wlan0)",
    )
    parser.add_argument(
        "-scan",
        action="store_true",
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--wps-pin",
        action="store_true",
        help="Use one caller-provided WPS PIN with the target BSSID",
    )
    parser.add_argument("--bssid", help="Target access point BSSID")
    parser.add_argument("--pin", help="Eight-digit WPS PIN")
    parser.add_argument(
        "--wps-timeout",
        type=float,
        default=120.0,
        help="Single WPS transaction timeout in seconds (default: 120)",
    )
    args = parser.parse_args()

    if args.wps_pin:
        validation_message = None
        if args.scan:
            validation_message = "--wps-pin 不能与 -scan 同时使用"
        elif not args.bssid or not MAC_ADDRESS_RE.match(args.bssid):
            validation_message = "必须提供格式正确的 --bssid"
        elif not args.pin or not WPS_PIN_RE.match(args.pin):
            validation_message = "必须提供 8 位数字 --pin"
        elif args.wps_timeout <= 0:
            validation_message = "--wps-timeout 必须大于 0"
        elif os.getuid() != 0:
            validation_message = "WPS 单 PIN 连接要求 root 身份"
        if validation_message is not None:
            emit_json({"type": "failure", "message": validation_message})
            return 1
        connector = SinglePinWpsConnector(
            interface=args.interface,
            bssid=args.bssid,
            pin=args.pin,
            timeout=args.wps_timeout,
        )
        return 0 if connector.run() else 1

    if sys.hexversion < 0x03060F0:
        emit_json(
            {
                "action": "start_scan_callback",
                "ok": False,
                "message": "Python 版本低于 3.6",
            }
        )
        return 1
    if os.getuid() != 0:
        emit_json(
            {
                "action": "start_scan_callback",
                "ok": False,
                "message": "扫描进程未以 root 身份运行",
            }
        )
        return 1

    scanner: Optional[Nl80211Scanner] = None
    started = False
    known_networks: Dict[str, dict] = {}

    def on_started() -> None:
        nonlocal started
        started = True
        emit_json({"action": "start_scan_callback", "ok": True})

    def publish_entries(entries: List[bytes], scanning: bool = True) -> None:
        for entry in entries:
            network = parse_bss(entry)
            if network is not None:
                known_networks[network["BSSID"]] = network
        ordered = sorted(
            known_networks.values(),
            key=lambda network: network["level"],
            reverse=True,
        )
        emit_json(wifi_state_event(ordered, scanning=scanning))

    try:
        scanner = Nl80211Scanner(args.interface)
        entries = scanner.scan(
            on_started=on_started,
            on_update=publish_entries,
        )
    except ScanTooFrequent:
        emit_json(
            {
                "action": "start_scan_callback",
                "ok": False,
                "message": "操作过于频繁，请稍后再试",
            }
        )
        return 0
    except InterfaceDisabled as error:
        message = str(error)
        emit_json(
            {
                "action": "start_scan_callback",
                "ok": False,
                "message": message,
            }
        )
        emit_json(
            {
                "action": "update_wifi_state",
                "state": {"type": "error", "message": message},
            }
        )
        return 1
    except (OSError, ScanError) as error:
        message = str(error)
        if not started:
            emit_json(
                {
                    "action": "start_scan_callback",
                    "ok": False,
                    "message": message,
                }
            )
        else:
            emit_json(
                {
                    "action": "update_wifi_state",
                    "state": {"type": "error", "message": message},
                }
            )
        return 1
    finally:
        if scanner is not None:
            scanner.close()

    publish_entries(entries, scanning=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
