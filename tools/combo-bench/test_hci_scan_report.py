import io
import json
import struct
import unittest

from hci_scan_report import coexistence_report, scan_events
from test_hci_connection_report import ADDRESS, TARGET, capture, hci_command, hci_event


def complete(opcode, status=0):
    return hci_event(0x0E, b"\x01" + struct.pack("<H", opcode) + bytes([status]))


class HciScanReportTest(unittest.TestCase):
    def test_request_is_not_confirmation(self):
        events = scan_events(capture(hci_command(0x2042, b"\x01\x00\x00\x00\x00\x00")))
        self.assertEqual(len(events), 1)
        self.assertTrue(events[0]["enabled"])
        self.assertNotIn("applied", events[0])

    def test_acknowledged_extended_parameters_and_continuous_scan(self):
        events = scan_events(capture(
            hci_command(0x2041, b"\x00\x00\x01\x01\xa0\x00\xa0\x00"), complete(0x2041),
            hci_command(0x2042, b"\x01\x00\x00\x00\x00\x00"), complete(0x2042)))
        self.assertTrue(events[1]["applied"])
        self.assertEqual(events[1]["parameters"]["phys"],
                         [dict(phy="1M", scanType=1, intervalMs=100.0, windowMs=100.0)])
        self.assertTrue(events[3]["parameters"]["enabled"])
        self.assertEqual(events[3]["requestPacketIndex"], 2)
        self.assertEqual(events[3]["parameters"]["durationMs"], 0)

    def test_rejected_enable_not_reported_as_applied(self):
        events = scan_events(capture(hci_command(0x200C, b"\x01\x00"), complete(0x200C, 0x0C)))
        self.assertFalse(events[-1]["applied"])
        self.assertEqual(events[-1]["status"], "0x0c")

    def test_missing_request_cannot_invent_state(self):
        event = scan_events(capture(complete(0x2042)))[0]
        self.assertFalse(event["matchedCommand"])
        self.assertFalse(event["applied"])
        self.assertNotIn("parameters", event)

    def test_both_phys_and_finite_duration(self):
        events = scan_events(capture(
            hci_command(0x2041, b"\x00\x00\x05" + struct.pack("<BHHBHH", 1, 160, 80, 0, 320, 160)),
            complete(0x2041), hci_command(0x2042, struct.pack("<BBHH", 1, 2, 20, 3)), complete(0x2042)))
        self.assertEqual([p["phy"] for p in events[1]["parameters"]["phys"]], ["1M", "coded"])
        self.assertEqual(events[3]["parameters"]["durationMs"], 200)
        self.assertEqual(events[3]["parameters"]["periodMs"], 3840)

    def test_legacy_parameters(self):
        events = scan_events(capture(hci_command(0x200B, struct.pack("<BHHBB", 0, 16, 8, 0, 0)), complete(0x200B)))
        self.assertEqual(events[1]["parameters"]["phys"][0]["windowMs"], 5.0)

    def test_reset_discards_pending_request(self):
        events = scan_events(capture(hci_command(0x200C, b"\x01\x00"), complete(0x0C03), complete(0x200C)))
        self.assertEqual(events[1]["kind"], "controller_reset_complete")
        self.assertFalse(events[-1]["matchedCommand"])

    def test_capture_loss_prevents_matching_old_request(self):
        request = hci_command(0x200C, b"\x01\x00")
        data = bytearray(capture(request, complete(0x200C)).getvalue())
        # The second btsnoop record reports a lost packet before its completion.
        struct.pack_into(">I", data, 16 + 24 + len(request) + 12, 1)
        events = scan_events(io.BytesIO(data))
        self.assertEqual(events[1]["kind"], "capture_loss")
        self.assertFalse(events[-1]["matchedCommand"])

    def test_truncated_hci_scan_command_rejected(self):
        with self.assertRaises(ValueError):
            scan_events(capture(hci_command(0x200C, b"\x01\x00")[:-1]))

    def test_disable_requires_its_own_confirmation(self):
        events = scan_events(capture(hci_command(0x200C, b"\x01\x00"), complete(0x200C),
                                     hci_command(0x200C, b"\x00\x00")))
        self.assertTrue(events[1]["parameters"]["enabled"])
        self.assertEqual(events[-1]["kind"], "legacy_scan_enable_requested")
        self.assertNotIn("applied", events[-1])

    def test_repeated_commands_match_in_order(self):
        events = scan_events(capture(hci_command(0x200C, b"\x01\x00"), hci_command(0x200C, b"\x00\x00"),
                                     complete(0x200C), complete(0x200C)))
        self.assertTrue(events[2]["parameters"]["enabled"])
        self.assertFalse(events[3]["parameters"]["enabled"])

    def test_scan_timeout_reported(self):
        self.assertEqual(scan_events(capture(hci_event(0x3E, b"\x11")))[0]["kind"], "le_scan_timeout")

    def test_malformed_scan_parameters_rejected(self):
        for data in (b"\x00\x00\x00", b"\x00\x00\x02", b"\x00\x00\x05\x01\x10\x00\x08\x00"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                scan_events(capture(hci_command(0x2041, data)))

    def test_combined_timeline_is_ordered_and_excludes_secrets_and_other_peers(self):
        data = capture(hci_command(0x200C, b"\x01\x00"), complete(0x200C),
                       hci_command(0x0405, TARGET + b"\x18\xcc\x01\x00\x00\x00\x01"),
                       hci_command(0x040B, TARGET + b"SECRET_KEY_BYTES!"),
                       hci_event(3, b"\x00\x23\x00" + b"\x99" * 6 + b"\x01\x00"),
                       hci_event(3, b"\x04\x00\x00" + TARGET + b"\x01\x00")).getvalue()
        report = coexistence_report(data, ADDRESS)
        self.assertEqual([e["packetIndex"] for e in report["timeline"]], [0, 1, 2, 5])
        self.assertEqual(report["timeline"][-1]["meaning"], "page_timeout")
        self.assertNotIn("SECRET", json.dumps(report))
        self.assertNotIn(b"SECRET_KEY_BYTES!".hex(), json.dumps(report))


if __name__ == "__main__":
    unittest.main()
