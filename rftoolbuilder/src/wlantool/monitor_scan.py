#!/usr/bin/env python3
"""Three-second monitor scan: management-frame discovery, never associates."""
import argparse
import json
import math
import os
import re
import select
import socket
import struct
import sys
import subprocess
import time

from scapy.layers.dot11 import RadioTap
import scan as managed
from monitor_diagnostics import interface_snapshot


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


def diagnostic(event, **fields):
    try:
        print("[MonitorDiagnostic] " + json.dumps(dict(
            timeUnixMillis=int(time.time() * 1000), pid=os.getpid(),
            source="monitor-scan", event=event, **fields),
            ensure_ascii=False, separators=(",", ":")), file=sys.stderr, flush=True)
    except (OSError, ValueError):
        pass


def supported_channels(interface):
    info = subprocess.check_output(["iw", "dev", interface, "info"], text=True)
    phy = re.search(r"wiphy (\d+)", info)
    if phy is None:
        raise RuntimeError("无法读取无线物理设备")
    output = subprocess.check_output(["iw", "phy", "phy" + phy[1], "info"], text=True)
    channels = []
    for line in output.splitlines():
        match = re.search(r"\* (\d+(?:\.\d+)?) MHz \[(\d+)\]", line)
        if match and "disabled" not in line:
            channels.append({"channel": int(match[2]), "frequencyMhz": int(float(match[1]))})
    if not channels:
        raise RuntimeError("没有可接收的信道")
    # Stay within a band before switching bands to reduce radio settling overhead.
    channel = re.search(r"channel \d+ \((\d+(?:\.\d+)?) MHz\)", info)
    return channels, int(float(channel[1])) if channel else None


def supported_frequencies(interface):
    channels, original = supported_channels(interface)
    return [channel["frequencyMhz"] for channel in channels], original


def decode_result(raw, frequency):
    if len(raw) < 8:
        return None
    radio_length = struct.unpack_from("<H", raw, 2)[0]
    if radio_length < 8 or len(raw) < radio_length + 36:
        return None
    frame = raw[radio_length:]
    if frame[0] & 0xfc not in (0x80, 0x50):
        return None
    bssid = frame[16:22]
    if bssid == bytes(6) or bssid[0] & 1:
        return None
    radio = RadioTap(raw[:radio_length])
    signal = getattr(radio, "dBm_AntSignal", None)
    attrs = managed.pack_attr(managed.NL80211_BSS_BSSID, bssid)
    attrs += managed.pack_attr(managed.NL80211_BSS_INFORMATION_ELEMENTS, frame[36:])
    attrs += managed.pack_attr(managed.NL80211_BSS_CAPABILITY, frame[34:36])
    received_frequency = getattr(radio, "ChannelFrequency", None) or frequency
    # Radiotap describes the receiver channel. Adjacent-channel beacons announce
    # the AP's actual primary channel through DS Parameter Set / HT Operation.
    elements = managed.parse_information_elements(frame[36:])
    primary_channel = next((value[0] for kind, value in elements if kind == 3 and value), None)
    if primary_channel is None:
        primary_channel = next((value[0] for kind, value in elements if kind == 61 and value), None)
    if received_frequency < 5955 and primary_channel is not None:
        if 1 <= primary_channel <= 13:
            received_frequency = 2407 + primary_channel * 5
        elif primary_channel == 14:
            received_frequency = 2484
        elif 36 <= primary_channel <= 177:
            received_frequency = 5000 + primary_channel * 5
    attrs += managed.pack_attr(managed.NL80211_BSS_FREQUENCY, struct.pack("=I", received_frequency))
    if signal is not None:
        attrs += managed.pack_attr(managed.NL80211_BSS_SIGNAL_MBM, struct.pack("=i", int(signal) * 100))
    result = managed.parse_bss(attrs)
    if result is None:
        return None
    if signal is None:
        result["level"] = 0
    return result


class ChannelState:
    """信道控制线程记录最后一次被内核 ACK 确认的频率。"""
    def __init__(self):
        self.frequency_mhz = None
        self.layouts = {}

    def observe(self, result):
        frequency = result["frequency"]
        layout = (result.get("channelWidth", 0), result.get("centerFreq0", frequency), result.get("centerFreq1", 0))
        # Operation IEs describe the occupied bandwidth; capabilities do not.
        previous = self.layouts.get(frequency)
        if previous is None or layout[0] >= previous[0]:
            self.layouts[frequency] = layout


def set_frequency(scanner, frequency, channel_state, capture=False):
    attributes = managed.pack_attr(3, struct.pack("=I", scanner.ifindex))
    attributes += managed.pack_attr(38, struct.pack("=I", frequency))
    width, center0, center1 = channel_state.layouts.get(frequency, (0, frequency, 0)) if capture else (0, frequency, 0)
    # nl80211 channel-width enum differs from Android ScanResult's enum.
    if width == 0:
        attributes += managed.pack_attr(39, struct.pack("=I", 1))
    else:
        attributes += managed.pack_attr(159, struct.pack("=I", {1: 2, 2: 3, 3: 5, 4: 4, 5: 13}[width]))
        attributes += managed.pack_attr(160, struct.pack("=I", center0 or frequency))
        if center1:
            attributes += managed.pack_attr(161, struct.pack("=I", center1))
    sequence = scanner._send(scanner.command_socket, scanner.family_id, 2,
                             managed.NLM_F_REQUEST | managed.NLM_F_ACK, attributes)
    scanner._receive_ack(scanner.command_socket, sequence)
    channel_state.frequency_mhz = frequency
    if capture:
        diagnostic("captureChannelAck", frequencyMhz=frequency, channelWidth=width,
                   centerFreq0=center0, centerFreq1=center1)


def run(interface, duration, channel_state, request_id=None):
    frequencies, reported_frequency = supported_frequencies(interface)
    # 现场频率和最后一次 ACK 仅用于诊断。恢复目标由服务在采集结束后下发。
    diagnostic("scanInterfaceBefore", reportedFrequencyMhz=reported_frequency,
               lastAcknowledgedFrequencyMhz=channel_state.frequency_mhz, requestId=request_id,
               requestedDurationSeconds=duration, interfaceState=interface_snapshot(interface))
    scanner = managed.Nl80211Scanner(interface)
    receiver = None
    found = {}
    received_packets = 0
    last_received_frequency = None
    try:
        receiver = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3))
        receiver.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
        receiver.bind((interface, 0))
        receiver.setblocking(False)
        emit({"action": "start_scan_callback", "requestId": request_id, "ok": True})
        started = time.monotonic()
        deadline = started + duration
        next_update = started
        dwell = duration / len(frequencies)
        visited = 0
        for index, frequency in enumerate(frequencies):
            if time.monotonic() >= deadline:
                break
            try:
                channel_started = time.monotonic()
                set_frequency(scanner, frequency, channel_state)
                diagnostic("scanFrequencyAck", frequencyMhz=frequency,
                           elapsedMillis=int((time.monotonic() - channel_started) * 1000))
            except OSError as error:
                diagnostic("scanFrequencyRejected", frequencyMhz=frequency, error=str(error))
                continue
            visited += 1
            channel_packets = 0
            channel_deadline = min(deadline, started + (index + 1) * dwell)
            while time.monotonic() < channel_deadline:
                remaining = channel_deadline - time.monotonic()
                if remaining <= 0:
                    break
                ready = select.select([receiver], [], [], min(remaining, max(0, next_update - time.monotonic())))[0]
                result = None
                if ready:
                    raw = receiver.recv(65536)
                    received_packets += 1
                    channel_packets += 1
                    last_received_frequency = frequency
                    try:
                        result = decode_result(raw, frequency)
                    except (ValueError, IndexError, struct.error):
                        continue
                if result:
                    channel_state.observe(result)
                    previous = found.get(result["BSSID"])
                    if previous and not result["SSID"]:
                        result["SSID"] = previous["SSID"]
                    found[result["BSSID"]] = result
                now = time.monotonic()
                if now >= next_update:
                    publish(found, True)
                    next_update = now + 0.1
            diagnostic("scanChannelFinished", frequencyMhz=frequency,
                       receivedPackets=channel_packets, networks=len(found))
        if not visited:
            raise RuntimeError("驱动拒绝所有 monitor 扫描信道")
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(remaining)
        publish(found, True)
    finally:
        diagnostic("scanSocketCloseBegin", requestId=request_id,
                   lastReceivedFrequencyMhz=last_received_frequency,
                   receivedPackets=received_packets, networks=len(found),
                   interfaceState=interface_snapshot(interface))
        try:
            if receiver is not None:
                receiver.close()
        finally:
            scanner.close()
            diagnostic("scanSocketCloseEnd", requestId=request_id,
                       interfaceState=interface_snapshot(interface))


def publish(found, scanning):
    emit({"action": "update_wifi_state", "state": {
        "type": "enabled", "scanning": scanning, "wifilist": list(found.values())}})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("-i", "--interface", default="wlan0")
    parser.add_argument("--duration", type=float, default=3.0)
    parser.add_argument("--listen", action="store_true")
    args = parser.parse_args()
    channel_state = ChannelState()

    def execute_scan(request_id=None):
        try:
            run(args.interface, args.duration, channel_state, request_id)
            return 0
        except Exception as error:
            emit({"action": "update_wifi_state", "state": {"type": "error", "message": str(error)}})
            return 1

    if args.listen:
        channels, _ = supported_channels(args.interface)
        frequencies = [channel["frequencyMhz"] for channel in channels]
        emit({"action": "ready", "channels": channels})
        hopping = False
        hop_scanner = None
        hop_index = 0
        hop_dwells = {}
        next_hop = 0.0
        awaiting_resume = False
        input_buffer = b""
        try:
            while True:
                timeout = max(0, next_hop - time.monotonic()) if hopping else None
                if select.select([sys.stdin], [], [], timeout)[0]:
                    data = os.read(sys.stdin.fileno(), 4096)
                    if not data:
                        break
                    input_buffer += data
                    while b"\n" in input_buffer:
                        line, input_buffer = input_buffer.split(b"\n", 1)
                        try:
                            command = json.loads(line)
                        except ValueError:
                            continue
                        action = command.get("action")
                        if action == "scan":
                            request_id = command.get("requestId")
                            if awaiting_resume:
                                emit({"action": "start_scan_callback", "requestId": request_id,
                                      "ok": False, "message": "上次扫描尚未恢复"})
                                continue
                            # 同一个线程执行全部信道操作；保留跳频游标和 tcpdump，
                            # 暂停周期跳频，采集结束后等待服务的最新恢复计划。
                            hopping = False
                            awaiting_resume = True
                            code = execute_scan(request_id)
                            emit({"action": "scan_completed", "requestId": request_id, "code": code})
                        elif action == "resume":
                            scanner = None
                            request_id = command.get("requestId")
                            try:
                                plan = command["capturePlan"]
                                kind = plan["type"]
                                if kind not in ("stopped", "fixed", "hopping"):
                                    raise ValueError("未知恢复计划")
                                diagnostic("resumeBegin", requestId=request_id, capturePlan=plan)
                                hopping = False
                                if kind != "hopping" and hop_scanner is not None:
                                    hop_scanner.close()
                                    hop_scanner = None
                                    hop_index = 0
                                if kind == "fixed":
                                    frequency = plan["frequencyMhz"]
                                    if frequency not in frequencies:
                                        raise ValueError("所选频率不可用")
                                    scanner = managed.Nl80211Scanner(args.interface)
                                    set_frequency(scanner, frequency, channel_state, capture=True)
                                elif kind == "hopping":
                                    # 权重由服务根据上次扫描快照计算；此处只执行时间表。
                                    entries = plan["channelDwells"]
                                    updated_dwells = {entry["frequencyMhz"]: float(entry["dwellMillis"]) / 1000
                                                      for entry in entries}
                                    if (len(entries) != len(frequencies) or set(updated_dwells) != set(frequencies)
                                            or any(not math.isfinite(value) or value < 0.1
                                                   for value in updated_dwells.values())):
                                        raise ValueError("跳频停留时间表无效")
                                    hop_dwells = updated_dwells
                                    if hop_scanner is None:
                                        hop_scanner = managed.Nl80211Scanner(args.interface)
                                    # 第一次跳频成功后才确认恢复，后续继续同一个循环。
                                    frequency = frequencies[hop_index]
                                    set_frequency(hop_scanner, frequency, channel_state, capture=True)
                                    hop_index = (hop_index + 1) % len(frequencies)
                                    next_hop = time.monotonic() + hop_dwells[frequency]
                                    hopping = True
                                awaiting_resume = False
                                diagnostic("resumeAck", requestId=request_id, capturePlan=plan,
                                           lastAcknowledgedFrequencyMhz=channel_state.frequency_mhz,
                                           interfaceState=interface_snapshot(args.interface))
                                emit({"action": "resume_completed", "requestId": request_id, "ok": True})
                            except Exception as error:
                                hopping = False
                                if hop_scanner is not None:
                                    hop_scanner.close()
                                    hop_scanner = None
                                diagnostic("resumeFailed", requestId=request_id,
                                           errorType=type(error).__name__, error=str(error))
                                emit({"action": "resume_completed", "requestId": request_id,
                                      "ok": False, "message": str(error)})
                            finally:
                                if scanner is not None:
                                    scanner.close()
                if hopping and time.monotonic() >= next_hop:
                    try:
                        frequency = frequencies[hop_index]
                        set_frequency(hop_scanner, frequency, channel_state, capture=True)
                        hop_index = (hop_index + 1) % len(frequencies)
                        next_hop = time.monotonic() + hop_dwells[frequency]
                    except Exception as error:
                        hopping = False
                        hop_scanner.close()
                        hop_scanner = None
                        emit({"action": "hopping_failed", "message": str(error)})
        finally:
            if hop_scanner is not None:
                hop_scanner.close()
    else:
        raise SystemExit(execute_scan())
