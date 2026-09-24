#!/usr/bin/env python3
"""Audit every ELF in an APK or explicit .so paths; no dependencies needed.

Exit 1 if any LOAD segment fails 16 KiB alignment/congruence (including ARM32),
or any uncompressed APK .so is not ZIP-aligned. Compressed entries are extracted
by Android; their ZIP data offsets do not constrain mmap alignment. This is a
static packaging check, not proof of device/runtime compatibility.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def audit_elf(data, name):
    if data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError(f"Not a supported ELF: {name}")
    endian = "<" if data[5] == 1 else ">"
    is64 = data[4] == 2
    offset = struct.unpack_from(endian + ("Q" if is64 else "I"), data, 32 if is64 else 28)[0]
    size, count = struct.unpack_from(endian + "HH", data, 54 if is64 else 42)
    segments = []
    for index in range(count):
        fields = struct.unpack_from(endian + ("IIQQQQQQ" if is64 else "IIIIIIII"), data, offset + index * size)
        if fields[0] != 1:
            continue
        file_offset, vaddr, align = (fields[2], fields[3], fields[7]) if is64 else (fields[1], fields[2], fields[7])
        segments.append({
            "offset": file_offset, "vaddr": vaddr, "align": align,
            "congruent_16k": (vaddr - file_offset) % 16384 == 0,
            "ok": align >= 16384 and (vaddr - file_offset) % 16384 == 0,
        })
    return {
        "name": name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
        "machine": struct.unpack_from(endian + "H", data, 18)[0],
        "elf_class": 64 if is64 else 32, "segments": segments,
        "elf_ok": bool(segments) and all(segment["ok"] for segment in segments),
    }


def audit_path(path):
    if path.suffix.lower() != ".apk":
        return [audit_elf(path.read_bytes(), str(path))]
    rows = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            row = audit_elf(archive.read(info), info.filename)
            raw.seek(info.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", raw.read(4))
            data_offset = info.header_offset + 30 + name_size + extra_size
            row.update({
                "zip_method": info.compress_type, "zip_offset": data_offset,
                "zip_alignment_required": info.compress_type == zipfile.ZIP_STORED,
                "zip_ok": info.compress_type != zipfile.ZIP_STORED or data_offset % 16384 == 0,
            })
            rows.append(row)
    if not rows:
        raise ValueError(f"No .so entries in {path}")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rows = [row for path in args.paths for row in audit_path(path)]
    result = {
        "inputs": [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in args.paths],
        "libraries": rows,
        "all_elf_and_zip_ok": all(row["elf_ok"] and row.get("zip_ok", True) for row in rows),
    }
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text)
    return 0 if result["all_elf_and_zip_ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
