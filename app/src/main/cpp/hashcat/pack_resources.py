#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Package only freshly compiled Android modules and upstream runtime data.

This runs at build time. The source tree contains no generated ELF/archive.
Archive ordering, ownership, modes and timestamps are fixed for reproducibility.
"""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile


def opencl_dependencies(source):
    pending = ["m22000-pure.cl", "m22001-pure.cl", "shared.cl", "amp_a0.cl"]
    files = {}
    while pending:
        name = pending.pop()
        relative = f"OpenCL/{name}"
        if relative in files:
            continue
        path = source / relative
        if not path.is_file():
            raise RuntimeError(f"Missing WPA OpenCL dependency: {relative}")
        files[relative] = path
        content = path.read_text(encoding="utf-8")
        # Include both static GPU and native/header branches, along with macro
        # include operands (COMPARE_M/S). Vendor branches stay in the source.
        pending.extend(re.findall(r'INCLUDE_PATH/([A-Za-z0-9_]+\.(?:cl|h))', content))
        pending.extend(re.findall(r'^\s*#\s*include\s+"([A-Za-z0-9_]+\.(?:cl|h))"', content, re.M))
    return files


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("strip", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    files = opencl_dependencies(args.source)
    # Keep every device alias and tuning entry for portable auto-tuning.
    for folder in ("tunings",):
        for path in sorted((args.source / folder).rglob("*")):
            if path.is_file():
                files[path.relative_to(args.source).as_posix()] = path
    for relative in ("modules/module_22000.so", "modules/module_22001.so", "feeds/feed_wordlist.so"):
        path = args.source / relative
        # ELF e_machine == EM_AARCH64. Reject accidental host libraries.
        elf = path.read_bytes()[:20]
        if elf[:5] != b"\x7fELF\x02" or elf[18:20] != b"\xb7\x00":
            raise RuntimeError(f"Not an Android ARM64 module: {path}")
        files[relative] = path
    for relative in ("docs/license.txt", "docs/credits.txt"):
        files[relative] = args.source / relative
    for path in sorted((args.source / "docs/license_libs").rglob("*")):
        if path.is_file():
            files[path.relative_to(args.source).as_posix()] = path

    # Retain the selected plugins' core ABI without rooting unused algorithms
    # through the core's default exported symbol table.
    exports = {"wlantool_hashcat_*", "Java_io_github_bszapp_wifitoolbox_hashcat_*", "hashcat_android_start"}
    nm = args.strip.with_name("llvm-nm")
    core_symbols = subprocess.check_output(
        [str(nm), "--defined-only", "--format=posix", str(args.source / "obj/combined.NATIVE.a")], text=True)
    defined = {line.split()[0] for line in core_symbols.splitlines() if line.strip()}
    for name, path in files.items():
        if not name.endswith(".so"):
            continue
        symbols = subprocess.check_output(
            [str(nm), "-D", "--undefined-only", "--format=posix", str(path)], text=True)
        for line in symbols.splitlines():
            symbol = line.split()[0].split("@")[0]
            if symbol in defined and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", symbol):
                exports.add(symbol)
    (args.output / "exports.map").write_text(
        "{\n  global:\n" + "".join(f"    {symbol};\n" for symbol in sorted(exports)) +
        "  local: *;\n};\n", encoding="utf-8")

    archive = args.output / "resources.tar.gz"
    with archive.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0, compresslevel=9) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.USTAR_FORMAT) as tar:
                directories = set()
                for name in files:
                    directories.update(p.as_posix() for p in Path(name).parents if p.as_posix() != ".")
                for name in sorted(directories):
                    entry = tarfile.TarInfo(name)
                    entry.type, entry.mode = tarfile.DIRTYPE, 0o700
                    tar.addfile(entry)
                for name, path in sorted(files.items()):
                    if name.endswith(".so"):
                        # Keep the dynamic symbol table needed by the plugin ABI.
                        with tempfile.TemporaryDirectory(dir=args.output) as scratch:
                            stripped = Path(scratch) / path.name
                            subprocess.run([str(args.strip), "--strip-unneeded", "-o", str(stripped), str(path)], check=True)
                            content = stripped.read_bytes()
                    else:
                        content = path.read_bytes()
                    entry = tarfile.TarInfo(name)
                    entry.size = len(content)
                    entry.mode = 0o700 if name.endswith(".so") else 0o600
                    tar.addfile(entry, io.BytesIO(content))
    identity = hashlib.sha256(archive.read_bytes()).hexdigest()
    (args.output / "resources_identity.h").write_text(
        f'/* Generated from source-built resources. */\n#define HASHCAT_RESOURCE_ID "{identity}"\n', encoding="utf-8")
    (args.output / "resources.S").write_text(
        '.section .rodata.hashcat_resources,"a",%progbits\n'
        '.balign 16\n.global hashcat_resources_start\n.hidden hashcat_resources_start\n'
        'hashcat_resources_start:\n'
        f'.incbin "{archive.as_posix()}"\n'
        '.global hashcat_resources_end\n.hidden hashcat_resources_end\n'
        'hashcat_resources_end:\n.section .note.GNU-stack,"",%progbits\n', encoding="utf-8")
    print(f"Embedded resources: {len(files)} files, {archive.stat().st_size} bytes, sha256={identity}")


if __name__ == "__main__":
    main()
