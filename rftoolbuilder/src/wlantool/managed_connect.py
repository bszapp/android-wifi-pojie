#!/usr/bin/env python3
"""One managed WPA2-PSK/CCMP diagnostic; a password per valid M1.

JSON Lines stdin: one configuration, followed by optional {"type":"stop"}.
Uses nl80211/EAPOL/DHCP directly; never changes Android saved configurations.
No automatic reassociation or password cycling. Results are JSON Lines stdout.
"""
import datetime
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
started = time.monotonic()
overall_deadline = None
handshake_deadline = None
stop_requested = threading.Event()
supplicant_guard = None

# IEEE 802.11 status/reason values used by wpa_supplicant's ieee802_11_defs.h.
STATUS_NAMES = {1: '未指定的拒绝', 13: '不支持的认证算法', 15: '认证挑战失败',
                16: '认证超时', 17: 'AP 无法接收更多设备', 18: '不支持的速率',
                30: 'AP 暂时拒绝关联', 31: '管理帧保护策略不满足',
                40: '无效 IE', 41: '组播加密不匹配', 42: '单播加密不匹配',
                43: '认证密钥管理不匹配', 45: 'RSN 能力不匹配', 46: '加密策略拒绝'}
REASON_NAMES = {1: '未指定原因', 2: '先前认证无效', 3: '设备离开',
                4: '空闲超时', 5: 'AP 繁忙', 6: '未认证设备发送 Class 2 帧',
                7: '未关联设备发送 Class 3 帧', 8: '设备已离开', 13: '无效 IE',
                14: 'MIC 完整性错误', 15: '四次握手超时（不能单独证明密码错误）',
                16: '组密钥更新超时', 17: '握手 IE 不一致', 18: '组播加密不匹配',
                19: '单播加密不匹配', 20: '认证密钥管理不匹配', 23: '802.1X 认证失败',
                24: '加密策略拒绝', 34: 'ACK 过少', 39: '连接超时'}


def ensure_running():
    if stop_requested.is_set():
        raise InterruptedError('收到停止任务请求')
    now = time.monotonic()
    if overall_deadline is not None and now >= overall_deadline:
        raise TimeoutError('任务总超时；未收到明确失败包时不能推断密码错误')
    if handshake_deadline is not None and now >= handshake_deadline:
        raise TimeoutError('从首个 M1 开始的握手超时')
    if supplicant_guard is not None:
        supplicant_guard.ensure_active()


def stage(value, message):
    report('stage', stage=value, message=message)


class HandshakeRestart(Exception):
    def __init__(self, packet):
        super().__init__('收到新的有效 M1，继续下一行密码')
        self.packet = packet


def report(event, **fields):
    record = dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  elapsedMs=round((time.monotonic() - started) * 1000, 3),
                  event=event, **fields)
    line = json.dumps(record, ensure_ascii=False)
    print(line, flush=True)
    if log_file:
        log_file.write(line + '\n')
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
                           description=STATUS_NAMES.get(status_code, '成功' if status_code == 0 else '未映射的状态码'),
                           raw=frame.hex())
                    if status_code:
                        raise RuntimeError('认证/关联响应拒绝接入: status=%s %s' %
                                           (status_code, STATUS_NAMES.get(status_code, '未映射的状态码')))
        if command == 46:
            status = scan.first_attr(attrs, 72)
            if status and struct.unpack('=H', status)[0]:
                code = struct.unpack('=H', status)[0]
                report('associationRejected', statusCode=code,
                       description=STATUS_NAMES.get(code, '未映射的状态码'))
                raise RuntimeError('接入点拒绝关联: status=%s %s' %
                                   (code, STATUS_NAMES.get(code, '未映射的状态码')))
            if 65 in attrs:
                raise RuntimeError('驱动报告关联超时')
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
                   description=REASON_NAMES.get(code, '未映射的原因码'),
                   disconnectedByAp=71 in attrs, frame=frame.hex() if frame else None)
            raise RuntimeError('本次关联已终止; command=%s reason=%s' %
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
            report('eapolDropped', reason='EAPOL 帧被截断', raw=raw.hex())
            continue
        key = raw[14:18+length]
        if key[1] != 3 or len(key) < 99:
            report('otherEapol', raw=key.hex())
            continue
        info = struct.unpack_from('>H', key, 5)[0]
        if key[4] != 2 or info & 7 != 2 or not info & 8 or not info & 0x80 or info & 0xc00:
            report('eapolDropped', reason='不符合 WPA2-PSK/CCMP pairwise ACK descriptor，或带 ERROR/REQUEST', raw=key.hex())
            continue
        if len(key) != 99 + struct.unpack_from('>H', key, 97)[0]:
            report('eapolDropped', reason='EAPOL-Key Data 长度错误', raw=key.hex())
            continue
        stage = 'M3' if info & 0x100 and info & 0x80 else 'M1' if info & 0x80 else 'other'
        report('rxEapol', stage=stage, keyInfo=hex(info),
               replayCounter=int.from_bytes(key[9:17], 'big'),
               nonce=key[17:49].hex(), mic=key[81:97].hex(), raw=key.hex(),
               install=bool(info & 0x40), ack=bool(info & 0x80),
               micPresent=bool(info & 0x100), secure=bool(info & 0x200),
               encrypted=bool(info & 0x1000), keyData=key[99:].hex())
        return key, info, stage
    raise TimeoutError('等待目标 EAPOL 消息超时')


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
           replayCounter=int.from_bytes(frame[9:17], 'big'),
           mic=frame[81:97].hex(), raw=frame.hex(), **fields)


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
        raise RuntimeError('M3 Key Data 长度不一致')
    info = struct.unpack_from('>H', key, 5)[0]
    if not info & 0x1000:
        raise RuntimeError('M3 GTK Key Data 未加密')
    plain = aes_key_unwrap(ptk[16:32], key[99:])
    report('m3KeyDataDecrypted', bytes=len(plain))
    result = None
    for eid, data in scan.parse_information_elements(plain):
        if eid == 48:
            report('m3Rsn', data=data.hex())
            if len(data) < 8 or data[:2] != b'\x01\x00' or data[2:6] != bytes.fromhex('000fac04'):
                raise RuntimeError('M3 中的 RSN group cipher 与目标 CCMP 不符')
        if eid == 221 and data[:4] == bytes.fromhex('000fac01'):
            if len(data) != 22:
                raise RuntimeError('M3 GTK 长度不符合 CCMP')
            result = (data[4] & 3, data[6:])
    if result is None:
        raise RuntimeError('M3 缺少 GTK KDE')
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
            raise ValueError('DHCP 设备名称的编码长度必须为 1~255 字节')
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
                    raise RuntimeError('DHCP OFFER 缺少地址或 server identifier')
                offered, server = ip, options[54]
                message_type, next_send = 3, 0
            elif kind == 5 and message_type == 3:
                if ip != offered or options.get(54) != server:
                    report('dhcpIgnored', reason='ACK 不属于已选择的租约')
                    continue
                mask = options.get(1)
                if mask is None or len(mask) != 4:
                    raise RuntimeError('DHCP ACK 缺少 subnet mask')
                mask_number = int.from_bytes(mask, 'big')
                prefix = bin(mask_number).count('1')
                if mask_number != ((0xffffffff << (32-prefix)) & 0xffffffff):
                    raise RuntimeError('DHCP subnet mask 不连续')
                return ip, prefix, options
            elif kind == 6 and message_type == 3:
                raise RuntimeError('DHCP 服务器拒绝地址请求 NAK')
        raise TimeoutError('DHCP 未在 15 秒内取得 IP 地址')


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
    global log_file, pcap_file, SSID, overall_deadline, handshake_deadline, started
    global supplicant_guard
    started = time.monotonic()
    SSID = args.ssid.encode('utf-8')
    if not 1 <= len(SSID) <= 32:
        raise ValueError('SSID 必须为 1~32 字节')
    if not args.passwords or any(not 8 <= len(p.encode('utf-8')) <= 63 for p in args.passwords):
        raise ValueError('每行 WPA2 密码必须为 8~63 字节')
    encoded_name = args.hostname.encode(args.hostname_encoding)
    if not 1 <= len(encoded_name) <= 255:
        raise ValueError('名称编码后必须为 1~255 字节')
    requested_mac = bytes.fromhex(args.mac.replace(':', '')) if args.mac else None
    if requested_mac is not None and (len(requested_mac) != 6 or requested_mac[0] & 1 or requested_mac == bytes(6)):
        raise ValueError('测试 MAC 必须为有效的六字节单播地址')
    if args.timeout_millis <= 0:
        raise ValueError('总超时必须大于零')
    if args.output:
        os.makedirs(args.output, exist_ok=True)
        os.umask(0o077)
        log_file = open(args.output + '/events.jsonl', 'w', encoding='utf-8')
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
        raise InterruptedError('收到终止信号 %s' % signum)

    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, interrupted)
    try:
        original_flags = flags(args.interface)
        report('begin', interface=args.interface, ssid=args.ssid, passwordCount=len(args.passwords),
               timeoutMillis=args.timeout_millis, hostname=args.hostname, hostnameEncoding=args.hostname_encoding,
               maxHandshakeAttempts=args.max_attempts, handshakeTimeoutMillis=args.handshake_timeout,
               passwordErrorFlag=args.password_error)
        stage('ROUTER_COMMUNICATION', '进入阶段：与路由器建立通信')
        nl = Netlink(args.interface)
        info = nl.command(5, name='read interface', reply=True)[0]
        mode = struct.unpack('=I', scan.first_attr(info, 5))[0]
        if mode != 2:
            raise RuntimeError('接口未处于 managed: iftype=%s' % mode)
        # Pause Android's controller before changing the interface or associating.
        # Its independent recovery process survives termination of this terminal.
        supplicant_guard = SupplicantPauseGuard(args.timeout_millis + 15000, report)
        supplicant_guard.start(ensure_running)
        info = nl.command(5, name='read interface after pausing system supplicant', reply=True)[0]
        mode = struct.unpack('=I', scan.first_attr(info, 5))[0]
        if mode != 2:
            raise RuntimeError('暂停系统控制器后接口已不处于 managed: iftype=%s' % mode)
        own = original_mac = scan.first_attr(info, 6)
        report('interface', mode=mode, mac=own.hex(':'))
        initial_addresses = read_addresses(nl.ifindex)
        report('initialAddresses', addresses=[socket.inet_ntoa(ip)+'/'+str(prefix)
                                              for ip, prefix in initial_addresses])
        events = nl.events_socket()
        associated = any(scan.first_attr(scan.parse_attrs(bss), 9) == struct.pack('=I', 1)
                         for bss in nl.read_results())
        if associated:
            report('disconnectingExistingConnection', message='按配置先断开网卡当前连接，不操作保存配置')
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
                raise RuntimeError('驱动未保留指定 MAC')
        flags(args.interface, original_flags | 1)
        if read_mac(nl) != own:
            raise RuntimeError('接口启用后 MAC 被改变')
        ensure_running()
        report('scanStarted', scope='scan only; association limited to submitted SSID')
        try:
            nl._trigger_scan()
        except OSError as error:
            if error.errno != errno.EBUSY:
                raise
            report('scanBusy', message='等待已有扫描并读取缓存')
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
            raise RuntimeError('目标网络不支持本测试的 WPA2-PSK/CCMP（不含强制 PMF）' if target_seen else '未扫描到目标网络')
        _, bss, target_rsn = max(candidates, key=lambda item: item[0])
        ap = scan.first_attr(bss, 1)
        frequency = struct.unpack('=I', scan.first_attr(bss, 2))[0]
        report('target', bssid=ap.hex(':'), frequency=frequency,
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
                    report('eapolDropped', reason='M1 的 INSTALL/SECURE/ANonce 无效')
                    continue
                if last_m1_replay is not None and replay < last_m1_replay:
                    report('eapolDropped', reason='M1 Replay Counter 倒退', replayCounter=replay)
                    continue
                stage('WPA_HANDSHAKE_1_OF_4', '进入阶段：WPA 握手 1/4')
                if args.handshake_timeout is not None and handshake_deadline is None:
                    handshake_deadline = time.monotonic()+args.handshake_timeout/1000
                if attempts >= len(pmks):
                    raise RuntimeError('密码行已耗尽：收到第 %s 个有效 M1，仅配置 %s 行密码，立即中断' %
                                       (attempts+1, len(pmks)))
                if args.max_attempts is not None and attempts+1 > args.max_attempts:
                    raise RuntimeError('握手超次：%s/%s，立即中断' % (attempts+1, args.max_attempts))
                anonce = key[17:49]
                ptk = derive(pmks[attempts], ap, own, anonce, snonce)
                last_m1_replay = replay
                attempts += 1
                stage('WPA_HANDSHAKE_2_OF_4', '进入阶段：WPA 握手 2/4')
                report('handshakeCount', count=attempts, maximum=args.max_attempts,
                       message='%s/%s' % (attempts, args.max_attempts if args.max_attempts is not None else 'null'))
                send_eapol(eth, ap, own, response(key, 0x010a, snonce, ASSOC_IE, ptk[:16]),
                           'M2', passwordLine=attempts)
                continue
            if message != 'M3' or ptk is None:
                report('eapolDropped', reason='尚无可匹配的 M2 或消息类型不匹配')
                continue
            if key[17:49] != anonce or replay <= last_m1_replay:
                report('eapolDropped', reason='M3 的 ANonce 或 Replay Counter 不匹配')
                continue
            expected = hmac.new(ptk[:16], key[:81]+bytes(16)+key[97:], hashlib.sha1).digest()[:16]
            if not hmac.compare_digest(key[81:97], expected):
                report('eapolDropped', reason='WPA: Invalid EAPOL-Key MIC - dropping packet')
                continue
            stage('WPA_HANDSHAKE_3_OF_4', '进入阶段：WPA 握手 3/4')
            report('m3Verified', passwordLine=attempts, install=bool(info & 0x40), secure=bool(info & 0x200))
            index, gtk = gtk_from_m3(key, ptk)
            if not info & 0x40 or not info & 0x200:
                raise RuntimeError('M3 缺少 INSTALL/SECURE 标志')
            stage('WPA_HANDSHAKE_4_OF_4', '进入阶段：WPA 握手 4/4')
            send_eapol(eth, ap, own, response(key, 0x030a, bytes(32), b'', ptk[:16]), 'M4')
            install_key(nl, 0, ptk[32:48], True, ap, bytes(6))
            install_key(nl, index, gtk, False, ap, key[65:71])
            authorized = struct.pack('=II', 1 << 1, 1 << 1)
            nl.command(18, scan.pack_attr(6, ap)+scan.pack_attr(67, authorized), 'authorize controlled port')
            if not any(scan.first_attr(scan.parse_attrs(entry), 1) == ap and
                       scan.first_attr(scan.parse_attrs(entry), 9) == struct.pack('=I', 1)
                       for entry in nl.read_results()):
                raise RuntimeError('完成密钥安装后，内核没有报告目标关联状态')
            report('handshakeCompletedLocally', associationRetained=True, m3MicVerified=True,
                   ptkInstalled=True, gtkInstalled=True, controlledPortAuthorized=True)
            handshake_deadline = None
            if read_mac(nl) != own:
                raise RuntimeError('握手完成后 MAC 被改变')
            stage('IP_NEGOTIATION', '进入阶段：获取 DHCP 地址')
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
                        report('eapolDropped', reason='密钥安装后 M1 Replay Counter 未递增')
                        return
                    raise HandshakeRestart((retry_key, retry_info, retry_message))
                if retry_message != 'M3':
                    report('eapolDropped', reason='获取 IP 期间收到未匹配的 EAPOL 消息')
                    return
                retry_mic = hmac.new(ptk[:16], retry_key[:81]+bytes(16)+retry_key[97:], hashlib.sha1).digest()[:16]
                if retry_key[17:49] != anonce or retry_replay < accepted_replay or not hmac.compare_digest(retry_key[81:97], retry_mic):
                    report('eapolDropped', reason='DHCP 期间的 M3 重传未通过 ANonce/Replay/MIC 检查')
                    return
                if not retry_info & 0x40 or not retry_info & 0x200 or gtk_from_m3(retry_key, ptk) != (index, gtk):
                    raise RuntimeError('DHCP 期间 M3 密钥内容发生变化')
                # A lost M4 may cause retransmitted M3. Never reinstall keys/reset packet numbers.
                report('m3Retransmission', message='重新发送 M4，保持已经安装的密钥和 packet number')
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
                raise RuntimeError('DHCP 地址未能从接口读回确认')
            if read_mac(nl) != own:
                raise RuntimeError('取得 IP 后发现 MAC 已被其他网卡管理程序修改')
            ensure_running()
            report('completed', ip=socket.inet_ntoa(ip), prefix=prefix,
                   server=socket.inet_ntoa(options[54]), mac=own.hex(':'),
                   hostname=args.hostname, hostnameEncoding=args.hostname_encoding,
                   message='连接测试成功：握手完成且取得 IP，立即断开')
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
                    raise RuntimeError('原 MAC 未能读回确认')
                report('macRestored', mac=original_mac.hex(':'))
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='restore MAC', error=repr(error))
        if original_flags is not None:
            try:
                flags(args.interface, original_flags)
                if nl and original_mac and read_mac(nl) != original_mac:
                    raise RuntimeError('恢复接口 flags 后 MAC 发生变化')
                report('flagsRestored', flags=hex(original_flags))
            except Exception as error:
                cleanup_failed = True
                report('cleanupError', operation='restore interface flags', error=repr(error))
        if nl:
            nl.close()
        if supplicant_guard is not None:
            try:
                if not supplicant_guard.release():
                    raise RuntimeError('未确认系统 wpa_supplicant 恢复，请检查守护进程日志')
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
            report('controlRejected', message='控制命令须为 JSON 对象')
    stop_requested.set()


def main():
    report('ready', protocol=1)
    line = sys.stdin.readline()
    if not line:
        raise InterruptedError('未收到配置 JSON，输入通道已关闭')
    request = json.loads(line)
    if not isinstance(request, dict):
        raise ValueError('配置须为 JSON 对象')
    if request.get('type') == 'stop':
        report('end', success=False, cleanupCompleted=True, message='准备阶段停止')
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
