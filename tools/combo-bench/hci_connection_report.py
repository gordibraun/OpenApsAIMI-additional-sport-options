#!/usr/bin/env python3
"""Read-only, target-filtered HCI connection metadata. Never prints keys or ACL data."""

import argparse
from collections import Counter, defaultdict, deque
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import re
import struct
import zipfile

BTSNOOP_EPOCH_US = 0x00DC_DDB3_0F2F_8000
STATUS = {
    0x00: "success", 0x04: "page_timeout", 0x05: "authentication_failure",
    0x06: "pin_or_key_missing", 0x08: "connection_timeout",
    0x0C: "command_disallowed", 0x0D: "connection_rejected_limited_resources",
    0x0E: "connection_rejected_security", 0x0F: "connection_rejected_bd_addr",
    0x10: "connection_accept_timeout", 0x13: "remote_user_terminated",
    0x16: "local_host_terminated", 0x18: "pairing_not_allowed",
    0x22: "lmp_response_timeout",
}


def records(stream):
    if stream.read(8) != b"btsnoop\0":
        raise ValueError("Not a btsnoop capture")
    header = stream.read(8)
    if len(header) != 8 or struct.unpack(">II", header) != (1, 1002):
        raise ValueError("Only btsnoop v1 HCI UART (1002) is supported")
    while header := stream.read(24):
        if len(header) != 24:
            raise ValueError("Truncated record header")
        original, included, flags, drops, timestamp = struct.unpack(">IIIIQ", header)
        if included > original or included > 1_048_576:
            raise ValueError("Invalid record length")
        packet = stream.read(included)
        if len(packet) != included:
            raise ValueError("Truncated record payload")
        yield timestamp, packet, drops, flags


def connection_events(stream, address, include_flow_control=False):
    return _connection_events(records(stream), address, include_flow_control)


def rotated_connection_events(captures, address, max_boundary_gap_us=1_000_000, include_flow_control=False):
    """Inspect explicitly ordered adjacent segments; never guess order from filenames.

    A short boundary gap is only a continuity check, not proof of complete history.
    Resets and dropped-record counters still invalidate handle attribution.
    """
    if max_boundary_gap_us < 0:
        raise ValueError("Boundary gap must not be negative")
    segments = []
    seen_names = set()

    def joined_records():
        previous_time = None
        packet_index = 0
        for name, data in captures:
            if name in seen_names:
                raise ValueError("Duplicate capture segment")
            seen_names.add(name)
            start = packet_index
            first_time = None
            for record in records(io.BytesIO(data)):
                timestamp = record[0]
                if first_time is None:
                    if previous_time is not None and timestamp <= previous_time:
                        raise ValueError("Capture segments overlap at boundary")
                    gap = None if previous_time is None else timestamp - previous_time
                    if gap is not None and gap > max_boundary_gap_us:
                        raise ValueError("Capture boundary gap is too large for shared handle attribution")
                    first_time = timestamp
                    segments.append(dict(member=name, firstPacketIndex=start, boundaryGapUs=gap))
                previous_time = timestamp
                packet_index += 1
                yield record
            if first_time is None:
                raise ValueError("Empty capture segment")
            segments[-1].update(lastPacketIndex=packet_index - 1, recordCount=packet_index - start)
        if not segments:
            raise ValueError("At least one capture segment is required")

    result = _connection_events(joined_records(), address, include_flow_control)
    result.update(segments=segments,
                  continuityNote="Explicit ordered segments; short gaps do not prove lossless capture or one boot")
    return result


def _connection_events(record_iter, address, include_flow_control=False):
    if not re.fullmatch(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}", address):
        raise ValueError("A Bluetooth address is required")
    target = bytes.fromhex(address.replace(":", ""))[::-1]
    handles = set()
    outstanding_acl = {}
    pending_commands = defaultdict(deque)
    events = []
    counts = Counter()
    max_drops = 0
    last_drops = 0
    last_timestamp = None

    def event(timestamp, kind, **details):
        capture_time = datetime.fromtimestamp((timestamp - BTSNOOP_EPOCH_US) / 1_000_000,
                                             timezone.utc).isoformat(timespec="milliseconds")
        events.append(dict(captureTime=capture_time, packetIndex=packet_index, kind=kind, **details))

    def status_fields(value):
        return dict(status=f"0x{value:02x}", meaning=STATUS.get(value, "unmapped"))

    def invalidate(timestamp, reason):
        handles.clear()
        outstanding_acl.clear()
        pending_commands.clear()
        event(timestamp, "attribution_reset", reason=reason)

    def retire_handle(handle):
        handles.discard(handle)
        outstanding_acl.pop(handle, None)

    for packet_index, (timestamp, packet, drops, flags) in enumerate(record_iter):
        # Wall-clock corrections do not reorder controller traffic within a file.
        if last_timestamp is not None and timestamp < last_timestamp:
            event(timestamp, "capture_clock_regression", backwardUs=last_timestamp - timestamp)
        last_timestamp = timestamp
        max_drops = max(max_drops, drops)
        if drops != last_drops:
            invalidate(timestamp, "capture_drop_counter_changed")
        last_drops = drops
        if not packet:
            continue
        counts[packet[0]] += 1
        if packet[0] == 1 and len(packet) >= 4:
            opcode, size = struct.unpack_from("<HB", packet, 1)
            payload = packet[4:]
            if len(payload) != size:
                raise ValueError("Truncated HCI command")
            if opcode == 0x0C03:
                invalidate(timestamp, "controller_reset_command")
            # Queue all peers, including non-target commands, so statuses cannot
            # accidentally inherit the most recent target command of this opcode.
            if opcode in (0x0405, 0x080B, 0x0413, 0x0804):
                is_target = (payload[:6] == target if opcode in (0x0405, 0x080B)
                             else len(payload) >= 2 and int.from_bytes(payload[:2], "little") in handles)
                pending_commands[opcode].append((is_target, packet_index))
            if opcode == 0x0405 and len(payload) >= 6:
                if payload[:6] == target:
                    details = {}
                    if len(payload) == 13:
                        details = dict(packetTypes=f"0x{int.from_bytes(payload[6:8], 'little'):04x}",
                                       pageScanRepetitionMode=payload[8], allowRoleSwitch=payload[12],
                                       clockOffsetValid=bool(int.from_bytes(payload[10:12], 'little') & 0x8000))
                    event(timestamp, "create_connection", **details)
            elif opcode in (0x0408, 0x0409, 0x040A, 0x040B, 0x040C, 0x040D, 0x040E, 0x0419, 0x041A) and payload[:6] == target:
                # Deliberately never expose the remainder: it may contain a key or PIN.
                details = dict(role=payload[6]) if opcode == 0x0409 and len(payload) == 7 else {}
                event(timestamp, {0x0408: "cancel_create", 0x0409: "accept_connection",
                                  0x040A: "reject_connection", 0x040B: "link_key_reply",
                                  0x040C: "link_key_negative_reply", 0x040D: "pin_reply",
                                  0x040E: "pin_negative_reply", 0x0419: "remote_name_request",
                                  0x041A: "remote_name_cancel"}[opcode], **details)
            elif opcode == 0x080B and len(payload) == 7 and payload[:6] == target:
                event(timestamp, "switch_role", role=payload[6])
            elif opcode == 0x1408 and len(payload) == 2:
                if int.from_bytes(payload, "little") in handles:
                    event(timestamp, "read_encryption_key_size")
            elif opcode in (0x0406, 0x040F, 0x0411, 0x0413, 0x041B, 0x041C, 0x041D, 0x041F,
                            0x0803, 0x0804, 0x080D) and len(payload) >= 2:
                if int.from_bytes(payload[:2], "little") in handles:
                    # Decode only named, non-secret fields; never dump command payloads.
                    details = {}
                    if opcode == 0x0406 and len(payload) == 3:
                        details = status_fields(payload[2])
                    elif opcode == 0x040F and len(payload) == 4:
                        details = dict(packetTypes=f"0x{int.from_bytes(payload[2:4], 'little'):04x}")
                    elif opcode == 0x0413 and len(payload) == 3:
                        details = dict(enabled=payload[2])
                    elif opcode == 0x080D and len(payload) == 4:
                        policy = int.from_bytes(payload[2:4], "little")
                        details = dict(policy=f"0x{policy:04x}", roleSwitchAllowed=bool(policy & 1),
                                       sniffAllowed=bool(policy & 4))
                    event(timestamp, {0x0406: "disconnect_requested", 0x040F: "change_packet_type",
                                      0x0411: "authentication_requested", 0x0413: "set_encryption",
                                      0x041B: "read_remote_features", 0x041C: "read_remote_extended_features",
                                      0x041D: "read_remote_version", 0x041F: "read_clock_offset", 0x0803: "enter_sniff_mode",
                                      0x0804: "exit_sniff_mode", 0x080D: "write_link_policy"}[opcode], **details)
        elif packet[0] == 4 and len(packet) >= 3:
            code, size = packet[1:3]
            payload = packet[3:]
            if len(payload) != size:
                raise ValueError("Truncated HCI event")
            if code == 3 and len(payload) == 11:
                handle = int.from_bytes(payload[1:3], "little")
                # A successful handle can be reused later for a different peer.
                if payload[0] == 0:
                    retire_handle(handle)
                if payload[3:9] == target:
                    if payload[0] == 0:
                        handles.add(handle)
                        outstanding_acl[handle] = 0
                    details = dict(linkType=payload[9], encryptionEnabled=payload[10]) if payload[0] == 0 else {}
                    event(timestamp, "connection_complete", **status_fields(payload[0]),
                          **details)
            elif code == 0x3E and payload and payload[0] in (0x01, 0x0A):
                expected_size = 19 if payload[0] == 0x01 else 31
                if len(payload) == expected_size and payload[1] == 0:
                    # LE and Classic use the same controller handle namespace.
                    # Do not attribute a reused LE handle to the old Classic peer.
                    retire_handle(int.from_bytes(payload[2:4], "little"))
            elif code == 0x0E and len(payload) >= 4 and int.from_bytes(payload[1:3], "little") == 0x0C03:
                if payload[3] == 0:
                    invalidate(timestamp, "controller_reset_complete")
            elif code == 0x0E and len(payload) == 7 and int.from_bytes(payload[1:3], "little") == 0x1408:
                if int.from_bytes(payload[4:6], "little") in handles:
                    # This command returns a length, never key material. On error
                    # the size field is not evidence of the negotiated key size.
                    details = dict(keySizeOctets=payload[6]) if payload[3] == 0 else {}
                    event(timestamp, "encryption_key_size_complete", **status_fields(payload[3]), **details)
            elif code == 0x10:
                invalidate(timestamp, "controller_hardware_error")
            elif include_flow_control and code == 0x13:
                if not payload or len(payload) != 1 + 4 * payload[0]:
                    raise ValueError("Invalid Number Of Completed Packets event")
                for offset in range(1, len(payload), 4):
                    handle, completed = struct.unpack_from("<HH", payload, offset)
                    if handle not in handles:
                        continue
                    before = outstanding_acl[handle]
                    # Completed can mean transmitted OR flushed, not delivery
                    # to the remote application. Inconsistent counts stay unknown.
                    after = before - completed if before is not None and completed <= before else None
                    outstanding_acl[handle] = after
                    event(timestamp, "acl_completed_packets", completedPackets=completed,
                          outstandingBefore=before, outstandingAfter=after,
                          accountingKnown=after is not None)
            elif include_flow_control and code == 0x1A and len(payload) == 1:
                event(timestamp, "data_buffer_overflow", scope="controller", linkType=payload[0])
            elif code == 4 and len(payload) == 10 and payload[:6] == target:
                event(timestamp, "connection_request")
            elif code in (0x16, 0x17) and payload == target:
                event(timestamp, "pin_request" if code == 0x16 else "link_key_request")
            elif code == 0x18 and len(payload) == 23 and payload[:6] == target:
                event(timestamp, "link_key_notification", keyType=payload[22])
            elif code == 0x07 and len(payload) == 255 and payload[1:7] == target:
                # Do not expose the remote name, even for successful responses.
                event(timestamp, "remote_name_complete", **status_fields(payload[0]))
            elif code == 0x12 and len(payload) == 8 and payload[1:7] == target:
                event(timestamp, "role_change", **status_fields(payload[0]), role=payload[7])
            elif code == 0x14 and len(payload) == 6:
                if int.from_bytes(payload[1:3], "little") in handles:
                    event(timestamp, "mode_change", **status_fields(payload[0]), mode=payload[3])
            elif code in (0x1C, 0x1D, 0x30) and len(payload) == (3 if code == 0x30 else 5):
                if int.from_bytes(payload[1:3], "little") in handles:
                    details = {}
                    if code == 0x1D and payload[0] == 0:
                        details = dict(packetTypes=f"0x{int.from_bytes(payload[3:5], 'little'):04x}")
                    event(timestamp, {0x1C: "clock_offset_complete", 0x1D: "packet_type_changed",
                                      0x30: "encryption_key_refresh_complete"}[code], **status_fields(payload[0]), **details)
            elif code in (0x0B, 0x0C, 0x23) and len(payload) >= 3:
                if int.from_bytes(payload[1:3], "little") in handles:
                    details = {}
                    if payload[0] == 0:
                        if code == 0x0B and len(payload) == 11:
                            details = dict(features=payload[3:11].hex())
                        elif code == 0x0C and len(payload) == 8:
                            details = dict(version=payload[3],
                                           manufacturer=int.from_bytes(payload[4:6], "little"),
                                           subversion=int.from_bytes(payload[6:8], "little"))
                        elif code == 0x23 and len(payload) == 13:
                            details = dict(page=payload[3], maxPage=payload[4], features=payload[5:13].hex())
                    event(timestamp, {0x0B: "remote_features_complete", 0x0C: "remote_version_complete",
                                      0x23: "remote_extended_features_complete"}[code], **status_fields(payload[0]), **details)
            elif code in (5, 6, 8) and len(payload) >= 3:
                handle = int.from_bytes(payload[1:3], "little")
                if handle in handles:
                    if code == 5 and len(payload) == 4:
                        event(timestamp, "disconnected", **status_fields(payload[3]))
                        retire_handle(handle)
                    elif code == 6 and len(payload) == 3:
                        event(timestamp, "authentication_complete", **status_fields(payload[0]))
                    elif code == 8 and len(payload) == 4:
                        event(timestamp, "encryption_change", **status_fields(payload[0]), enabled=payload[3])
            elif code == 0x0F and len(payload) == 4:
                opcode = int.from_bytes(payload[2:4], "little")
                if pending_commands.get(opcode):
                    is_target, command_index = pending_commands[opcode].popleft()
                    if is_target:
                        kind = {0x0405: "create_command_status", 0x080B: "switch_role_command_status",
                                0x0413: "set_encryption_command_status", 0x0804: "exit_sniff_command_status"}[opcode]
                        event(timestamp, kind, commandPacketIndex=command_index, **status_fields(payload[0]))
        elif packet[0] == 2 and len(packet) >= 5:
            handle_flags, size = struct.unpack_from("<HH", packet, 1)
            if len(packet) - 5 != size:
                raise ValueError("Truncated ACL packet")
            handle = handle_flags & 0xFFF
            boundary = (handle_flags >> 12) & 3
            if handle not in handles:
                continue
            direction = "controller_to_host" if flags & 1 else "host_to_controller"
            if include_flow_control:
                if direction == "host_to_controller" and outstanding_acl[handle] is not None:
                    outstanding_acl[handle] += 1
                event(timestamp, "acl_packet", direction=direction, dataOctets=size,
                      outstandingPackets=outstanding_acl[handle])
            # Only complete signalling PDUs. No reassembly or application payload output.
            if len(packet) < 9 or boundary == 1:
                continue
            length, cid = struct.unpack_from("<HH", packet, 5)
            if cid != 1 or length != size - 4:
                continue
            signal = packet[9:]
            while len(signal) >= 4:
                code, identifier, length = struct.unpack_from("<BBH", signal)
                body, signal = signal[4:4 + length], signal[4 + length:]
                if len(body) != length:
                    raise ValueError("Truncated L2CAP signal")
                if code == 2 and length == 4:
                    event(timestamp, "l2cap_connection_request", psm=int.from_bytes(body[:2], "little"),
                          sourceCid=int.from_bytes(body[2:4], "little"), identifier=identifier, direction=direction)
                elif code == 3 and length == 8:
                    destination, source, result, status = struct.unpack("<HHHH", body)
                    details = {}
                    if result == 1:
                        details = dict(pendingStatus=status, pendingMeaning={
                            0: "no_further_information", 1: "authentication_pending",
                            2: "authorization_pending"}.get(status, "reserved"))
                    event(timestamp, "l2cap_connection_response", result=result,
                          destinationCid=destination, sourceCid=source,
                          identifier=identifier, direction=direction, **details)
                elif code in (6, 7) and length == 4:
                    destination, source = struct.unpack("<HH", body)
                    event(timestamp, "l2cap_disconnection_request" if code == 6 else "l2cap_disconnection_response",
                          destinationCid=destination, sourceCid=source,
                          identifier=identifier, direction=direction)
                elif code == 0x0A and length == 2:
                    event(timestamp, "l2cap_information_request", infoType=int.from_bytes(body, "little"),
                          identifier=identifier, direction=direction)
                elif code == 0x0B and length >= 4:
                    info_type, result = struct.unpack_from("<HH", body)
                    details = {}
                    if info_type == 2 and result == 0 and length == 8:
                        details = dict(features=f"0x{int.from_bytes(body[4:8], 'little'):08x}")
                    event(timestamp, "l2cap_information_response", infoType=info_type, result=result,
                          identifier=identifier, direction=direction, **details)
                elif code == 1 and length >= 2:
                    event(timestamp, "l2cap_command_reject", reason=int.from_bytes(body[:2], "little"),
                          identifier=identifier, direction=direction)

    result = dict(events=events, packetTypeCounts=dict(counts), droppedPackets=max_drops,
                  timeNote="Capture clock; vendor offset must be checked against logcat")
    if include_flow_control:
        result["flowControlNote"] = (
            "Target ACL packet accounting only; completed means transmitted or flushed, "
            "not remote application receipt. Does not measure global controller buffer availability."
        )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--address", required=True)
    parser.add_argument("--ordered-member", action="append",
                        help="Explicit adjacent ZIP member, repeat in chronological order; keep handle history")
    parser.add_argument("--flow-control", action="store_true",
                        help="Include target ACL lengths and completed-packet counts, never payloads")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if zipfile.is_zipfile(args.capture):
        with zipfile.ZipFile(args.capture) as archive:
            if args.ordered_member:
                results = {"joined": rotated_connection_events(
                    ((name, archive.read(name)) for name in args.ordered_member), args.address,
                    include_flow_control=args.flow_control)}
            else:
                results = {}
            names = [name for name in archive.namelist()
                     if "btsnoop" in name.lower() and not name.endswith("/") and not args.ordered_member]
            if not names and not args.ordered_member:
                raise ValueError("No btsnoop capture in archive")
            for name in names:
                with archive.open(name) as stream:
                    data = stream.read()
                if data.startswith(b"btsnoop\0"):
                    results[name] = connection_events(io.BytesIO(data), args.address, args.flow_control)
            if not results:
                raise ValueError("No supported btsnoop capture in archive")
    else:
        if args.ordered_member:
            parser.error("--ordered-member requires a ZIP capture")
        with args.capture.open("rb") as stream:
            results = {args.capture.name: connection_events(stream, args.address, args.flow_control)}
    report = json.dumps(results, indent=2)
    if args.output:
        args.output.touch(mode=0o600, exist_ok=True)
        args.output.chmod(0o600)
        args.output.write_text(report, encoding="utf-8")
        print(args.output)
    else:
        print(report)


if __name__ == "__main__":
    main()
