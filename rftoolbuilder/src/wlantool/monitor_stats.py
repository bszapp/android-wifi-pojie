#!/usr/bin/env python3
"""Incrementally analyze and export a growing monitor-mode pcap file."""

import argparse
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
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


PUBLISH_INTERVAL_SECONDS = 0.05
REALTIME_WINDOW_SECONDS = 1.0
PCAP_GLOBAL_HEADER_SIZE = 24
PCAP_PACKET_HEADER_SIZE = 16
MAX_CAPTURED_PACKET_SIZE = 16 * 1024 * 1024
EXPORT_BATCH_PACKETS = 2048
WPS_VENDOR_PREFIX = b"\x00\x50\xf2\x04"
WPS_DEVICE_NAME = 0x1011
WPS_MANUFACTURER = 0x1021
WPS_MODEL_NAME = 0x1023
WPS_MODEL_NUMBER = 0x1024
WPA_VENDOR_PREFIX = b"\x00\x50\xf2\x01"
RSN_AKM_OUI = b"\x00\x0f\xac"
WPA_AKM_OUI = b"\x00\x50\xf2"
PSK_AKM_TYPE = 2


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
        first_octet = int(normalized.split(":", 1)[0], 16)
    except (ValueError, IndexError):
        return None
    if first_octet & 1:
        return None
    return normalized


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
    if dot11 is None or int(dot11.type) != 0 or int(dot11.subtype) not in (0, 2, 5, 8):
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
    cutoff = now - REALTIME_WINDOW_SECONDS
    while samples and samples[0][0] < cutoff:
        samples.popleft()
    return sum(sample[1] for sample in samples)


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


def handshake_snapshot(record, now):
    end_unix_millis = (
        int(now * 1000)
        if record["status"] == "inProgress"
        else record["lastUnixMillis"]
    )
    return {
        "id": record["id"],
        "startUnixMillis": record["startUnixMillis"],
        "durationMillis": max(
            0,
            end_unix_millis - record["startUnixMillis"],
        ),
        "status": record["status"],
        "canValidate": record["validation"] is not None,
        "validationDataComplete": record["validationDataComplete"],
        "capturedSteps": sorted(
            record["capturedSteps"],
            key=HANDSHAKE_STEP_ORDER.index,
        ),
        "failedAtStep": record["failedAtStep"],
        "failureReason": record["failureReason"],
        "m2AttemptCount": record["m2AttemptCount"],
        "exportPacketCount": len(record["packets"]),
    }


def device_snapshot(device, now):
    return {
        "mac": device["mac"],
        "name": device["name"],
        "frameGroups": frame_groups_snapshot(device),
        "handshakes": [
            handshake_snapshot(record, now) for record in device["handshakes"]
        ],
        "uploadBytesPerSecond": byte_rate(device["uploadSamples"], now),
        "downloadBytesPerSecond": byte_rate(device["downloadSamples"], now),
        "signal": signal_snapshot(device["signal"], now),
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


def append_handshake_packet(record, packet_record, handshake_lock):
    packet_offset, packet_header, packet_payload = packet_record
    with handshake_lock:
        if packet_offset in record["packetOffsets"]:
            return
        record["packetOffsets"].add(packet_offset)
        record["packets"].append((packet_offset, packet_header, packet_payload))


def create_handshake_record(
    device,
    access_point,
    bssid,
    device_mac,
    timestamp_millis,
    handshake_cache,
    handshake_lock,
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
        "validationDataComplete": False,
        "packetOffsets": set(),
        "packets": [],
        "hasSsidContext": False,
        "hasSecurityContext": False,
    }
    device["handshakes"].append(record)
    device["activeHandshake"] = record
    with handshake_lock:
        handshake_cache[handshake_id] = record
    context_packets = {
        packet[0]: packet
        for packet in (
            access_point["ssidContextPacket"],
            access_point["securityContextPacket"],
            device["ssidContextPacket"],
        )
        if packet is not None
    }
    for packet_record in sorted(context_packets.values(), key=lambda value: value[0]):
        append_handshake_packet(record, packet_record, handshake_lock)
    record["hasSsidContext"] = (
        access_point["ssidContextPacket"] is not None
        or device["ssidContextPacket"] is not None
    )
    record["hasSecurityContext"] = access_point["securityContextPacket"] is not None
    return record, next_handshake_id + 1


def add_context_to_handshake_records(
    access_point,
    packet_record,
    has_ssid,
    has_security,
    handshake_lock,
):
    for device in access_point["devices"].values():
        for record in device["handshakes"]:
            needs_ssid = has_ssid and not record["hasSsidContext"]
            needs_security = has_security and not record["hasSecurityContext"]
            if not needs_ssid and not needs_security:
                continue
            append_handshake_packet(record, packet_record, handshake_lock)
            if needs_ssid:
                record["hasSsidContext"] = True
            if needs_security:
                record["hasSecurityContext"] = True


def add_handshake_step(record, step):
    record["capturedSteps"].add(step)
    record["lastStep"] = step


def set_handshake_validation_data(
    record,
    authenticator_key,
    supplicant_key,
    handshake_lock,
):
    if (
        authenticator_key["descriptorVersion"]
        != supplicant_key["descriptorVersion"]
        or not any(authenticator_key["nonce"])
        or not any(supplicant_key["nonce"])
        or not any(supplicant_key["mic"])
    ):
        return
    record["validationDataComplete"] = True
    if supplicant_key["descriptorVersion"] not in (1, 2):
        return
    with handshake_lock:
        record["validation"] = {
            "anonce": authenticator_key["nonce"],
            "snonce": supplicant_key["nonce"],
            "descriptorVersion": supplicant_key["descriptorVersion"],
            "mic": supplicant_key["mic"],
            "eapolFrame": supplicant_key["frame"],
        }


def finish_handshake(
    device,
    status,
    timestamp_millis,
    failure_reason=None,
    failed_at_step=None,
):
    record = device["activeHandshake"]
    if record is None:
        return
    record["lastUnixMillis"] = max(record["lastUnixMillis"], timestamp_millis)
    record["status"] = status
    record["failureReason"] = failure_reason
    record["failedAtStep"] = failed_at_step
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
    handshake_cache,
    handshake_lock,
    next_handshake_id,
):
    timestamp_millis = int(packet_timestamp * 1000)
    record = device["activeHandshake"]
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
    if starts_association and packet_ssid(packet):
        device["ssidContextPacket"] = packet_record
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
                handshake_cache,
                handshake_lock,
                next_handshake_id,
            )
        if record is not None:
            previous_step = record["lastStep"]
            append_handshake_packet(record, packet_record, handshake_lock)
            if packet_ssid(packet):
                record["hasSsidContext"] = True
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
            append_handshake_packet(record, packet_record, handshake_lock)
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
            handshake_cache,
            handshake_lock,
            next_handshake_id,
        )
    append_handshake_packet(record, packet_record, handshake_lock)
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
            set_handshake_validation_data(record, m1, key, handshake_lock)
    elif key["message"] == 3:
        record["m3ReplayCounter"] = key["replayCounter"]
        m2 = record["lastM2"]
        if (
            m2 is not None
            and key["replayCounter"] == m2["replayCounter"] + 1
        ):
            set_handshake_validation_data(record, key, m2, handshake_lock)
    elif key["message"] == 4:
        if (
            record["m3ReplayCounter"] is None
            or record["m3ReplayCounter"] == key["replayCounter"]
        ):
            finish_handshake(device, "success", timestamp_millis)
    return next_handshake_id


def wpa_pairwise_key_expansion(pmk, bssid, device_mac, anonce, snonce):
    ap_mac = bytes.fromhex(bssid.replace(":", ""))
    station_mac = bytes.fromhex(device_mac.replace(":", ""))
    data = (
        min(ap_mac, station_mac)
        + max(ap_mac, station_mac)
        + min(anonce, snonce)
        + max(anonce, snonce)
    )
    label = b"Pairwise key expansion"
    output = b""
    counter = 0
    while len(output) < 64:
        output += hmac.new(
            pmk,
            label + b"\x00" + data + bytes((counter,)),
            hashlib.sha1,
        ).digest()
        counter += 1
    return output[:64]


def password_to_pmk(ssid, password):
    if len(password) == 64:
        try:
            return bytes.fromhex(password)
        except ValueError:
            pass
    if not 8 <= len(password) <= 63:
        raise ValueError("WPA/WPA2 密码长度无效")
    return hashlib.pbkdf2_hmac(
        "sha1",
        password.encode("utf-8"),
        ssid.encode("utf-8"),
        4096,
        32,
    )


def validate_handshake_record(record, ssid, password):
    validation = record["validation"]
    if validation is None:
        raise ValueError("该握手记录缺少可校验的 EAPOL 1/4 与 2/4")
    pmk = password_to_pmk(ssid, password)
    ptk = wpa_pairwise_key_expansion(
        pmk,
        record["bssid"],
        record["deviceMac"],
        validation["anonce"],
        validation["snonce"],
    )
    eapol_frame = bytearray(validation["eapolFrame"])
    eapol_frame[81:97] = b"\x00" * 16
    descriptor_version = validation["descriptorVersion"]
    if descriptor_version == 1:
        calculated_mic = hmac.new(ptk[:16], eapol_frame, hashlib.md5).digest()
    elif descriptor_version == 2:
        calculated_mic = hmac.new(ptk[:16], eapol_frame, hashlib.sha1).digest()[:16]
    else:
        raise ValueError(f"不支持的 EAPOL 密钥描述符版本: {descriptor_version}")
    return hmac.compare_digest(calculated_mic, validation["mic"])


class GrowingPcapReader:
    def __init__(self, path):
        self.path = path
        self.stream = None
        self.endian = None
        self.link_type = None
        self.offset = 0
        self.nanosecond_timestamps = False

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

    def _open_if_ready(self):
        if self.stream is not None:
            if os.path.getsize(self.path) < self.offset:
                self._reset()
            else:
                return True
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
        self.endian = endian
        self.link_type = struct.unpack(endian + "I", header[20:24])[0]
        self.offset = PCAP_GLOBAL_HEADER_SIZE
        self.nanosecond_timestamps = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
        return True

    def read_available(self, limit_offset=None, max_packets=None):
        if not self._open_if_ready():
            return []
        packets = []
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
            payload = self.stream.read(included_length)
            if len(payload) < included_length:
                self.stream.seek(packet_start)
                return packets
            self.offset = packet_end
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
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
        with self.lock:
            self.stream.write(line)
            self.stream.flush()


def unique_export_path(directory):
    timestamp = int(time.time() * 1000)
    while True:
        output = os.path.join(directory, f"{timestamp}.pcap")
        if not os.path.exists(output) and not os.path.exists(output + ".part"):
            return output
        timestamp += 1


def copy_prefix(source_path, output, byte_count):
    remaining = byte_count
    with open(source_path, "rb") as source:
        while remaining > 0:
            chunk = source.read(min(1024 * 1024, remaining))
            if not chunk:
                raise IOError("原始 pcap 在导出过程中提前结束")
            output.write(chunk)
            remaining -= len(chunk)


def export_filtered(source_path, output, cutoff, bssid, device_mac, subtype_ids):
    reader = GrowingPcapReader(source_path)
    try:
        if not reader._open_if_ready():
            raise IOError("原始 pcap 文件头尚未写入完成")
        with open(source_path, "rb") as source:
            global_header = source.read(PCAP_GLOBAL_HEADER_SIZE)
        output.write(global_header)
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


def export_handshake(source_path, output, packets):
    if not packets:
        raise ValueError("握手记录没有可导出的数据包")
    with open(source_path, "rb") as source:
        global_header = source.read(PCAP_GLOBAL_HEADER_SIZE)
    if len(global_header) != PCAP_GLOBAL_HEADER_SIZE:
        raise IOError("原始 pcap 文件头尚未写入完成")
    output.write(global_header)
    for _, packet_header, packet_payload in sorted(packets, key=lambda value: value[0]):
        output.write(packet_header)
        output.write(packet_payload)


def run_export(
    command,
    source_path,
    cutoff,
    allowed_directory,
    handshake_cache,
    handshake_lock,
    event_writer,
):
    request_id = str(command.get("requestId", ""))
    try:
        requested_directory = os.path.realpath(str(command.get("outputDirectory", "")))
        if requested_directory != allowed_directory:
            raise ValueError("导出目录不在监听模式临时目录内")
        if cutoff < PCAP_GLOBAL_HEADER_SIZE:
            raise IOError("pcap 文件头尚未写入完成")
        os.makedirs(allowed_directory, exist_ok=True)
        os.chmod(allowed_directory, 0o755)
        output_path = unique_export_path(allowed_directory)
        partial_path = output_path + ".part"
        mode = command.get("mode")
        with open(partial_path, "wb") as output:
            if mode == "all":
                copy_prefix(source_path, output, cutoff)
            elif mode == "filtered":
                bssid = normalized_unicast_mac(command.get("bssid"))
                device_mac = normalized_unicast_mac(command.get("deviceMac"))
                subtype_ids = set(command.get("subtypeIds") or ())
                if not bssid or not device_mac:
                    raise ValueError("局部导出缺少有效的接入点或设备 MAC")
                if not subtype_ids or not subtype_ids.issubset(EXPORT_SUBTYPE_IDS):
                    raise ValueError("局部导出的帧子类型无效")
                export_filtered(
                    source_path,
                    output,
                    cutoff,
                    bssid,
                    device_mac,
                    subtype_ids,
                )
            elif mode == "handshake":
                bssid = normalized_unicast_mac(command.get("bssid"))
                device_mac = normalized_unicast_mac(command.get("deviceMac"))
                handshake_id = str(command.get("handshakeId", ""))
                if not bssid or not device_mac or not handshake_id:
                    raise ValueError("握手包导出缺少必要参数")
                with handshake_lock:
                    record = handshake_cache.get(handshake_id)
                    if record is None:
                        raise ValueError("找不到指定的握手记录")
                    if record["bssid"] != bssid or record["deviceMac"] != device_mac:
                        raise ValueError("握手记录与指定接入点或设备不一致")
                    packets = list(record["packets"])
                export_handshake(source_path, output, packets)
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


def run_handshake_test(command, handshake_cache, handshake_lock, event_writer):
    request_id = str(command.get("requestId", ""))
    try:
        handshake_id = str(command.get("handshakeId", ""))
        bssid = normalized_unicast_mac(command.get("bssid"))
        device_mac = normalized_unicast_mac(command.get("deviceMac"))
        ssid = str(command.get("ssid", ""))
        password = str(command.get("password", ""))
        if not request_id or not handshake_id or not bssid or not device_mac:
            raise ValueError("握手包校验请求缺少必要参数")
        if not ssid:
            raise ValueError("握手包校验请求缺少 SSID")
        with handshake_lock:
            record = handshake_cache.get(handshake_id)
            if record is None:
                raise ValueError("找不到指定的握手记录")
            if record["bssid"] != bssid or record["deviceMac"] != device_mac:
                raise ValueError("握手记录与指定接入点或设备不一致")
            matched = validate_handshake_record(record, ssid, password)
        event_writer.write(
            {
                "type": "handshakeTestCompleted",
                "requestId": request_id,
                "matched": matched,
            }
        )
    except Exception as error:
        event_writer.write(
            {
                "type": "handshakeTestFailed",
                "requestId": request_id,
                "message": str(error) or error.__class__.__name__,
            }
        )


def command_loop(
    path,
    reader,
    reader_lock,
    source_path,
    allowed_directory,
    handshake_cache,
    handshake_lock,
    event_writer,
):
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="monitor-commands")
    with open(path, "r", encoding="utf-8") as command_pipe:
        for line in command_pipe:
            if not line.strip():
                continue
            command = {}
            try:
                command = json.loads(line)
                command_type = command.get("type")
                if command_type == "export":
                    with reader_lock:
                        cutoff = reader.offset
                    executor.submit(
                        run_export,
                        command,
                        source_path,
                        cutoff,
                        allowed_directory,
                        handshake_cache,
                        handshake_lock,
                        event_writer,
                    )
                elif command_type == "test":
                    executor.submit(
                        run_handshake_test,
                        command,
                        handshake_cache,
                        handshake_lock,
                        event_writer,
                    )
                else:
                    raise ValueError(f"未知监听模式命令: {command_type}")
            except Exception as error:
                event_writer.write(
                    {
                        "type": "commandFailed",
                        "requestId": str(command.get("requestId", "")),
                        "message": str(error) or error.__class__.__name__,
                    }
                )


def main():
    args = parse_args()
    reader = GrowingPcapReader(args.pcap)
    reader_lock = threading.Lock()
    access_points = {}
    device_identities = {}
    handshake_cache = {}
    handshake_lock = threading.Lock()
    next_handshake_id = 1
    next_publish = time.monotonic()
    allowed_export_directory = os.path.realpath(
        os.path.join(os.path.dirname(args.event_pipe), "exports")
    )

    try:
        with open(args.event_pipe, "w", encoding="utf-8", buffering=1) as event_pipe:
            event_writer = EventWriter(event_pipe)
            threading.Thread(
                target=command_loop,
                args=(
                    args.command_pipe,
                    reader,
                    reader_lock,
                    args.pcap,
                    allowed_export_directory,
                    handshake_cache,
                    handshake_lock,
                    event_writer,
                ),
                name="monitor-commands",
                daemon=True,
            ).start()

            while True:
                with reader_lock:
                    records = reader.read_available()
                for (
                    payload,
                    recorded_bytes,
                    link_type,
                    packet_start,
                    packet_header,
                    packet_timestamp,
                ) in records:
                    packet = decode_packet(payload, link_type)
                    if packet is None:
                        continue
                    packet_record = (packet_start, packet_header, payload)

                    bssid = packet_bssid(packet)
                    if bssid is not None:
                        access_point = access_points.setdefault(bssid, empty_access_point(bssid))
                        ssid = packet_ssid(packet)
                        if ssid:
                            access_point["ssid"] = ssid
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
                        if management_subtype in (5, 8):
                            if ssid:
                                access_point["ssidContextPacket"] = packet_record
                            if protocols:
                                access_point["securityContextPacket"] = packet_record
                            if ssid or protocols:
                                add_context_to_handshake_records(
                                    access_point,
                                    packet_record,
                                    has_ssid=bool(ssid),
                                    has_security=bool(protocols),
                                    handshake_lock=handshake_lock,
                                )
                        transmitter = normalized_unicast_mac(dot11.addr2) if dot11 else None
                        signal_dbm = packet_signal_dbm(packet)
                        if transmitter == bssid:
                            add_signal_sample(
                                access_point["signal"], packet_timestamp, signal_dbm
                            )

                    dot11 = packet.getlayer(Dot11)
                    transmitter = normalized_unicast_mac(dot11.addr2) if dot11 else None
                    wps_name, wps_priority = wps_device_identity(packet)
                    if transmitter and wps_name:
                        previous = device_identities.get(transmitter)
                        if previous is None or wps_priority > previous[1]:
                            device_identities[transmitter] = (wps_name, wps_priority)
                            for known_access_point in access_points.values():
                                known_device = known_access_point["devices"].get(transmitter)
                                if known_device is not None:
                                    update_device_identity(
                                        known_device, wps_name, wps_priority
                                    )

                    relation = packet_device_relation(
                        packet,
                        known_bssids=access_points.keys(),
                    )
                    if relation is None:
                        continue
                    bssid, device_mac, direction = relation
                    access_point = access_points.setdefault(bssid, empty_access_point(bssid))
                    device = access_point["devices"].setdefault(
                        device_mac, empty_device(device_mac)
                    )
                    frame_bytes = packet_frame_bytes(packet, recorded_bytes)
                    subtype_id = frame_subtype_id(packet)
                    add_frame_counter(device, subtype_id, frame_bytes)
                    next_handshake_id = process_handshake_packet(
                        access_point=access_point,
                        device=device,
                        bssid=bssid,
                        device_mac=device_mac,
                        packet=packet,
                        subtype_id=subtype_id,
                        transmitter=transmitter,
                        packet_record=packet_record,
                        packet_timestamp=packet_timestamp,
                        handshake_cache=handshake_cache,
                        handshake_lock=handshake_lock,
                        next_handshake_id=next_handshake_id,
                    )
                    if direction == "upload" and subtype_id != "data.eapol":
                        device["uploadSamples"].append((packet_timestamp, frame_bytes))
                    elif direction == "download" and subtype_id != "data.eapol":
                        device["downloadSamples"].append((packet_timestamp, frame_bytes))

                    if transmitter == device_mac:
                        add_signal_sample(
                            device["signal"], packet_timestamp, packet_signal_dbm(packet)
                        )

                    identity_candidates = [dhcp_device_identity(packet)]
                    cached_identity = device_identities.get(device_mac)
                    if cached_identity is not None:
                        identity_candidates.append(cached_identity)
                    if direction == "upload":
                        identity_candidates.append(mdns_device_identity(packet))
                    for name, priority in identity_candidates:
                        update_device_identity(device, name, priority)

                now = time.time()
                access_point_updates = []
                for bssid, access_point in sorted(access_points.items()):
                    signal = signal_snapshot(access_point["signal"], now)
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
                    for device_mac, device in sorted(access_point["devices"].items()):
                        snapshot = device_snapshot(device, now)
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
                                "bssid": bssid,
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

                try:
                    recorded_file_bytes = os.path.getsize(args.pcap)
                except FileNotFoundError:
                    recorded_file_bytes = 0

                event_writer.write(
                    {
                        "type": "statistics",
                        "recordedBytes": recorded_file_bytes,
                        "accessPointUpdates": access_point_updates,
                    }
                )

                next_publish += PUBLISH_INTERVAL_SECONDS
                remaining = next_publish - time.monotonic()
                if remaining > 0:
                    time.sleep(remaining)
                else:
                    next_publish = time.monotonic()
    finally:
        reader.close()


if __name__ == "__main__":
    main()
