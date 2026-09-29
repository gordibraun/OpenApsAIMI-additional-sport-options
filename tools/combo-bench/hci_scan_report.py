#!/usr/bin/env python3
"""Offline comparison of target Classic connections and controller LE scan settings.

Acknowledged scan settings are not evidence of actual radio airtime or causation.
Never emits keys, advertisement contents, peer names or application payloads.
"""

import argparse
from collections import defaultdict, deque
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import struct
import zipfile

from hci_connection_report import BTSNOOP_EPOCH_US, STATUS, connection_events, records

SCAN_COMMANDS = {
    0x200B: "legacy_scan_parameters", 0x200C: "legacy_scan_enable",
    0x2041: "extended_scan_parameters", 0x2042: "extended_scan_enable",
}
TARGET_KINDS = {
    "create_connection", "create_command_status", "cancel_create",
    "connection_request", "accept_connection", "reject_connection",
    "connection_complete", "disconnected",
}


def scan_parameters(opcode, payload):
    def phy(name, raw):
        scan_type, interval, window = struct.unpack("<BHH", raw)
        return dict(phy=name, scanType=scan_type, intervalMs=interval * 0.625,
                    windowMs=window * 0.625)

    if opcode == 0x200B and len(payload) == 7:
        return dict(phys=[phy("1M", payload[:5])])
    if opcode == 0x2041 and len(payload) >= 3:
        mask = payload[2]
        names = [name for bit, name in ((1, "1M"), (4, "coded")) if mask & bit]
        if mask and (mask & ~5) == 0 and len(payload) == 3 + 5 * len(names):
            return dict(phys=[phy(name, payload[3 + 5 * i:8 + 5 * i])
                              for i, name in enumerate(names)])
    if opcode == 0x200C and len(payload) == 2 and payload[0] in (0, 1):
        return dict(enabled=bool(payload[0]), filterDuplicates=payload[1])
    if opcode == 0x2042 and len(payload) == 6 and payload[0] in (0, 1):
        enabled, duplicates, duration, period = struct.unpack("<BBHH", payload)
        return dict(enabled=bool(enabled), filterDuplicates=duplicates,
                    durationMs=duration * 10, periodMs=period * 1280)
    raise ValueError("Invalid LE scan command parameters")


def scan_events(stream):
    pending = defaultdict(deque)
    events = []
    previous_drops = 0

    def emit(kind, **details):
        capture_time = datetime.fromtimestamp((timestamp - BTSNOOP_EPOCH_US) / 1_000_000,
                                             timezone.utc).isoformat(timespec="milliseconds")
        events.append(dict(captureTime=capture_time, packetIndex=index, kind=kind, **details))

    for index, (timestamp, packet, drops, flags) in enumerate(records(stream)):
        if drops != previous_drops:
            pending.clear()
            emit("capture_loss", droppedPackets=drops)
            previous_drops = drops
        if not packet:
            continue
        if packet[0] == 1 and len(packet) >= 4:
            opcode, length = struct.unpack_from("<HB", packet, 1)
            if len(packet) != 4 + length:
                raise ValueError("Truncated HCI command")
            if opcode in SCAN_COMMANDS:
                details = scan_parameters(opcode, packet[4:])
                pending[opcode].append(dict(requestPacketIndex=index, parameters=details))
                emit(SCAN_COMMANDS[opcode] + "_requested", **details)
        elif packet[0] == 4 and len(packet) >= 3:
            code, length = packet[1:3]
            payload = packet[3:]
            if len(payload) != length:
                raise ValueError("Truncated HCI event")
            if code == 0x0E and len(payload) >= 4:
                opcode = int.from_bytes(payload[1:3], "little")
                status = payload[3]
                if opcode == 0x0C03 and status == 0:
                    pending.clear()
                    emit("controller_reset_complete")
                elif opcode in SCAN_COMMANDS:
                    request = pending[opcode].popleft() if pending[opcode] else {}
                    emit(SCAN_COMMANDS[opcode] + "_complete", status=f"0x{status:02x}",
                         meaning=STATUS.get(status, "unmapped"), matchedCommand=bool(request),
                         applied=bool(request) and status == 0, **request)
            elif code == 0x3E and payload == b"\x11":
                emit("le_scan_timeout")
    return events


def coexistence_report(data, address):
    target = connection_events(io.BytesIO(data), address)
    timeline = [e for e in target["events"] if e["kind"] in TARGET_KINDS]
    timeline += scan_events(io.BytesIO(data))
    timeline.sort(key=lambda e: e["packetIndex"])
    return dict(timeline=timeline, droppedPackets=target["droppedPackets"],
                timeNote=target["timeNote"],
                interpretation="Confirmed settings are not measured airtime. Scan/connection overlap does not prove causation.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--address", required=True)
    parser.add_argument("--member", help="Exact btsnoop member to inspect in a bugreport ZIP")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = {}
    if zipfile.is_zipfile(args.capture):
        with zipfile.ZipFile(args.capture) as archive:
            names = [n for n in archive.namelist()
                     if "btsnoop" in n.lower() and not n.endswith("/")
                     and (args.member is None or n == args.member)]
            for name in names:
                data = archive.read(name)
                if data.startswith(b"btsnoop\0"):
                    results[name] = coexistence_report(data, args.address)
    else:
        if args.member:
            parser.error("--member requires a ZIP capture")
        results[args.capture.name] = coexistence_report(args.capture.read_bytes(), args.address)
    if not results:
        raise ValueError("No matching supported btsnoop capture")
    # Create private before writing; an existing output may have broader permissions.
    args.output.touch(mode=0o600, exist_ok=True)
    args.output.chmod(0o600)
    args.output.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
