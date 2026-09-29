import base64
from datetime import datetime
import json
import struct
import unittest
import zlib
from btsnooz_summary import decode_summary


ADDRESS = "7C:F0:E5:5F:5D:52"
ADDRESS_BYTES = bytes.fromhex(ADDRESS.replace(":", ""))[::-1]


def section(body, version=2):
    raw = struct.pack("<BQ", version, 1000000) + zlib.compress(body)
    return "--- BEGIN:BTSNOOP_LOG_SUMMARY ---\n" + base64.b64encode(raw).decode() + "\n--- END:BTSNOOP_LOG_SUMMARY ---"


def record(kind, payload, delta=0, original=None):
    length = len(payload) + 1
    return struct.pack("<HHIB", length, original or length, delta, kind) + payload


class SummaryTest(unittest.TestCase):
    def test_target_page_timeout_and_relative_time(self):
        create = b"\x05\x04\x0d" + ADDRESS_BYTES + b"\x18\xcc\x01\x00\x00\x80\x01"
        failed = b"\x03\x0b\x04\xff\xff" + ADDRESS_BYTES + b"\x01\x00"
        report = decode_summary(section(record(0x20, create) + record(0x10, failed, 6409000)), ADDRESS)
        self.assertEqual(["create_connection", "connection_complete"], [e["kind"] for e in report["events"]])
        self.assertEqual("page_timeout", report["events"][-1]["meaning"])
        self.assertIsNone(report["droppedPackets"])
        self.assertEqual(1, report["events"][-1]["packetIndex"])
        start, end = (datetime.fromisoformat(e["captureTime"]) for e in report["events"])
        self.assertAlmostEqual(6.409, (end - start).total_seconds())

    def test_key_payload_is_not_exported(self):
        secret = b"SECRET_NOT_PRINT"
        self.assertEqual(16, len(secret))
        key_reply = b"\x0b\x04\x16" + ADDRESS_BYTES + secret
        report = decode_summary(section(record(0x20, key_reply)), ADDRESS)
        self.assertEqual("link_key_reply", report["events"][0]["kind"])
        self.assertNotIn(secret.decode(), json.dumps(report))
        self.assertNotIn(secret.hex(), json.dumps(report))

    def test_partial_packets_and_acl_are_skipped(self):
        report = decode_summary(section(record(0x10, b"\x03", original=14) + record(0x11, b"private")), ADDRESS)
        self.assertEqual([], report["events"])
        self.assertEqual(2, report["skippedRecords"])

    def test_other_device_is_not_attributed(self):
        event = b"\x03\x0b\x04\xff\xff" + b"\x01" * 6 + b"\x01\x00"
        self.assertEqual([], decode_summary(section(record(0x10, event)), ADDRESS)["events"])

    def test_invalid_or_ambiguous_summary_is_rejected(self):
        for text in ("missing", section(b"", version=1), section(b"short"),
                     section(struct.pack("<HHIB", 20, 10, 0, 0x10)), section(b"") * 2):
            with self.subTest(text=text), self.assertRaises(ValueError):
                decode_summary(text, ADDRESS)


if __name__ == "__main__":
    unittest.main()
