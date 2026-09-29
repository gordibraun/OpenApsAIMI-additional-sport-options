import io
import json
import struct
import unittest

from hci_connection_report import BTSNOOP_EPOCH_US, connection_events, rotated_connection_events

ADDRESS = "00:01:02:03:04:05"
TARGET = bytes.fromhex("050403020100")


def capture(*packets, flags=None, drops=None, start_us=0):
    data = b"btsnoop\0" + struct.pack(">II", 1, 1002)
    for i, packet in enumerate(packets):
        data += struct.pack(">IIIIQ", len(packet), len(packet), flags[i] if flags else 0,
                            drops[i] if drops else 0,
                            BTSNOOP_EPOCH_US + start_us + i * 1_000_000) + packet
    return io.BytesIO(data)


def hci_event(code, payload):
    return bytes([4, code, len(payload)]) + payload


def hci_command(opcode, payload):
    return b"\x01" + struct.pack("<HB", opcode, len(payload)) + payload


def acl_packet(payload=b"\x00", handle_flags=0x1023):
    return b"\x02" + struct.pack("<HH", handle_flags, len(payload)) + payload


def completed_packets(*entries):
    return hci_event(0x13, bytes([len(entries)]) + b"".join(struct.pack("<HH", *e) for e in entries))


class HciConnectionReportTest(unittest.TestCase):
    def test_key_size_reports_metadata_without_key_material(self):
        secret = b"SECRET_KEY_BYTES!"
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            hci_command(0x040B, TARGET + secret),
            hci_command(0x1408, b"\x23\x00"),
            hci_event(0x0E, b"\x01\x08\x14\x00\x23\x00\x10")), ADDRESS)
        self.assertEqual(result["events"][-2]["kind"], "read_encryption_key_size")
        last = result["events"][-1]
        self.assertEqual(last["kind"], "encryption_key_size_complete")
        self.assertEqual(last["status"], "0x00")
        self.assertEqual(last["keySizeOctets"], 16)
        self.assertNotIn(secret.decode(), json.dumps(result))
        self.assertNotIn(secret.hex(), json.dumps(result))

    def test_failed_key_size_read_does_not_report_undefined_size(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            hci_event(0x0E, b"\x01\x08\x14\x0c\x23\x00\x10")), ADDRESS)
        self.assertEqual(result["events"][-1]["meaning"], "command_disallowed")
        self.assertNotIn("keySizeOctets", result["events"][-1])

    def test_key_size_ignores_unknown_and_other_peer_handles(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x24\x00" + b"\x99" * 6 + b"\x01\x00"),
            hci_command(0x1408, b"\x24\x00"),
            hci_event(0x0E, b"\x01\x08\x14\x00\x24\x00\x10")), ADDRESS)
        self.assertEqual(result["events"], [])

    def test_key_size_attribution_does_not_survive_disconnect_reset_reuse_or_loss(self):
        for closing, drops in (
            (hci_event(5, b"\x00\x23\x00\x16"), [0, 0, 0, 0]),
            (hci_command(0x0C03, b""), [0, 0, 0, 0]),
            (hci_event(3, b"\x00\x23\x00" + b"\x99" * 6 + b"\x01\x00"), [0, 0, 0, 0]),
            (hci_event(0x3E, b"\x01\x00\x23\x00" + b"\x00" * 15), [0, 0, 0, 0]),
            (hci_event(0x01, b"\x00"), [0, 1, 1, 1]),
        ):
            with self.subTest(closing=closing.hex(), drops=drops):
                result = connection_events(capture(
                    hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"), closing,
                    hci_command(0x1408, b"\x23\x00"),
                    hci_event(0x0E, b"\x01\x08\x14\x00\x23\x00\x10"), drops=drops), ADDRESS)
                self.assertFalse(any(e["kind"] in ("read_encryption_key_size", "encryption_key_size_complete")
                                     for e in result["events"]))

    def test_key_size_keeps_target_across_capture_rotation(self):
        first = capture(hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00")).getvalue()
        second = capture(hci_event(0x0E, b"\x01\x08\x14\x00\x23\x00\x10"), start_us=100).getvalue()
        result = rotated_connection_events([("old", first), ("current", second)], ADDRESS)
        self.assertEqual(result["events"][-1]["keySizeOctets"], 16)
        self.assertEqual(result["events"][-1]["packetIndex"], 1)

    def test_key_size_ignores_unexpected_parameter_lengths(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            hci_command(0x1408, b"\x23\x00\x10"),
            hci_event(0x0E, b"\x01\x08\x14\x00\x23\x00"),
            hci_event(0x0E, b"\x01\x08\x14\x00\x23\x00\x10\xff")), ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]], ["connection_complete"])

    def test_flow_counts_tx_and_rx_without_exposing_payload(self):
        packets = [hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
                   acl_packet(b"PRIVATE_APPLICATION_DATA"), acl_packet(b"PRIVATE_RESPONSE"),
                   completed_packets((0x23, 1))]
        result = connection_events(capture(*packets, flags=[3, 0, 1, 3]), ADDRESS, include_flow_control=True)
        self.assertEqual(result["events"][1]["direction"], "host_to_controller")
        self.assertEqual(result["events"][1]["outstandingPackets"], 1)
        self.assertEqual(result["events"][2]["direction"], "controller_to_host")
        self.assertEqual(result["events"][2]["outstandingPackets"], 1)
        self.assertEqual(result["events"][3]["outstandingAfter"], 0)
        self.assertTrue(result["events"][3]["accountingKnown"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn(b"PRIVATE".hex(), json.dumps(result))

    def test_flow_metadata_is_opt_in(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            acl_packet(), completed_packets((0x23, 1)), hci_event(0x1A, b"\x01")), ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]], ["connection_complete"])
        self.assertNotIn("flowControlNote", result)

    def test_completed_packet_entries_are_interleaved_and_target_filtered(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            acl_packet(), acl_packet(),
            completed_packets((0x24, 10), (0x23, 2))), ADDRESS, include_flow_control=True)
        last = result["events"][-1]
        self.assertEqual(last["kind"], "acl_completed_packets")
        self.assertEqual(last["completedPackets"], 2)
        self.assertEqual(last["outstandingBefore"], 2)
        self.assertEqual(last["outstandingAfter"], 0)

    def test_excess_completions_make_count_unknown_until_next_connection(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            completed_packets((0x23, 1)), acl_packet(), completed_packets((0x23, 1)),
            hci_event(5, b"\x00\x23\x00\x16"),
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            acl_packet()), ADDRESS, include_flow_control=True)
        self.assertFalse(result["events"][1]["accountingKnown"])
        self.assertIsNone(result["events"][2]["outstandingPackets"])
        self.assertIsNone(result["events"][3]["outstandingAfter"])
        self.assertEqual(result["events"][-1]["outstandingPackets"], 1)

    def test_flow_counters_do_not_survive_disconnect_or_other_peer_reuse(self):
        for closing in (hci_event(5, b"\x00\x23\x00\x16"),
                        hci_event(3, b"\x00\x23\x00" + b"\x99" * 6 + b"\x01\x00"),
                        hci_event(0x3E, b"\x01\x00\x23\x00" + b"\x00" * 15)):
            result = connection_events(capture(
                hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
                acl_packet(), closing, completed_packets((0x23, 1))), ADDRESS, include_flow_control=True)
            self.assertNotIn("acl_completed_packets", [e["kind"] for e in result["events"]])

    def test_flow_counters_are_invalidated_by_capture_loss(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            acl_packet(), completed_packets((0x23, 1)), drops=[0, 0, 1]), ADDRESS, include_flow_control=True)
        self.assertNotIn("acl_completed_packets", [e["kind"] for e in result["events"]])

    def test_malformed_completed_packets_is_rejected(self):
        for payload in (b"", b"\x01", b"\x00\x23\x00\x01\x00"):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                connection_events(capture(hci_event(0x13, payload)), ADDRESS, include_flow_control=True)

    def test_flow_counts_cross_explicit_rotation_and_include_short_fragments(self):
        first = capture(hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"), acl_packet()).getvalue()
        second = capture(completed_packets((0x23, 1)), start_us=1_000_001).getvalue()
        result = rotated_connection_events([("old", first), ("current", second)], ADDRESS, include_flow_control=True)
        self.assertEqual(result["events"][-1]["outstandingAfter"], 0)
        self.assertEqual(result["events"][-1]["packetIndex"], 2)

    def test_overflow_is_explicitly_controller_wide(self):
        event = connection_events(capture(hci_event(0x1A, b"\x01")), ADDRESS,
                                  include_flow_control=True)["events"][0]
        self.assertEqual(event["kind"], "data_buffer_overflow")
        self.assertEqual(event["scope"], "controller")

    def test_controller_rejection_labels_match_core_specification(self):
        labels = {0x0C: "command_disallowed", 0x0D: "connection_rejected_limited_resources",
                  0x0E: "connection_rejected_security", 0x0F: "connection_rejected_bd_addr"}
        for status, meaning in labels.items():
            with self.subTest(status=status):
                event = connection_events(capture(hci_event(3, bytes([status, 0, 0]) + TARGET + b"\x01\x00")),
                                          ADDRESS)["events"][0]
                self.assertEqual(event["meaning"], meaning)

    def test_rotated_capture_preserves_existing_acl_and_provenance(self):
        first = capture(hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00")).getvalue()
        second = capture(hci_command(0x0804, b"\x23\x00"), start_us=10_000).getvalue()
        self.assertEqual(connection_events(io.BytesIO(second), ADDRESS)["events"], [])
        result = rotated_connection_events([("old", first), ("current", second)], ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]], ["connection_complete", "exit_sniff_mode"])
        self.assertEqual(result["events"][1]["packetIndex"], 1)
        self.assertEqual(result["segments"][1], dict(member="current", firstPacketIndex=1,
                                                    lastPacketIndex=1, recordCount=1, boundaryGapUs=10_000))

    def test_rotated_capture_rejects_duplicates_overlap_disorder_and_gaps(self):
        first = capture(hci_event(0x17, TARGET), start_us=1_000_000).getvalue()
        for name, start in (("a", 1_100_000), ("b", 0), ("b", 1_000_000), ("b", 2_000_001)):
            with self.subTest(name=name, start=start), self.assertRaises(ValueError):
                rotated_connection_events([("a", first), (name, capture(hci_event(0x17, TARGET),
                                                                       start_us=start).getvalue())], ADDRESS)

    def test_rotated_capture_rejects_empty_and_bad_header(self):
        for segments in ([], [("a", capture().getvalue())], [("a", b"invalid")]):
            with self.subTest(segments=len(segments)), self.assertRaises(ValueError):
                rotated_connection_events(segments, ADDRESS)

    def test_internal_clock_regression_is_reported_without_reordering_traffic(self):
        first = capture(hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"), start_us=1_000_000)
        second = capture(hci_command(0x0804, b"\x23\x00"), start_us=900_000)
        result = rotated_connection_events([("current", first.getvalue() + second.getvalue()[16:])], ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]],
                         ["connection_complete", "capture_clock_regression", "exit_sniff_mode"])
        self.assertEqual(result["events"][1]["backwardUs"], 100_000)
        self.assertEqual(result["events"][2]["packetIndex"], 1)

    def test_reset_command_completion_and_hardware_error_invalidate_handles(self):
        for reset in (hci_command(0x0C03, b""), hci_event(0x0E, b"\x01\x03\x0c\x00"),
                      hci_event(0x10, b"\x01")):
            with self.subTest(reset=reset.hex()):
                result = connection_events(capture(
                    hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
                    reset, hci_command(0x0413, b"\x23\x00\x00")), ADDRESS)
                self.assertEqual([e["kind"] for e in result["events"]],
                                 ["connection_complete", "attribution_reset"])

    def test_drop_counter_increase_or_restart_invalidates_handles(self):
        for drops in ([0, 1], [10, 0]):
            with self.subTest(drops=drops):
                result = connection_events(capture(
                    hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
                    hci_command(0x0413, b"\x23\x00\x00"), drops=drops), ADDRESS)
                self.assertNotIn("set_encryption", [e["kind"] for e in result["events"]])
                self.assertEqual(result["events"][-1]["reason"], "capture_drop_counter_changed")

    def test_le_handle_reuse_retires_classic_attribution(self):
        for subevent, size in ((0x01, 19), (0x0A, 31)):
            for status in (0, 1):
                with self.subTest(subevent=subevent, status=status):
                    le = bytes([subevent, status, 0x23, 0]) + b"\x00" * (size - 4)
                    result = connection_events(capture(
                        hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
                        hci_event(0x3E, le), hci_command(0x0804, b"\x23\x00")), ADDRESS)
                    self.assertEqual(len(result["events"]), 1 if status == 0 else 2)

    def test_role_status_matches_command_not_completion(self):
        result = connection_events(capture(
            hci_command(0x080B, TARGET + b"\x00"),
            hci_event(0x0F, b"\x00\x01\x0b\x08")), ADDRESS)
        self.assertEqual(result["events"][1]["kind"], "switch_role_command_status")
        self.assertEqual(result["events"][1]["commandPacketIndex"], 0)
        self.assertEqual(result["events"][1]["meaning"], "success")
        self.assertNotIn("role_change", [e["kind"] for e in result["events"]])

    def test_command_status_queue_keeps_other_peer_positions(self):
        result = connection_events(capture(
            hci_command(0x080B, b"\x99" * 6 + b"\x00"),
            hci_command(0x080B, TARGET + b"\x00"),
            hci_event(0x0F, b"\x00\x01\x0b\x08"),
            hci_event(0x0F, b"\x0c\x01\x0b\x08"),
            hci_event(0x0F, b"\x00\x01\x0b\x08")), ADDRESS)
        self.assertEqual(len(result["events"]), 2)
        self.assertEqual(result["events"][-1]["status"], "0x0c")
        self.assertEqual(result["events"][-1]["commandPacketIndex"], 1)

    def test_handle_commands_have_separate_status_queues(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            hci_command(0x0413, b"\x23\x00\x00"),
            hci_command(0x0804, b"\x23\x00"),
            hci_command(0x0413, b"\x24\x00\x00"),
            hci_event(0x0F, b"\x00\x01\x04\x08"),
            hci_event(0x0F, b"\x00\x01\x13\x04"),
            hci_event(0x0F, b"\x0c\x01\x13\x04")), ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]][-2:],
                         ["exit_sniff_command_status", "set_encryption_command_status"])
        self.assertEqual(result["events"][-2]["commandPacketIndex"], 2)
        self.assertEqual(result["events"][-1]["commandPacketIndex"], 1)

    def test_create_status_queue_keeps_interleaved_peer_positions(self):
        suffix = b"\x18\xcc\x01\x00\x00\x00\x01"
        result = connection_events(capture(
            hci_command(0x0405, TARGET + suffix), hci_command(0x0405, b"\x99" * 6 + suffix),
            hci_event(0x0F, b"\x00\x01\x05\x04"), hci_event(0x0F, b"\x0c\x01\x05\x04")), ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]], ["create_connection", "create_command_status"])
        self.assertEqual(result["events"][-1]["status"], "0x00")

    def test_reset_or_capture_loss_clears_pending_command_status(self):
        for reset, drops in ((hci_command(0x0C03, b""), [0, 0, 0]),
                             (hci_event(0x01, b"\x00"), [0, 1, 1])):
            result = connection_events(capture(
                hci_command(0x080B, TARGET + b"\x00"), reset,
                hci_event(0x0F, b"\x00\x01\x0b\x08"), drops=drops), ADDRESS)
            self.assertNotIn("switch_role_command_status", [e["kind"] for e in result["events"]])

    def test_bond_metadata_excludes_names_keys_and_other_devices(self):
        private_key = b"SECRET_KEY_BYTES"
        private_key = private_key.ljust(16, b"!")
        private_name = b"PRIVATE_REMOTE_NAME".ljust(248, b"\x00")
        result = connection_events(capture(
            hci_command(0x0409, TARGET + b"\x01"),
            hci_command(0x0419, TARGET + b"\x01\x00\x00\x00"),
            hci_event(0x07, b"\x00" + TARGET + private_name),
            hci_event(0x18, TARGET + private_key + b"\x00"),
            hci_event(0x18, b"\x99" * 6 + private_key + b"\x00"),
        ), ADDRESS)
        self.assertEqual([e["kind"] for e in result["events"]],
                         ["accept_connection", "remote_name_request", "remote_name_complete", "link_key_notification"])
        self.assertEqual(result["events"][0]["role"], 1)
        self.assertNotIn("SECRET", json.dumps(result))
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_packet_type_completion_and_clock_failure_only_for_target(self):
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            hci_command(0x041F, b"\x23\x00"),
            hci_event(0x1C, b"\x22\x23\x00\xFF\xFF"),
            hci_event(0x1D, b"\x00\x23\x00\x18\xCC"),
            hci_event(0x1D, b"\x22\x23\x00\xFF\xFF"),
            hci_event(0x1D, b"\x00\x24\x00\x18\xCC"),
        ), ADDRESS)
        self.assertEqual(len(result["events"]), 5)
        self.assertEqual(result["events"][2]["meaning"], "lmp_response_timeout")
        self.assertEqual(result["events"][3]["packetTypes"], "0xcc18")
        self.assertNotIn("packetTypes", result["events"][4])

    def test_l2cap_feature_exchange_is_metadata_only(self):
        def acl(signal):
            data = struct.pack("<HH", len(signal), 1) + signal
            return b"\x02" + struct.pack("<HH", 0x2023, len(data)) + data
        result = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            acl(b"\x0a\x01\x02\x00\x02\x00"),
            acl(b"\x0b\x01\x08\x00\x02\x00\x00\x00\x03\x00\x00\x00"),
            acl(b"\x01\x01\x02\x00\x00\x00"),
            acl(b"\x0b\x02\x0b\x00\x63\x00\x00\x00PRIVATE"),
            flags=[3, 0, 1, 1, 1]), ADDRESS)
        self.assertEqual(result["events"][1]["kind"], "l2cap_information_request")
        self.assertEqual(result["events"][2]["features"], "0x00000003")
        self.assertEqual(result["events"][2]["direction"], "controller_to_host")
        self.assertEqual(result["events"][3]["kind"], "l2cap_command_reject")
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_page_timeout_belongs_to_target(self):
        packet = hci_event(3, b"\x04\x00\x00" + TARGET + b"\x01\x00")
        result = connection_events(capture(packet), ADDRESS)
        self.assertEqual(result["events"][0]["meaning"], "page_timeout")
        self.assertNotIn("encryptionEnabled", result["events"][0])

    def test_other_peer_is_excluded(self):
        packet = hci_event(3, b"\x04\x00\x00" + b"\x99" * 6 + b"\x01\x00")
        self.assertEqual(connection_events(capture(packet), ADDRESS)["events"], [])

    def test_authentication_failure_after_connection(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_event(6, b"\x05\x23\x01")]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(events[-1]["kind"], "authentication_complete")
        self.assertEqual(events[-1]["meaning"], "authentication_failure")

    def test_key_payload_is_never_reported(self):
        secret = b"PRIVATE_KEY_1234"
        packet = b"\x01" + struct.pack("<HB", 0x040B, 22) + TARGET + secret
        result = connection_events(capture(packet), ADDRESS)
        self.assertEqual(result["events"][0]["kind"], "link_key_reply")
        self.assertNotIn(secret.decode(), json.dumps(result))
        self.assertNotIn(secret.hex(), json.dumps(result))

    def test_handle_reuse_does_not_leak_other_peer(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_event(3, b"\x00\x23\x01" + b"\x99" * 6 + b"\x01\x00"),
                   hci_event(6, b"\x05\x23\x01")]
        self.assertEqual(len(connection_events(capture(*packets), ADDRESS)["events"]), 1)

    def test_truncated_capture_is_rejected(self):
        data = capture(hci_event(0x17, TARGET)).getvalue()[:-1]
        with self.assertRaises(ValueError):
            connection_events(io.BytesIO(data), ADDRESS)

    def test_disconnection_reason(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_event(5, b"\x00\x23\x01\x13")]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(events[-1]["meaning"], "remote_user_terminated")

    def test_remote_feature_timeout_is_target_filtered(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_event(0x0B, b"\x22\x23\x01" + b"\x00" * 8)]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(events[-1]["kind"], "remote_features_complete")
        self.assertEqual(events[-1]["meaning"], "lmp_response_timeout")

    def test_create_connection_parameters(self):
        packet = hci_command(0x0405, TARGET + b"\x18\xcc\x01\x00\x00\x00\x01")
        event = connection_events(capture(packet), ADDRESS)["events"][0]
        self.assertEqual(event["packetTypes"], "0xcc18")
        self.assertEqual(event["pageScanRepetitionMode"], 1)
        self.assertEqual(event["allowRoleSwitch"], 1)
        self.assertFalse(event["clockOffsetValid"])

    def test_cached_clock_validity_is_reported_without_clock_value(self):
        packet = hci_command(0x0405, TARGET + b"\x18\xcc\x02\x00\x34\x92\x01")
        event = connection_events(capture(packet), ADDRESS)["events"][0]
        self.assertTrue(event["clockOffsetValid"])
        self.assertEqual(event["pageScanRepetitionMode"], 2)
        self.assertNotIn("clockOffset", event)

    def test_role_switch_is_address_filtered(self):
        packets = [hci_command(0x080B, b"\x99" * 6 + b"\x00"),
                   hci_command(0x080B, TARGET + b"\x01")]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["kind"], "switch_role")
        self.assertEqual(events[0]["role"], 1)

    def test_security_and_policy_only_report_target_handle(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_command(0x080D, b"\x23\x01\x05\x00"),
                   hci_command(0x0411, b"\x23\x01"),
                   hci_command(0x0413, b"\x23\x01\x01"),
                   hci_command(0x0411, b"\x24\x01"),
                   hci_command(0x080D, b"\x24\x01\x05\x00")]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(len(events), 4)
        self.assertTrue(events[1]["roleSwitchAllowed"])
        self.assertTrue(events[1]["sniffAllowed"])
        self.assertEqual(events[2]["kind"], "authentication_requested")
        self.assertEqual(events[3]["enabled"], 1)

    def test_mode_and_disconnect_are_handle_filtered(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_command(0x0803, b"\x23\x01" + b"\x00" * 8),
                   hci_event(0x14, b"\x00\x23\x01\x02\x20\x00"),
                   hci_command(0x0804, b"\x23\x01"),
                   hci_command(0x0406, b"\x23\x01\x13"),
                   hci_event(0x14, b"\x00\x24\x01\x02\x20\x00")]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(len(events), 5)
        self.assertEqual(events[1]["kind"], "enter_sniff_mode")
        self.assertEqual(events[2]["mode"], 2)
        self.assertEqual(events[3]["kind"], "exit_sniff_mode")
        self.assertEqual(events[4]["meaning"], "remote_user_terminated")

    def test_remote_metadata_only_on_success_and_target(self):
        packets = [hci_event(3, b"\x00\x23\x01" + TARGET + b"\x01\x00"),
                   hci_event(0x0B, b"\x00\x23\x01" + b"\x01" * 8),
                   hci_event(0x0C, b"\x00\x23\x01\x03\x0a\x00\x11\x22"),
                   hci_event(0x23, b"\x00\x23\x01\x01\x02" + b"\x02" * 8),
                   hci_event(0x0B, b"\x22\x23\x01" + b"\x03" * 8),
                   hci_event(0x0B, b"\x00\x24\x01" + b"\x04" * 8)]
        events = connection_events(capture(*packets), ADDRESS)["events"]
        self.assertEqual(len(events), 5)
        self.assertEqual(events[0]["encryptionEnabled"], 0)
        self.assertEqual(events[1]["features"], "01" * 8)
        self.assertEqual(events[2]["manufacturer"], 10)
        self.assertEqual(events[2]["subversion"], 0x2211)
        self.assertEqual(events[3]["maxPage"], 2)
        self.assertNotIn("features", events[4])

    def test_l2cap_direction_and_no_application_data(self):
        def acl(signal, cid=1):
            l2cap = struct.pack("<HH", len(signal), cid) + signal
            return b"\x02" + struct.pack("<HH", 0x2023, len(l2cap)) + l2cap

        # Connection handle 0x23 is used for ACL flags 0x2023.
        packets = [hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
                   acl(b"\x02\x01\x04\x00\x01\x00\x40\x00"),
                   acl(b"\x03\x01\x08\x00\x41\x00\x40\x00\x00\x00\x00\x00"),
                   acl(b"PRIVATE_APPLICATION_DATA", cid=0x41)]
        result = connection_events(capture(*packets, flags=[3, 0, 1, 0]), ADDRESS)
        self.assertEqual(len(result["events"]), 3)
        self.assertEqual(result["events"][1]["direction"], "host_to_controller")
        self.assertEqual(result["events"][2]["direction"], "controller_to_host")
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_l2cap_authorization_pending_is_distinct_from_authentication(self):
        packets = [hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00")]
        for status in (1, 2):
            signal = struct.pack("<BBHHHHH", 3, 6, 8, 67, 72, 1, status)
            data = struct.pack("<HH", len(signal), 1) + signal
            packets.append(b"\x02" + struct.pack("<HH", 0x2023, len(data)) + data)
        events = connection_events(capture(*packets, flags=[3, 1, 1]), ADDRESS)["events"]
        self.assertEqual(events[1]["pendingMeaning"], "authentication_pending")
        self.assertEqual(events[2]["pendingMeaning"], "authorization_pending")
        self.assertEqual(events[2]["sourceCid"], 72)
        self.assertEqual(events[2]["destinationCid"], 67)
        self.assertEqual(events[2]["identifier"], 6)
        self.assertEqual(events[2]["direction"], "controller_to_host")

    def test_l2cap_success_does_not_interpret_undefined_pending_status(self):
        signal = struct.pack("<BBHHHHH", 3, 6, 8, 67, 72, 0, 2)
        data = struct.pack("<HH", len(signal), 1) + signal
        events = connection_events(capture(
            hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00"),
            b"\x02" + struct.pack("<HH", 0x2023, len(data)) + data), ADDRESS)["events"]
        self.assertEqual(events[-1]["result"], 0)
        self.assertNotIn("pendingMeaning", events[-1])

    def test_l2cap_disconnect_request_and_reply_are_correlated(self):
        packets = [hci_event(3, b"\x00\x23\x00" + TARGET + b"\x01\x00")]
        for code in (6, 7):
            signal = struct.pack("<BBHHH", code, 8, 4, 65, 66)
            data = struct.pack("<HH", len(signal), 1) + signal
            packets.append(b"\x02" + struct.pack("<HH", 0x2023, len(data)) + data)
        events = connection_events(capture(*packets, flags=[3, 0, 1]), ADDRESS)["events"]
        self.assertEqual(events[1]["kind"], "l2cap_disconnection_request")
        self.assertEqual(events[2]["kind"], "l2cap_disconnection_response")
        self.assertEqual(events[1]["identifier"], events[2]["identifier"])
        self.assertEqual(events[1]["sourceCid"], events[2]["sourceCid"])
        self.assertEqual(events[1]["direction"], "host_to_controller")
        self.assertEqual(events[2]["direction"], "controller_to_host")


if __name__ == "__main__":
    unittest.main()
