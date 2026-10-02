"""Read-only interface and capture-process samples for monitor diagnostics."""
import array
import fcntl
import os
import re
import socket
import struct
import subprocess
import termios
import time


def read_text(path):
    with open(path, encoding="utf-8") as source:
        return source.read().strip()


def interface_snapshot(interface):
    result = dict(sampleUnixMillis=int(time.time() * 1000), interface=interface)
    errors = {}
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as control:
            response = fcntl.ioctl(control.fileno(), 0x8913,
                                   struct.pack("256s", interface.encode()))
        flags = struct.unpack_from("H", response, 16)[0]
        result.update(flags=flags, isUp=bool(flags & 1))
    except (OSError, ValueError) as error:
        errors["flags"] = str(error)
    base = "/sys/class/net/" + interface
    for name in ("ifindex", "operstate", "carrier", "statistics/rx_packets",
                 "statistics/rx_bytes", "statistics/rx_dropped", "statistics/rx_errors",
                 "statistics/tx_packets", "statistics/tx_bytes"):
        try:
            value = read_text(base + "/" + name)
            result[name.replace("statistics/", "")] = int(value) if value.isdecimal() else value
        except (OSError, ValueError) as error:
            errors[name] = str(error)
    try:
        rows = read_text("/proc/net/packet").splitlines()
        result["packetSockets"] = [row.split()[1:] for row in rows[1:]
                                   if len(row.split()) >= 9 and
                                   row.split()[4] == str(result.get("ifindex"))]
        result["packetSocketColumns"] = rows[0].split()[1:] if rows else []
    except (OSError, ValueError) as error:
        errors["packetSockets"] = str(error)
    try:
        info = subprocess.run(["iw", "dev", interface, "info"], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=0.5)
        result.update(iwExitCode=info.returncode, iwInfo=info.stdout.strip(),
                      iwStderr=info.stderr.strip())
        channel = re.search(r"channel (\d+) \((\d+(?:\.\d+)?) MHz\)", info.stdout)
        mode = re.search(r"^\s*type (\S+)", info.stdout, re.MULTILINE)
        result.update(channel=int(channel[1]) if channel else None,
                      frequencyMhz=int(float(channel[2])) if channel else None,
                      mode=mode[1] if mode else None)
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        errors["iw"] = str(error)
    result["errors"] = errors
    result["elapsedMillis"] = int(time.time() * 1000) - result["sampleUnixMillis"]
    return result


def process_snapshot(process):
    if process is None:
        return None
    result = dict(pid=process.pid, sampleUnixMillis=int(time.time() * 1000))
    errors = {}
    for name in ("status", "wchan"):
        try:
            value = read_text(f"/proc/{process.pid}/{name}")
            if name == "status":
                value = "\n".join(line for line in value.splitlines()
                                  if line.startswith(("State:", "Threads:")))
            result[name] = value
        except (OSError, ValueError) as error:
            errors[name] = str(error)
    try:
        descriptors = {}
        for name in os.listdir(f"/proc/{process.pid}/fd"):
            try:
                target = os.readlink(f"/proc/{process.pid}/fd/{name}")
                if target.startswith("socket:["):
                    descriptors[name] = target
            except OSError:
                continue
        result["socketDescriptors"] = descriptors
    except OSError as error:
        errors["socketDescriptors"] = str(error)
    try:
        result["stdoutTarget"] = os.readlink(f"/proc/{process.pid}/fd/1")
        available = array.array("i", [0])
        fcntl.ioctl(process.stdout.fileno(), termios.FIONREAD, available, True)
        result["stdoutPendingBytes"] = available[0]
    except (OSError, ValueError) as error:
        errors["stdout"] = str(error)
    result["errors"] = errors
    return result
