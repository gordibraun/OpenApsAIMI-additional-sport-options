"""Offline, target-filtered HCI metadata from the S8's btsnooz v2 summary.

Format reference: AOSP system/bt/tools/scripts/btsnooz.py, revision
ea7ab70a711e642653dd5922b83aa04a53af9e0e. Never exports raw packets or keys.
This is a bounded summary, not a lossless HCI capture or an air trace.
"""
import argparse
import base64
import io
from pathlib import Path
import re
import struct
import sys
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "combo-bench"))
from hci_connection_report import BTSNOOP_EPOCH_US, connection_events
from peer_lab import save


def decode_summary(text, address):
    sections = re.findall(r"--- BEGIN:BTSNOOP_LOG_SUMMARY[^\n]*\n(.*?)--- END:BTSNOOP_LOG_SUMMARY", text, re.S)
    if len(sections) != 1:
        raise ValueError("Expected one complete btsnooz section")
    raw = base64.b64decode("".join(sections[0].split()), validate=True)
    if len(raw) < 9:
        raise ValueError("Truncated btsnooz header")
    version, last = struct.unpack_from("<BQ", raw)
    if version != 2:
        raise ValueError("Only the observed btsnooz v2 format is supported")
    body = zlib.decompress(raw[9:])
    rows = []
    offset = 0
    while offset < len(body):
        if offset + 9 > len(body):
            raise ValueError("Truncated record header")
        length, original, delta, kind = struct.unpack_from("<HHIB", body, offset)
        offset += 9
        if not 1 <= length <= original or offset + length - 1 > len(body):
            raise ValueError("Invalid record length")
        rows.append((length, original, delta, kind, body[offset:offset + length - 1]))
        offset += length - 1
    timestamp = last + BTSNOOP_EPOCH_US - sum(row[2] for row in rows)
    output = io.BytesIO(b"btsnoop\0" + struct.pack(">II", 1, 1002))
    output.seek(0, io.SEEK_END)
    skipped = 0
    for length, original, delta, kind, packet in rows:
        timestamp += delta
        if kind not in (0x10, 0x20) or length != original:
            skipped += 1
            # Keep source record indexes without passing partial packets or ACL.
            packet = b""
        else:
            packet = bytes([4 if kind == 0x10 else 1]) + packet
        output.write(struct.pack(">IIIIQ", len(packet), len(packet), int(kind == 0x10), 0, timestamp))
        output.write(packet)
    output.seek(0)
    result = connection_events(output, address)
    result["droppedPackets"] = None
    result.update(summaryRecords=len(rows), skippedRecords=skipped,
                  scope="btsnooz v2: complete HCI commands/events only; no ACL; loss count unknown")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--address", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = decode_summary(args.input.read_text(errors="replace"), args.address)
    result["source"] = str(args.input.resolve())
    save(args.output, result)
    print(args.output)


if __name__ == "__main__":
    main()
