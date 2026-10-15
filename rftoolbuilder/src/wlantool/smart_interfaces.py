#!/usr/bin/env python3
"""Add marked managed interfaces until each driver refuses; remove only marked interfaces."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid


def log(event, **fields):
    print(json.dumps(dict(event=event, **fields), ensure_ascii=False), flush=True)


def iw(*args):
    return subprocess.run(['iw', *args], capture_output=True, text=True)


def wireless_interfaces():
    return {entry.name for entry in Path('/sys/class/net').iterdir()
            if (entry / 'phy80211').exists()}


def add(prefix):
    created = 0
    for phy in sorted(Path('/sys/class/ieee80211').iterdir()):
        info = iw('phy', phy.name, 'info')
        if info.returncode:
            log('phy_unavailable', phy=phy.name, error=info.stderr.strip())
            continue
        modes = re.search(r'Supported interface modes:\s*\n((?:\s+\*[^\n]*\n)+)', info.stdout)
        if modes is None or re.search(r'^\s*\* managed\s*$', modes[1], re.MULTILINE) is None:
            continue
        while True:
            name = prefix + uuid.uuid4().hex[:8]
            if (Path('/sys/class/net') / name).exists():
                continue
            result = iw('phy', phy.name, 'interface', 'add', name, 'type', 'managed')
            if result.returncode:
                log('driver_refused', phy=phy.name, code=result.returncode,
                    error=(result.stderr or result.stdout).strip())
                break
            if name not in wireless_interfaces():
                log('interface_missing', phy=phy.name, name=name)
                break
            created += 1
            log('created', phy=phy.name, name=name)
    return created


def remove(mark):
    failed = False
    for name in sorted(wireless_interfaces()):
        if mark.fullmatch(name) is None:
            continue
        result = iw('dev', name, 'del')
        if result.returncode or name in wireless_interfaces():
            failed = True
            log('remove_failed', name=name, code=result.returncode,
                error=(result.stderr or result.stdout).strip())
        else:
            log('removed', name=name)
    return not failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('add', 'remove'))
    parser.add_argument('--prefix', required=True)
    args = parser.parse_args()
    # 8 hex characters plus the marker must fit Linux's 15-byte interface name.
    if re.fullmatch(r'[a-z][a-z0-9_]{0,6}', args.prefix) is None:
        parser.error('prefix must be 1-7 lower-case ASCII letters/digits/underscores')
    mark = re.compile(re.escape(args.prefix) + r'[0-9a-f]{8}')
    if args.action == 'add':
        count = add(args.prefix)
        log('finished', created=count)
        return 0 if count else 1
    return 0 if remove(mark) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        log('error', message=str(error))
        sys.exit(1)
