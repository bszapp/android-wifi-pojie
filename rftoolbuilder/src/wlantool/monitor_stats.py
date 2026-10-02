#!/usr/bin/env python3
"""Incrementally analyze and export a growing monitor-mode pcap file."""

import argparse
import copy
import uuid
import base64
import queue
import signal
import subprocess
import sys
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import json
import os
import struct
import threading
import time

from scapy.config import conf
from scapy.layers.dhcp import DHCP
from scapy.layers.dns import DNS, DNSRR
from scapy.layers.dot11 import Dot11, Dot11Elt, RadioTap
from scapy.layers.eap import EAPOL
from scapy.layers.inet import UDP
from monitor_diagnostics import interface_snapshot, process_snapshot


PUBLISH_INTERVAL_SECONDS = 0.05
REALTIME_WINDOW_SECONDS = 1.0
BYTE_RATE_WINDOW_SECONDS = 5.0
ANALYSIS_BATCH_MAX_BYTES = 1024 * 1024
ANALYSIS_BATCH_MAX_PACKETS = 2048
PCAP_GLOBAL_HEADER_SIZE = 24
PCAP_PACKET_HEADER_SIZE = 16
MAX_CAPTURED_PACKET_SIZE = 16 * 1024 * 1024
EXPORT_BATCH_PACKETS = 2048
HANDSHAKE_TIMEOUT_MILLIS = 15_000
HANDSHAKE_EVENT_CHUNK_BYTES = 24 * 1024
WPS_VENDOR_PREFIX = b"\x00\x50\xf2\x04"
WPS_DEVICE_NAME = 0x1011
WPS_MANUFACTURER = 0x1021
WPS_MODEL_NAME = 0x1023
WPS_MODEL_NUMBER = 0x1024
WPA_VENDOR_PREFIX = b"\x00\x50\xf2\x01"
RSN_AKM_OUI = b"\x00\x0f\xac"
WPA_AKM_OUI = b"\x00\x50\xf2"
PSK_AKM_TYPE = 2


def diagnostic(event, **fields):
    """独立于事件 FIFO 输出；仅记录进度元数据，不输出包体或凭据。"""
    try:
        print("[MonitorDiagnostic] " + json.dumps(dict(
            timeUnixMillis=int(time.time() * 1000), pid=os.getpid(),
            thread=threading.current_thread().name, event=event, **fields),
            ensure_ascii=False, separators=(",", ":")), file=sys.stderr, flush=True)
    except (OSError, ValueError):
        pass


class MonitorDiagnostics:
    def __init__(self):
        self.lock = threading.Lock()
        self.phases = {}
        self.sent_events = 0
        self.sent_characters = 0
        self.last_sent = None
        self.stop_event = threading.Event()

    def phase(self, owner, stage, **details):
        with self.lock:
            self.phases[owner] = dict(stage=stage, since=time.monotonic(), **details)

    def done(self, owner):
        with self.lock:
            self.phases.pop(owner, None)

    def sent(self, event_type, source, character_count):
        with self.lock:
            self.sent_events += 1
            self.sent_characters += character_count
            self.last_sent = dict(type=event_type, source=source,
                                  atUnixMillis=int(time.time() * 1000))

    def heartbeat(self, snapshot):
        while not self.stop_event.wait(1.0):
            now = time.monotonic()
            with self.lock:
                fields = dict(phases={owner: dict(
                    ageMillis=int((now - state["since"]) * 1000),
                    **{key: value for key, value in state.items() if key != "since"})
                    for owner, state in self.phases.items()},
                    sentEvents=self.sent_events, sentCharacters=self.sent_characters,
                    lastSent=self.last_sent)
            try:
                fields.update(snapshot())
                diagnostic("heartbeat", **fields)
            except Exception as error:
                diagnostic("heartbeatFailed", errorType=type(error).__name__)


DIAGNOSTICS = MonitorDiagnostics()


def diagnostic_file_size(path):
    try:
        return os.path.getsize(path) if path is not None else None
    except OSError:
        return None


def subtype_definitions(group_id, names):
    return tuple(
        {
            "id": f"{group_id}.{subtype}",
            "displayName": name,
            "subtype": subtype,
        }
        for subtype, name in enumerate(names)
    )


FRAME_GROUP_DEFINITIONS = (
    {
        "id": "management",
        "displayName": "管理帧",
        "type": 0,
        "subtypes": subtype_definitions(
            "management",
            (
                "关联请求 (Association Request)",
                "关联响应 (Association Response)",
                "重新关联请求 (Reassociation Request)",
                "重新关联响应 (Reassociation Response)",
                "探测请求 (Probe Request)",
                "探测响应 (Probe Response)",
                "定时公告 (Timing Advertisement)",
                "保留子类型 7",
                "信标 (Beacon)",
                "ATIM",
                "解除关联 (Disassociation)",
                "身份验证 (Authentication)",
                "解除身份验证 (Deauthentication)",
                "动作帧 (Action)",
                "无确认动作帧 (Action No Ack)",
                "保留子类型 15",
            ),
        ),
    },
    {
        "id": "control",
        "displayName": "控制帧",
        "type": 1,
        "subtypes": subtype_definitions(
            "control",
            (
                "保留子类型 0",
                "保留子类型 1",
                "触发帧 (Trigger)",
                "TACK",
                "波束成形报告轮询 (Beamforming Report Poll)",
                "VHT NDP 公告",
                "控制帧扩展 (Control Frame Extension)",
                "控制包装帧 (Control Wrapper)",
                "块确认请求 (Block Ack Request)",
                "块确认 (Block Ack)",
                "省电轮询 (PS-Poll)",
                "请求发送 (RTS)",
                "允许发送 (CTS)",
                "确认 (ACK)",
                "CF-End",
                "CF-End + CF-Ack",
            ),
        ),
    },
    {
        "id": "data",
        "displayName": "数据帧",
        "type": 2,
        "subtypes": subtype_definitions(
            "data",
            (
                "数据 (Data)",
                "Data + CF-Ack",
                "Data + CF-Poll",
                "Data + CF-Ack + CF-Poll",
                "空数据 (Null Data)",
                "CF-Ack（无数据）",
                "CF-Poll（无数据）",
                "CF-Ack + CF-Poll（无数据）",
                "QoS 数据 (QoS Data)",
                "QoS Data + CF-Ack",
                "QoS Data + CF-Poll",
                "QoS Data + CF-Ack + CF-Poll",
                "QoS 空数据 (QoS Null)",
                "保留子类型 13",
                "QoS CF-Poll（无数据）",
                "QoS CF-Ack + CF-Poll（无数据）",
            ),
        ) + (
            {
                "id": "data.eapol",
                "displayName": "EAPOL 四次握手",
                "subtype": None,
            },
        ),
    },
    {
        "id": "other",
        "displayName": "其他",
        "type": 3,
        "subtypes": subtype_definitions(
            "other",
            tuple(f"扩展帧子类型 {subtype}" for subtype in range(16)),
        ) + (
            {
                "id": "other.unclassified",
                "displayName": "无法分类",
                "subtype": None,
            },
        ),
    },
)

FRAME_SUBTYPE_BY_TYPE = {
    (group["type"], subtype["subtype"]): subtype["id"]
    for group in FRAME_GROUP_DEFINITIONS
    for subtype in group["subtypes"]
    if subtype["subtype"] is not None
}
EXPORT_SUBTYPE_IDS = {
    subtype["id"]
    for group in FRAME_GROUP_DEFINITIONS
    for subtype in group["subtypes"]
}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pcap", required=True)
    parser.add_argument("--command-pipe", required=True)
    parser.add_argument("--event-pipe", required=True)
    return parser.parse_args()


def normalized_unicast_mac(value):
    if not value:
        return None
    normalized = value.lower()
    try:
        parts = normalized.split(":")
        if len(parts) != 6 or any(len(part) != 2 for part in parts):
            return None
        octets = tuple(int(part, 16) for part in parts)
    except ValueError:
        return None
    if not any(octets) or octets[0] & 1:
        return None
    return ":".join(f"{octet:02x}" for octet in octets)


def packet_bssid(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return None
    frame_type = int(dot11.type)
    if frame_type == 0:
        return normalized_unicast_mac(dot11.addr3)
    if frame_type != 2:
        return None
    flags = int(dot11.FCfield)
    to_ds = bool(flags & 0x1)
    from_ds = bool(flags & 0x2)
    if to_ds and not from_ds:
        return normalized_unicast_mac(dot11.addr1)
    if from_ds and not to_ds:
        return normalized_unicast_mac(dot11.addr2)
    if not to_ds and not from_ds:
        return normalized_unicast_mac(dot11.addr3)
    return None


def decode_text(value):
    if value is None:
        return None
    if isinstance(value, str):
        text = value
    else:
        try:
            text = bytes(value).decode("utf-8", errors="replace")
        except (TypeError, ValueError):
            return None
    text = text.replace("\x00", "").strip()
    return text or None


def iter_dot11_elements(packet):
    element = packet.getlayer(Dot11Elt)
    while isinstance(element, Dot11Elt):
        yield element
        element = element.payload


def ssid_element(packet):
    for element in iter_dot11_elements(packet):
        if int(element.ID) == 0:
            return bytes(element.info)
    return None


def packet_ssid(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None or int(dot11.type) != 0 or int(dot11.subtype) not in (0, 2, 4, 5, 8):
        return None
    return decode_text(ssid_element(packet))


def beacon_visibility(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None or int(dot11.type) != 0 or int(dot11.subtype) != 8:
        return None
    raw_ssid = ssid_element(packet)
    if raw_ssid is None:
        return None
    if not raw_ssid or all(value == 0 for value in raw_ssid):
        return "hidden"
    return "visible"


def parse_akm_suites(data, expected_oui):
    if len(data) < 8:
        return ()
    offset = 2 + 4
    pairwise_count = struct.unpack("<H", data[offset:offset + 2])[0]
    offset += 2 + pairwise_count * 4
    if offset + 2 > len(data):
        return ()
    akm_count = struct.unpack("<H", data[offset:offset + 2])[0]
    offset += 2
    suites = []
    for _ in range(akm_count):
        if offset + 4 > len(data):
            return tuple(suites)
        suite = data[offset:offset + 4]
        offset += 4
        if suite[:3] == expected_oui:
            suites.append(suite[3])
    return tuple(suites)


def packet_security_protocols(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None or int(dot11.type) != 0 or int(dot11.subtype) not in (5, 8):
        return ()
    protocols = set()
    for element in iter_dot11_elements(packet):
        info = bytes(element.info)
        element_id = int(element.ID)
        if element_id == 48:
            if PSK_AKM_TYPE in parse_akm_suites(info, RSN_AKM_OUI):
                protocols.add("wpa2")
        elif element_id == 221 and info.startswith(WPA_VENDOR_PREFIX):
            if PSK_AKM_TYPE in parse_akm_suites(
                info[len(WPA_VENDOR_PREFIX):],
                WPA_AKM_OUI,
            ):
                protocols.add("wpa")
    return tuple(sorted(protocols))


def parse_wps_attributes(data):
    attributes = {}
    offset = 0
    while offset + 4 <= len(data):
        attribute_type, length = struct.unpack(">HH", data[offset:offset + 4])
        offset += 4
        if offset + length > len(data):
            break
        attributes[attribute_type] = decode_text(data[offset:offset + length])
        offset += length
    return attributes


def wps_device_identity(packet):
    best_name = None
    best_priority = 0
    for element in iter_dot11_elements(packet):
        info = bytes(element.info)
        if int(element.ID) != 221 or not info.startswith(WPS_VENDOR_PREFIX):
            continue
        attributes = parse_wps_attributes(info[len(WPS_VENDOR_PREFIX):])
        device_name = attributes.get(WPS_DEVICE_NAME)
        model_name = attributes.get(WPS_MODEL_NAME)
        model_number = attributes.get(WPS_MODEL_NUMBER)
        manufacturer = attributes.get(WPS_MANUFACTURER)
        if device_name:
            best_name, best_priority = device_name, 40
        elif model_name:
            best_name = " ".join(
                part for part in (manufacturer, model_name, model_number) if part
            )
            best_priority = 30
    return best_name, best_priority


def dhcp_device_identity(packet):
    dhcp = packet.getlayer(DHCP)
    if dhcp is None:
        return None, 0
    for option in dhcp.options:
        if isinstance(option, tuple) and option and option[0] in ("hostname", "host_name"):
            return decode_text(option[1]), 35
    return None, 0


def dns_names(section, count):
    current = section
    for _ in range(int(count or 0)):
        if not isinstance(current, DNSRR):
            break
        yield decode_text(current.rrname)
        current = current.payload


def mdns_device_identity(packet):
    udp = packet.getlayer(UDP)
    dns = packet.getlayer(DNS)
    if udp is None or dns is None or (int(udp.sport) != 5353 and int(udp.dport) != 5353):
        return None, 0
    candidates = []
    for section, count in ((dns.an, dns.ancount), (dns.ns, dns.nscount), (dns.ar, dns.arcount)):
        for name in dns_names(section, count):
            if not name:
                continue
            normalized = name.rstrip(".")
            lowered = normalized.lower()
            if not lowered.endswith(".local") or "._tcp." in lowered or "._udp." in lowered:
                continue
            label = normalized[:-6].rstrip(".")
            if label and not label.startswith("_"):
                candidates.append(label)
    return (candidates[0], 20) if candidates else (None, 0)


def frame_subtype_id(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return "other.unclassified"
    frame_type = int(dot11.type)
    if frame_type == 2 and packet.haslayer(EAPOL):
        return "data.eapol"
    return FRAME_SUBTYPE_BY_TYPE.get(
        (frame_type, int(dot11.subtype)),
        "other.unclassified",
    )


def packet_device_relation(packet, known_bssids=(), target_pair=None):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return None
    frame_type = int(dot11.type)
    if frame_type == 2:
        flags = int(dot11.FCfield)
        to_ds = bool(flags & 0x1)
        from_ds = bool(flags & 0x2)
        if to_ds and not from_ds:
            bssid = normalized_unicast_mac(dot11.addr1)
            device = normalized_unicast_mac(dot11.addr2)
            direction = "upload"
        elif from_ds and not to_ds:
            bssid = normalized_unicast_mac(dot11.addr2)
            device = normalized_unicast_mac(dot11.addr1)
            direction = "download"
        else:
            return None
        if bssid and device and bssid != device:
            return bssid, device, direction
        return None

    if frame_type == 0:
        bssid = normalized_unicast_mac(dot11.addr3)
        transmitter = normalized_unicast_mac(dot11.addr2)
        receiver = normalized_unicast_mac(dot11.addr1)
        if not bssid:
            return None
        if transmitter and transmitter != bssid:
            return bssid, transmitter, "management"
        if receiver and receiver != bssid:
            return bssid, receiver, "management"
        return None

    if frame_type not in (1, 3):
        return None
    first = normalized_unicast_mac(dot11.addr1)
    second = normalized_unicast_mac(dot11.addr2)
    if not first or not second or first == second:
        return None
    if target_pair is not None:
        target_bssid, target_device = target_pair
        if {first, second} == {target_bssid, target_device}:
            return target_bssid, target_device, "control" if frame_type == 1 else "other"
        return None
    if first in known_bssids and second not in known_bssids:
        return first, second, "control" if frame_type == 1 else "other"
    if second in known_bssids and first not in known_bssids:
        return second, first, "control" if frame_type == 1 else "other"
    return None


def packet_signal_dbm(packet):
    radiotap = packet.getlayer(RadioTap)
    if radiotap is None or getattr(radiotap, "dBm_AntSignal", None) is None:
        return None
    try:
        return int(radiotap.dBm_AntSignal)
    except (TypeError, ValueError):
        return None


def packet_frame_bytes(packet, fallback):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return fallback
    try:
        return len(bytes(dot11))
    except (TypeError, ValueError):
        return fallback


def empty_signal_state():
    return {"samples": deque(), "latest": None, "lastSeenUnixMillis": 0}


def add_signal_sample(state, timestamp, value):
    if value is None:
        return
    state["samples"].append((timestamp, value))
    state["latest"] = value
    state["lastSeenUnixMillis"] = int(timestamp * 1000)


def signal_snapshot(state, now):
    samples = state["samples"]
    cutoff = now - REALTIME_WINDOW_SECONDS
    while samples and samples[0][0] < cutoff:
        samples.popleft()
    latest = state["latest"]
    if latest is None:
        return None
    values = [sample[1] for sample in samples]
    if not values:
        values = [latest]
    return {
        "latestDbm": latest,
        "averageDbm": sum(values) / len(values),
        "minimumDbm": min(values),
        "maximumDbm": max(values),
        "sampleCount": len(samples),
        "lastSeenUnixMillis": state["lastSeenUnixMillis"],
    }


def empty_access_point(bssid):
    return {
        "bssid": bssid,
        "ssid": None,
        "ssidBytes": None,
        "ssidVisibility": "unknown",
        "securityProtocols": set(),
        "ssidContextPacket": None,
        "securityContextPacket": None,
        "signal": empty_signal_state(),
        "devices": {},
        "publishedSignature": None,
    }


def empty_device(mac):
    return {
        "mac": mac,
        "name": None,
        "namePriority": 0,
        "frameCounters": {},
        "handshakes": [],
        "activeHandshake": None,
        "ssidContextPacket": None,
        "uploadSamples": deque(),
        "downloadSamples": deque(),
        "signal": empty_signal_state(),
        "publishedSignature": None,
    }


def update_device_identity(device, name, priority):
    if name and priority > device["namePriority"]:
        device["name"] = name
        device["namePriority"] = priority


def byte_rate(samples, now):
    cutoff = now - BYTE_RATE_WINDOW_SECONDS
    while samples and samples[0][0] < cutoff:
        samples.popleft()
    return int(sum(sample[1] for sample in samples) / BYTE_RATE_WINDOW_SECONDS)


def add_frame_counter(device, subtype_id, frame_bytes):
    counter = device["frameCounters"].setdefault(
        subtype_id,
        {"packetCount": 0, "byteCount": 0},
    )
    counter["packetCount"] += 1
    counter["byteCount"] += frame_bytes


def frame_groups_snapshot(device):
    counters = device["frameCounters"]
    groups = []
    for definition in FRAME_GROUP_DEFINITIONS:
        subtype_snapshots = []
        packet_count = 0
        byte_count = 0
        for subtype in definition["subtypes"]:
            counter = counters.get(subtype["id"])
            if not counter:
                continue
            packet_count += counter["packetCount"]
            byte_count += counter["byteCount"]
            subtype_snapshots.append(
                {
                    "id": subtype["id"],
                    "displayName": subtype["displayName"],
                    "packetCount": counter["packetCount"],
                    "byteCount": counter["byteCount"],
                }
            )
        groups.append(
            {
                "id": definition["id"],
                "displayName": definition["displayName"],
                "packetCount": packet_count,
                "byteCount": byte_count,
                "subtypes": subtype_snapshots,
            }
        )
    return groups


def device_snapshot(device, now):
    captured_subtype_ids = {
        subtype_id
        for subtype_id, counter in device["frameCounters"].items()
        if counter["packetCount"] > 0
    }
    return {
        "mac": device["mac"],
        "name": device["name"],
        "frameGroups": frame_groups_snapshot(device),
        "handshakes": [],
        "uploadBytesPerSecond": byte_rate(device["uploadSamples"], now),
        "downloadBytesPerSecond": byte_rate(device["downloadSamples"], now),
        "signal": signal_snapshot(device["signal"], now),
        "probeOnly": captured_subtype_ids == {"management.5"},
    }


def stable_signature(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def parse_eapol_key(packet):
    eapol = packet.getlayer(EAPOL)
    if eapol is None:
        return None
    raw = bytes(eapol)
    if len(raw) < 99 or raw[1] != 3:
        return None
    body_length = struct.unpack(">H", raw[2:4])[0]
    frame_length = 4 + body_length
    if body_length < 95 or len(raw) < frame_length:
        return None
    frame = raw[:frame_length]
    key_info = struct.unpack(">H", frame[5:7])[0]
    descriptor_version = key_info & 0x7
    pairwise = bool(key_info & (1 << 3))
    install = bool(key_info & (1 << 6))
    acknowledge = bool(key_info & (1 << 7))
    has_mic = bool(key_info & (1 << 8))
    secure = bool(key_info & (1 << 9))
    if not pairwise:
        return None
    if acknowledge and not has_mic:
        message = 1
    elif has_mic and not acknowledge and not secure and not install:
        message = 2
    elif acknowledge and has_mic:
        message = 3
    elif has_mic and not acknowledge and secure:
        message = 4
    else:
        return None
    return {
        "message": message,
        "descriptorVersion": descriptor_version,
        "replayCounter": struct.unpack(">Q", frame[9:17])[0],
        "nonce": frame[17:49],
        "mic": frame[81:97],
        "frame": frame,
    }


HANDSHAKE_STEP_ORDER = (
    "authentication",
    "association",
    "eapol1",
    "eapol2",
    "eapol3",
    "eapol4",
    "disconnection",
)


def append_handshake_packet(record, packet_record):
    packet_offset, packet_header, packet_payload = packet_record
    if packet_offset in record["packetOffsets"]:
        return False
    record["packetOffsets"].add(packet_offset)
    record["packets"].append((packet_offset, packet_header, packet_payload))
    return True


def create_handshake_record(
    device,
    access_point,
    bssid,
    device_mac,
    timestamp_millis,
    next_handshake_id,
):
    handshake_id = str(next_handshake_id)
    record = {
        "id": handshake_id,
        "bssid": bssid,
        "deviceMac": device_mac,
        "startUnixMillis": timestamp_millis,
        "lastUnixMillis": timestamp_millis,
        "status": "inProgress",
        "capturedSteps": set(),
        "lastStep": None,
        "failedAtStep": None,
        "failureReason": None,
        "lastM1": None,
        "lastM2": None,
        "m3ReplayCounter": None,
        "m2AttemptCount": 0,
        "validation": None,
        "ssidBytes": access_point["ssidBytes"],
        "timedOut": False,
        "packetOffsets": set(),
        "packets": [],
        "transferredPacketCount": 0,
        "transferSequence": 0,
        "headerTransferred": False,
        "transferredHc22000": None,
        "latestSsidContextPacket": None,
        "latestSecurityContextPacket": access_point["securityContextPacket"],
    }
    device["handshakes"].append(record)
    device["activeHandshake"] = record
    ssid_context_packets = [
        packet
        for packet in (
            access_point["ssidContextPacket"],
            device["ssidContextPacket"],
        )
        if packet is not None
    ]
    if ssid_context_packets:
        record["latestSsidContextPacket"] = max(
            ssid_context_packets,
            key=lambda value: value[0],
        )
    context_packets = {
        packet[0]: packet
        for packet in (
            record["latestSsidContextPacket"],
            access_point["securityContextPacket"],
        )
        if packet is not None
    }
    for packet_record in sorted(context_packets.values(), key=lambda value: value[0]):
        append_handshake_packet(record, packet_record)
    return record, next_handshake_id + 1


def add_context_to_handshake_records(
    access_point,
    packet_record,
    ssid_bytes,
    has_security,
    device_macs=None,
):
    devices = access_point["devices"]
    selected_devices = (
        devices.values()
        if device_macs is None
        else (devices[mac] for mac in device_macs if mac in devices)
    )
    for device in selected_devices:
        for record in device["handshakes"]:
            if ssid_bytes:
                record["ssidBytes"] = ssid_bytes
                record["latestSsidContextPacket"] = packet_record
            if has_security:
                record["latestSecurityContextPacket"] = packet_record


def add_handshake_step(record, step):
    record["capturedSteps"].add(step)
    record["lastStep"] = step


def set_handshake_validation_data(
    record,
    authenticator_key,
    supplicant_key,
):
    if (
        authenticator_key["descriptorVersion"]
        != supplicant_key["descriptorVersion"]
        or not any(authenticator_key["nonce"])
        or not any(supplicant_key["nonce"])
        or not any(supplicant_key["mic"])
    ):
        return
    if supplicant_key["descriptorVersion"] not in (1, 2, 3):
        return
    record["validation"] = {
        "anonce": authenticator_key["nonce"],
        "snonce": supplicant_key["nonce"],
        "descriptorVersion": supplicant_key["descriptorVersion"],
        "mic": supplicant_key["mic"],
        "eapolFrame": supplicant_key["frame"],
        "messagePair": 0 if authenticator_key["message"] == 1 else 2,
    }


def finish_handshake(
    device,
    status,
    timestamp_millis,
    failure_reason=None,
    failed_at_step=None,
    timed_out=False,
):
    record = device["activeHandshake"]
    if record is None:
        return
    for context_packet in (
        record["latestSsidContextPacket"],
        record["latestSecurityContextPacket"],
    ):
        if context_packet is not None:
            append_handshake_packet(record, context_packet)
    record["lastUnixMillis"] = max(record["lastUnixMillis"], timestamp_millis)
    record["status"] = status
    record["failureReason"] = failure_reason
    record["failedAtStep"] = failed_at_step
    record["timedOut"] = timed_out
    device["activeHandshake"] = None


def management_status_code(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return None
    status = getattr(dot11.payload, "status", None)
    try:
        return int(status) if status is not None else None
    except (TypeError, ValueError):
        return None


def authentication_sequence(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return None


def management_reason_code(packet):
    dot11 = packet.getlayer(Dot11)
    if dot11 is None:
        return None
    reason = getattr(dot11.payload, "reason", None)
    try:
        return int(reason) if reason is not None else None
    except (TypeError, ValueError):
        return None
    sequence = getattr(dot11.payload, "seqnum", None)
    try:
        return int(sequence) if sequence is not None else None
    except (TypeError, ValueError):
        return None


def is_protected_data_from_device(packet, transmitter, device_mac):
    dot11 = packet.getlayer(Dot11)
    return (
        dot11 is not None
        and int(dot11.type) == 2
        and transmitter == device_mac
        and bool(int(dot11.FCfield) & 0x40)
    )


def process_handshake_packet(
    access_point,
    device,
    bssid,
    device_mac,
    packet,
    subtype_id,
    transmitter,
    packet_record,
    packet_timestamp,
    next_handshake_id,
):
    timestamp_millis = int(packet_timestamp * 1000)
    record = device["activeHandshake"]
    if (
        record is not None
        and timestamp_millis - record["startUnixMillis"] >= HANDSHAKE_TIMEOUT_MILLIS
    ):
        finish_handshake(
            device,
            "unknown",
            record["startUnixMillis"] + HANDSHAKE_TIMEOUT_MILLIS,
            timed_out=True,
        )
        record = None
    management_subtype = None
    dot11 = packet.getlayer(Dot11)
    if dot11 is not None and int(dot11.type) == 0:
        management_subtype = int(dot11.subtype)

    starts_authentication = (
        management_subtype == 11
        and transmitter == device_mac
        and authentication_sequence(packet) in (None, 1)
    )
    starts_association = management_subtype in (0, 2) and transmitter == device_mac
    association_ssid_bytes = ssid_element(packet) if starts_association else None
    if starts_association and association_ssid_bytes:
        device["ssidContextPacket"] = packet_record
        access_point["ssidBytes"] = association_ssid_bytes
        access_point["ssid"] = decode_text(association_ssid_bytes)
    if record is not None and (
        (starts_authentication and record["lastStep"] != "authentication")
        or (
            starts_association
            and any(step.startswith("eapol") for step in record["capturedSteps"])
        )
    ):
        finish_handshake(
            device,
            "failed",
            timestamp_millis,
            failure_reason="replacedByNewAttempt",
            failed_at_step=record["lastStep"],
        )
        record = None

    if management_subtype in (0, 1, 2, 3, 10, 11, 12):
        if record is None and management_subtype not in (10, 12):
            record, next_handshake_id = create_handshake_record(
                device,
                access_point,
                bssid,
                device_mac,
                timestamp_millis,
                next_handshake_id,
            )
        if record is not None:
            previous_step = record["lastStep"]
            append_handshake_packet(record, packet_record)
            packet_ssid_bytes = ssid_element(packet)
            if packet_ssid_bytes:
                record["ssidBytes"] = packet_ssid_bytes
            record["lastUnixMillis"] = max(record["lastUnixMillis"], timestamp_millis)
            if management_subtype == 11:
                add_handshake_step(record, "authentication")
                if management_status_code(packet) not in (None, 0):
                    finish_handshake(
                        device,
                        "failed",
                        timestamp_millis,
                        failure_reason="routerRejectedConnection",
                        failed_at_step="authentication",
                    )
            elif management_subtype in (0, 1, 2, 3):
                add_handshake_step(record, "association")
                if management_subtype in (1, 3) and management_status_code(packet) not in (None, 0):
                    finish_handshake(
                        device,
                        "failed",
                        timestamp_millis,
                        failure_reason="routerRejectedConnection",
                        failed_at_step="association",
                    )
            else:
                add_handshake_step(record, "disconnection")
                if record["m2AttemptCount"] > 1:
                    reason = "m2RetryLimitExceeded"
                    failed_step = "eapol2"
                elif record["m2AttemptCount"] == 1:
                    reason = "disconnectedAfterM2"
                    failed_step = "eapol2"
                else:
                    reason = "disconnectedDuringHandshake"
                    failed_step = previous_step
                finish_handshake(
                    device,
                    "failed",
                    timestamp_millis,
                    failure_reason=reason,
                    failed_at_step=failed_step,
                )
        return next_handshake_id

    key = parse_eapol_key(packet)
    if key is None:
        record = device["activeHandshake"]
        if (
            record is not None
            and record["m3ReplayCounter"] is not None
            and is_protected_data_from_device(packet, transmitter, device_mac)
        ):
            append_handshake_packet(record, packet_record)
            finish_handshake(device, "success", timestamp_millis)
        return next_handshake_id
    record = device["activeHandshake"]
    if key["message"] == 1:
        if (
            record is not None
            and record["lastM1"] is not None
            and record["lastM1"]["nonce"] != key["nonce"]
            and record["m2AttemptCount"] > 0
        ):
            finish_handshake(
                device,
                "failed",
                timestamp_millis,
                failure_reason="replacedByNewAttempt",
                failed_at_step=record["lastStep"],
            )
            record = None
    if record is None:
        record, next_handshake_id = create_handshake_record(
            device,
            access_point,
            bssid,
            device_mac,
            timestamp_millis,
            next_handshake_id,
        )
    append_handshake_packet(record, packet_record)
    record["lastUnixMillis"] = max(record["lastUnixMillis"], timestamp_millis)
    add_handshake_step(record, f"eapol{key['message']}")
    if key["message"] == 1:
        record["lastM1"] = key
        return next_handshake_id
    if key["message"] == 2:
        record["m2AttemptCount"] += 1
        record["lastM2"] = key
        m1 = record["lastM1"]
        if (
            m1 is not None
            and key["replayCounter"] == m1["replayCounter"]
        ):
            set_handshake_validation_data(record, m1, key)
    elif key["message"] == 3:
        record["m3ReplayCounter"] = key["replayCounter"]
        m2 = record["lastM2"]
        if (
            m2 is not None
            and key["replayCounter"] == m2["replayCounter"] + 1
        ):
            set_handshake_validation_data(record, key, m2)
    elif key["message"] == 4:
        if (
            record["m3ReplayCounter"] is None
            or record["m3ReplayCounter"] == key["replayCounter"]
        ):
            finish_handshake(device, "success", timestamp_millis)
    return next_handshake_id


def build_hc22000(record, ssid_bytes):
    validation = record["validation"]
    if validation is None or not ssid_bytes or len(ssid_bytes) > 32:
        return None
    return "*".join(
        (
            "WPA",
            "02",
            validation["mic"].hex(),
            record["bssid"].replace(":", ""),
            record["deviceMac"].replace(":", ""),
            ssid_bytes.hex(),
            validation["anonce"].hex(),
            validation["eapolFrame"].hex(),
            f"{validation['messagePair']:02x}",
        )
    )


def has_intermediate_handshake_gap(record):
    steps = record["capturedSteps"]
    if "eapol4" in steps:
        return not {"eapol1", "eapol2", "eapol3"}.issubset(steps)
    if "eapol3" in steps:
        return not {"eapol1", "eapol2"}.issubset(steps)
    if "eapol2" in steps:
        return "eapol1" not in steps
    return False


def handshake_capture_quality(record, hc22000):
    password_failure = record["failureReason"] in (
        "m2RetryLimitExceeded",
        "disconnectedAfterM2",
    )
    if (record["status"] == "success" or password_failure) and hc22000 is None:
        return "dataIncomplete"
    if record["timedOut"] or has_intermediate_handshake_gap(record):
        return "partiallyMissing"
    return "complete"


class GrowingPcapReader:
    def __init__(self, path, source_stream=None):
        self.path = path
        self.source_stream = source_stream
        self.stream = None
        self.endian = None
        self.link_type = None
        self.offset = 0
        self.nanosecond_timestamps = False
        self.global_header = None

    def close(self):
        if self.stream is not None:
            self.stream.close()
            self.stream = None

    def _reset(self):
        self.close()
        self.endian = None
        self.link_type = None
        self.offset = 0
        self.nanosecond_timestamps = False
        self.global_header = None

    def _open_if_ready(self):
        if self.stream is not None:
            if self.path is not None and os.path.getsize(self.path) < self.offset:
                self._reset()
            else:
                return True
        if self.source_stream is not None:
            stream = self.source_stream
            stream.seek(0)
        else:
            try:
                stream = open(self.path, "rb", buffering=0)
            except FileNotFoundError:
                return False
        header = stream.read(PCAP_GLOBAL_HEADER_SIZE)
        if len(header) < PCAP_GLOBAL_HEADER_SIZE:
            stream.close()
            return False
        magic = header[:4]
        if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
            endian = "<"
        elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
            endian = ">"
        else:
            stream.close()
            raise ValueError("不支持的 pcap 文件格式")
        self.stream = stream
        self.global_header = header
        self.endian = endian
        self.link_type = struct.unpack(endian + "I", header[20:24])[0]
        self.offset = PCAP_GLOBAL_HEADER_SIZE
        self.nanosecond_timestamps = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
        return True

    def read_available(self, limit_offset=None, max_packets=None, max_bytes=None):
        if not self._open_if_ready():
            return []
        packets = []
        batch_bytes = 0
        while max_packets is None or len(packets) < max_packets:
            packet_start = self.offset
            header = self.stream.read(PCAP_PACKET_HEADER_SIZE)
            if len(header) < PCAP_PACKET_HEADER_SIZE:
                self.stream.seek(packet_start)
                return packets
            seconds, fraction, included_length, _ = struct.unpack(
                self.endian + "IIII", header
            )
            packet_end = packet_start + PCAP_PACKET_HEADER_SIZE + included_length
            if limit_offset is not None and packet_end > limit_offset:
                self.stream.seek(packet_start)
                return packets
            if included_length > MAX_CAPTURED_PACKET_SIZE:
                raise ValueError(f"pcap 数据包长度异常: {included_length}")
            packet_bytes = PCAP_PACKET_HEADER_SIZE + included_length
            if max_bytes is not None and packets and batch_bytes + packet_bytes > max_bytes:
                self.stream.seek(packet_start)
                return packets
            payload = self.stream.read(included_length)
            if len(payload) < included_length:
                self.stream.seek(packet_start)
                return packets
            self.offset = packet_end
            batch_bytes += packet_bytes
            divisor = 1_000_000_000 if self.nanosecond_timestamps else 1_000_000
            timestamp = seconds + fraction / divisor
            packets.append(
                (payload, included_length, self.link_type, packet_start, header, timestamp)
            )
        return packets


def decode_packet(payload, link_type):
    decoder = conf.l2types.get(link_type)
    if decoder is None:
        return None
    return decoder(payload)


class EventWriter:
    def __init__(self, stream):
        self.stream = stream
        self.lock = threading.Lock()

    def write(self, event):
        owner = "fifo:" + threading.current_thread().name
        event_type, source = event.get("type"), event.get("source")
        started = time.monotonic()
        DIAGNOSTICS.phase(owner, "encodeEvent", type=event_type, source=source)
        try:
            line = json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
            DIAGNOSTICS.phase(owner, "waitWriterLock", type=event_type, source=source,
                              characters=len(line))
            with self.lock:
                DIAGNOSTICS.phase(owner, "writeEvent", type=event_type, source=source,
                                  characters=len(line))
                self.stream.write(line)
                self.stream.flush()
            DIAGNOSTICS.sent(event_type, source, len(line))
            elapsed = int((time.monotonic() - started) * 1000)
            if elapsed >= 1000:
                diagnostic("slowFifoWrite", type=event_type, source=source,
                           characters=len(line), elapsedMillis=elapsed)
        finally:
            DIAGNOSTICS.done(owner)


def unique_export_path(directory):
    timestamp = int(time.time() * 1000)
    while True:
        output = os.path.join(directory, f"{timestamp}.pcap")
        if not os.path.exists(output) and not os.path.exists(output + ".part"):
            return output
        timestamp += 1


def copy_prefix(source, output, byte_count, write_header=True):
    start = 0 if write_header else PCAP_GLOBAL_HEADER_SIZE
    source.seek(start)
    remaining = byte_count - start
    while remaining > 0:
        chunk = source.read(min(1024 * 1024, remaining))
        if not chunk:
            raise IOError("原始 pcap 在导出过程中提前结束")
        output.write(chunk)
        remaining -= len(chunk)


def export_filtered(source, output, cutoff, bssid, device_mac, subtype_ids, write_header=True):
    reader = GrowingPcapReader(None, source)
    try:
        if not reader._open_if_ready():
            raise IOError("原始 pcap 文件头尚未写入完成")
        if write_header:
            output.write(reader.global_header)
        while reader.offset < cutoff:
            records = reader.read_available(
                limit_offset=cutoff,
                max_packets=EXPORT_BATCH_PACKETS,
            )
            if not records:
                break
            for payload, _, link_type, _, header, _ in records:
                packet = decode_packet(payload, link_type)
                relation = packet_device_relation(
                    packet,
                    target_pair=(bssid, device_mac),
                ) if packet is not None else None
                if relation is None:
                    continue
                packet_bssid_value, packet_device_mac, _ = relation
                if (
                    packet_bssid_value == bssid
                    and packet_device_mac == device_mac
                    and frame_subtype_id(packet) in subtype_ids
                ):
                    output.write(header)
                    output.write(payload)
    finally:
        reader.close()


def finish_timed_out_handshakes(access_points, handshake_device_keys, now_millis):
    for bssid, device_mac in tuple(handshake_device_keys):
        access_point = access_points.get(bssid)
        device = access_point["devices"].get(device_mac) if access_point else None
        record = device["activeHandshake"] if device else None
        if (
            record is not None
            and now_millis - record["startUnixMillis"] >= HANDSHAKE_TIMEOUT_MILLIS
        ):
            finish_handshake(
                device,
                "unknown",
                record["startUnixMillis"] + HANDSHAKE_TIMEOUT_MILLIS,
                timed_out=True,
            )


def handshake_event_metadata(record, hc22000=None):
    return {
        "id": record["id"],
        "bssid": record["bssid"],
        "deviceMac": record["deviceMac"],
        "startUnixMillis": record["startUnixMillis"],
        "lastUnixMillis": record["lastUnixMillis"],
        "status": record["status"],
        "captureQuality": handshake_capture_quality(record, hc22000),
        "capturedSteps": sorted(
            record["capturedSteps"],
            key=HANDSHAKE_STEP_ORDER.index,
        ),
        "failedAtStep": record["failedAtStep"],
        "failureReason": record["failureReason"],
        "m2AttemptCount": record["m2AttemptCount"],
        "exportPacketCount": len(record["packets"]),
    }


def handoff_handshake_updates(
    access_points,
    handshake_device_keys,
    pcap_header,
    event_writer,
):
    remaining_keys = set()
    for bssid, device_mac in tuple(handshake_device_keys):
        access_point = access_points.get(bssid)
        device = access_point["devices"].get(device_mac) if access_point else None
        if device is None:
            continue
        for record in list(device["handshakes"]):
            hc22000 = build_hc22000(record, record["ssidBytes"])
            pending_packets = record["packets"][record["transferredPacketCount"] :]
            for _, packet_header, packet_payload in pending_packets:
                if not record["headerTransferred"]:
                    if pcap_header is None or len(pcap_header) != PCAP_GLOBAL_HEADER_SIZE:
                        raise IOError("pcap 文件头尚未写入完成")
                    header_base64 = base64.b64encode(pcap_header).decode("ascii")
                else:
                    header_base64 = None
                part = packet_header + packet_payload
                for offset in range(0, len(part), HANDSHAKE_EVENT_CHUNK_BYTES):
                    chunk = part[offset : offset + HANDSHAKE_EVENT_CHUNK_BYTES]
                    event = {
                        "type": "handshakeData",
                        "handshake": handshake_event_metadata(record, hc22000),
                        "sequence": record["transferSequence"],
                        "pcapPartBase64": base64.b64encode(chunk).decode("ascii"),
                        "packetComplete": offset + len(chunk) >= len(part),
                    }
                    if header_base64 is not None:
                        event["pcapHeaderBase64"] = header_base64
                        header_base64 = None
                        record["headerTransferred"] = True
                    event_writer.write(event)
                    record["transferSequence"] += 1
                record["transferredPacketCount"] += 1

            if hc22000 is not None and hc22000 != record["transferredHc22000"]:
                event_writer.write(
                    {
                        "type": "handshakeValidation",
                        "handshake": handshake_event_metadata(record, hc22000),
                        "hc22000": hc22000,
                    }
                )
                record["transferredHc22000"] = hc22000

            if record["status"] != "inProgress":
                event_writer.write(
                    {
                        "type": "handshakeFinished",
                        "handshake": handshake_event_metadata(record, hc22000),
                    }
                )
                device["handshakes"].remove(record)
        if device["handshakes"]:
            remaining_keys.add((bssid, device_mac))
    return remaining_keys


def handoff_disconnection(
    pcap_header,
    event_writer,
    disconnection_id,
    bssid,
    device_mac,
    packet,
    packet_record,
    timestamp_millis,
):
    if pcap_header is None or len(pcap_header) != PCAP_GLOBAL_HEADER_SIZE:
        raise IOError("pcap 文件头尚未写入完成")
    dot11 = packet.getlayer(Dot11)
    subtype = int(dot11.subtype)
    event_metadata = {
        "id": str(disconnection_id),
        "bssid": bssid,
        "deviceMac": device_mac,
        "timestampUnixMillis": timestamp_millis,
        "disconnectionType": (
            "disassociation" if subtype == 10 else "deauthentication"
        ),
        "reasonCode": management_reason_code(packet),
        "exportPacketCount": 1,
    }
    _, packet_header, packet_payload = packet_record
    payload = packet_header + packet_payload
    sequence = 0
    for offset in range(0, len(payload), HANDSHAKE_EVENT_CHUNK_BYTES):
        chunk = payload[offset : offset + HANDSHAKE_EVENT_CHUNK_BYTES]
        event = {
            "type": "disconnectionData",
            "disconnection": event_metadata,
            "sequence": sequence,
            "pcapPartBase64": base64.b64encode(chunk).decode("ascii"),
            "packetComplete": offset + len(chunk) >= len(payload),
        }
        if sequence == 0:
            event["pcapHeaderBase64"] = base64.b64encode(pcap_header).decode("ascii")
        event_writer.write(event)
        sequence += 1


def run_export(
    command,
    sources,
    allowed_directory,
    event_writer,
):
    request_id = str(command.get("requestId", ""))
    try:
        requested_directory = os.path.realpath(str(command.get("outputDirectory", "")))
        if requested_directory != allowed_directory:
            raise ValueError("导出目录不在监听模式临时目录内")
        if not sources:
            raise IOError("pcap 文件头尚未写入完成")
        os.makedirs(allowed_directory, exist_ok=True)
        os.chmod(allowed_directory, 0o755)
        output_path = unique_export_path(allowed_directory)
        partial_path = output_path + ".part"
        mode = command.get("mode")
        with open(partial_path, "wb") as output:
            if mode == "all":
                for index, (source, cutoff) in enumerate(sources):
                    copy_prefix(source, output, cutoff, write_header=index == 0)
            elif mode == "filtered":
                bssid = normalized_unicast_mac(command.get("bssid"))
                device_mac = normalized_unicast_mac(command.get("deviceMac"))
                subtype_ids = set(command.get("subtypeIds") or ())
                if not bssid or not device_mac:
                    raise ValueError("局部导出缺少有效的接入点或设备 MAC")
                if not subtype_ids or not subtype_ids.issubset(EXPORT_SUBTYPE_IDS):
                    raise ValueError("局部导出的帧子类型无效")
                for index, (source, cutoff) in enumerate(sources):
                    export_filtered(source, output, cutoff, bssid, device_mac,
                                    subtype_ids, write_header=index == 0)
            else:
                raise ValueError(f"未知导出模式: {mode}")
            output.flush()
            os.fsync(output.fileno())
        os.replace(partial_path, output_path)
        os.chmod(output_path, 0o644)
        event_writer.write(
            {
                "type": "exportCompleted",
                "requestId": request_id,
                "path": output_path,
                "fileName": os.path.basename(output_path),
                "size": os.path.getsize(output_path),
            }
        )
    except Exception as error:
        try:
            if "partial_path" in locals() and os.path.exists(partial_path):
                os.remove(partial_path)
        except OSError:
            pass
        event_writer.write(
            {
                "type": "exportFailed",
                "requestId": request_id,
                "message": str(error) or error.__class__.__name__,
            }
        )
    finally:
        for source, _ in sources:
            source.close()


class CaptureSession:
    """tcpdump 的 stdout 为 pcap；仅追加完整记录，暂停后仍保留主文件。"""
    def __init__(self, path, writer):
        self.path = path
        self.writer = writer
        self.process = None
        self.worker = None
        self.analyzed_offset = 0
        self.copied_packets = 0
        self.copied_bytes = 0
        self.last_packet_unix_millis = None

    def start(self):
        if self.process is not None:
            return
        diagnostic("captureStartRequested", analyzedOffset=self.analyzed_offset)
        self.process = subprocess.Popen(
            ["tcpdump", "-U", "-i", "wlan0", "-w", "-"],
            stdout=subprocess.PIPE, stderr=None)
        process = self.process
        diagnostic("captureProcessStarted", tcpdumpPid=process.pid)
        self.worker = threading.Thread(target=self._copy, args=(process,),
                                       name="capture-copy", daemon=True)
        self.worker.start()
        self.writer.write({"type": "captureState", "capturing": True})

    def _copy(self, process):
        try:
            DIAGNOSTICS.phase("copy", "readGlobalHeader", tcpdumpPid=process.pid)
            header = process.stdout.read(24)
            if len(header) != 24:
                raise IOError("tcpdump 没有返回完整 pcap 文件头")
            endian = "<" if header[:4] in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1") else ">"
            with open(self.path, "ab", buffering=0) as output:
                if output.tell() == 0:
                    output.write(header)
                while True:
                    DIAGNOSTICS.phase("copy", "readPacketHeader", tcpdumpPid=process.pid)
                    record = process.stdout.read(16)
                    if not record:
                        diagnostic("captureStreamEnded", tcpdumpPid=process.pid)
                        break
                    if len(record) != 16:
                        diagnostic("capturePartialHeader", tcpdumpPid=process.pid,
                                   receivedBytes=len(record))
                        break
                    length = struct.unpack(endian + "IIII", record)[2]
                    if length > MAX_CAPTURED_PACKET_SIZE:
                        raise IOError("tcpdump 返回异常包长度")
                    DIAGNOSTICS.phase("copy", "readPayload", tcpdumpPid=process.pid,
                                      expectedBytes=length)
                    payload = process.stdout.read(length)
                    if len(payload) != length:
                        diagnostic("capturePartialPayload", tcpdumpPid=process.pid,
                                   expectedBytes=length, receivedBytes=len(payload))
                        break
                    DIAGNOSTICS.phase("copy", "backpressure", tcpdumpPid=process.pid)
                    while output.tell() - self.analyzed_offset >= ANALYSIS_BATCH_MAX_BYTES and self.process is process:
                        time.sleep(0.01)
                    DIAGNOSTICS.phase("copy", "appendRecord", tcpdumpPid=process.pid)
                    output.write(record + payload)
                    self.copied_packets += 1
                    self.copied_bytes += 16 + length
                    seconds, fraction = struct.unpack(endian + "IIII", record)[:2]
                    divisor = 1_000_000 if header[:4] in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d") else 1000
                    self.last_packet_unix_millis = seconds * 1000 + fraction // divisor
            DIAGNOSTICS.phase("copy", "waitProcessExit", tcpdumpPid=process.pid)
            code = process.wait()
            diagnostic("captureProcessExited", tcpdumpPid=process.pid, exitCode=code,
                       stopRequested=self.process is not process)
            if self.process is process:
                self.process = None
                self.writer.write({"type": "captureState", "capturing": False})
                self.writer.write({"type": "commandFailed", "message": f"tcpdump 意外退出，退出码 {code}"})
        except Exception as error:
            diagnostic("captureCopyFailed", tcpdumpPid=process.pid, errorType=type(error).__name__)
            if process.poll() is None:
                process.terminate()
                process.wait()
            process.stdout.close()
            if self.process is process:
                self.process = None
                self.writer.write({"type": "captureState", "capturing": False})
                self.writer.write({"type": "commandFailed", "message": str(error)})
        finally:
            DIAGNOSTICS.done("copy")

    def stop(self):
        process = self.process
        self.process = None
        if process is not None:
            started = time.monotonic()
            diagnostic("captureStopBegin", tcpdumpPid=process.pid)
            DIAGNOSTICS.phase("capture-stop", "terminate", tcpdumpPid=process.pid)
            process.terminate()
            DIAGNOSTICS.phase("capture-stop", "waitProcessExit", tcpdumpPid=process.pid)
            process.wait()
            if self.worker is not None:
                DIAGNOSTICS.phase("capture-stop", "joinCopyWorker", tcpdumpPid=process.pid)
                self.worker.join()
            process.stdout.close()
            DIAGNOSTICS.done("capture-stop")
            diagnostic("captureStopEnd", tcpdumpPid=process.pid, exitCode=process.returncode,
                       elapsedMillis=int((time.monotonic() - started) * 1000))


class CaptureAnalysis:
    """同一解析实现分别处理完整流与实际保留流，不从旧镜像拼装清理结果。"""
    def __init__(self):
        self.access_points = {}
        self.ssid_access_points = {}
        self.device_identities = {}
        self.dirty_access_points = set()
        self.dirty_devices = set()
        self.realtime_access_points = set()
        self.realtime_devices = set()
        self.handshake_device_keys = set()
        self.next_handshake_id = 1
        self.next_disconnection_id = 1
        self.next_packet_id = 0
        self.signal_samples = 0
        self.last_signal_unix_millis = None

    def fork(self):
        # 包体 bytes 为不可变对象，deepcopy 共享它们；只分离可变解析状态。
        return copy.deepcopy(self)

    def consume(self, record, packet, pcap_header, event_writer):
        payload, recorded_bytes, link_type, _, packet_header, packet_timestamp = record
        # 分段切换后物理偏移会归零，使用单调 ID 标识握手证据与上下文。
        self.next_packet_id += 1
        packet_start = self.next_packet_id
        if packet is None:
            return False
        packet_record = (packet_start, packet_header, payload)

        ssid = packet_ssid(packet)
        ssid_bytes = ssid_element(packet) if ssid else None
        if ssid_bytes:
            for known_bssid in tuple(self.ssid_access_points.get(ssid_bytes, ())):
                known_access_point = self.access_points.get(known_bssid)
                if known_access_point is None:
                    continue
                known_access_point["ssidContextPacket"] = packet_record
                active_device_macs = (
                    device_mac
                    for active_bssid, device_mac in self.handshake_device_keys
                    if active_bssid == known_bssid
                )
                add_context_to_handshake_records(
                    known_access_point,
                    packet_record,
                    ssid_bytes=ssid_bytes,
                    has_security=False,
                    device_macs=active_device_macs,
                )

        bssid = packet_bssid(packet)
        if bssid is not None:
            access_point = self.access_points.setdefault(bssid, empty_access_point(bssid))
            self.dirty_access_points.add(bssid)
            if ssid:
                previous_ssid_bytes = access_point["ssidBytes"]
                access_point["ssid"] = ssid
                access_point["ssidBytes"] = ssid_bytes
                access_point["ssidContextPacket"] = packet_record
                if previous_ssid_bytes != ssid_bytes:
                    if previous_ssid_bytes:
                        previous_bssids = self.ssid_access_points.get(previous_ssid_bytes)
                        if previous_bssids is not None:
                            previous_bssids.discard(bssid)
                            if not previous_bssids:
                                self.ssid_access_points.pop(previous_ssid_bytes, None)
                    if ssid_bytes:
                        self.ssid_access_points.setdefault(ssid_bytes, set()).add(bssid)
            visibility = beacon_visibility(packet)
            if visibility is not None:
                access_point["ssidVisibility"] = visibility
            dot11 = packet.getlayer(Dot11)
            management_subtype = (
                int(dot11.subtype)
                if dot11 is not None and int(dot11.type) == 0
                else None
            )
            protocols = packet_security_protocols(packet)
            access_point["securityProtocols"].update(protocols)
            if management_subtype in (0, 2, 5, 8):
                if protocols:
                    access_point["securityContextPacket"] = packet_record
                if ssid or protocols:
                    active_device_macs = (
                        device_mac
                        for active_bssid, device_mac in self.handshake_device_keys
                        if active_bssid == bssid
                    )
                    add_context_to_handshake_records(
                        access_point,
                        packet_record,
                        ssid_bytes=ssid_bytes,
                        has_security=bool(protocols),
                        device_macs=active_device_macs,
                    )
            transmitter = normalized_unicast_mac(dot11.addr2) if dot11 else None
            signal_dbm = packet_signal_dbm(packet)
            if transmitter == bssid:
                add_signal_sample(
                    access_point["signal"], packet_timestamp, signal_dbm
                )
                if signal_dbm is not None:
                    self.signal_samples += 1
                    self.last_signal_unix_millis = int(packet_timestamp * 1000)
                    self.realtime_access_points.add(bssid)

        dot11 = packet.getlayer(Dot11)
        transmitter = normalized_unicast_mac(dot11.addr2) if dot11 else None
        wps_name, wps_priority = wps_device_identity(packet)
        if transmitter and wps_name:
            previous = self.device_identities.get(transmitter)
            if previous is None or wps_priority > previous[1]:
                self.device_identities[transmitter] = (wps_name, wps_priority)
                for known_access_point in self.access_points.values():
                    known_device = known_access_point["devices"].get(transmitter)
                    if known_device is not None:
                        update_device_identity(
                            known_device, wps_name, wps_priority
                        )
                        self.dirty_devices.add(
                            (known_access_point["bssid"], transmitter)
                        )

        relation = packet_device_relation(
            packet,
            known_bssids=self.access_points.keys(),
        )
        if relation is None:
            return False
        bssid, device_mac, direction = relation
        access_point = self.access_points.setdefault(bssid, empty_access_point(bssid))
        self.dirty_access_points.add(bssid)
        device = access_point["devices"].setdefault(
            device_mac, empty_device(device_mac)
        )
        device_key = (bssid, device_mac)
        self.dirty_devices.add(device_key)
        frame_bytes = packet_frame_bytes(packet, recorded_bytes)
        subtype_id = frame_subtype_id(packet)
        add_frame_counter(device, subtype_id, frame_bytes)
        if subtype_id in ("management.10", "management.12"):
            handoff_disconnection(
                pcap_header=pcap_header,
                event_writer=event_writer,
                disconnection_id=self.next_disconnection_id,
                bssid=bssid,
                device_mac=device_mac,
                packet=packet,
                packet_record=packet_record,
                timestamp_millis=int(packet_timestamp * 1000),
            )
            self.next_disconnection_id += 1
        previous_ssid_bytes = access_point["ssidBytes"]
        self.next_handshake_id = process_handshake_packet(
            access_point=access_point,
            device=device,
            bssid=bssid,
            device_mac=device_mac,
            packet=packet,
            subtype_id=subtype_id,
            transmitter=transmitter,
            packet_record=packet_record,
            packet_timestamp=packet_timestamp,
            next_handshake_id=self.next_handshake_id,
        )
        current_ssid_bytes = access_point["ssidBytes"]
        if previous_ssid_bytes != current_ssid_bytes:
            if previous_ssid_bytes:
                previous_bssids = self.ssid_access_points.get(previous_ssid_bytes)
                if previous_bssids is not None:
                    previous_bssids.discard(bssid)
                    if not previous_bssids:
                        self.ssid_access_points.pop(previous_ssid_bytes, None)
            if current_ssid_bytes:
                self.ssid_access_points.setdefault(current_ssid_bytes, set()).add(bssid)
        if device["handshakes"]:
            self.handshake_device_keys.add(device_key)
        if direction == "upload" and subtype_id != "data.eapol":
            device["uploadSamples"].append((packet_timestamp, frame_bytes))
            self.realtime_devices.add(device_key)
        elif direction == "download" and subtype_id != "data.eapol":
            device["downloadSamples"].append((packet_timestamp, frame_bytes))
            self.realtime_devices.add(device_key)

        if transmitter == device_mac:
            signal_dbm = packet_signal_dbm(packet)
            add_signal_sample(
                device["signal"], packet_timestamp, signal_dbm
            )
            if signal_dbm is not None:
                self.signal_samples += 1
                self.last_signal_unix_millis = int(packet_timestamp * 1000)
                self.realtime_devices.add(device_key)

        identity_candidates = [dhcp_device_identity(packet)]
        cached_identity = self.device_identities.get(device_mac)
        if cached_identity is not None:
            identity_candidates.append(cached_identity)
        if direction == "upload":
            identity_candidates.append(mdns_device_identity(packet))
        for name, priority in identity_candidates:
            update_device_identity(device, name, priority)

        return any(packet_start in value["packetOffsets"] for value in device["handshakes"])

    def publish(self, pcap_header, event_writer, now):
        finish_timed_out_handshakes(
            self.access_points,
            self.handshake_device_keys,
            int(now * 1000),
        )
        self.handshake_device_keys = handoff_handshake_updates(
            self.access_points,
            self.handshake_device_keys,
            pcap_header,
            event_writer,
        )

        candidate_devices = self.dirty_devices | self.realtime_devices
        devices_by_bssid = {}
        for candidate_bssid, candidate_device_mac in candidate_devices:
            devices_by_bssid.setdefault(candidate_bssid, set()).add(
                candidate_device_mac
            )
        candidate_bssids = (
            self.dirty_access_points
            | self.realtime_access_points
            | set(devices_by_bssid)
        )
        next_realtime_access_points = set()
        next_realtime_devices = set()
        access_point_updates = []
        for candidate_bssid in sorted(candidate_bssids):
            access_point = self.access_points.get(candidate_bssid)
            if access_point is None:
                continue
            signal = signal_snapshot(access_point["signal"], now)
            if access_point["signal"]["samples"]:
                next_realtime_access_points.add(candidate_bssid)
            access_point_signature = stable_signature(
                {
                    "ssid": access_point["ssid"],
                    "ssidVisibility": access_point["ssidVisibility"],
                    "securityProtocols": sorted(
                        access_point["securityProtocols"]
                    ),
                    "signal": signal,
                }
            )
            changed_device_snapshots = []
            for device_mac in sorted(devices_by_bssid.get(candidate_bssid, ())):
                device = access_point["devices"].get(device_mac)
                if device is None:
                    continue
                snapshot = device_snapshot(device, now)
                if (
                    device["uploadSamples"]
                    or device["downloadSamples"]
                    or device["signal"]["samples"]
                ):
                    next_realtime_devices.add((candidate_bssid, device_mac))
                signature = stable_signature(snapshot)
                if signature != device["publishedSignature"]:
                    changed_device_snapshots.append(snapshot)
                    device["publishedSignature"] = signature
            if (
                access_point_signature != access_point["publishedSignature"]
                or changed_device_snapshots
            ):
                access_point_updates.append(
                    {
                        "bssid": candidate_bssid,
                        "ssid": access_point["ssid"],
                        "ssidVisibility": access_point["ssidVisibility"],
                        "securityProtocols": sorted(
                            access_point["securityProtocols"]
                        ),
                        "signal": signal,
                        "devices": changed_device_snapshots,
                    }
                )
                access_point["publishedSignature"] = access_point_signature

        self.dirty_access_points.clear()
        self.dirty_devices.clear()
        self.realtime_access_points = next_realtime_access_points
        self.realtime_devices = next_realtime_devices
        if access_point_updates:
            event_writer.write(
                {
                    "type": "statistics",
                    "accessPointUpdates": access_point_updates,
                }
            )


class AnalysisEventWriter:
    def __init__(self, writer, source):
        self.writer = writer
        self.source = source

    def write(self, event):
        self.writer.write(dict(event, source=self.source))


class CaptureFiles:
    """保留前缀为不可变 PCAP 段；清理只切换引用，导出时合成单个标准 PCAP。"""
    def __init__(self, path, directory):
        self.path = path
        self.directory = directory
        os.makedirs(directory, exist_ok=True)
        self.segments = []
        self.header = None
        self.retained_path = None
        self.retained_stream = None
        self.non_handshake_bytes = 0
        self.retired = set()
        self.lock = threading.Lock()
        self.deleter = ThreadPoolExecutor(max_workers=1, thread_name_prefix="capture-delete")
        self._new_retained()

    def _new_path(self):
        return os.path.join(self.directory, str(uuid.uuid4()) + ".pcap")

    def _new_retained(self):
        self.retained_path = self._new_path()
        self.retained_stream = open(self.retained_path, "wb")
        if self.header is not None:
            self.retained_stream.write(self.header)

    def retain(self, record, header):
        if self.header is None:
            self.header = header
            self.retained_stream.write(header)
        self.retained_stream.write(record[4] + record[0])

    def flush(self):
        self.retained_stream.flush()

    def paths(self):
        return self.segments + [self.path]

    def snapshot(self, cutoff):
        sources = []
        try:
            for path in self.paths():
                end = cutoff if path == self.path else os.path.getsize(path)
                if end >= PCAP_GLOBAL_HEADER_SIZE:
                    sources.append((open(path, "rb", buffering=0), end))
            return sources
        except Exception:
            for stream, _ in sources:
                stream.close()
            raise

    def _remove(self, path):
        started = time.monotonic()
        diagnostic("removeSegmentBegin", segment=os.path.basename(path),
                   bytes=diagnostic_file_size(path))
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
        finally:
            with self.lock:
                self.retired.discard(path)
            diagnostic("removeSegmentEnd", segment=os.path.basename(path),
                       elapsedMillis=int((time.monotonic() - started) * 1000))

    def clear(self, keep):
        started = time.monotonic()
        diagnostic("clearFilesBegin", handshakesOnly=keep, segments=len(self.segments),
                   tailBytes=diagnostic_file_size(self.path),
                   retainedTailBytes=diagnostic_file_size(self.retained_path))
        self.flush()
        self.retained_stream.close()
        added_segment = None
        if keep:
            if os.path.getsize(self.retained_path) > PCAP_GLOBAL_HEADER_SIZE:
                self.segments.append(self.retained_path)
                added_segment = self.retained_path
            else:
                self._remove(self.retained_path)
            if os.path.exists(self.path):
                discarded = self._new_path()
                os.replace(self.path, discarded)
                with self.lock:
                    self.retired.add(discarded)
                self.deleter.submit(self._remove, discarded)
        else:
            # 全部清空包括后台待删除旧段；只删除本会话记录的明确文件。
            with self.lock:
                obsolete = set(self.retired)
            obsolete.update(self.segments)
            obsolete.update((self.path, self.retained_path))
            for path in obsolete:
                self._remove(path)
            self.segments.clear()
        with open(self.path, "wb", buffering=0) as output:
            if self.header is not None:
                output.write(self.header)
        self._new_retained()
        self.non_handshake_bytes = 0
        diagnostic("clearFilesEnd", handshakesOnly=keep, segments=len(self.segments),
                   addedSegment=os.path.basename(added_segment) if added_segment else None,
                   elapsedMillis=int((time.monotonic() - started) * 1000))
        return added_segment

    def close(self):
        self.retained_stream.close()
        self.deleter.shutdown(wait=True)


def command_loop(
    path,
    reader,
    reader_lock,
    files,
    allowed_directory,
    event_writer,
    commands,
):
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="monitor-commands")
    DIAGNOSTICS.phase("commands", "openCommandFifo")
    with open(path, "r", encoding="utf-8") as command_pipe:
        diagnostic("commandFifoOpened", fifoId=os.path.basename(path))
        DIAGNOSTICS.phase("commands", "readCommand")
        for line in command_pipe:
            if not line.strip():
                continue
            command = {}
            try:
                command = json.loads(line)
                command_type = command.get("type")
                diagnostic("commandReceived", type=command_type,
                           enabled=command.get("enabled"),
                           handshakesOnly=command.get("handshakesOnly"),
                           requestId=command.get("requestId"), queued=commands.qsize())
                DIAGNOSTICS.phase("commands", "dispatchCommand", type=command_type)
                if command_type == "export":
                    DIAGNOSTICS.phase("commands", "snapshotExport", type=command_type)
                    with reader_lock:
                        sources = files.snapshot(reader.offset)
                    executor.submit(run_export, command, sources, allowed_directory, event_writer)
                elif command_type in ("capture", "clear"):
                    commands.put(command)
                    diagnostic("commandQueued", type=command_type, queued=commands.qsize())
                else:
                    raise ValueError(f"未知监听模式命令: {command_type}")
            except Exception as error:
                diagnostic("commandFailed", errorType=type(error).__name__)
                event_writer.write(
                    {
                        "type": "commandFailed",
                        "requestId": str(command.get("requestId", "")),
                        "message": str(error) or error.__class__.__name__,
                    }
                )
            finally:
                DIAGNOSTICS.phase("commands", "readCommand")
    diagnostic("commandFifoClosed")
    DIAGNOSTICS.done("commands")


def main():
    args = parse_args()
    reader = GrowingPcapReader(args.pcap)
    reader_lock = threading.Lock()
    commands = queue.Queue()
    full = CaptureAnalysis()
    retained = CaptureAnalysis()
    next_publish = time.monotonic()
    pending_clear = None
    capture = None
    files = None
    interface_diagnostics = None
    next_interface_diagnostics = 0.0
    directory = os.path.dirname(args.event_pipe)
    allowed_export_directory = os.path.realpath(os.path.join(directory, "exports"))
    def progress_snapshot():
        nonlocal interface_diagnostics, next_interface_diagnostics
        if time.monotonic() >= next_interface_diagnostics:
            interface_diagnostics = interface_snapshot("wlan0")
            next_interface_diagnostics = time.monotonic() + 3.0
        tail_bytes = diagnostic_file_size(args.pcap)
        process = capture.process if capture else None
        clear = pending_clear
        return dict(eventFifoId=os.path.basename(args.event_pipe),
                    commandFifoId=os.path.basename(args.command_pipe),
                    readerOffset=reader.offset, tailBytes=tail_bytes,
                    unreadBytes=max(0, tail_bytes - reader.offset) if tail_bytes is not None else None,
                    analysisBacklogBytes=max(0, tail_bytes - capture.analyzed_offset) if tail_bytes is not None and capture else None,
                    fullPackets=full.next_packet_id, retainedPackets=retained.next_packet_id,
                    accessPoints=len(full.access_points), activeHandshakeDevices=len(full.handshake_device_keys),
                    signalSamples=full.signal_samples, lastSignalUnixMillis=full.last_signal_unix_millis,
                    retainedAccessPoints=len(retained.access_points),
                    retainedSignalSamples=retained.signal_samples,
                    retainedLastSignalUnixMillis=retained.last_signal_unix_millis,
                    queuedCommands=commands.qsize(), pendingClear=clear is not None,
                    clearCutoff=clear.get("cutoff") if clear else None,
                    tcpdumpPid=process.pid if process else None,
                    tcpdumpState=process_snapshot(process),
                    interfaceState=interface_diagnostics,
                    copyWorkerAlive=capture.worker.is_alive() if capture and capture.worker else False,
                    copiedPackets=capture.copied_packets if capture else 0,
                    copiedBytes=capture.copied_bytes if capture else 0,
                    lastPacketUnixMillis=capture.last_packet_unix_millis if capture else None,
                    analyzedOffset=capture.analyzed_offset if capture else None,
                    segments=len(files.segments) if files else 0,
                    nonHandshakeBytes=files.non_handshake_bytes if files else 0,
                    retainedTailBytes=diagnostic_file_size(files.retained_path) if files else None)
    diagnostic("analysisStart", eventFifoId=os.path.basename(args.event_pipe),
               commandFifoId=os.path.basename(args.command_pipe))
    DIAGNOSTICS.phase("main", "openEventFifo")
    threading.Thread(target=DIAGNOSTICS.heartbeat, args=(progress_snapshot,),
                     name="monitor-diagnostics", daemon=True).start()
    try:
        with open(args.event_pipe, "w", encoding="utf-8", buffering=1) as event_pipe:
            diagnostic("eventFifoOpened", fifoId=os.path.basename(args.event_pipe))
            DIAGNOSTICS.phase("main", "prepareCapture")
            writer = EventWriter(event_pipe)
            full_writer = AnalysisEventWriter(writer, "full")
            retained_writer = AnalysisEventWriter(writer, "retained")
            files = CaptureFiles(args.pcap, os.path.join(directory, "capture"))
            capture = CaptureSession(args.pcap, writer)
            writer.write({"type": "captureFiles", "paths": files.paths()})
            threading.Thread(target=command_loop,
                             args=(args.command_pipe, reader, reader_lock, files,
                                   allowed_export_directory, writer, commands),
                             name="monitor-commands", daemon=True).start()
            while True:
                if pending_clear is None and not commands.empty():
                    command = commands.get()
                    diagnostic("commandExecuteBegin", type=command["type"],
                               enabled=command.get("enabled"), handshakesOnly=command.get("handshakesOnly"),
                               queued=commands.qsize(), readerOffset=reader.offset)
                    DIAGNOSTICS.phase("main", "executeCommand", type=command["type"])
                    try:
                        if command["type"] == "capture":
                            if command["enabled"]:
                                capture.start()
                            else:
                                capture.stop()
                                writer.write({"type": "captureState", "capturing": False})
                        else:
                            resume = capture.process is not None
                            capture.stop()
                            cutoff = os.path.getsize(args.pcap) if os.path.exists(args.pcap) else 0
                            pending_clear = dict(command, resume=resume, cutoff=cutoff,
                                                 startOffset=reader.offset)
                            diagnostic("clearDrainBegin", handshakesOnly=command["handshakesOnly"],
                                       resume=resume, cutoff=cutoff, startOffset=reader.offset)
                    except Exception as error:
                        diagnostic("commandExecuteFailed", type=command["type"],
                                   errorType=type(error).__name__)
                        writer.write({"type": "commandFailed", "message": str(error)})
                        writer.write({"type": "captureState", "capturing": capture.process is not None})
                    diagnostic("commandExecuteEnd", type=command["type"], pendingClear=pending_clear is not None)

                keep = pending_clear is None or pending_clear["handshakesOnly"]
                records = []
                if keep:
                    DIAGNOSTICS.phase("main", "readBatch", readerOffset=reader.offset)
                    with reader_lock:
                        records = reader.read_available(max_packets=ANALYSIS_BATCH_MAX_PACKETS,
                                                        max_bytes=ANALYSIS_BATCH_MAX_BYTES)
                    if reader.global_header is not None and files.header is None:
                        files.header = reader.global_header
                        files.retained_stream.write(files.header)
                    DIAGNOSTICS.phase("main", "parseBatch", packets=len(records), endOffset=reader.offset)
                    for record in records:
                        packet = decode_packet(record[0], record[2])
                        evidence = full.consume(record, packet, reader.global_header, full_writer)
                        dot11 = packet.getlayer(Dot11) if packet is not None else None
                        candidate = packet is not None and (frame_subtype_id(packet) == "data.eapol" or (
                            dot11 is not None and int(dot11.type) == 0 and
                            int(dot11.subtype) in (0, 1, 2, 3, 4, 5, 8, 10, 11, 12)))
                        if candidate or evidence:
                            files.retain(record, reader.global_header)
                            retained.consume(record, packet, reader.global_header, retained_writer)
                        else:
                            files.non_handshake_bytes += PCAP_PACKET_HEADER_SIZE + len(record[0])
                    capture.analyzed_offset = reader.offset

                ready = pending_clear is not None and (
                    not pending_clear["handshakesOnly"] or reader.offset >= pending_clear["cutoff"] or not records)
                now = time.monotonic()
                if now >= next_publish or ready:
                    timestamp = time.time()
                    DIAGNOSTICS.phase("main", "publishFull")
                    full.publish(reader.global_header, full_writer, timestamp)
                    DIAGNOSTICS.phase("main", "publishRetained")
                    retained.publish(reader.global_header, retained_writer, timestamp)
                    DIAGNOSTICS.phase("main", "flushRetained")
                    files.flush()
                    writer.write({"type": "captureSizes", "nonHandshakeBytes": files.non_handshake_bytes})
                    next_publish = now + PUBLISH_INTERVAL_SECONDS
                    if pending_clear is not None:
                        total = max(0, pending_clear["cutoff"] - pending_clear["startOffset"])
                        writer.write({"type": "clearProgress", "stage": "preparing",
                                      "processedBytes": max(0, reader.offset - pending_clear["startOffset"]),
                                      "totalBytes": total})
                if ready:
                    command = pending_clear
                    pending_clear = None
                    try:
                        diagnostic("clearSwitchBegin", handshakesOnly=command["handshakesOnly"],
                                   resume=command["resume"], readerOffset=reader.offset)
                        DIAGNOSTICS.phase("main", "switchClearMirror")
                        writer.write({"type": "clearProgress", "stage": "switching",
                                      "processedBytes": 0, "totalBytes": 0})
                        if command["handshakesOnly"]:
                            next_full = retained.fork()
                            next_retained = retained
                        else:
                            next_full = CaptureAnalysis()
                            next_retained = CaptureAnalysis()
                        DIAGNOSTICS.phase("main", "clearFiles")
                        with reader_lock:
                            added_segment = files.clear(command["handshakesOnly"])
                            DIAGNOSTICS.phase("main", "resetReader")
                            reader._reset()
                            reader._open_if_ready()
                            capture.analyzed_offset = reader.offset
                        full = next_full
                        retained = next_retained
                        DIAGNOSTICS.phase("main", "publishCaptureReset")
                        writer.write({"type": "captureReset", "retainPrepared": command["handshakesOnly"],
                                      "addedSegment": added_segment})
                        writer.write({"type": "clearProgress", "stage": "finished",
                                      "processedBytes": 0, "totalBytes": 0})
                        if command["resume"]:
                            DIAGNOSTICS.phase("main", "resumeCapture")
                            capture.start()
                        else:
                            writer.write({"type": "captureState", "capturing": False})
                        diagnostic("clearSwitchEnd", handshakesOnly=command["handshakesOnly"],
                                   resumed=capture.process is not None, readerOffset=reader.offset)
                    except Exception as error:
                        diagnostic("clearSwitchFailed", errorType=type(error).__name__)
                        writer.write({"type": "commandFailed", "message": str(error)})
                        writer.write({"type": "captureState", "capturing": False})
                try:
                    has_backlog = reader.offset < os.path.getsize(args.pcap)
                except FileNotFoundError:
                    has_backlog = False
                if has_backlog or pending_clear is not None or not commands.empty():
                    continue
                DIAGNOSTICS.phase("main", "idle")
                time.sleep(max(0, next_publish - time.monotonic()))
    finally:
        diagnostic("analysisCleanupBegin")
        DIAGNOSTICS.phase("main", "cleanupCapture")
        if capture is not None:
            capture.stop()
        DIAGNOSTICS.phase("main", "cleanupFiles")
        if files is not None:
            files.close()
        reader.close()
        DIAGNOSTICS.stop_event.set()
        diagnostic("analysisCleanupEnd")


if __name__ == "__main__":
    def stop_signal(_signal, _frame):
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, stop_signal)
    main()
