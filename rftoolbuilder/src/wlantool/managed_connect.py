#!/usr/bin/env python3
"""One managed WPA2-PSK/CCMP diagnostic; a password per valid M1.

JSON Lines stdin: one configuration, followed by optional {"type":"stop"}.
Uses nl80211/EAPOL/DHCP directly; never changes Android saved configurations.
No automatic reassociation or password cycling. Detailed English text logs stdout.
"""
import errno
import fcntl
import hashlib
import hmac
import json
import os
import select
import signal
import socket
import struct
import sys
import time
import traceback
import threading
from types import SimpleNamespace

sys.path.insert(0, '/wlantool')
import scan
from managed_supplicant_guard import SupplicantPauseGuard
from managed_diagnostic_log import format_event
from cryptography.hazmat.primitives.keywrap import aes_key_unwrap

SSID = b''
CCMP = 0x000fac04
PSK = 0x000fac02
RSN = bytes.fromhex('0100000fac040100000fac040100000fac020000')
ASSOC_IE = bytes([48, len(RSN)]) + RSN


def flags(interface, value=None):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        req = struct.pack('16sH', interface.encode(), 0) + bytes(22)
        before = struct.unpack_from('H', fcntl.ioctl(sock, 0x8913, req), 16)[0]
        if value is not None:
            fcntl.ioctl(sock, 0x8914,
                        struct.pack('16sH', interface.encode(), value) + bytes(22))
        return before


def set_mac(interface, value):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        request = struct.pack('16sH6s', interface.encode(), 1, value) + bytes(16)
        fcntl.ioctl(sock, 0x8924, request)


def read_mac(nl):
    return scan.first_attr(nl.command(5, name='read interface MAC', reply=True)[0], 6)


class Netlink(scan.Nl80211Scanner):
    def _ensure_interface_up(self):
        # Resolving the family/querying interface state also works while DOWN.
        pass

    def command(self, command, attrs=b'', name='', reply=False):
        attrs = scan.pack_u32_attr(3, self.ifindex) + attrs
        seq = self._send(self.command_socket, self.family_id, command, 5, attrs)
        replies = []
        while True:
            data = self.command_socket.recv(1024 * 1024)
            for kind, _, sequence, payload in scan.iter_netlink_messages(data):
                if sequence != seq:
                    continue
                if kind == 2:
                    try:
                        self._raise_netlink_error(payload)
                    except OSError as error:
                        report('netlinkError', command=command, name=name,
                               errno=error.errno, error=str(error))
                        raise
                    report('netlinkAccepted', command=command, name=name)
                    return replies
                if kind == self.family_id:
                    replies.append(scan.parse_attrs(payload[4:]))

    def events_socket(self):
        seq = self._send(self.command_socket, 16, 3, 1,
                         scan.pack_attr(2, b'nl80211\0'))
        response = self._receive_single(self.command_socket, seq)
        attrs = scan.parse_attrs(response[4:])
        groups = scan.first_attr(attrs, 7)
        sock = self._open_socket(5)
        for entries in scan.parse_attrs(groups).values():
            for entry in entries:
                group = scan.parse_attrs(entry)
                name = scan.first_attr(group, 1).rstrip(b'\0').decode()
                if name in ('mlme', 'scan'):
                    index = struct.unpack('=I', scan.first_attr(group, 2))[0]
                    sock.setsockopt(270, 1, index)
                    report('subscribed', group=name, id=index)
        sock.setblocking(False)
        return sock


log_file = None
pcap_file = None
overall_deadline = None
handshake_deadline = None
stop_requested = threading.Event()
supplicant_guard = None

# IEEE 802.11 status/reason values used by wpa_supplicant's ieee802_11_defs.h.
STATUS_NAMES = {1: 'Unspecified rejection', 13: 'Unsupported authentication algorithm', 15: 'Authentication challenge failed',
                16: 'Authentication timed out', 17: 'AP cannot accept more stations', 18: 'Unsupported rates',
                30: 'AP temporarily rejected association', 31: 'Management frame protection policy not satisfied',
                40: 'Invalid information element', 41: 'Group cipher mismatch', 42: 'Pairwise cipher mismatch',
                43: 'Authentication/key management mismatch', 45: 'RSN capabilities mismatch', 46: 'Cipher policy rejected'}
REASON_NAMES = {1: 'Unspecified reason', 2: 'Previous authentication invalid', 3: 'Station leaving',
                4: 'Inactivity timeout', 5: 'AP busy', 6: 'Class 2 frame from an unauthenticated station',
                7: 'Class 3 frame from an unassociated station', 8: 'Station has left', 13: 'Invalid information element',
                14: 'MIC integrity failure', 15: 'Four-way handshake timed out (does not alone prove an incorrect password)',
                16: 'Group key update timed out', 17: 'Handshake information elements mismatch', 18: 'Group cipher mismatch',
                19: 'Pairwise cipher mismatch', 20: 'Authentication/key management mismatch', 23: '802.1X authentication failed',
                24: 'Cipher policy rejected', 34: 'Too few acknowledgements', 39: 'Connection timed out'}


def ensure_running():
    if stop_requested.is_set():
        raise InterruptedError('Stop requested')
    now = time.monotonic()
    if overall_deadline is not None and now >= overall_deadline:
        raise TimeoutError('Overall task timeout; no explicit failure frame was received to establish an incorrect password')
    if handshake_deadline is not None and now >= handshake_deadline:
        raise TimeoutError('Handshake timeout measured from the first M1')
    if supplicant_guard is not None:
        supplicant_guard.ensure_active()


def stage(value, message):
    report('stage', stage=value, message=message)


class HandshakeRestart(Exception):
    def __init__(self, packet):
        super().__init__('Received a new valid M1; continuing with the next password line')
        self.packet = packet


def report(event, **fields):
    for line in format_event(event, fields):
        print(line, flush=True)
        if log_file:
            log_file.write(line + '\n')
    if log_file:
        log_file.flush()


def record_frame(raw):
    if pcap_file is None:
        return
    seconds, fraction = divmod(time.time(), 1)
    pcap_file.write(struct.pack('<IIII', int(seconds), int(fraction * 1e6),
                                len(raw), len(raw)) + raw)
    pcap_file.flush()


def read_events(nl, events):
    data = events.recv(1024 * 1024)
    result = []
    for kind, _, _, payload in scan.iter_netlink_messages(data):
        if kind != nl.family_id or len(payload) < 4:
            continue
        attrs = scan.parse_attrs(payload[4:])
        ifindex = scan.first_attr(attrs, 3)
        if ifindex and struct.unpack('=I', ifindex)[0] != nl.ifindex:
            continue
        command = payload[0]
        report('netlinkEvent', command=command,
               attributes={str(k): [v.hex() for v in vals]
                           for k, vals in attrs.items()})
        result.append((command, attrs))
    return result


def check_events(nl, events):
    for command, attrs in read_events(nl, events):
        frame = scan.first_attr(attrs, 51)
        if frame and len(frame) >= 24:
            control = struct.unpack_from('<H', frame)[0]
            subtype = (control >> 4) & 15
            if control & 0x400f == 0:  # Unprotected IEEE 802.11 management frame.
                if subtype in (1, 3, 11) and len(frame) >= 30:
                    offset = 28 if subtype == 11 else 26
                    status_code = struct.unpack_from('<H', frame, offset)[0]
                    report('managementResponse', subtype=subtype, statusCode=status_code,
                           description=STATUS_NAMES.get(status_code, 'Success' if status_code == 0 else 'Unmapped status code'),
                           raw=frame.hex())
                    if status_code:
                        raise RuntimeError('Authentication/association response rejected access: status=%s %s' %
                                           (status_code, STATUS_NAMES.get(status_code, 'Unmapped status code')))
        if command == 46:
            status = scan.first_attr(attrs, 72)
            if status and struct.unpack('=H', status)[0]:
                code = struct.unpack('=H', status)[0]
                report('associationRejected', statusCode=code,
                       description=STATUS_NAMES.get(code, 'Unmapped status code'))
                raise RuntimeError('AP rejected association: status=%s %s' %
                                   (code, STATUS_NAMES.get(code, 'Unmapped status code')))
            if 65 in attrs:
                raise RuntimeError('Driver reported association timeout')
            report('routerAssociated', bssid=scan.first_attr(attrs, 6).hex(':')
                   if scan.first_attr(attrs, 6) else None)
        if command in (39, 40, 48):
            reason = scan.first_attr(attrs, 54)
            code = struct.unpack('=H', reason)[0] if reason else None
            frame = scan.first_attr(attrs, 51)
            # AUTH/ASSOC reject status and DEAUTH/DISASSOC reason in raw 802.11 frames.
            if frame and len(frame) >= 26 and not struct.unpack_from('<H', frame)[0] & 0x4000:
                subtype = (frame[0] >> 4) & 15
                if subtype in (10, 12):
                    code = struct.unpack_from('<H', frame, 24)[0]
            report('connectionTerminated', command=command, reasonCode=code,
                   description=REASON_NAMES.get(code, 'Unmapped reason code'),
                   disconnectedByAp=71 in attrs, frame=frame.hex() if frame else None)
            raise RuntimeError('Association terminated; command=%s reason=%s' %
                               (command, code))


def wait_eapol(nl, events, eth, ap, own, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ensure_running()
        readable, _, _ = select.select([events, eth], [], [], min(.25, max(0, deadline-time.monotonic())))
        if events in readable:
            check_events(nl, events)
        if eth not in readable:
            continue
        raw, address = eth.recvfrom(65536)
        if len(raw) < 18 or raw[12:14] != b'\x88\x8e':
            continue
        # Ignore PACKET_OUTGOING and unrelated peers before logging/capture.
        if address[2] == 4 or raw[6:12] != ap or raw[:6] != own:
            continue
        record_frame(raw)
        length = struct.unpack_from('>H', raw, 16)[0]
        if len(raw) < 18 + length:
            report('eapolDropped', reason='Truncated EAPOL frame', raw=raw.hex())
            continue
        key = raw[14:18+length]
        if key[1] != 3 or len(key) < 99:
            report('otherEapol', raw=key.hex())
            continue
        info = struct.unpack_from('>H', key, 5)[0]
        if key[4] != 2 or info & 7 != 2 or not info & 8 or not info & 0x80 or info & 0xc00:
            report('eapolDropped', reason='Not a WPA2-PSK/CCMP pairwise ACK descriptor, or ERROR/REQUEST is set', raw=key.hex())
            continue
        if len(key) != 99 + struct.unpack_from('>H', key, 97)[0]:
            report('eapolDropped', reason='Invalid EAPOL-Key Data length', raw=key.hex())
            continue
        stage = 'M3' if info & 0x100 and info & 0x80 else 'M1' if info & 0x80 else 'other'
        report('rxEapol', stage=stage, keyInfo=hex(info),
               bssid=ap.hex(':'), station=own.hex(':'),
               replayCounter=int.from_bytes(key[9:17], 'big'),
               nonce=key[17:49].hex(), mic=key[81:97].hex(), raw=key.hex(),
               install=bool(info & 0x40), ack=bool(info & 0x80),
               micPresent=bool(info & 0x100), secure=bool(info & 0x200),
               encrypted=bool(info & 0x1000), keyData=key[99:].hex())
        return key, info, stage
    raise TimeoutError('Timed out waiting for EAPOL from the target AP')


def derive(pmk, ap, own, anonce, snonce):
    context = min(ap, own) + max(ap, own) + min(anonce, snonce) + max(anonce, snonce)
    return b''.join(hmac.new(pmk, b'Pairwise key expansion\0' + context + bytes([i]),
                             hashlib.sha1).digest() for i in range(4))[:64]


def response(key, info, nonce, data, kck):
    body = key[4:5] + struct.pack('>HH', info, 0) + key[9:17] + nonce
    body += bytes(16 + 8 + 8 + 16) + struct.pack('>H', len(data)) + data
    frame = key[:1] + b'\x03' + struct.pack('>H', len(body)) + body
    mic = hmac.new(kck, frame, hashlib.sha1).digest()[:16]
    return frame[:81] + mic + frame[97:]


def send_eapol(eth, ap, own, frame, stage, **fields):
    raw = ap + own + b'\x88\x8e' + frame
    size = eth.send(raw)
    record_frame(raw)
    # An outgoing PCAP record is local submission evidence, not a radio ACK.
    report('txEapolSubmitted', stage=stage, bytes=size,
           bssid=ap.hex(':'), station=own.hex(':'),
           replayCounter=int.from_bytes(frame[9:17], 'big'),
           nonce=frame[17:49].hex(), mic=frame[81:97].hex(), raw=frame.hex(), **fields)


def install_key(nl, index, key, pairwise, ap, sequence):
    attrs = scan.pack_attr(8, bytes([index])) + scan.pack_attr(7, key)
    attrs += scan.pack_u32_attr(9, CCMP) + scan.pack_attr(10, sequence)
    attrs += scan.pack_u32_attr(55, 1 if pairwise else 0)
    if pairwise:
        attrs += scan.pack_attr(6, ap)
    nl.command(11, attrs, 'install PTK' if pairwise else 'install GTK')
    report('keyInstalled', kind='PTK' if pairwise else 'GTK', index=index,
           keyBytes=len(key), sequence=sequence.hex())


def gtk_from_m3(key, ptk):
    size = struct.unpack_from('>H', key, 97)[0]
    if len(key) != 99 + size:
        raise RuntimeError('M3 Key Data length mismatch')
    info = struct.unpack_from('>H', key, 5)[0]
    if not info & 0x1000:
        raise RuntimeError('M3 GTK Key Data is not encrypted')
    plain = aes_key_unwrap(ptk[16:32], key[99:])
    report('m3KeyDataDecrypted', bytes=len(plain))
    result = None
    for eid, data in scan.parse_information_elements(plain):
        if eid == 48:
            report('m3Rsn', data=data.hex())
            if len(data) < 8 or data[:2] != b'\x01\x00' or data[2:6] != bytes.fromhex('000fac04'):
                raise RuntimeError('M3 RSN group cipher does not match negotiated CCMP')
        if eid == 221 and data[:4] == bytes.fromhex('000fac01'):
            if len(data) != 22:
                raise RuntimeError('M3 GTK length does not match CCMP')
            result = (data[4] & 3, data[6:])
    if result is None:
        raise RuntimeError('M3 is missing GTK KDE')
    return result


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    total = sum(struct.unpack('!%dH' % (len(data)//2), data))
    while total >> 16:
        total = (total & 65535) + (total >> 16)
    return (~total) & 65535


def dhcp_packet(own, xid, message_type, requested=None, server=None, hostname=None,
                hostname_encoding='utf-8'):
    bootp = struct.pack('!BBBBIHH4s4s4s4s16s64s128s', 1, 1, 6, 0, xid,
                        0, 0x8000, bytes(4), bytes(4), bytes(4), bytes(4),
                        own + bytes(10), bytes(64), bytes(128))
    options = b'\x63\x82\x53\x63' + bytes([53, 1, message_type])
    options += bytes([61, 7, 1]) + own
    if hostname is not None:
        encoded = hostname.encode(hostname_encoding)
        if not 1 <= len(encoded) <= 255:
            raise ValueError('DHCP hostname must encode to 1-255 bytes')
        options += bytes([12, len(encoded)]) + encoded
    options += bytes([55, 5, 1, 3, 6, 51, 54])
    if requested is not None:
        options += bytes([50, 4]) + requested
    if server is not None:
        options += bytes([54, 4]) + server
    payload = bootp + options + b'\xff'
    payload += bytes(max(0, 300-len(payload)))
    udp = struct.pack('!HHHH', 68, 67, len(payload)+8, 0) + payload
    ip = struct.pack('!BBHHHBBH4s4s', 0x45, 0, len(udp)+20, xid & 65535,
                     0, 64, 17, 0, bytes(4), b'\xff'*4)
    ip = ip[:10] + struct.pack('!H', checksum(ip)) + ip[12:]
    return b'\xff'*6 + own + b'\x08\x00' + ip + udp


def parse_dhcp(raw, xid, own):
    if len(raw) < 14+20+8+240 or raw[12:14] != b'\x08\x00':
        return None
    ip = raw[14:]
    ihl = (ip[0] & 15)*4
    if ip[0] >> 4 != 4 or ihl < 20 or ip[9] != 17:
        return None
    udp = ip[ihl:]
    if len(udp) < 8 or struct.unpack_from('!HH', udp) != (67, 68):
        return None
    length = struct.unpack_from('!H', udp, 4)[0]
    if len(udp) < length or length < 248:
        return None
    bootp = udp[8:length]
    if bootp[0:3] != b'\x02\x01\x06' or struct.unpack_from('!I', bootp, 4)[0] != xid:
        return None
    if bootp[28:34] != own or bootp[236:240] != b'\x63\x82\x53\x63':
        return None
    options, pos = {}, 240
    while pos < len(bootp):
        code = bootp[pos]
        pos += 1
        if code == 255:
            break
        if code == 0:
            continue
        if pos >= len(bootp):
            return None
        size = bootp[pos]
        pos += 1
        if pos+size > len(bootp):
            return None
        options[code] = bootp[pos:pos+size]
        pos += size
    return bootp[16:20], options


def obtain_dhcp(interface, own, hostname=None, hostname_encoding='utf-8', nl=None, events=None,
                eth=None, retransmission=None):
    xid = int.from_bytes(os.urandom(4), 'big')
    with socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0800)) as sock:
        sock.bind((interface, 0))
        sock.setblocking(False)
        offered = server = None
        message_type = 1
        next_send = 0
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            ensure_running()
            if events is not None and select.select([events], [], [], 0)[0]:
                check_events(nl, events)
            if eth is not None and select.select([eth], [], [], 0)[0]:
                retransmission()
            if time.monotonic() >= next_send:
                raw = dhcp_packet(own, xid, message_type, offered, server, hostname,
                                  hostname_encoding)
                submitted = sock.send(raw)
                record_frame(raw)
                report('dhcpTxSubmitted', message='DISCOVER' if message_type == 1 else 'REQUEST',
                       xid=xid, bytes=submitted, hostname=hostname,
                       hostnameEncoding=hostname_encoding,
                       hostnameHex=hostname.encode(hostname_encoding).hex() if hostname else None,
                       clientMac=own.hex(':'), raw=raw.hex())
                next_send = time.monotonic()+3
            if not select.select([sock], [], [], .2)[0]:
                continue
            raw, address = sock.recvfrom(65536)
            if address[2] == 4:
                continue
            result = parse_dhcp(raw, xid, own)
            if result is None:
                continue
            ip, options = result
            record_frame(raw)
            kind = options.get(53, b'\0')[0]
            report('dhcpRx', type=kind, offeredIp=socket.inet_ntoa(ip), xid=xid,
                   options={str(k): v.hex() for k, v in options.items()}, raw=raw.hex())
            if kind == 2 and message_type == 1:
                if ip == bytes(4) or len(options.get(54, b'')) != 4:
                    raise RuntimeError('DHCP OFFER is missing an address or server identifier')
                offered, server = ip, options[54]
                message_type, next_send = 3, 0
            elif kind == 5 and message_type == 3:
                if ip != offered or options.get(54) != server:
                    report('dhcpIgnored', reason='ACK does not match the selected lease')
                    continue
                mask = options.get(1)
                if mask is None or len(mask) != 4:
                    raise RuntimeError('DHCP ACK is missing the subnet mask')
                mask_number = int.from_bytes(mask, 'big')
                prefix = bin(mask_number).count('1')
                if mask_number != ((0xffffffff << (32-prefix)) & 0xffffffff):
                    raise RuntimeError('DHCP subnet mask is not contiguous')
                return ip, prefix, options
            elif kind == 6 and message_type == 3:
                raise RuntimeError('DHCP server rejected the address request (NAK)')
        raise TimeoutError('DHCP did not obtain an IP address within 15 seconds')


def route_address(ifindex, ip, prefix, add):
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0) as sock:
        sock.bind((0, 0))
        sock.settimeout(3)
        seq = 1
        flags_value = 5 | (0x400 | 0x200 if add else 0)
        payload = struct.pack('=BBBBI', socket.AF_INET, prefix, 0, 0, ifindex)
        payload += scan.pack_attr(1, ip) + scan.pack_attr(2, ip)
        packet = struct.pack('=IHHII', 16+len(payload), 20 if add else 21,
                             flags_value, seq, sock.getsockname()[0]) + payload
        sock.sendto(packet, (0, 0))
        while True:
            for kind, _, sequence, data in scan.iter_netlink_messages(sock.recv(65536)):
                if sequence == seq and kind == 2:
                    scan.Nl80211Scanner._raise_netlink_error(data)
                    return


def read_addresses(ifindex):
    result = []
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0) as sock:
        sock.bind((0, 0))
        sock.settimeout(3)
        payload = struct.pack('=BBBBI', socket.AF_INET, 0, 0, 0, 0)
        sock.sendto(struct.pack('=IHHII', 24, 22, 0x301, 1, sock.getsockname()[0])+payload, (0, 0))
        while True:
            for kind, _, sequence, data in scan.iter_netlink_messages(sock.recv(65536)):
                if sequence != 1:
                    continue
                if kind == 3:
                    return result
                if kind == 2:
                    scan.Nl80211Scanner._raise_netlink_error(data)
                if kind == 20 and len(data) >= 8:
                    family, prefix, _, _, index = struct.unpack_from('=BBBBI', data)
                    if family == socket.AF_INET and index == ifindex:
                        attrs = scan.parse_attrs(data[8:])
                        ip = scan.first_attr(attrs, 2) or scan.first_attr(attrs, 1)
                        if ip:
                            result.append((ip, prefix))


def compatible_rsn(data):
    """Require advertised PSK + pairwise/group CCMP; reject mandatory PMF."""
    try:
        if len(data) < 8 or data[:2] != b'\x01\x00' or data[2:6] != bytes.fromhex('000fac04'):
            return False
        count = struct.unpack_from('<H', data, 6)[0]
        offset = 8
        pairwise = [data[offset+i*4:offset+(i+1)*4] for i in range(count)]
        offset += count*4
        akm_count = struct.unpack_from('<H', data, offset)[0]
        offset += 2
        akms = [data[offset+i*4:offset+(i+1)*4] for i in range(akm_count)]
        offset += akm_count*4
        if offset > len(data):
            return False
        capabilities = struct.unpack_from('<H', data, offset)[0] if len(data) >= offset+2 else 0
        return bytes.fromhex('000fac04') in pairwise and bytes.fromhex('000fac02') in akms and not capabilities & 0x40
    except (struct.error, IndexError):
        return False


def run(args):
    global log_file, pcap_file, SSID, overall_deadline, handshake_deadline
    global supplicant_guard
    SSID = args.ssid.encode('utf-8')
    if not 1 <= len(SSID) <= 32:
        raise ValueError('SSID must be 1-32 bytes')
    if not args.passwords or any(not 8 <= len(p.encode('utf-8')) <= 63 for p in args.passwords):
        raise ValueError('Each WPA2 password line must be 8-63 bytes')
    encoded_name = args.hostname.encode(args.hostname_encoding)
    if not 1 <= len(encoded_name) <= 255:
        raise ValueError('Hostname must encode to 1-255 bytes')
    requested_mac = bytes.fromhex(args.mac.replace(':', '')) if args.mac else None
    if requested_mac is not None and (len(requested_mac) != 6 or requested_mac[0] & 1 or requested_mac == bytes(6)):
        raise ValueError('Test MAC must be a valid six-byte unicast address')
    if args.timeout_millis <= 0:
        raise ValueError('Overall timeout must be greater than zero')
    if args.output:
        os.makedirs(args.output, exist_ok=True)
        os.umask(0o077)
        log_file = open(args.output + '/diagnostic.log', 'w', encoding='utf-8')
        pcap_file = open(args.output + '/eapol.pcap', 'wb')
        pcap_file.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
    overall_deadline = time.monotonic() + args.timeout_millis/1000
    handshake_deadline = None
    original_flags = None
    original_mac = None
    nl = events = eth = None
    connecting = success = restore_mac = False
    added_address = None
    cleanup_failed = False

    def interrupted(signum, frame):
        raise InterruptedError('Received termination signal %s' % signum)

    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, interrupted)
    try:
        original_flags = flags(args.interface)
        report('begin', interface=args.interface, ssid=args.ssid, passwordCount=len(args.passwords),
               timeoutMillis=args.timeout_millis, hostname=args.hostname, hostnameEncoding=args.hostname_encoding,
               maxHandshakeAttempts=args.max_attempts, handshakeTimeoutMillis=args.handshake_timeout,
               passwordErrorFlag=args.password_error)
        stage('ROUTER_COMMUNICATION', 'Communicating with router')
        nl = Netlink(args.interface)
        info = nl.command(5, name='read interface', reply=True)[0]
        mode = struct.unpack('=I', scan.first_attr(info, 5))[0]
        if mode != 2:
            raise RuntimeError('Interface is not managed: iftype=%s' % mode)
        # Pause Android's controller before changing the interface or associating.
        # Its independent recovery process survives termination of this terminal.
        supplicant_guard = SupplicantPauseGuard(args.timeout_millis + 15000, report)
        supplicant_guard.start(ensure_running)
        info = nl.command(5, name='read interface after pausing system supplicant', reply=True)[0]
        mode = struct.unpack('=I', scan.first_attr(info, 5))[0]
        if mode != 2:
            raise RuntimeError('Interface is no longer managed after pausing the system controller: iftype=%s' % mode)
        own = original_mac = scan.first_attr(info, 6)
        report('interface', interface=args.interface, mode=mode, mac=own.hex(':'))
        initial_addresses = read_addresses(nl.ifindex)
        report('initialAddresses', addresses=[socket.inet_ntoa(ip)+'/'+str(prefix)
                                              for ip, prefix in initial_addresses])
        events = nl.events_socket()
        associated = any(scan.first_attr(scan.parse_attrs(bss), 9) == struct.pack('=I', 1)
                         for bss in nl.read_results())
        if associated:
            report('disconnectingExistingConnection', message='Disconnect the current interface connection without changing saved configurations')
            nl.command(48, scan.pack_attr(54, struct.pack('=H', 3)), 'disconnect existing connection')
            while any(scan.first_attr(scan.parse_attrs(bss), 9) == struct.pack('=I', 1)
                      for bss in nl.read_results()):
                ensure_running()
                if select.select([events], [], [], .1)[0]:
                    read_events(nl, events)
            report('existingConnectionDisconnected')
        # Consume only pre-test disconnect events before requesting a new association.
        while select.select([events], [], [], 0)[0]:
            read_events(nl, events)
        if requested_mac is not None:
            restore_mac = True
            flags(args.interface, original_flags & ~1)
            set_mac(args.interface, requested_mac)
            own = read_mac(nl)
            report('macSet', requested=requested_mac.hex(':'), actual=own.hex(':'), original=original_mac.hex(':'))
            if own != requested_mac:
                raise RuntimeError('Driver did not retain the requested MAC')
        flags(args.interface, original_flags | 1)
        if read_mac(nl) != own:
            raise RuntimeError('MAC changed after bringing the interface up')
        ensure_running()
        report('scanStarted', scope='scan only; association limited to submitted SSID')
        try:
            nl._trigger_scan()
        except OSError as error:
            if error.errno != errno.EBUSY:
                raise
            report('scanBusy', message='Waiting for the existing scan and reading cached results')
        scan_deadline = min(overall_deadline, time.monotonic()+3)
        while time.monotonic() < scan_deadline:
            ensure_running()
            if select.select([events], [], [], .1)[0]:
                if any(cmd in (34, 35) for cmd, _ in read_events(nl, events)):
                    break
        candidates = []
        target_seen = False
        for entry in nl.read_results():
            bss = scan.parse_attrs(entry)
            ies = scan.first_attr(bss, 6) or scan.first_attr(bss, 11) or b''
            elements = scan.parse_information_elements(ies)
            if not any(eid == 0 and data == SSID for eid, data in elements):
                continue
            target_seen = True
            target_rsn = next((data for eid, data in elements if eid == 48), None)
            if target_rsn is None or not compatible_rsn(target_rsn):
                report('unsupportedBss', bssid=scan.first_attr(bss, 1).hex(':'), rsn=target_rsn.hex() if target_rsn else None)
                continue
            signal_value = scan.first_attr(bss, 7)
            strength = struct.unpack('=i', signal_value)[0] if signal_value else -10000
            candidates.append((strength, bss, target_rsn))
        if not candidates:
            raise RuntimeError('Target does not support this WPA2-PSK/CCMP test without mandatory PMF' if target_seen else 'Target network was not found in scan results')
        _, bss, target_rsn = max(candidates, key=lambda item: item[0])
        ap = scan.first_attr(bss, 1)
        frequency = struct.unpack('=I', scan.first_attr(bss, 2))[0]
        report('target', bssid=ap.hex(':'), frequency=frequency,
               signalDbm=strength / 100,
               advertisedRsn=target_rsn.hex(), associationRsn=RSN.hex())
        pmks = []
        for password in args.passwords:
            ensure_running()
            pmks.append(hashlib.pbkdf2_hmac('sha1', password.encode('utf-8'), SSID, 4096, 32))
        eth = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x888e))
        eth.bind((args.interface, 0))
        eth.setblocking(False)
        attrs = scan.pack_attr(6, ap) + scan.pack_u32_attr(38, frequency)
        attrs += scan.pack_attr(52, SSID) + scan.pack_u32_attr(53, 0)
        attrs += scan.pack_attr(42, ASSOC_IE) + scan.pack_attr(68, b'')
        attrs += scan.pack_attr(70, b'') + scan.pack_u32_attr(73, CCMP)
        attrs += scan.pack_u32_attr(74, CCMP) + scan.pack_u32_attr(75, 2)
        attrs += scan.pack_u32_attr(76, PSK)
        ensure_running()
        connecting = True
        nl.command(46, attrs, 'CONNECT WPA2-PSK without handshake offload')
        attempts = 0
        last_m1_replay = None
        ptk = anonce = None
        snonce = os.urandom(32)
        pending_packet = None
        while True:
            ensure_running()
            if pending_packet is not None:
                key, info, message = pending_packet
                pending_packet = None
            else:
                key, info, message = wait_eapol(nl, events, eth, ap, own, max(.01, overall_deadline-time.monotonic()))
            replay = int.from_bytes(key[9:17], 'big')
            if message == 'M1':
                if info & 0x40 or info & 0x200 or key[17:49] == bytes(32):
                    report('eapolDropped', reason='Invalid M1 INSTALL/SECURE flags or ANonce')
                    continue
                if last_m1_replay is not None and replay < last_m1_replay:
                    report('eapolDropped', reason='M1 Replay Counter moved backwards', replayCounter=replay)
                    continue
                stage('WPA_HANDSHAKE_1_OF_4', 'WPA handshake M1 (1/4)')
                if args.handshake_timeout is not None and handshake_deadline is None:
                    handshake_deadline = time.monotonic()+args.handshake_timeout/1000
                if attempts >= len(pmks):
                    raise RuntimeError('Password lines exhausted: received valid M1 #%s with only %s password lines configured; aborting immediately' %
                                       (attempts+1, len(pmks)))
                if args.max_attempts is not None and attempts+1 > args.max_attempts:
                    raise RuntimeError('Handshake attempt limit exceeded: %s/%s; aborting immediately' % (attempts+1, args.max_attempts))
                anonce = key[17:49]
                ptk = derive(pmks[attempts], ap, own, anonce, snonce)
                last_m1_replay = replay
                attempts += 1
                stage('WPA_HANDSHAKE_2_OF_4', 'WPA handshake M2 (2/4)')
                report('handshakeCount', count=attempts, maximum=args.max_attempts,
                       message='%s/%s' % (attempts, args.max_attempts if args.max_attempts is not None else 'null'))
                report('tryingPsk', password=args.passwords[attempts-1])
                send_eapol(eth, ap, own, response(key, 0x010a, snonce, ASSOC_IE, ptk[:16]),
                           'M2', passwordLine=attempts)
                continue
            if message != 'M3' or ptk is None:
                report('eapolDropped', reason='No matching M2 exists, or message type does not match')
                continue
            if key[17:49] != anonce or replay <= last_m1_replay:
                report('eapolDropped', reason='M3 ANonce or Replay Counter does not match')
                continue
            expected = hmac.new(ptk[:16], key[:81]+bytes(16)+key[97:], hashlib.sha1).digest()[:16]
            if not hmac.compare_digest(key[81:97], expected):
                report('eapolDropped', reason='WPA: Invalid EAPOL-Key MIC - dropping packet')
                continue
            stage('WPA_HANDSHAKE_3_OF_4', 'WPA handshake M3 (3/4)')
            report('m3Verified', passwordLine=attempts, install=bool(info & 0x40), secure=bool(info & 0x200))
            index, gtk = gtk_from_m3(key, ptk)
            if not info & 0x40 or not info & 0x200:
                raise RuntimeError('M3 is missing INSTALL/SECURE flags')
            stage('WPA_HANDSHAKE_4_OF_4', 'WPA handshake M4 (4/4)')
            send_eapol(eth, ap, own, response(key, 0x030a, bytes(32), b'', ptk[:16]), 'M4')
            install_key(nl, 0, ptk[32:48], True, ap, bytes(6))
            install_key(nl, index, gtk, False, ap, key[65:71])
            authorized = struct.pack('=II', 1 << 1, 1 << 1)
            nl.command(18, scan.pack_attr(6, ap)+scan.pack_attr(67, authorized), 'authorize controlled port')
            if not any(scan.first_attr(scan.parse_attrs(entry), 1) == ap and
                       scan.first_attr(scan.parse_attrs(entry), 9) == struct.pack('=I', 1)
                       for entry in nl.read_results()):
                raise RuntimeError('Kernel does not report association with the target after key installation')
            report('handshakeCompletedLocally', associationRetained=True, m3MicVerified=True,
                   ptkInstalled=True, gtkInstalled=True, controlledPortAuthorized=True)
            handshake_deadline = None
            if read_mac(nl) != own:
                raise RuntimeError('MAC changed after handshake completion')
            stage('IP_NEGOTIATION', 'DHCP address acquisition')
            accepted_replay = replay

            def handle_retransmission():
                nonlocal accepted_replay
                try:
                    retry_key, retry_info, retry_message = wait_eapol(nl, events, eth, ap, own, .5)
                except TimeoutError:
                    ensure_running()
                    return
                retry_replay = int.from_bytes(retry_key[9:17], 'big')
                if retry_message == 'M1':
                    if retry_replay <= accepted_replay:
                        report('eapolDropped', reason='M1 Replay Counter did not increase after key installation')
                        return
                    raise HandshakeRestart((retry_key, retry_info, retry_message))
                if retry_message != 'M3':
                    report('eapolDropped', reason='Unmatched EAPOL message received during DHCP')
                    return
                retry_mic = hmac.new(ptk[:16], retry_key[:81]+bytes(16)+retry_key[97:], hashlib.sha1).digest()[:16]
                if retry_key[17:49] != anonce or retry_replay < accepted_replay or not hmac.compare_digest(retry_key[81:97], retry_mic):
                    report('eapolDropped', reason='Retransmitted M3 failed ANonce/Replay/MIC checks during DHCP')
                    return
                if not retry_info & 0x40 or not retry_info & 0x200 or gtk_from_m3(retry_key, ptk) != (index, gtk):
                    raise RuntimeError('M3 key material changed during DHCP')
                # A lost M4 may cause retransmitted M3. Never reinstall keys/reset packet numbers.
                report('m3Retransmission', message='Resending M4; preserving installed keys and packet numbers')
                send_eapol(eth, ap, own, response(retry_key, 0x030a, bytes(32), b'', ptk[:16]), 'M4', retransmission=True)
                accepted_replay = retry_replay

            try:
                ip, prefix, options = obtain_dhcp(args.interface, own, args.hostname,
                                                  args.hostname_encoding, nl, events, eth, handle_retransmission)
            except HandshakeRestart as restart:
                pending_packet = restart.packet
                snonce = os.urandom(32)
                handshake_deadline = None
                report('handshakeRestarted', message=str(restart), passwordsUsed=attempts)
                continue
            if (ip, prefix) not in initial_addresses:
                route_address(nl.ifindex, ip, prefix, True)
                added_address = (ip, prefix)
            if (ip, prefix) not in read_addresses(nl.ifindex):
                raise RuntimeError('DHCP address could not be confirmed by reading it back from the interface')
            if read_mac(nl) != own:
                raise RuntimeError('Another interface controller changed the MAC after IP acquisition')
            ensure_running()
            report('completed', ip=socket.inet_ntoa(ip), prefix=prefix,
                   server=socket.inet_ntoa(options[54]), mac=own.hex(':'),
                   hostname=args.hostname, hostnameEncoding=args.hostname_encoding,
                   message='Connectivity test passed: handshake completed and IP obtained; disconnecting immediately')
            success = True
            break
    except Exception as error:
        report('failed', error=repr(error), trace=traceback.format_exc())
    finally:
        if added_address and nl:
            try:
                route_address(nl.ifindex, *added_address, False)
                report('temporaryAddressRemoved', ip=socket.inet_ntoa(added_address[0]))
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='remove temporary IPv4', error=repr(error))
        if connecting and nl:
            try:
                nl.command(48, scan.pack_attr(54, struct.pack('=H', 3)), 'disconnect test connection')
                report('disconnected')
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='disconnect', error=repr(error))
        for sock in (eth, events):
            if sock:
                sock.close()
        if restore_mac and original_mac:
            try:
                flags(args.interface, flags(args.interface) & ~1)
                set_mac(args.interface, original_mac)
                if read_mac(nl) != original_mac:
                    raise RuntimeError('Original MAC could not be confirmed by reading it back')
                report('macRestored', mac=original_mac.hex(':'))
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='restore MAC', error=repr(error))
        if original_flags is not None:
            try:
                flags(args.interface, original_flags)
                if nl and original_mac and read_mac(nl) != original_mac:
                    raise RuntimeError('MAC changed after restoring interface flags')
                report('flagsRestored', flags=hex(original_flags))
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='restore interface flags', error=repr(error))
        if nl:
            nl.close()
        if supplicant_guard is not None:
            try:
                if not supplicant_guard.release():
                    raise RuntimeError('System supplicant recovery was not confirmed; check guard logs')
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='resume system wpa_supplicant', error=repr(error))
            finally:
                supplicant_guard = None
        report('end', success=success, cleanupCompleted=not cleanup_failed)
        if pcap_file:
            pcap_file.close()
        if log_file:
            log_file.close()
    return 0 if success and not cleanup_failed else 1


def read_control():
    for line in sys.stdin:
        try:
            if json.loads(line).get('type') == 'stop':
                stop_requested.set()
                return
        except (ValueError, AttributeError):
            report('controlRejected', message='Control request must be a JSON object')
    stop_requested.set()


def main():
    report('ready', protocol=1)
    line = sys.stdin.readline()
    if not line:
        raise InterruptedError('No configuration JSON received; input channel closed')
    request = json.loads(line)
    if not isinstance(request, dict):
        raise ValueError('Configuration must be a JSON object')
    if request.get('type') == 'stop':
        report('end', success=False, cleanupCompleted=True, message='Stopped during preparation')
        return 1
    args = SimpleNamespace(
        interface='wlan0', ssid=request['ssid'], passwords=request['passwords'],
        mac=request.get('mac'), hostname=request['hostname'],
        hostname_encoding=request.get('hostnameEncoding', 'gbk'),
        timeout_millis=request['timeoutMillis'], handshake_timeout=request.get('handshakeTimeoutMillis'),
        max_attempts=request.get('maxHandshakeAttempts'), password_error=request.get('passwordError', True),
        output=None,
    )
    threading.Thread(target=read_control, daemon=True).start()
    return run(args)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        report('failed', error=repr(error), trace=traceback.format_exc())
        sys.exit(1)
