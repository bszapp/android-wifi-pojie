"""Passive, authenticated WPA-PSK decoding using locally saved credentials.

AP advertisements never select a client's AKM. The current M2's selected AKM
and a verified EAPOL MIC select it. TLS application data stays encrypted.
State belongs to CaptureAnalysis; no second pcap reader or accumulated JSON blob.
"""

import base64
import hashlib
import hmac
import struct
import threading
import time
import zlib
from collections import OrderedDict, deque
from functools import lru_cache

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
from scapy.layers.inet import IP, TCP, UDP
from scapy.layers.inet6 import IPv6
from scapy.layers.l2 import LLC
from scapy.modules.krack.crypto import gen_TKIP_RC4_key, michael


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
                            yield bssid, mac, decoded, direction, timestamp
                    else:
                        keep = session["status"] == "loading"
                elif mac is None:
                    if self.group_clients.get(bssid):
                        for target, decoded in self.group_packets(packet, point):
                            yield bssid, target, decoded, "download", timestamp
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

    def new_record(self, bssid, mac, kind, timestamp, source, destination, summary, writer, complete=True):
        record = dict(id=str(self.next_id), timestampUnixMillis=int(timestamp * 1000),
                      kind=kind, sourceAddress=source, destinationAddress=destination,
                      summary=summary[:512], complete=complete)
        self.next_id += 1
        state = dict(record=record, part=0, bssid=bssid, mac=mac, detailBuffer=bytearray(), lastFlush=time.monotonic())
        self.emit_record(state, writer)
        return state

    @staticmethod
    def emit_record(state, writer):
        writer.write(dict(type="communication", bssid=state["bssid"], deviceMac=state["mac"], **state["record"]))

    def detail(self, state, value, writer, final=True):
        if isinstance(value, str):
            value = value.encode("utf-8")
        state["detailBuffer"].extend(value)
        # Each independently keyed part remains well below the transport page limit.
        while len(state["detailBuffer"]) >= 8192 or (final and state["detailBuffer"]):
            data = bytes(state["detailBuffer"][:8192])
            del state["detailBuffer"][:8192]
            writer.write(dict(type="communicationPart", bssid=state["bssid"], deviceMac=state["mac"],
                id=state["record"]["id"], index=state["part"], data=base64.b64encode(data).decode("ascii")))
            state["part"] += 1
            state["lastFlush"] = time.monotonic()
        if state["detailBuffer"]:
            self.pending_details[state["record"]["id"]] = state
        else:
            self.pending_details.pop(state["record"]["id"], None)

    def flush_details(self, writer, force=False):
        for state in tuple(self.pending_details.values()):
            if force or time.monotonic() - state["lastFlush"] >= 0.25:
                self.detail(state, b"", writer)

    def dns(self, payload, bssid, mac, timestamp, source, destination, writer):
        try:
            dns = DNS(payload)
            if dns.qr != 0 or not dns.qdcount:
                return
            queries = []
            for query in dns.qd:
                queries.append(query.qname.decode("utf-8", "replace").rstrip("."))
            state = self.new_record(bssid, mac, "dns", timestamp, source, destination, ", ".join(queries), writer)
            self.detail(state, dns.show(dump=True), writer)
        except (ValueError, IndexError, struct.error):
            return

    def consume_network(self, packet, bssid, mac, direction, timestamp, writer):
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
                    for option in packet[DHCP].options:
                        if isinstance(option, tuple) and option[0] in ("hostname", "host_name"):
                            value = option[1]
                            dhcp_name = (value.decode("utf-8", "replace") if isinstance(value, bytes) else str(value)).strip("\x00 ") or None
                    if not dhcp_name:
                        for option in packet[DHCP].options:
                            if isinstance(option, tuple) and option[0] == "client_FQDN" and isinstance(option[1], bytes):
                                value = option[1]
                                if len(value) > 3:
                                    domain = value[3:]
                                    if value[0] & 4:
                                        labels, position = [], 0
                                        while position < len(domain) and domain[position]:
                                            length = domain[position]
                                            if length > 63 or position + length + 1 > len(domain):
                                                labels = []
                                                break
                                            labels.append(domain[position + 1:position + 1 + length].decode("utf-8", "replace"))
                                            position += length + 1
                                        dhcp_name = ".".join(labels) or None
                                    else:
                                        dhcp_name = domain.decode("utf-8", "replace").strip("\x00 ") or None
                    state = self.new_record(bssid, mac, "dhcp", timestamp, source, destination, "DHCP" + (" · " + dhcp_name if dhcp_name else ""), writer)
                    self.detail(state, packet[BOOTP].show(dump=True), writer)
            if direction == "upload" and int(udp.dport) in (53, 5353):
                self.dns(bytes(udp.payload), bssid, mac, timestamp, source, destination, writer)
            if direction == "upload":
                self.quic(bytes(udp.payload), bssid, mac, timestamp, source, destination, writer)
        tcp = packet.getlayer(TCP)
        if tcp is not None and direction == "upload":
            self.tcp(tcp, bssid, mac, timestamp, source, destination, writer)
        return dhcp_name

    def quic(self, datagram, bssid, mac, timestamp, source, destination, writer):
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
                        state = self.new_record(bssid, mac, "https", timestamp, source, destination, name, writer)
                        self.detail(state, "QUIC Initial / TLS ClientHello SNI: " + name + "\nHTTPS 正文保持加密。", writer)
                    stream["parts"].clear()
                    stream["done"] = True
                offset += end
        except (ValueError, InvalidTag, IndexError, struct.error):
            return

    @staticmethod
    def flow_size(flow):
        return len(flow["buffer"]) + len(flow["tls"]) + sum(map(len, flow["pending"].values()))

    def drop_flow(self, key):
        self.buffered_bytes -= self.flow_size(self.flows.pop(key))

    def tcp(self, tcp, bssid, mac, timestamp, source, destination, writer):
        key = (bssid, mac, source, int(tcp.sport), destination, int(tcp.dport))
        while self.flows:
            first = next(iter(self.flows))
            if self.flows[first]["lastSeen"] >= timestamp - 60 and len(self.flows) < 4096 and self.buffered_bytes < 16 * 1024 * 1024:
                break
            # Only incomplete stream reassembly is evicted. Emitted history stays on disk.
            self.drop_flow(first)
        sequence = int(tcp.seq)
        if int(tcp.flags) & 2:
            if key in self.flows:
                self.drop_flow(key)
            self.flows[key] = dict(next=sequence + 1, pending={}, buffer=b"", tls=b"", request=None, remaining=0, chunked=False, chunkRemaining=None, lastSeen=timestamp)
            sequence += 1
        data = bytes(tcp.payload)
        if not data:
            if int(tcp.flags) & 5:
                if key in self.flows:
                    self.drop_flow(key)
            return
        flow = self.flows.setdefault(key, dict(next=sequence, pending={}, buffer=b"", tls=b"", request=None, remaining=0, chunked=False, chunkRemaining=None, lastSeen=timestamp))
        self.flows.move_to_end(key)
        flow["lastSeen"] = timestamp
        # Unwrap 32-bit TCP sequence numbers relative to the contiguous frontier.
        sequence = flow["next"] + ((sequence - flow["next"] + 0x80000000) & 0xffffffff) - 0x80000000
        end = sequence + len(data)
        if end <= flow["next"]:
            return
        if sequence > flow["next"]:
            existing = flow["pending"].get(sequence, b"")
            if len(data) > len(existing):
                flow["pending"][sequence] = data
                self.buffered_bytes += len(data) - len(existing)
            if self.flow_size(flow) > 1024 * 1024:
                self.drop_flow(key)
            return
        previous_size = self.flow_size(flow)
        data = data[flow["next"] - sequence:]
        flow["next"] += len(data)
        flow["buffer"] += data
        for pending_sequence in sorted(tuple(flow["pending"])):
            if pending_sequence > flow["next"]:
                break
            pending = flow["pending"].pop(pending_sequence)
            tail = pending[max(0, flow["next"] - pending_sequence):]
            flow["buffer"] += tail
            flow["next"] += len(tail)
        self.parse_stream(flow, bssid, mac, int(tcp.dport), timestamp, source + ":" + str(tcp.sport), destination + ":" + str(tcp.dport), writer)
        self.buffered_bytes += self.flow_size(flow) - previous_size
        if self.flow_size(flow) > 1024 * 1024:
            self.drop_flow(key)
            return
        if int(tcp.flags) & 5:
            self.drop_flow(key)

    def parse_stream(self, flow, bssid, mac, port, timestamp, source, destination, writer):
        while flow["buffer"]:
            data = flow["buffer"]
            if port == 53:
                if len(data) < 2 or len(data) < 2 + int.from_bytes(data[:2], "big"):
                    return
                length = int.from_bytes(data[:2], "big")
                self.dns(data[2:2 + length], bssid, mac, timestamp, source, destination, writer)
                flow["buffer"] = data[2 + length:]
                continue
            if flow["request"]:
                if flow["chunked"]:
                    if flow["chunkRemaining"] is None:
                        end = data.find(b"\r\n")
                        if end < 0:
                            return
                        try:
                            length = int(data[:end].split(b";", 1)[0], 16)
                        except ValueError:
                            flow["buffer"] = b""
                            return
                        if length == 0:
                            trailer_end = data.find(b"\r\n\r\n")
                            if trailer_end < 0:
                                return
                            self.detail(flow["request"], data[:trailer_end + 4], writer)
                            flow["buffer"] = data[trailer_end + 4:]
                            flow["request"]["record"]["complete"] = True
                            self.emit_record(flow["request"], writer)
                            flow["request"] = None
                            continue
                        self.detail(flow["request"], data[:end + 2], writer)
                        flow["buffer"] = data[end + 2:]
                        flow["chunkRemaining"] = length + 2
                        continue
                    count = min(len(data), flow["chunkRemaining"])
                    self.detail(flow["request"], data[:count], writer, final=False)
                    flow["buffer"] = data[count:]
                    flow["chunkRemaining"] -= count
                    if flow["chunkRemaining"] == 0:
                        flow["chunkRemaining"] = None
                    continue
                count = min(len(data), flow["remaining"])
                self.detail(flow["request"], data[:count], writer, final=False)
                flow["buffer"] = data[count:]
                flow["remaining"] -= count
                if flow["remaining"] == 0:
                    self.detail(flow["request"], b"", writer)
                    flow["request"]["record"]["complete"] = True
                    self.emit_record(flow["request"], writer)
                    flow["request"] = None
                continue
            # TLS records and handshake messages can each span several TCP segments.
            if data[0] in (20, 21, 22, 23) and len(data) >= 2 and data[1] == 3:
                if len(data) < 5:
                    return
                length = int.from_bytes(data[3:5], "big")
                if len(data) < length + 5:
                    return
                if data[0] == 22:
                    flow["tls"] += data[5:5 + length]
                    while len(flow["tls"]) >= 4:
                        hello = flow["tls"]
                        hello_len = int.from_bytes(hello[1:4], "big")
                        if len(hello) < 4 + hello_len:
                            break
                        if hello[0] == 1:
                            name = tls_sni(hello[4:4 + hello_len])
                            if name:
                                state = self.new_record(bssid, mac, "https", timestamp, source, destination, name, writer)
                                self.detail(state, "TLS ClientHello SNI: " + name + "\nHTTPS 正文保持加密；未从流量推测缺失域名。", writer)
                        flow["tls"] = hello[4 + hello_len:]
                flow["buffer"] = data[5 + length:]
                continue
            # Do not interpret arbitrary binary/encrypted protocols as HTTP.
            methods = (b"GET ", b"POST ", b"HEAD ", b"PUT ", b"DELETE ", b"OPTIONS ", b"PATCH ", b"CONNECT ", b"TRACE ")
            if not any(data.startswith(method) for method in methods):
                if any(method.startswith(data) for method in methods) or (len(data) < 5 and data[0] in (20, 21, 22, 23)):
                    return
                flow["buffer"] = b""
                return
            header_end = data.find(b"\r\n\r\n")
            if header_end < 0:
                return
            header = data[:header_end + 4]
            lines = header.decode("iso-8859-1").split("\r\n")
            fields = dict(line.split(":", 1) for line in lines[1:] if ":" in line)
            fields = {key.strip().lower(): value.strip() for key, value in fields.items()}
            if " HTTP/" not in lines[0]:
                flow["buffer"] = b""
                return
            try:
                remaining = max(0, int(fields.get("content-length", "0")))
            except ValueError:
                flow["buffer"] = b""
                return
            chunked = "chunked" in fields.get("transfer-encoding", "").lower()
            state = self.new_record(bssid, mac, "http", timestamp, source, destination, lines[0] + (" · " + fields["host"] if fields.get("host") else ""), writer, not remaining and not chunked)
            self.detail(state, header, writer)
            flow.update(buffer=data[header_end + 4:], request=state if remaining or chunked else None,
                        remaining=remaining, chunked=chunked, chunkRemaining=None)
