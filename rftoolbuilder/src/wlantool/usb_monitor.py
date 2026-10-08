#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Temporary FunctionFS/WinUSB monitor capture, launched through libterminal.

No Wi-Fi mode changes, system policy changes, or container mounts.
Raw transmission is explicit, caller-supplied, and reports kernel acceptance only.
The independent watchdog owns rollback. Service sessions run until stopped;
standalone hardware experiments retain their bounded lease.
"""
import argparse
import base64
import ctypes
import errno
import json
import os
from pathlib import Path
import queue
import re
import select
import signal
import socket
import struct
import subprocess
import threading
import time
import traceback
import uuid

INTERFACE_GUID = "{DAD28F60-390A-4AF2-AEA0-49C32B540B58}"
DEVICE_NAME = "Wifitoolbox Network Driver"
HEADER = struct.Struct("<4sBI")
MAX_PAYLOAD = 65552
PCAP_HEADER = struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 127)


def log(event, **fields):
    try:
        print(json.dumps(dict(event=event, time=time.time(), **fields),
                         ensure_ascii=False), flush=True)
    except OSError:
        # Losing the service terminal must never interrupt rollback.
        pass


def descriptors():
    def speed(packet):
        return (struct.pack("9B", 9, 4, 0, 0, 2, 255, 0, 1, 1)
                + struct.pack("<BBBBHB", 7, 5, 0x81, 2, packet, 0)
                + struct.pack("<BBBBHB", 7, 5, 0x02, 2, packet, 0))
    compat = struct.pack("<BB8s8s6s", 0, 1, b"WINUSB", b"", b"")
    compat = struct.pack("<BIHHBB", 0, 11 + len(compat), 1, 4, 1, 0) + compat
    name = ("DeviceInterfaceGUIDs\0").encode("utf-16le")
    value = (INTERFACE_GUID + "\0\0").encode("utf-16le")
    prop = (struct.pack("<IIH", 14 + len(name) + len(value), 7, len(name))
            + name + struct.pack("<I", len(value)) + value)
    prop = struct.pack("<BIHHH", 0, 11 + len(prop), 1, 5, 1) + prop
    payload = struct.pack("<III", 3, 3, 2) + speed(64) + speed(512) + compat + prop
    return struct.pack("<III", 3, 12 + len(payload), 1 | 2 | 8) + payload


def strings():
    payload = struct.pack("<H", 0x409) + DEVICE_NAME.encode() + b"\0"
    return struct.pack("<IIII", 2, 16 + len(payload), 1, 1) + payload


def frame(kind, payload):
    if len(payload) > MAX_PAYLOAD:
        raise ValueError("USB payload too large")
    data = HEADER.pack(b"WLT1", kind, len(payload)) + payload
    # Always terminate a USB bulk transfer with a short packet, including FS.
    return data + (b"\0" if len(data) % 64 == 0 else b"")


def write_attr(path, value):
    with open(path, "w", encoding="utf-8") as target:
        target.write(str(value).strip() + "\n")


def mount_functionfs(name, path):
    libc = ctypes.CDLL(None, use_errno=True)
    libc.mount.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                           ctypes.c_ulong, ctypes.c_char_p]
    libc.mount.restype = ctypes.c_int
    if libc.mount(name.encode(), os.fsencode(path), b"functionfs", 0, None):
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code), str(path))


def wait_process_exit(pid, timeout):
    """SIGKILL delivery does not synchronously release FunctionFS endpoints."""
    try:
        fd = os.pidfd_open(pid)
    except ProcessLookupError:
        return
    except (AttributeError, OSError):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                state = Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[0]
                if state == "Z":
                    return
            except FileNotFoundError:
                return
            time.sleep(.02)
        raise TimeoutError("Capture process did not release its endpoints")
    else:
        try:
            if not select.select([fd], [], [], timeout)[0]:
                raise TimeoutError("Capture process did not release its endpoints")
        finally:
            os.close(fd)


def radio_state(interface):
    result = subprocess.run(["iw", "dev", interface, "info"], check=True,
                            capture_output=True, text=True, timeout=1)
    mode = re.search(r"^\s*type (\S+)", result.stdout, re.MULTILINE)
    frequency = re.search(r"channel \d+ \((\d+) MHz\)", result.stdout)
    width = re.search(r"width: (\d+) MHz", result.stdout)
    center = re.search(r"center1: (\d+) MHz", result.stdout)
    center2 = re.search(r"center2: (\d+) MHz", result.stdout)
    return dict(mode=mode[1] if mode else None,
                frequencyMhz=int(frequency[1]) if frequency else None,
                widthMhz=int(width[1]) if width else None,
                centerMhz=int(center[1]) if center else None,
                center2Mhz=int(center2[1]) if center2 else None)


class GadgetLease:
    """The parent must never disarm its watchdog before rollback succeeds."""
    ATTRIBUTES = ("idProduct", "bDeviceClass", "bDeviceSubClass", "bDeviceProtocol", "bcdDevice",
                  "os_desc/use", "os_desc/b_vendor_code", "os_desc/qw_sign")

    def __init__(self, gadget, duration, interface, stop_fd=None):
        self.gadget = gadget
        self.interface = interface
        self.duration = duration
        self.stop_fd = stop_fd
        self.token = uuid.uuid4().hex
        # Android FunctionFS limits instance names more tightly than NAME_MAX.
        self.name = "wm" + self.token[:12]
        self.function = gadget / "functions" / ("ffs." + self.name)
        self.mount = Path("/dev/usb-ffs") / self.name
        self.config = gadget / "configs/b.1"
        self.link = self.config / ("wltmon_" + self.token)
        self.os_link = gadget / "os_desc/b.1"
        self.original = {name: (gadget / name).read_text().strip()
                         for name in self.ATTRIBUTES}
        self.product_names = {p / "product": (p / "product").read_text().strip()
                              for p in (gadget / "strings").iterdir()
                              if (p / "product").is_file()}
        self.serial_numbers = {p / "serialnumber": (p / "serialnumber").read_text().strip()
                               for p in (gadget / "strings").iterdir()
                               if (p / "serialnumber").is_file()}
        self.udc = (gadget / "UDC").read_text().strip()
        self.links = {x.name: os.readlink(x) for x in self.config.iterdir() if x.is_symlink()}
        self.had_os_link = self.os_link.is_symlink()
        self.original_usb_config = subprocess.check_output(
            ["/system/bin/getprop", "sys.usb.config"], text=True, timeout=1).strip()
        if not self.udc or radio_state(interface)["mode"] != "monitor":
            raise RuntimeError("Connect USB and enter monitor mode in the app first")
        if self.original_usb_config != "adb":
            raise RuntimeError("Safety check: sys.usb.config must be adb")
        # /proc/1/root is a kernel magic link: Path.resolve() inside chroot
        # cannot safely canonicalize it by re-reading textual symlink targets.
        if len(self.links) != 1 or any(Path(target).name != "ffs.adb"
                                       for target in self.links.values()):
            raise RuntimeError("Safety check: test supports an ADB-only USB configuration")
        self.guard_pid = None
        self.control_fd = None

    def arm(self):
        commands, self.control_fd = os.pipe()
        ready, ready_write = os.pipe()
        owner = os.getpid()
        pid = os.fork()
        if pid == 0:
            os.close(self.control_fd)
            os.close(ready)
            os.setsid()
            signal.signal(signal.SIGHUP, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            # Only standalone experiments have a deadline. Service sessions
            # remain alive through preparation and capture until stopped.
            deadline = time.monotonic() + 20 if self.duration else None
            changed = False
            try:
                os.write(ready_write, b"R")
                os.close(ready_write)
                while True:
                    wait = None if deadline is None else max(0, deadline - time.monotonic())
                    if not select.select([commands], [], [], wait)[0]:
                        break
                    command = os.read(commands, 1)
                    if command == b"A":
                        changed = True
                        deadline = (time.monotonic() + max(0.1, self.duration - 2)
                                    if self.duration else None)
                    elif command in (b"S", b""):
                        break
            finally:
                # Close ALL endpoint holders even if the worker is blocked in a
                # kernel write or its ADB terminal vanished during enumeration.
                try:
                    os.kill(owner, signal.SIGKILL)
                    wait_process_exit(owner, 1)
                except ProcessLookupError:
                    pass
                except Exception as error:
                    log("endpoint_release_error", error=repr(error))
                try:
                    success = self.restore(changed)
                except Exception as error:
                    log("restore_error", error=repr(error))
                    success = False
                log("watchdog_finished", restored=success)
                os._exit(0 if success else 1)
        os.close(commands)
        os.close(ready_write)
        self.guard_pid = pid
        if not select.select([ready], [], [], 2)[0] or os.read(ready, 1) != b"R":
            os.close(ready)
            raise RuntimeError("Watchdog failed to arm; USB untouched")
        os.close(ready)
        log("watchdog_ready", pid=pid, durationSeconds=self.duration)

    def activate(self):
        log("prepare_function", function=str(self.function))
        self.function.mkdir()
        self.mount.mkdir(mode=0o700)
        log("mount_functionfs", mount=str(self.mount))
        mount_functionfs(self.name, self.mount)
        ep0 = os.open(self.mount / "ep0", os.O_RDWR)
        log("write_descriptors", length=len(descriptors()))
        os.write(ep0, descriptors())
        os.write(ep0, strings())
        # Tell the watchdog BEFORE the first externally visible mutation.
        os.write(self.control_fd, b"A")
        # Existing init rules key on exact combinations such as 'adb'. A unique
        # temporary combination keeps their ffs-ready callbacks from overwriting
        # this lease while adbd remains running. The watchdog restores it last.
        subprocess.run(["/system/bin/setprop", "sys.usb.config", "wltmon"],
                       check=True, capture_output=True, timeout=1)
        write_attr(self.gadget / "UDC", "")
        for name in self.links:
            (self.config / name).unlink()
        write_attr(self.gadget / "bDeviceClass", "0")
        write_attr(self.gadget / "bDeviceSubClass", "0")
        write_attr(self.gadget / "bDeviceProtocol", "0")
        for path in self.product_names:
            write_attr(path, DEVICE_NAME)
        # Keep a stable, separate USB persona so Windows does not reuse the
        # phone container's cached friendly name for the capture interface.
        for path, original in self.serial_numbers.items():
            write_attr(path, original + "-WLTMON")
        # Laboratory-only identity: avoid an installed Xiaomi MI_01 INF taking
        # precedence over the generic WINUSB compatible-ID match. Restored below.
        write_attr(self.gadget / "idProduct", "0xfff0")
        write_attr(self.gadget / "bcdDevice", hex((int(self.original["bcdDevice"], 16) + 1) & 65535))
        write_attr(self.gadget / "os_desc/use", "1")
        write_attr(self.gadget / "os_desc/qw_sign", "MSFT100")
        write_attr(self.gadget / "os_desc/b_vendor_code", self.original["os_desc/b_vendor_code"])
        os.symlink(self.function, self.link)
        if not self.had_os_link:
            os.symlink(self.config, self.os_link)
        write_attr(self.gadget / "UDC", self.udc)
        log("usb_bound", udc=self.udc, interfaceGuid=INTERFACE_GUID)
        return ep0

    def restore(self, changed):
        errors = []
        def attempt(label, callback):
            try:
                callback()
            except Exception as error:
                errors.append(label + ": " + str(error))
        if changed:
            def unbind():
                try:
                    write_attr(self.gadget / "UDC", "")
                except OSError as error:
                    if error.errno != errno.ENODEV:
                        raise
            attempt("unbind", unbind)
        if self.link.is_symlink():
            attempt("remove_own_link", self.link.unlink)
        if not self.had_os_link and self.os_link.is_symlink():
            attempt("remove_own_os_link", self.os_link.unlink)
        if changed:
            for name, target in self.links.items():
                if not (self.config / name).is_symlink():
                    attempt("restore_adb_link", lambda n=name, t=target: os.symlink(
                        self.gadget / "functions" / Path(t).name, self.config / n))
            for name, value in self.original.items():
                attempt(name, lambda n=name, v=value: write_attr(self.gadget / n, v))
            for path, value in self.product_names.items():
                attempt("restore_product", lambda p=path, v=value: write_attr(p, v))
            for path, value in self.serial_numbers.items():
                attempt("restore_serial", lambda p=path, v=value: write_attr(p, v))
            attempt("bind_original", lambda: write_attr(self.gadget / "UDC", self.udc))
            attempt("restore_usb_config", lambda: subprocess.run(
                ["/system/bin/setprop", "sys.usb.config", self.original_usb_config],
                check=True, capture_output=True, timeout=1))
        if self.mount.exists():
            def unmount():
                libc = ctypes.CDLL(None, use_errno=True)
                if libc.umount2(os.fsencode(self.mount), 0):
                    code = ctypes.get_errno()
                    if code != errno.EINVAL:
                        raise OSError(code, os.strerror(code))
            attempt("unmount_own_functionfs", unmount)
            attempt("remove_own_mountpoint", self.mount.rmdir)
        if self.function.exists():
            attempt("remove_own_function", self.function.rmdir)
        restored = ((self.gadget / "UDC").read_text().strip() == self.udc
                    and all((self.gadget / key).read_text().strip() == value
                            for key, value in self.original.items())
                    and {x.name: os.readlink(x) for x in self.config.iterdir()
                         if x.is_symlink()} == self.links)
        restored = restored and all(path.read_text().strip() == value
                                    for path, value in self.product_names.items())
        restored = restored and all(path.read_text().strip() == value
                                    for path, value in self.serial_numbers.items())
        restored = restored and subprocess.check_output(
            ["/system/bin/getprop", "sys.usb.config"], text=True, timeout=1).strip() == self.original_usb_config
        log("usb_restored", verified=restored, errors=errors)
        return restored and not errors


class Capture:
    def __init__(self, lease, ep0):
        self.lease = lease
        self.ep0 = ep0
        self.tx = os.open(lease.mount / "ep1", os.O_WRONLY)
        self.rx = os.open(lease.mount / "ep2", os.O_RDONLY)
        self.outgoing = queue.Queue(maxsize=256)
        self.stop = threading.Event()
        self.enabled = threading.Event()
        self.capturing = threading.Event()
        self.receiver = None
        self.count = 0
        self.dropped = 0
        self.pcap_started = False
        self.client_connected = False
        self.last_peer_state = None
        self.inject_pending = None

    def publish_peer(self):
        state = (self.enabled.is_set(), self.client_connected, self.capturing.is_set())
        if state != self.last_peer_state:
            self.last_peer_state = state
            log("peer_state", usbConnected=state[0], clientConnected=state[1], capturing=state[2])

    def disconnected(self):
        self.enabled.clear()
        self.capturing.clear()
        self.client_connected = False
        self.pcap_started = False
        self.inject_pending = None
        while True:
            try:
                self.outgoing.get_nowait()
            except queue.Empty:
                break
        self.publish_peer()

    def send_json(self, close_after=False, **value):
        self.outgoing.put((1, json.dumps(value, separators=(",", ":")).encode(), close_after), timeout=.5)

    def inject(self, packet):
        if not 18 <= len(packet) <= 65535:
            raise ValueError("Expected a complete Radiotap + 802.11 frame (18..65535 bytes)")
        radio_length = struct.unpack_from("<H", packet, 2)[0]
        if packet[0] != 0 or not 8 <= radio_length <= len(packet) - 10:
            raise ValueError("Invalid Radiotap header")
        if radio_state(self.lease.interface)["mode"] != "monitor":
            raise RuntimeError("Interface is no longer in monitor mode")
        # Pass all Radiotap TX parameters and frame bytes to the driver intact.
        with socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3)) as tx:
            tx.bind((self.lease.interface, 0))
            tx.settimeout(.5)
            accepted = tx.send(packet)
        return dict(ok=accepted == len(packet), acceptedBytes=accepted,
                    airTransmissionConfirmed=False,
                    message="Kernel acceptance only; verify over the air separately")

    def command(self, value):
        request_id = value.get("id")
        action = value.get("command")
        try:
            self.client_connected = True
            if action == "status":
                self.send_json(id=request_id, ok=True, protocol=1,
                               interface=self.lease.interface, **radio_state(self.lease.interface),
                               capturing=self.capturing.is_set(), packets=self.count,
                               dropped=self.dropped, maxInjectBytes=65535,
                               maxSessionSeconds=self.lease.duration or None)
            elif action == "set_channel":
                frequency = int(value["frequencyMhz"])
                from monitor_scan import supported_frequencies, set_frequency, ChannelState
                from scan import Nl80211Scanner
                supported, _ = supported_frequencies(self.lease.interface)
                if frequency not in supported:
                    raise ValueError("Unsupported frequency")
                scanner = Nl80211Scanner(self.lease.interface)
                try:
                    set_frequency(scanner, frequency, ChannelState())
                finally:
                    scanner.close()
                self.send_json(id=request_id, ok=True, **radio_state(self.lease.interface))
            elif action == "inject":
                packet = base64.b64decode(value["radiotapBase64"], validate=True)
                self.send_json(id=request_id, **self.inject(packet))
            elif action == "inject_begin":
                length = int(value["length"])
                transfer = value["transferId"]
                if not 18 <= length <= 65535 or not isinstance(transfer, str) or not 1 <= len(transfer) <= 64:
                    raise ValueError("Invalid injection transfer")
                self.inject_pending = (transfer, length, bytearray())
                self.send_json(id=request_id, ok=True)
            elif action in ("inject_chunk", "inject_finish"):
                if self.inject_pending is None or value["transferId"] != self.inject_pending[0]:
                    raise ValueError("Injection transfer not found")
                transfer, length, packet = self.inject_pending
                if action == "inject_chunk":
                    chunk = base64.b64decode(value["dataBase64"], validate=True)
                    if int(value["offset"]) != len(packet) or not 1 <= len(chunk) <= 4096 or len(packet) + len(chunk) > length:
                        raise ValueError("Invalid injection chunk")
                    packet.extend(chunk)
                    self.send_json(id=request_id, ok=True, receivedBytes=len(packet))
                else:
                    self.inject_pending = None
                    if len(packet) != length:
                        raise ValueError("Incomplete injection transfer")
                    self.send_json(id=request_id, **self.inject(bytes(packet)))
            elif action == "start":
                if self.receiver is None:
                    self.receiver = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3))
                    self.receiver.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
                    self.receiver.bind((self.lease.interface, 0))
                    self.receiver.settimeout(.2)
                    try:
                        self.receiver.setsockopt(socket.SOL_SOCKET, 35, 1)  # SO_TIMESTAMPNS
                    except OSError:
                        pass
                if not self.pcap_started:
                    self.outgoing.put((2, PCAP_HEADER, False), timeout=.5)
                    self.pcap_started = True
                self.capturing.set()
                self.send_json(id=request_id, ok=True, capturing=True)
            elif action == "stop":
                self.capturing.clear()
                self.send_json(id=request_id, ok=True, capturing=False)
            elif action == "close":
                self.capturing.clear()
                self.send_json(close_after=True, id=request_id, ok=True, closing=True)
            else:
                raise ValueError("Unknown command")
        except Exception as error:
            if action in ("inject_begin", "inject_chunk", "inject_finish"):
                self.inject_pending = None
            self.send_json(id=request_id, ok=False, error=str(error),
                           errno=error.errno if isinstance(error, OSError) else None)
        finally:
            self.publish_peer()

    def writer(self):
        pending = None
        while not self.stop.is_set():
            try:
                if not self.enabled.wait(.1):
                    continue
                try:
                    kind, payload, close_after = pending or self.outgoing.get(timeout=.1)
                    pending = None
                except queue.Empty:
                    continue
                if kind == 2 and payload != PCAP_HEADER:
                    # Batch only already-queued records: no timer or added
                    # capture latency, and never reorder control replies.
                    records = [payload]
                    length = len(payload)
                    while length < MAX_PAYLOAD:
                        try:
                            item = self.outgoing.get_nowait()
                        except queue.Empty:
                            break
                        if item[0] != 2 or item[1] == PCAP_HEADER or length + len(item[1]) > MAX_PAYLOAD:
                            pending = item
                            break
                        records.append(item[1])
                        length += len(item[1])
                    payload = b"".join(records)
                data = frame(kind, payload)
                if os.write(self.tx, data) != len(data):
                    raise OSError("Short USB bulk write")
                if close_after:
                    self.stop.set()
            except OSError as error:
                if error.errno in (errno.ESHUTDOWN, errno.EPIPE, errno.ECONNRESET):
                    self.disconnected()
                else:
                    log("writer_error", error=str(error))
                    self.stop.set()

    def reader(self):
        pending = bytearray()
        while not self.stop.is_set():
            try:
                if not self.enabled.wait(.1):
                    continue
                data = os.read(self.rx, 4096)
                if not data:
                    self.disconnected()
                    pending.clear()
                    continue
                pending.extend(data)
                if len(pending) > 8192:
                    raise ValueError("Control message exceeds limit")
                while b"\n" in pending:
                    line, _, remainder = pending.partition(b"\n")
                    pending[:] = remainder
                    value = json.loads(line)
                    if not isinstance(value, dict):
                        raise ValueError("Expected JSON object")
                    self.command(value)
            except OSError as error:
                if error.errno in (errno.ESHUTDOWN, errno.EPIPE, errno.ECONNRESET):
                    self.disconnected()
                    pending.clear()
                else:
                    log("reader_error", error=str(error))
                    self.stop.set()
            except (ValueError, queue.Full) as error:
                log("reader_error", error=str(error))
                self.stop.set()

    def packets(self):
        while not self.stop.is_set():
            if self.receiver is None:
                self.stop.wait(.1)
                continue
            try:
                packet, ancillary, flags, _ = self.receiver.recvmsg(65535, 128)
                # Keep draining while paused instead of replaying buffered frames
                # from the pause interval when capture resumes.
                if not self.capturing.is_set():
                    continue
                if flags & socket.MSG_TRUNC or len(packet) < 8:
                    self.dropped += 1
                    continue
                radio_len = struct.unpack_from("<H", packet, 2)[0]
                if packet[0] != 0 or not 8 <= radio_len <= len(packet):
                    continue
                # Preserve the kernel receive timestamp whenever available.
                now = time.time_ns()
                for level, kind, stamp in ancillary:
                    if level == socket.SOL_SOCKET and kind == 35 and len(stamp) >= 16:
                        seconds, nanos = struct.unpack_from("=qq", stamp)
                        now = seconds * 1000000000 + nanos
                payload = struct.pack("<IIII", now // 1000000000,
                                      now % 1000000000 // 1000, len(packet), len(packet)) + packet
                try:
                    self.outgoing.put_nowait((2, payload, False))
                    self.count += 1
                except queue.Full:
                    self.dropped += 1
            except socket.timeout:
                pass
            except OSError as error:
                log("packet_error", error=str(error))
                self.stop.set()

    def run(self):
        self.publish_peer()
        for target in (self.writer, self.reader, self.packets):
            threading.Thread(target=target, daemon=True).start()
        next_mode_check = time.monotonic() + 1
        while not self.stop.is_set():
            watched = [self.ep0]
            if self.lease.stop_fd is not None:
                watched.append(self.lease.stop_fd)
            ready = select.select(watched, [], [], .1)[0]
            if self.lease.stop_fd is not None and self.lease.stop_fd in ready:
                os.read(self.lease.stop_fd, 1)
                log("stop_requested")
                self.stop.set()
                break
            if time.monotonic() >= next_mode_check:
                if radio_state(self.lease.interface)["mode"] != "monitor":
                    raise RuntimeError("wlan0 left monitor mode")
                next_mode_check = time.monotonic() + 1
            if self.ep0 not in ready:
                continue
            events = os.read(self.ep0, 12 * 8)
            for offset in range(0, len(events), 12):
                event = events[offset:offset + 12]
                if len(event) != 12:
                    raise OSError("Truncated FunctionFS event")
                kind = event[8]
                log("functionfs_event", kind=kind)
                if kind == 2:
                    self.enabled.set()
                    self.publish_peer()
                elif kind == 3:
                    self.disconnected()
                elif kind == 1:
                    self.disconnected()
                    # Some Android UDCs unbind/rebind during initial enumeration.
                    # A cable disconnect leaves the task ready for the next host.
                elif kind == 4:
                    # No vendor control requests beyond OS descriptors are used.
                    try:
                        os.read(self.ep0, 0) if event[0] & 0x80 else os.write(self.ep0, b"")
                    except OSError:
                        pass


def run(args):
    if os.geteuid() != 0:
        raise RuntimeError("Root is required")
    gadget = Path(args.gadget)
    if not gadget.is_dir():
        gadget = Path("/proc/1/root/config/usb_gadget/g1")
    lease = GadgetLease(gadget, args.seconds, args.interface, getattr(args, "stop_fd", None))
    lease.arm()
    try:
        ep0 = lease.activate()
        Capture(lease, ep0).run()
    except Exception as error:
        log("session_error", error=repr(error))
        traceback.print_exc()
    finally:
        # The watchdog kills this detached worker and performs the sole rollback.
        os.write(lease.control_fd, b"S")
        while True:
            time.sleep(1)


def supervise(args):
    """Keep the service terminal alive until the independent guard confirms rollback."""
    events, events_out = os.pipe()
    stop_in, stop_out = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(events)
        os.close(stop_out)
        os.setsid()
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
        null = os.open("/dev/null", os.O_RDONLY)
        os.dup2(null, 0)
        os.dup2(events_out, 1)
        os.dup2(events_out, 2)
        os.close(null)
        os.close(events_out)
        args.stop_fd = stop_in
        try:
            run(args)
        except Exception as error:
            log("fatal", error=repr(error))
        finally:
            os._exit(1)
    os.close(events_out)
    os.close(stop_in)
    requested = False
    def request_stop(*unused):
        nonlocal requested, stop_out
        requested = True
        if stop_out is not None:
            os.close(stop_out)
            stop_out = None
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    pending = bytearray()
    restored = False
    try:
        while True:
            watched = [events] if requested else [events, 0]
            ready = select.select(watched, [], [], .2)[0]
            if 0 in ready:
                data = os.read(0, 4096)
                if not data:
                    request_stop()
                elif any(part.strip() for part in data.splitlines()):
                    # The service owns lifecycle; USB host commands use bulk OUT.
                    request_stop()
            if events in ready:
                data = os.read(events, 8192)
                if not data:
                    break
                pending.extend(data)
                while b"\n" in pending:
                    line, _, tail = pending.partition(b"\n")
                    pending[:] = tail
                    print(line.decode("utf-8", errors="replace"), flush=True)
                    try:
                        event = json.loads(line)
                        if event.get("event") == "watchdog_finished":
                            restored = event.get("restored") is True
                    except ValueError:
                        pass
    finally:
        request_stop()
        os.close(events)
        os.waitpid(pid, 0)
    return 0 if restored else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interface", default="wlan0")
    parser.add_argument("--gadget", default="/config/usb_gadget/g1")
    parser.add_argument("--seconds", type=float, help="Standalone experiment duration (3..20 seconds; default 20)")
    parser.add_argument("--service", action="store_true", help="Foreground service-owned session; runs until explicitly stopped")
    parser.add_argument("--log", default="/data/local/tmp/wifitoolbox-usb-monitor.log")
    args = parser.parse_args()
    if args.service:
        if args.seconds not in (None, 0):
            parser.error("service sessions have no time limit; --seconds may only be 0")
        args.seconds = 0
        raise SystemExit(supervise(args))
    args.seconds = 20 if args.seconds is None else args.seconds
    if not 3 <= args.seconds <= 20:
        parser.error("standalone seconds must be 3..20")
    # Android /data is visible through the existing service namespace's procfs.
    log_path = Path(args.log)
    if not log_path.parent.is_dir():
        log_path = Path("/proc/1/root") / str(log_path).lstrip("/")
    output = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    pid = os.fork()
    if pid:
        os.close(output)
        print(json.dumps(dict(pid=pid, log=str(log_path), maxSessionSeconds=20)), flush=True)
        return
    os.setsid()
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    null = os.open("/dev/null", os.O_RDONLY)
    os.dup2(null, 0)
    os.dup2(output, 1)
    os.dup2(output, 2)
    os.close(null)
    os.close(output)
    try:
        run(args)
    except Exception as error:
        log("fatal", error=repr(error))
    finally:
        os._exit(0)


if __name__ == "__main__":
    main()
