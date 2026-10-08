"""Passive, authenticated WPA-PSK decoding using locally saved credentials.

AP advertisements never select a client's AKM. The current M2's selected AKM
and a verified EAPOL MIC select it. TLS application data stays encrypted.
State belongs to CaptureAnalysis; no second pcap reader or accumulated JSON blob.
"""

import base64
import codecs
import hashlib
import hmac
import struct
import threading
import time
import zlib
from collections import OrderedDict, deque
from functools import lru_cache
from urllib.parse import urlsplit

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.kdf.hkdf import HKDFExpand
from cryptography.hazmat.primitives.keywrap import aes_key_unwrap, InvalidUnwrap
from cryptography.hazmat.primitives.ciphers.aead import AESCCM, AESGCM
from cryptography.hazmat.primitives.cmac import CMAC
from cryptography.hazmat.decrepit.ciphers.algorithms import ARC4
from scapy.layers.dhcp import BOOTP, DHCP
from scapy.layers.dns import DNS
from scapy.layers.dot11 import Dot11, Dot11Elt, Dot11FCS
from scapy.layers.eap import EAPOL
from scapy.layers.inet import IP, TCP, UDP, ICMP
from scapy.layers.inet6 import IPv6
from scapy.layers.l2 import LLC, ARP
from scapy.modules.krack.crypto import gen_TKIP_RC4_key, michael


def dhcp_device_name(packet, mac=None):
    """同一实现供通信解析与清理保留使用，按 BOOTP chaddr 归属设备。"""
    udp, bootp, dhcp = packet.getlayer(UDP), packet.getlayer(BOOTP), packet.getlayer(DHCP)
    if udp is None or bootp is None or dhcp is None or {int(udp.sport), int(udp.dport)} != {67, 68}:
        return None
    if mac is not None and bytes(bootp.chaddr)[:6].hex() != mac.replace(":", "").lower():
        return None
    for option in dhcp.options:
        if isinstance(option, tuple) and option[0] in ("hostname", "host_name"):
            value = option[1]
            name = (value.decode("utf-8", "replace") if isinstance(value, bytes) else str(value)).strip("\x00 ")
            if name:
                return name
    for option in dhcp.options:
        if isinstance(option, tuple) and option[0] == "client_FQDN" and isinstance(option[1], bytes):
            value = option[1]
            if len(value) <= 3:
                continue
            domain = value[3:]
            if not value[0] & 4:
                return domain.decode("utf-8", "replace").strip("\x00 ") or None
            labels, position = [], 0
            while position < len(domain) and domain[position]:
                length = domain[position]
                if length > 63 or position + length + 1 > len(domain):
                    return None
                labels.append(domain[position + 1:position + 1 + length].decode("utf-8", "replace"))
                position += length + 1
            return ".".join(labels) or None
    return None


class CredentialStore:
    """One targeted FIFO request per SSID; no credentials in argv or diagnostics."""
    def __init__(self):
        self.condition = threading.Lock()
        self.values = {}
        self.pending = {}
        self.revision = 0
        self.epoch = 0

    def update(self, command):
        with self.condition:
            if command.get("invalidate"):
                self.values.clear()
                self.pending.clear()
                self.epoch += 1
            else:
                if command.get("credentialEpoch", 0) != self.epoch:
                    return
                ssid = base64.b64decode(command["ssidBase64"], validate=True)
                self.values[ssid] = (command.get("password"), command.get("rawPsk", False), command.get("error", False))
                self.pending.pop(ssid, None)
            self.revision += 1

    def get(self, ssid, writer):
        if not ssid:
            return None
        with self.condition:
            if ssid not in self.values and self.pending.get(ssid, 0) <= time.monotonic():
                self.pending[ssid] = time.monotonic() + 2.0
                writer.write(dict(type="credentialRequest", ssidBase64=base64.b64encode(ssid).decode("ascii"), credentialEpoch=self.epoch))
            # Never wait for IPC on the incremental pcap reader's thread.
            return self.values.get(ssid)


def elements(packet):
    element = packet.getlayer(Dot11Elt)
    while isinstance(element, Dot11Elt):
        # Scapy's specialized RSN/WPA IE classes expose parsed fields, not .info.
        raw = bytes(element)
        if len(raw) < 2 or len(raw) < 2 + raw[1]:
            return
        yield raw[0], raw[2:2 + raw[1]]
        element = element.payload


def selected_security(packet):
    dot = packet.getlayer(Dot11)
    if dot is None or int(dot.type) != 0 or int(dot.subtype) not in (0, 2):
        return None
    return selected_security_elements(elements(packet))


def selected_security_elements(values):
    for element_id, original in values:
        wpa = element_id == 221 and original.startswith(b"\x00\x50\xf2\x01")
        if element_id != 48 and not wpa:
            continue
        data = original[4:] if wpa else original
        oui = b"\x00\x50\xf2" if wpa else b"\x00\x0f\xac"
        if len(data) < 8:
            continue
        count = struct.unpack_from("<H", data, 6)[0]
        offset = 8 + 4 * count
        if offset + 2 > len(data):
            continue
        akm_count = struct.unpack_from("<H", data, offset)[0]
        offset += 2
        # Several offered AKMs do not establish the negotiated AKM.
        if count != 1 or akm_count != 1 or offset + 4 > len(data):
            return "unknown", None, None
        suite = data[offset:offset + 4]
        cipher_suite = data[8:12]
        cipher = {2: "tkip", 4: "ccmp", 8: "gcmp", 9: "gcmp256", 10: "ccmp256"}.get(cipher_suite[3]) if cipher_suite[:3] == oui else None
        group_suite = data[2:6]
        group_cipher = {2: "tkip", 4: "ccmp", 8: "gcmp"}.get(group_suite[3]) if group_suite[:3] == oui else None
        akm = suite[3] if suite[:3] == oui else -1
        protocol = "wpaPsk" if wpa and akm == 2 else {
            2: "wpa2Psk", 6: "wpa2PskSha256", 8: "sae", 9: "sae", 18: "owe", 24: "sae", 25: "sae",
        }.get(akm, "other")
        return protocol, cipher, group_cipher
    return None


def eapol_security(frame):
    data = frame[99:99 + int.from_bytes(frame[97:99], "big")]
    values, position = [], 0
    while position + 2 <= len(data):
        kind, length = data[position:position + 2]
        if position + 2 + length > len(data):
            return None
        values.append((kind, data[position + 2:position + 2 + length]))
        position += 2 + length
    return selected_security_elements(values)


def derive_ptk(pmk, bssid, device, anonce, snonce, sha256=False):
    addresses = sorted((bytes.fromhex(bssid.replace(":", "")), bytes.fromhex(device.replace(":", ""))))
    nonces = sorted((anonce, snonce))
    data = b"".join(addresses + nonces)
    label = b"Pairwise key expansion"
    if sha256:
        return b"".join(hmac.new(pmk, struct.pack("<H", n) + label + data + struct.pack("<H", 384), hashlib.sha256).digest() for n in (1, 2))[:48]
    return b"".join(hmac.new(pmk, label + b"\0" + data + bytes((n,)), hashlib.sha1).digest() for n in range(4))[:64]


def valid_mic(ptk, validation):
    frame = bytearray(validation["eapolFrame"])
    frame[81:97] = bytes(16)
    version = validation["descriptorVersion"]
    if version == 1:
        expected = hmac.new(ptk[:16], frame, hashlib.md5).digest()
    elif version == 2:
        expected = hmac.new(ptk[:16], frame, hashlib.sha1).digest()[:16]
    elif version == 3:
        cmac = CMAC(algorithms.AES(ptk[:16]))
        cmac.update(bytes(frame))
        expected = cmac.finalize()
    else:
        return False
    return hmac.compare_digest(expected, validation["mic"])


def frame_parts(packet):
    dot = packet.getlayer(Dot11)
    raw = bytes(dot)
    if packet.haslayer(Dot11FCS):
        raw = raw[:-4]
    if len(raw) < 24:
        return None
    fc = int.from_bytes(raw[:2], "little")
    ds = (fc >> 8) & 3
    qos = bool(int(dot.subtype) & 8)
    header_len = 30 if ds == 3 else 24
    priority = raw[header_len] & 15 if qos and len(raw) > header_len else 0
    amsdu = qos and len(raw) > header_len and bool(raw[header_len] & 128)
    aad = bytes((raw[0] & 0x8f, raw[1] & 0xc7)) + raw[4:22] + struct.pack("<H", int.from_bytes(raw[22:24], "little") & 15)
    if ds == 3:
        aad += raw[24:30]
    if qos:
        aad += bytes((priority, 0))
        header_len += 2
        if fc & 0x8000:
            header_len += 4
    if len(raw) < header_len + 8:
        return None
    destination = raw[16:22] if ds in (1, 3) else raw[4:10]
    source = raw[24:30] if ds == 3 else raw[16:22] if ds == 2 else raw[10:16]
    return raw, fc, header_len, priority, amsdu, aad, destination, source


@lru_cache(maxsize=256)
def aead_cipher(key, cipher):
    return AESCCM(key, tag_length=8 if cipher == "ccmp" else 16) if cipher.startswith("ccmp") else AESGCM(key)


def decrypt_frame(packet, key, cipher, mic_key=None):
    parts = frame_parts(packet)
    if parts is None:
        return None
    raw, fc, offset, priority, amsdu, aad, destination, source = parts
    header = raw[offset:offset + 8]
    encrypted = raw[offset + 8:]
    if not header[3] & 32:
        return None
    if cipher == "tkip":
        tsc = [header[2], header[0], *header[4:8]]
        rc4_key = gen_TKIP_RC4_key(tsc, list(raw[10:16]), list(key))
        decryptor = Cipher(ARC4(bytes(rc4_key)), mode=None).decryptor()
        clear = decryptor.update(encrypted) + decryptor.finalize()
        if len(clear) < 4 or clear[-4:] != struct.pack("<I", zlib.crc32(clear[:-4]) & 0xffffffff):
            return None
        clear = clear[:-4]
    else:
        pn = bytes((header[7], header[6], header[5], header[4], header[1], header[0]))
        if cipher in ("ccmp", "ccmp256"):
            clear = aead_cipher(key, cipher).decrypt(bytes((priority,)) + raw[10:16] + pn, encrypted, aad)
        elif cipher in ("gcmp", "gcmp256"):
            clear = aead_cipher(key, cipher).decrypt(raw[10:16] + pn, encrypted, aad)
        else:
            return None
    return clear, parts, mic_key


def tls_sni(hello):
    """ClientHello body. ECH's public outer name is not the requested domain."""
    if len(hello) < 35:
        return None
    offset = 35 + hello[34]
    if offset + 2 > len(hello):
        return None
    offset += 2 + int.from_bytes(hello[offset:offset + 2], "big")
    if offset >= len(hello):
        return None
    offset += 1 + hello[offset]
    if offset + 2 > len(hello):
        return None
    end = min(len(hello), offset + 2 + int.from_bytes(hello[offset:offset + 2], "big"))
    offset += 2
    name = None
    while offset + 4 <= end:
        kind, length = struct.unpack_from(">HH", hello, offset)
        value = hello[offset + 4:offset + 4 + length]
        offset += 4 + length
        if offset > end:
            return None
        if kind == 0xfe0d:
            return None
        if kind == 0 and len(value) >= 5 and value[2] == 0:
            size = int.from_bytes(value[3:5], "big")
            if 5 + size <= len(value):
                name = value[5:5 + size].decode("ascii", "replace")
    return name


def quic_varint(data, offset):
    if offset >= len(data):
        raise ValueError("Truncated QUIC integer")
    length = 1 << (data[offset] >> 6)
    if offset + length > len(data):
        raise ValueError("Truncated QUIC integer")
    return int.from_bytes(data[offset:offset + length], "big") & ((1 << (length * 8 - 2)) - 1), offset + length


@lru_cache(maxsize=256)
def quic_initial_keys(version, dcid):
    # RFC 9001 / RFC 9369. Only public Initial keys are derived; never TLS traffic secrets.
    salt = bytes.fromhex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a" if version == 1 else "0dede3def700a6db819381be6e269dcbf9bd2ed9")
    initial = hmac.new(salt, dcid, hashlib.sha256).digest()
    def expand(secret, label, length):
        label = b"tls13 " + label
        info = struct.pack(">H", length) + bytes((len(label),)) + label + b"\0"
        return HKDFExpand(SHA256(), length, info).derive(secret)
    client = expand(initial, b"client in", 32)
    prefix = b"quic" if version == 1 else b"quicv2"
    return expand(client, prefix + b" key", 16), expand(client, prefix + b" iv", 12), expand(client, prefix + b" hp", 16)


class CommunicationDecoder:
    def __init__(self):
        self.sessions = {}
        self.pmks = {}
        self.next_id = 1
        self.flows = OrderedDict()
        self.buffered_bytes = 0
        self.fragments = OrderedDict()
        self.ip_fragments = OrderedDict()
        self.group_keys = {}
        self.group_clients = {}
        self.pending_details = {}
        self.dirty_records = {}
        self.datagrams = OrderedDict()
        self.quic_streams = OrderedDict()
        self.pending_frames = deque()
        self.pending_frame_bytes = 0

    def queue_packet(self, packet, bssid, mac, direction, timestamp):
        dot = packet.getlayer(Dot11)
        if dot is None or int(dot.type) != 2 or not int(dot.FCfield) & 0x40:
            return
        session = self.sessions.get((bssid, mac)) if mac else None
        if mac and (not session or session["status"] != "loading"):
            return
        size = len(packet)
        if size > 65535:
            return
        while self.pending_frames and (len(self.pending_frames) >= 512 or self.pending_frame_bytes + size > 2 * 1024 * 1024):
            self.pending_frame_bytes -= self.pending_frames.popleft()[-1]
        self.pending_frames.append((packet, bssid, mac, direction, timestamp,
            session["handshakeId"] if session else None, time.monotonic(), size))
        self.pending_frame_bytes += size

    def ready_packets(self, access_points):
        # Only a bounded, short-lived IPC backlog is replayed, never historical PCAP.
        waiting = deque()
        now = time.monotonic()
        while self.pending_frames:
            item = self.pending_frames.popleft()
            packet, bssid, mac, direction, timestamp, handshake_id, queued_at, size = item
            point = access_points.get(bssid)
            session = self.sessions.get((bssid, mac)) if mac else None
            keep = False
            if point and now - queued_at <= 2.0:
                if mac and session and session["handshakeId"] == handshake_id:
                    if session["status"] == "ready":
                        for decoded in self.cleartext(packet, bssid, mac, direction) or ():
                            yield bssid, mac, decoded, direction, timestamp, packet
                    else:
                        keep = session["status"] == "loading"
                elif mac is None:
                    if self.group_clients.get(bssid):
                        for target, decoded in self.group_packets(packet, point):
                            yield bssid, target, decoded, "download", timestamp, packet
                    else:
                        keep = True
            if keep:
                waiting.append(item)
            else:
                self.pending_frame_bytes -= size
        self.pending_frames = waiting

    def session(self, bssid, mac):
        return self.sessions.setdefault((bssid, mac), dict(protocol="unknown", cipher=None,
            validation=None, steps=set(), ptk=None, credential=None, handshakeId=None,
            status="protocolUnknown", eapolFrames={}, verified={}))

    def inspect(self, packet, access_point, device, eapol, credential_store, writer):
        bssid, mac = access_point["bssid"], device["mac"]
        session = self.session(bssid, mac)
        dot = packet.getlayer(Dot11)
        if session["status"] == "ready" and int(dot.type) == 2 and int(dot.FCfield) & 0x40:
            # Key changes arrive through EAPOL, and credential changes through refresh().
            # Do not repeat handshake bookkeeping for every protected data frame.
            return session
        if int(dot.type) == 0 and int(dot.subtype) in (0, 2, 11) and str(dot.addr2).lower() == mac:
            # A new connection attempt must not inherit the last connection's SAE label/key.
            self.sessions[(bssid, mac)] = session = dict(protocol="unknown",
                cipher=None, validation=None, steps=set(), ptk=None, credential=None,
                handshakeId=None, status="protocolUnknown", eapolFrames={}, verified={})
            for flow_key in tuple(self.flows):
                if flow_key[:2] == (bssid, mac):
                    self.drop_flow(flow_key)
            selected = selected_security(packet)
            if selected:
                # Association supplies cipher context; M2 confirms the actual AKM.
                _, session["cipher"], session["groupCipher"] = selected
        record = device["activeHandshake"]
        if record is None and device["handshakes"]:
            record = device["handshakes"][-1]
        if record is not None:
            if session["handshakeId"] != record["id"]:
                if session["handshakeId"] is not None:
                    session.update(protocol="unknown", cipher=None, groupCipher=None)
                session.update(validation=None, steps=set(), ptk=None, credential=None,
                               handshakeId=record["id"], eapolFrames={}, verified={})
            session["steps"].update(record["capturedSteps"])
            if record["validation"]:
                session["validation"] = record["validation"]
        if eapol:
            session["eapolFrames"][eapol["message"]] = eapol
            if eapol["message"] == 2:
                selected = eapol_security(eapol["frame"])
                if selected:
                    session["protocol"], session["cipher"], session["groupCipher"] = selected
                elif session["protocol"] in ("sae", "owe"):
                    # An old SAE attempt cannot label a later unobserved PSK fallback as safe.
                    session.update(protocol="unknown", cipher=None)
        if session["protocol"] in ("sae", "owe"):
            session["status"] = "secure"
        else:
            self.prepare_key(session, access_point.get("ssidBytes"), bssid, mac, credential_store, writer)
        if session["status"] == "ready" and str(dot.addr2).lower() == bssid:
            # WPA's GTK arrives in the separate two-message group handshake.
            layer = packet.getlayer(EAPOL)
            if layer is not None:
                frame = bytes(layer)
                if len(frame) >= 99 and frame[1] == 3:
                    size = 4 + int.from_bytes(frame[2:4], "big")
                    flags = int.from_bytes(frame[5:7], "big")
                    if 99 <= size <= len(frame) and not flags & 8 and flags & 128 and flags & 256:
                        frame = frame[:size]
                        if session.get("groupKeyFrame") != frame:
                            self.extract_group_key(session, bssid, dict(frame=frame,
                                descriptorVersion=flags & 7, mic=frame[81:97]))
                            session["groupKeyFrame"] = frame
        device["protocol"] = session["protocol"]
        device["decryptionStatus"] = session["status"]
        return session

    def prepare_key(self, session, ssid, bssid, mac, store, writer):
        if session["protocol"] == "other":
            session["status"] = "unsupported"
            return
        if session["protocol"] == "wpa2PskSha256" and session["cipher"] == "tkip":
            session["status"] = "unsupported"
            return
        credential = store.get(ssid, writer) if ssid else None
        if credential is None or credential[2]:
            session["status"] = "loading" if ssid else "incompleteHandshake"
            session["ptk"] = None
            session["credential"] = None
            return
        password, raw_psk, _ = credential
        if not password:
            session["status"] = "noPassword"
            session["ptk"] = None
            session["credential"] = None
            return
        validation = session["validation"]
        if validation is None:
            session["status"] = "incompleteHandshake"
            return
        signature = (ssid, password, raw_psk, validation["anonce"], validation["snonce"], validation["mic"])
        if signature != session["credential"]:
            session["credential"] = signature
            session["ptk"] = None
            session["verified"].clear()
            pmk_key = (ssid, password, raw_psk)
            if pmk_key not in self.pmks:
                try:
                    pmk = bytes.fromhex(password) if raw_psk else hashlib.pbkdf2_hmac("sha1", password.encode("utf-8"), ssid, 4096, 32)
                    if len(pmk) != 32:
                        raise ValueError("Invalid PSK length")
                    self.pmks[pmk_key] = pmk
                except ValueError:
                    session["status"] = "passwordMismatch"
                    return
            ptk = derive_ptk(self.pmks[pmk_key], bssid, mac, validation["anonce"], validation["snonce"], session["protocol"] == "wpa2PskSha256")
            if session["protocol"] == "unknown" and validation["descriptorVersion"] == 3 and not valid_mic(ptk, validation):
                sha256_ptk = derive_ptk(self.pmks[pmk_key], bssid, mac, validation["anonce"], validation["snonce"], True)
                if valid_mic(sha256_ptk, validation):
                    ptk = sha256_ptk
                    session["protocol"] = "wpa2PskSha256"
            if valid_mic(ptk, validation):
                session["ptk"] = ptk
                # Verifying a PSK-derived MIC is direct per-device evidence even if association was missed.
                if session["protocol"] == "unknown":
                    session["protocol"] = "wpaPsk" if validation["descriptorVersion"] == 1 and validation["eapolFrame"][4] == 254 else "wpa2Psk"
                if session["cipher"] is None:
                    # SHA1 MIC alone cannot distinguish CCMP and GCMP. Authenticate data before selecting one.
                    session["cipher"] = "tkip" if validation["descriptorVersion"] == 1 else None
        complete = {"eapol1", "eapol2", "eapol3", "eapol4"}.issubset(session["steps"])
        if not complete:
            session["status"] = "incompleteHandshake"
        elif session["ptk"] is None:
            session["status"] = "protocolUnknown" if session["protocol"] == "unknown" else "passwordMismatch"
        elif session["cipher"] not in (None, "tkip", "ccmp", "gcmp"):
            session["status"] = "unsupported"
        else:
            # Require authentic M3 and M4 as well; captured step labels alone are insufficient.
            verified = True
            for message in (3, 4):
                key = session["eapolFrames"].get(message)
                if key is None:
                    verified = False
                    break
                signature = key["frame"]
                if session["verified"].get(message) != signature:
                    if not valid_mic(session["ptk"], dict(eapolFrame=signature,
                            descriptorVersion=key["descriptorVersion"], mic=key["mic"])):
                        verified = False
                        break
                    session["verified"][message] = signature
            session["status"] = "ready" if verified else "incompleteHandshake"
            if verified:
                m3 = session["eapolFrames"][3]
                gtk_signature = (session["credential"], m3["frame"])
                if session.get("gtkExtracted") != gtk_signature:
                    self.extract_group_key(session, bssid, m3)
                    session["gtkExtracted"] = gtk_signature
                self.group_clients[bssid] = mac

    def extract_group_key(self, session, bssid, eapol):
        frame = eapol["frame"]
        if not valid_mic(session["ptk"], dict(eapolFrame=frame, descriptorVersion=eapol["descriptorVersion"], mic=eapol["mic"])):
            return
        key_data = frame[99:99 + int.from_bytes(frame[97:99], "big")]
        try:
            flags = int.from_bytes(frame[5:7], "big")
            wpa_group = frame[4] == 254 and not flags & 8
            if flags & (1 << 12) or wpa_group:
                if eapol["descriptorVersion"] == 1:
                    decryptor = Cipher(ARC4(frame[49:65] + session["ptk"][16:32]), mode=None).decryptor()
                    key_data = decryptor.update(bytes(256) + key_data)[256:]
                else:
                    key_data = aes_key_unwrap(session["ptk"][16:32], key_data)
            if wpa_group:
                length = int.from_bytes(frame[7:9], "big")
                if length in (16, 32) and len(key_data) >= length:
                    self.group_keys[(bssid, (flags >> 4) & 3)] = (
                        session.get("groupCipher") or session["cipher"], key_data[:length])
                return
            offset = 0
            while offset + 2 <= len(key_data):
                kind, length = key_data[offset:offset + 2]
                value = key_data[offset + 2:offset + 2 + length]
                offset += 2 + length
                if kind == 221 and value.startswith(b"\x00\x0f\xac\x01") and len(value) >= 22:
                    self.group_keys[(bssid, value[4] & 3)] = (session.get("groupCipher") or session["cipher"], value[6:])
        except (ValueError, InvalidUnwrap):
            pass

    def cleartext(self, packet, bssid, mac, direction):
        dot = packet.getlayer(Dot11)
        if dot is None or int(dot.type) != 2 or not int(dot.FCfield) & 0x40:
            return None
        session = self.sessions.get((bssid, mac))
        if session is None or session["status"] != "ready":
            return None
        parts = frame_parts(packet)
        if parts is None:
            return None
        raw, fc, offset, priority, amsdu, _, destination, source = parts
        group = bool(raw[4] & 1)
        ptk = session["ptk"]
        cipher, key = session["cipher"], ptk[32:48]
        mic_key = ptk[48:56] if direction == "download" else ptk[56:64]
        if group:
            value = self.group_keys.get((bssid, raw[offset + 3] >> 6))
            if value is None:
                return None
            cipher, gtk = value
            key, mic_key = gtk[:16], gtk[16:24]
        decrypted = None
        for candidate in (("ccmp", "gcmp") if cipher is None else (cipher,)):
            try:
                decrypted = decrypt_frame(packet, key, candidate, mic_key)
            except (InvalidTag, ValueError, IndexError, struct.error):
                continue
            if decrypted is not None:
                cipher = candidate
                if not group:
                    session["cipher"] = candidate
                break
        if decrypted is None:
            return None
        clear = decrypted[0]
        fragment = int(dot.SC) & 15
        frag_key = (bssid, mac, raw[10:16], priority, int(dot.SC) >> 4)
        if fragment or fc & 0x400:
            fragments = self.fragments.get(frag_key)
            if fragments is None or time.monotonic() - fragments["lastSeen"] > 2.0:
                fragments = dict(parts={}, end=None, lastSeen=time.monotonic())
                self.fragments[frag_key] = fragments
            self.fragments.move_to_end(frag_key)
            while len(self.fragments) > 256:
                self.fragments.popitem(last=False)
            fragments["lastSeen"] = time.monotonic()
            fragments["parts"][fragment] = clear
            if not fc & 0x400:
                fragments["end"] = fragment
            if sum(map(len, fragments["parts"].values())) > 65535:
                self.fragments.pop(frag_key, None)
                return None
            end = fragments["end"]
            if end is None or any(index not in fragments["parts"] for index in range(end + 1)):
                return None
            clear = b"".join(fragments["parts"][index] for index in range(end + 1))
            self.fragments.pop(frag_key, None)
        if cipher == "tkip":
            if len(clear) < 8 or len(mic_key) != 8 or not hmac.compare_digest(bytes(michael(mic_key, destination + source + bytes((priority, 0, 0, 0)) + clear[:-8])), clear[-8:]):
                return None
            clear = clear[:-8]
        if not amsdu:
            return [LLC(clear)]
        packets, offset = [], 0
        while offset + 14 <= len(clear):
            size = int.from_bytes(clear[offset + 12:offset + 14], "big")
            end = offset + 14 + size
            if end > len(clear):
                break
            packets.append(LLC(clear[offset + 14:end]))
            offset = (end + 3) & ~3
        return packets

    def group_packets(self, packet, access_point):
        """Decode group DHCP using GTK, then attribute it using BOOTP chaddr."""
        dot = packet.getlayer(Dot11)
        if dot is None or int(dot.type) != 2 or str(dot.addr2).lower() != access_point["bssid"]:
            return ()
        try:
            if not bytes.fromhex(str(dot.addr1).replace(":", ""))[0] & 1:
                return ()
        except (ValueError, IndexError):
            return ()
        bssid = access_point["bssid"]
        mac = self.group_clients.get(bssid)
        if not mac:
            return ()
        decoded = self.cleartext(packet, bssid, mac, "download") or ()
        result = []
        for value in decoded:
            bootp = value.getlayer(BOOTP)
            if bootp is None:
                continue
            target = ":".join(f"{byte:02x}" for byte in bytes(bootp.chaddr)[:6])
            session = self.sessions.get((bssid, target))
            if target in access_point["devices"] and session and session["status"] == "ready":
                result.append((target, value))
        return result

    def refresh(self, access_points, store, writer, keys=None):
        changed = set()
        for bssid, mac in (tuple(self.sessions) if keys is None else keys):
            session = self.sessions.get((bssid, mac))
            if session is None:
                continue
            point = access_points.get(bssid)
            device = point["devices"].get(mac) if point else None
            if device is None or session["protocol"] in ("sae", "owe"):
                continue
            previous = (session["protocol"], session["status"])
            self.prepare_key(session, point.get("ssidBytes"), bssid, mac, store, writer)
            device["protocol"], device["decryptionStatus"] = session["protocol"], session["status"]
            if previous != (session["protocol"], session["status"]):
                changed.add((bssid, mac))
        return changed

    def new_record(self, bssid, mac, kind, timestamp, source, destination, summary, writer, complete=True, tcp_stream_id=""):
        record = dict(id=str(self.next_id), timestampUnixMillis=int(timestamp * 1000),
                      kind=kind, sourceAddress=source, destinationAddress=destination,
                      summary=summary[:512], complete=complete, tcpStreamId=tcp_stream_id)
        self.next_id += 1
        record.update(uploadBytes=0, downloadBytes=0, protocol=kind.upper(),
                      transport="tcp" if kind in ("tcp", "http") else "udp" if kind in ("dns", "dhcp", "udp") else "other")
        state = dict(record=record, bssid=bssid, mac=mac, channels={})
        self.emit_record(state, writer)
        return state

    @staticmethod
    def emit_record(state, writer):
        writer.write(dict(type="communication", bssid=state["bssid"], deviceMac=state["mac"], **state["record"]))

    def detail(self, state, value, writer, final=True, channel="DETAIL"):
        if isinstance(value, str):
            value = value.encode("utf-8")
        part = state["channels"].setdefault(channel, dict(buffer=bytearray(), index=0, lastFlush=time.monotonic()))
        part["buffer"].extend(value)
        # Each independently keyed part remains well below the transport page limit.
        while len(part["buffer"]) >= 8192 or (final and part["buffer"]):
            data = bytes(part["buffer"][:8192])
            del part["buffer"][:8192]
            writer.write(dict(type="communicationPart", bssid=state["bssid"], deviceMac=state["mac"],
                id=state["record"]["id"], channel=channel, index=part["index"],
                searchable=channel in ("RAW_UPLOAD", "RAW_DOWNLOAD") and state["record"]["kind"] == "tcp",
                data=base64.b64encode(data).decode("ascii")))
            part["index"] += 1
            part["lastFlush"] = time.monotonic()
        key = (state["record"]["id"], channel)
        if part["buffer"]:
            self.pending_details[key] = state
        else:
            self.pending_details.pop(key, None)

    def counted(self, state, direction, size):
        state["record"]["uploadBytes" if direction == "upload" else "downloadBytes"] += size
        self.dirty_records[state["record"]["id"]] = state

    def flush_details(self, writer, force=False):
        for (_, channel), state in tuple(self.pending_details.items()):
            if force or time.monotonic() - state["channels"][channel]["lastFlush"] >= 0.25:
                self.detail(state, b"", writer, channel=channel)
        for state in tuple(self.dirty_records.values()):
            self.emit_record(state, writer)
        self.dirty_records.clear()

    def dns(self, payload, bssid, mac, timestamp, source, destination, writer, direction="upload"):
        try:
            if len(payload) < 12:
                return
            dns = DNS(payload)
            queries = []
            for query in dns.qd or []:
                queries.append(query.qname.decode("utf-8", "replace").rstrip("."))
            state = self.new_record(bssid, mac, "dns", timestamp, source, destination,
                                    ("Response " if dns.qr else "Query ") + ", ".join(queries), writer)
            state["record"]["domain"] = queries[0] if queries else ""
            self.counted(state, direction, len(payload))
            self.detail(state, payload, writer, channel="RAW_DNS")
            self.detail(state, dns.show(dump=True), writer)
        except (ValueError, IndexError, struct.error):
            return

    def consume_network(self, packet, bssid, mac, direction, timestamp, writer):
        arp = packet.getlayer(ARP)
        if arp is not None:
            state = self.new_record(bssid, mac, "arp", timestamp, str(arp.psrc), str(arp.pdst),
                ("ARP who-has " if int(arp.op) == 1 else "ARP is-at ") + str(arp.pdst if int(arp.op) == 1 else arp.hwsrc), writer)
            self.counted(state, direction, len(bytes(arp)))
            self.detail(state, arp.show(dump=True), writer)
            return None
        ip = packet.getlayer(IP) or packet.getlayer(IPv6)
        if ip is None:
            return None
        if isinstance(ip, IP) and (int(ip.frag) or int(ip.flags) & 1):
            fragment_key = (bssid, mac, ip.src, ip.dst, int(ip.id), int(ip.proto))
            fragments = self.ip_fragments.get(fragment_key)
            if fragments is None or timestamp - fragments["lastSeen"] > 30:
                fragments = dict(parts={}, end=None, header=None, lastSeen=timestamp)
                self.ip_fragments[fragment_key] = fragments
            fragments["lastSeen"] = timestamp
            self.ip_fragments.move_to_end(fragment_key)
            while len(self.ip_fragments) > 256:
                self.ip_fragments.popitem(last=False)
            start = int(ip.frag) * 8
            if start + len(bytes(ip.payload)) > 65535:
                self.ip_fragments.pop(fragment_key, None)
                return None
            fragments["parts"][start] = bytes(ip.payload)
            if start == 0:
                fragments["header"] = bytes(ip)[:int(ip.ihl) * 4]
            if not int(ip.flags) & 1:
                fragments["end"] = start + len(bytes(ip.payload))
            if fragments["end"] is None or fragments["header"] is None:
                return None
            data = bytearray()
            for start, value in sorted(fragments["parts"].items()):
                if start > len(data):
                    return None
                if start + len(value) > len(data):
                    data.extend(value[len(data) - start:])
            if len(data) != fragments["end"]:
                return None
            header = bytearray(fragments["header"])
            header[2:4] = struct.pack(">H", len(header) + len(data))
            header[6:8] = bytes(2)
            self.ip_fragments.pop(fragment_key, None)
            packet = ip = IP(bytes(header) + data)
        source, destination = str(ip.src), str(ip.dst)
        udp = packet.getlayer(UDP)
        dhcp_name = None
        if udp is not None:
            source += ":" + str(udp.sport)
            destination += ":" + str(udp.dport)
            if {int(udp.sport), int(udp.dport)} == {67, 68} and packet.haslayer(DHCP):
                bootp = packet.getlayer(BOOTP)
                chaddr = bytes(bootp.chaddr)[:6] if bootp else b""
                if chaddr.hex() == mac.replace(":", ""):
                    dhcp_name = dhcp_device_name(packet, mac)
                    state = self.new_record(bssid, mac, "dhcp", timestamp, source, destination, "DHCP" + (" · " + dhcp_name if dhcp_name else ""), writer)
                    state["record"]["retainDeviceName"] = bool(dhcp_name)
                    self.counted(state, direction, len(bytes(udp.payload)))
                    self.detail(state, packet[BOOTP].show(dump=True), writer)
            if int(udp.dport) in (53, 5353) or int(udp.sport) in (53, 5353):
                self.dns(bytes(udp.payload), bssid, mac, timestamp, source, destination, writer, direction)
            elif not packet.haslayer(DHCP):
                key = (bssid, mac, source, destination) if direction == "upload" else (bssid, mac, destination, source)
                state = self.datagrams.get(key)
                if state is None:
                    state = self.new_record(bssid, mac, "udp", timestamp, source, destination, "UDP", writer)
                    self.datagrams[key] = state
                self.datagrams.move_to_end(key)
                while len(self.datagrams) > 4096:
                    self.datagrams.popitem(last=False)
                self.counted(state, direction, len(bytes(udp.payload)))
                if direction == "upload":
                    self.quic(bytes(udp.payload), bssid, mac, timestamp, source, destination, writer, state)
                if state["record"]["kind"] == "udp":
                    self.detail(state, direction + " " + source + " -> " + destination + "\n" +
                        bytes(udp.payload).decode("utf-8", "replace") + "\n", writer, final=False)
        tcp = packet.getlayer(TCP)
        if tcp is not None:
            self.tcp(tcp, bssid, mac, timestamp, source, destination, writer, direction)
        elif udp is None:
            state = self.new_record(bssid, mac, "icmp" if packet.haslayer(ICMP) or int(getattr(ip, "nh", 0)) == 58 else "other", timestamp, source, destination,
                                    ip.payload.name, writer)
            self.counted(state, direction, len(bytes(ip.payload)))
            self.detail(state, ip.show(dump=True), writer)
        return dhcp_name

    def quic(self, datagram, bssid, mac, timestamp, source, destination, writer, state):
        offset = 0
        try:
            while offset + 7 <= len(datagram):
                data = datagram[offset:]
                if data[0] & 0xc0 != 0xc0:
                    return
                version = int.from_bytes(data[1:5], "big")
                initial_type = 0 if version == 1 else 1
                if version not in (1, 0x6b3343cf) or (data[0] >> 4) & 3 != initial_type:
                    return
                dcid_len = data[5]
                if dcid_len > 20 or 6 + dcid_len >= len(data):
                    return
                dcid = data[6:6 + dcid_len]
                cursor = 6 + dcid_len
                scid_len = data[cursor]
                if scid_len > 20:
                    return
                cursor += 1 + scid_len
                token_len, cursor = quic_varint(data, cursor)
                cursor += token_len
                protected_length, pn_offset = quic_varint(data, cursor)
                end = pn_offset + protected_length
                if end > len(data) or pn_offset + 20 > end:
                    return
                key, iv, hp = quic_initial_keys(version, dcid)
                encryptor = Cipher(algorithms.AES(hp), modes.ECB()).encryptor()
                mask = encryptor.update(data[pn_offset + 4:pn_offset + 20]) + encryptor.finalize()
                first = data[0] ^ (mask[0] & 15)
                pn_len = (first & 3) + 1
                pn_bytes = bytes(data[pn_offset + n] ^ mask[1 + n] for n in range(pn_len))
                truncated_pn = int.from_bytes(pn_bytes, "big")
                stream_key = (bssid, mac, source, destination, dcid)
                stream = self.quic_streams.setdefault(stream_key, dict(largest=-1, parts={}, done=False))
                self.quic_streams.move_to_end(stream_key)
                while len(self.quic_streams) > 128:
                    self.quic_streams.popitem(last=False)
                if stream["done"]:
                    offset += end
                    continue
                window = 1 << (pn_len * 8)
                expected = stream["largest"] + 1
                pn = (expected & ~(window - 1)) | truncated_pn
                if pn + window // 2 <= expected:
                    pn += window
                elif pn > expected + window // 2 and pn >= window:
                    pn -= window
                nonce = (int.from_bytes(iv, "big") ^ pn).to_bytes(12, "big")
                aad = bytes((first,)) + data[1:pn_offset] + pn_bytes
                clear = AESGCM(key).decrypt(nonce, data[pn_offset + pn_len:end], aad)
                state["record"].update(kind="https", protocol="QUIC", summary=state["record"]["summary"] if state["record"]["kind"] == "https" else "QUIC · domain not captured / ECH")
                stream["largest"] = max(stream["largest"], pn)
                position = 0
                while position < len(clear):
                    kind, position = quic_varint(clear, position)
                    if kind in (0, 1):
                        continue
                    if kind in (2, 3):
                        _, position = quic_varint(clear, position)  # largest acknowledged
                        _, position = quic_varint(clear, position)  # ACK delay
                        count, position = quic_varint(clear, position)
                        _, position = quic_varint(clear, position)  # first range
                        if count > len(clear):
                            return
                        for _ in range(count * 2 + (3 if kind == 3 else 0)):
                            _, position = quic_varint(clear, position)
                        continue
                    if kind != 6:
                        break
                    start, position = quic_varint(clear, position)
                    length, position = quic_varint(clear, position)
                    if start + length > 65536 or position + length > len(clear):
                        return
                    if length > len(stream["parts"].get(start, b"")):
                        stream["parts"][start] = clear[position:position + length]
                    position += length
                    if sum(map(len, stream["parts"].values())) > 131072:
                        stream["parts"].clear()
                        stream["done"] = True
                        return
                hello = bytearray()
                for start, part in sorted(stream["parts"].items()):
                    if start > len(hello):
                        break
                    hello.extend(part[max(0, len(hello) - start):])
                if len(hello) >= 4 and hello[0] == 1 and len(hello) >= 4 + int.from_bytes(hello[1:4], "big"):
                    name = tls_sni(bytes(hello[4:4 + int.from_bytes(hello[1:4], "big")]))
                    if name:
                        state["record"].update(kind="https", summary=name, domain=name, url="https://" + name, protocol="QUIC")
                        self.emit_record(state, writer)
                        self.detail(state, "QUIC Initial / TLS ClientHello SNI: " + name + "\nHTTPS 正文保持加密。", writer)
                    stream["parts"].clear()
                    stream["done"] = True
                offset += end
        except (ValueError, InvalidTag, IndexError, struct.error):
            return

    @staticmethod
    def flow_size(flow):
        return sum(len(s["buffer"]) + len(s["tls"]) + sum(map(len, s["pending"].values()))
                   for s in flow["streams"].values())

    def drop_flow(self, key):
        flow = self.flows.pop(key)
        self.buffered_bytes -= self.flow_size(flow)
        writer = flow["writer"]
        for stream in flow["streams"].values():
            active = stream.get("message")
            if active:
                self.finish_http(active, writer, complete=False)
        flow["record"]["record"]["complete"] = (
            flow.get("ended", False) and
            not flow["partial"] and not any(s["pending"] or s["gap"] for s in flow["streams"].values()))
        self.emit_record(flow["record"], writer)
        for state in [flow["record"]] + list(flow["requests"]):
            for channel in tuple(state["channels"]):
                self.detail(state, b"", writer, channel=channel)

    @staticmethod
    def stream_state():
        return dict(next=None, pending={}, buffer=b"", tls=b"", message=None, fin=False, gap=False)

    def tcp(self, tcp, bssid, mac, timestamp, source, destination, writer, direction):
        local = (source, int(tcp.sport)) if direction == "upload" else (destination, int(tcp.dport))
        remote = (destination, int(tcp.dport)) if direction == "upload" else (source, int(tcp.sport))
        key = (bssid, mac, local, remote)
        while self.flows:
            first = next(iter(self.flows))
            if self.flows[first]["lastSeen"] >= timestamp - 60 and len(self.flows) < 4096 and self.buffered_bytes < 16 * 1024 * 1024:
                break
            self.drop_flow(first)
        flags, sequence = int(tcp.flags), int(tcp.seq)
        if flags & 2 and not flags & 16 and key in self.flows:
            # A retransmitted SYN belongs to the same connection.
            if self.flows[key].get("syn") != sequence:
                self.drop_flow(key)
        if key not in self.flows:
            state = self.new_record(bssid, mac, "tcp", timestamp,
                local[0] + ":" + str(local[1]), remote[0] + ":" + str(remote[1]), "TCP stream", writer, False)
            self.flows[key] = dict(streams={d: self.stream_state() for d in ("upload", "download")},
                record=state, requests=deque(), lastSeen=timestamp, writer=writer,
                partial=not bool(flags & 2), syn=sequence if flags & 2 and not flags & 16 else None)
        flow = self.flows[key]
        self.flows.move_to_end(key)
        flow["lastSeen"] = timestamp
        stream = flow["streams"][direction]
        if flags & 2:
            sequence = (sequence + 1) & 0xffffffff
        if stream["next"] is None:
            stream["next"] = sequence
        sequence = stream["next"] + ((sequence - stream["next"] + 0x80000000) & 0xffffffff) - 0x80000000
        data = bytes(tcp.payload)
        previous_size = self.flow_size(flow)
        if data and sequence + len(data) > stream["next"]:
            if sequence > stream["next"]:
                existing = stream["pending"].get(sequence, b"")
                if len(data) > len(existing):
                    stream["pending"][sequence] = data
            else:
                parts = [data[max(0, stream["next"] - sequence):]]
                stream["next"] += len(parts[0])
                for start in sorted(tuple(stream["pending"])):
                    if start > stream["next"]:
                        break
                    pending = stream["pending"].pop(start)
                    tail = pending[max(0, stream["next"] - start):]
                    parts.append(tail)
                    stream["next"] += len(tail)
                contiguous = b"".join(parts)
                channel = "RAW_UPLOAD" if direction == "upload" else "RAW_DOWNLOAD"
                self.detail(flow["record"], contiguous, writer, final=False, channel=channel)
                self.counted(flow["record"], direction, len(contiguous))
                stream["buffer"] += contiguous
                self.parse_stream(flow, stream, bssid, mac, local, remote, direction, timestamp, writer)
        self.buffered_bytes += self.flow_size(flow) - previous_size
        if self.flow_size(flow) > 1024 * 1024:
            flow["partial"] = True
            self.drop_flow(key)
            return
        if flags & 1:
            stream["fin"] = True
            stream["gap"] = sequence + len(data) != stream["next"]
            active = stream["message"]
            if active and active["remaining"] is None and not active["chunked"]:
                self.finish_http(active, writer, complete=not bool(stream["pending"]) and not stream["gap"])
                stream["message"] = None
        if flags & 4 or all(s["fin"] for s in flow["streams"].values()):
            flow["ended"] = True
            self.drop_flow(key)

    def finish_http(self, message, writer, complete=True):
        state, direction = message["state"], message["direction"]
        decoder = message.get("decoder")
        if decoder:
            try:
                self.http_text(message, decoder.flush(), writer)
                complete = complete and decoder.eof
            except zlib.error:
                complete = False
        text_decoder = message.get("textDecoder")
        if text_decoder:
            try:
                text = text_decoder.decode(b"", final=True)
                self.detail(state, text, writer, channel=message["channel"])
                self.detail(state, text, writer, channel=message["channel"] + "_BODY")
            except (UnicodeError, ValueError):
                message["decodeError"] = True
                complete = False
        self.detail(state, b"", writer, channel=message["channel"])
        for channel in ("RAW_" + message["channel"], message["channel"] + "_BODY", "RAW_" + message["channel"] + "_BODY"):
            self.detail(state, b"", writer, channel=channel)
        state[direction + "Done"] = complete
        state["record"]["complete"] = state.get("uploadDone", False) and state.get("downloadDone", False)
        self.emit_record(state, writer)

    def http_body(self, message, value, writer):
        self.detail(message["state"], value, writer, final=False, channel="RAW_" + message["channel"])
        self.detail(message["state"], value, writer, final=False, channel="RAW_" + message["channel"] + "_BODY")
        self.counted(message["state"], message["direction"], len(value))
        if message.get("decoder"):
            try:
                # Bound each decompressor output; large responses stream directly to FIFO.
                pending = value
                while pending:
                    output = message["decoder"].decompress(pending, 8192)
                    self.http_text(message, output, writer)
                    pending = message["decoder"].unconsumed_tail
            except zlib.error:
                message["decodeError"] = True
        else:
            self.http_text(message, value, writer)

    def http_text(self, message, value, writer):
        text_decoder = message.get("textDecoder")
        try:
            text = text_decoder.decode(value) if text_decoder else value
        except (UnicodeError, ValueError):
            message["decodeError"] = True
            message["textDecoder"] = codecs.getincrementaldecoder("utf-8")(errors="replace")
            text = message["textDecoder"].decode(value)
        self.detail(message["state"], text, writer, final=False, channel=message["channel"])
        self.detail(message["state"], text, writer, final=False, channel=message["channel"] + "_BODY")

    def parse_stream(self, connection, stream, bssid, mac, local, remote, direction, timestamp, writer):
        source, destination = (local, remote) if direction == "upload" else (remote, local)
        source = source[0] + ":" + str(source[1])
        destination = destination[0] + ":" + str(destination[1])
        while stream["buffer"]:
            data = stream["buffer"]
            if remote[1] in (53, 5353):
                if len(data) < 2 or len(data) < 2 + int.from_bytes(data[:2], "big"):
                    return
                length = int.from_bytes(data[:2], "big")
                self.dns(data[2:2 + length], bssid, mac, timestamp, source, destination, writer, direction)
                stream["buffer"] = data[2 + length:]
                continue
            message = stream["message"]
            if message:
                if message["chunked"]:
                    if message.get("trailers"):
                        end = 2 if data.startswith(b"\r\n") else data.find(b"\r\n\r\n") + 4
                        if end < 4 and not data.startswith(b"\r\n"):
                            return
                        self.detail(message["state"], data[:end], writer, channel=message["channel"])
                        self.detail(message["state"], data[:end], writer, channel="RAW_" + message["channel"])
                        stream["buffer"] = data[end:]
                        self.finish_http(message, writer, not message.get("decodeError"))
                        stream["message"] = None
                        continue
                    if message["chunkRemaining"] is None:
                        end = data.find(b"\r\n")
                        if end < 0:
                            return
                        try:
                            length = int(data[:end].split(b";", 1)[0], 16)
                            if length < 0:
                                raise ValueError()
                        except ValueError:
                            stream["buffer"] = b""
                            connection["partial"] = True
                            return
                        stream["buffer"] = data[end + 2:]
                        self.detail(message["state"], data[:end + 2], writer, final=False, channel="RAW_" + message["channel"])
                        message["chunkRemaining"] = length
                        if length == 0:
                            message["trailers"] = True
                        continue
                    count = min(len(data), message["chunkRemaining"])
                    self.http_body(message, data[:count], writer)
                    stream["buffer"] = data[count:]
                    message["chunkRemaining"] -= count
                    if message["chunkRemaining"] == 0:
                        message["chunkCrlf"] = True
                    if message.get("chunkCrlf"):
                        if len(stream["buffer"]) < 2:
                            return
                        if not stream["buffer"].startswith(b"\r\n"):
                            connection["partial"] = True
                            stream["buffer"] = b""
                            return
                        stream["buffer"] = stream["buffer"][2:]
                        self.detail(message["state"], b"\r\n", writer, final=False, channel="RAW_" + message["channel"])
                        message["chunkCrlf"] = False
                        message["chunkRemaining"] = None
                    continue
                count = len(data) if message["remaining"] is None else min(len(data), message["remaining"])
                self.http_body(message, data[:count], writer)
                stream["buffer"] = data[count:]
                if message["remaining"] is not None:
                    message["remaining"] -= count
                    if message["remaining"] == 0:
                        self.finish_http(message, writer, not message.get("decodeError"))
                        stream["message"] = None
                continue
            if data[0] in (20, 21, 22, 23) and (len(data) < 2 or data[1] == 3):
                state = connection["record"]
                state["record"]["kind"] = "https"
                state["record"]["protocol"] = "TLS"
                if len(data) < 5:
                    return
                length = int.from_bytes(data[3:5], "big")
                if length > 18432:
                    stream["buffer"] = b""
                    connection["partial"] = True
                    return
                if len(data) < length + 5:
                    return
                if data[0] == 22 and direction == "upload":
                    stream["tls"] += data[5:5 + length]
                    while len(stream["tls"]) >= 4:
                        hello = stream["tls"]
                        hello_len = int.from_bytes(hello[1:4], "big")
                        if len(hello) < 4 + hello_len:
                            break
                        if hello[0] == 1:
                            name = tls_sni(hello[4:4 + hello_len])
                            state["record"]["summary"] = name or "TLS · domain not captured / ECH"
                            if name:
                                state["record"].update(domain=name, url="https://" + name)
                            self.detail(state, "TLS ClientHello SNI: " + (name or "<unknown>") +
                                "\nHTTPS application data remains encrypted.\n", writer)
                        stream["tls"] = hello[4 + hello_len:]
                stream["buffer"] = data[5 + length:]
                self.dirty_records[state["record"]["id"]] = state
                continue
            methods = (b"GET ", b"POST ", b"HEAD ", b"PUT ", b"DELETE ", b"OPTIONS ", b"PATCH ", b"CONNECT ", b"TRACE ")
            response = data.startswith(b"HTTP/")
            if not response and not any(data.startswith(method) for method in methods):
                if any(prefix.startswith(data) for prefix in methods + (b"HTTP/",)):
                    return
                stream["buffer"] = b""
                return
            end = data.find(b"\r\n\r\n")
            if end < 0:
                return
            header, stream["buffer"] = data[:end + 4], data[end + 4:]
            lines = header.decode("iso-8859-1").split("\r\n")
            fields = dict(line.split(":", 1) for line in lines[1:] if ":" in line)
            fields = {key.strip().lower(): value.strip() for key, value in fields.items()}
            connection["record"]["record"]["streamContainer"] = True
            self.dirty_records[connection["record"]["record"]["id"]] = connection["record"]
            if response:
                state = connection["requests"][0] if connection["requests"] else self.new_record(
                    bssid, mac, "http", timestamp, destination, source, "HTTP response · request not captured", writer, False, connection["record"]["record"]["id"])
                try:
                    status = int(lines[0].split()[1])
                except (ValueError, IndexError):
                    return
                informational = 100 <= status < 200 and status != 101
                no_body = informational or status in (101, 204, 304) or state.get("method") == "HEAD" or (state.get("method") == "CONNECT" and 200 <= status < 300)
                if not informational and connection["requests"]:
                    connection["requests"].popleft()
                channel = "RESPONSE"
                state["record"].update(statusCode=status, protocol=lines[0].split()[0],
                    contentType=fields.get("content-type", ""), responseHeaderCount=sum(":" in line for line in lines[1:]))
            else:
                state = self.new_record(bssid, mac, "http", timestamp, source, destination,
                    lines[0] + (" · " + fields["host"] if fields.get("host") else ""), writer, False, connection["record"]["record"]["id"])
                state["method"] = lines[0].split()[0]
                request_parts = lines[0].split()
                path = request_parts[1] if len(request_parts) > 1 else ""
                url = path if path.startswith(("http://", "https://")) else "http://" + fields.get("host", remote[0]) + path
                try:
                    domain = urlsplit(url).hostname or ""
                except ValueError:
                    domain = ""
                state["record"].update(method=state["method"], url=url[:2048], domain=domain[:253],
                    protocol=request_parts[-1], requestHeaderCount=sum(":" in line for line in lines[1:]), contentType=fields.get("content-type", ""))
                connection["requests"].append(state)
                while len(connection["requests"]) > 256:
                    connection["requests"].popleft()
                no_body, informational, channel = False, False, "REQUEST"
            try:
                remaining = 0 if no_body else int(fields["content-length"]) if "content-length" in fields else (None if response else 0)
                if remaining is not None and remaining < 0:
                    raise ValueError()
            except ValueError:
                connection["partial"] = True
                return
            chunked = not no_body and "chunked" in fields.get("transfer-encoding", "").lower()
            encoding = fields.get("content-encoding", "").lower()
            decoder = zlib.decompressobj(31 if encoding == "gzip" else 15) if (chunked or remaining != 0) and encoding in ("gzip", "deflate") else None
            self.detail(state, header.decode("iso-8859-1"), writer, channel=channel)
            self.detail(state, header, writer, channel="RAW_" + channel)
            self.detail(state, header.decode("iso-8859-1"), writer, channel=channel + "_HEADERS")
            self.dirty_records[state["record"]["id"]] = state
            self.counted(state, direction, len(header))
            charset = "utf-8"
            for parameter in fields.get("content-type", "").split(";")[1:]:
                if parameter.strip().lower().startswith("charset="):
                    charset = parameter.strip().split("=", 1)[1].strip("\"' ")
            try:
                b"".decode(charset)  # Reject non-text codecs in untrusted HTTP headers.
                text_decoder = codecs.getincrementaldecoder(charset)(errors="replace")
            except (LookupError, ValueError):
                text_decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
            message = dict(state=state, direction=direction, channel=channel, remaining=remaining,
                chunked=chunked, chunkRemaining=None, decoder=decoder, textDecoder=text_decoder)
            if not chunked and remaining == 0:
                if informational:
                    continue
                self.finish_http(message, writer)
            else:
                stream["message"] = message
