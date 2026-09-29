import unittest
from peer_lab import classify, cold_success, last_acl


def row(event, timestamp, **kwargs):
    return dict(event=event, elapsedMs=timestamp, interactive=False, deviceIdle=False, **kwargs)


class EvidenceTest(unittest.TestCase):
    def test_open_socket_without_acl_is_not_cold_success(self):
        result = classify([row("START", 100), row("SOCKET_OPEN", 200, openDurationMs=100),
                           row("FINISHED", 300, outcome="OK_ZERO_BYTES")])
        self.assertFalse(result["freshAcl"])
        self.assertFalse(result["aclClosed"])

    def test_pairing_acl_before_attempt_is_not_cold_success(self):
        result = classify([row("android.bluetooth.device.action.ACL_CONNECTED", 50),
                           row("START", 100), row("SOCKET_OPEN", 200, openDurationMs=100)])
        self.assertFalse(result["freshAcl"])

    def test_fresh_connection_and_close_are_recognized(self):
        rows = [row("START", 100), row("android.bluetooth.device.action.ACL_CONNECTED", 150),
                row("SOCKET_OPEN", 200, openDurationMs=100),
                row("FINISHED", 2200, outcome="OK_ZERO_BYTES"),
                row("android.bluetooth.device.action.ACL_DISCONNECTED", 3000)]
        result = classify(rows)
        self.assertTrue(result["freshAcl"])
        self.assertTrue(result["aclClosed"])
        self.assertFalse(result["idleAtStart"])
        self.assertEqual("OK_ZERO_BYTES", result["outcome"])
        self.assertTrue(cold_success(result))

    def test_socket_failure_cannot_pass_on_acl_events_alone(self):
        rows = [row("START", 100), row("android.bluetooth.device.action.ACL_CONNECTED", 150),
                row("SOCKET_OPEN", 200, openDurationMs=100),
                row("FINISHED", 2200, outcome="TIMEOUT"),
                row("android.bluetooth.device.action.ACL_DISCONNECTED", 3000)]
        self.assertFalse(cold_success(classify(rows)))

    def test_pairing_is_not_an_ordinary_reconnect(self):
        for event in ("UNEXPECTED_PAIRING", "android.bluetooth.device.action.PAIRING_REQUEST",
                      "android.bluetooth.device.action.BOND_STATE_CHANGED"):
            with self.subTest(event=event):
                rows = [row("START", 100), row("android.bluetooth.device.action.ACL_CONNECTED", 150),
                        row(event, 170), row("SOCKET_OPEN", 200, openDurationMs=100),
                        row("FINISHED", 2200, outcome="OK_ZERO_BYTES"),
                        row("android.bluetooth.device.action.ACL_DISCONNECTED", 3000)]
                self.assertFalse(cold_success(classify(rows)))

    def test_reconnected_peer_is_not_counted_as_disconnected(self):
        rows = [row("START", 100), row("SOCKET_OPEN", 200, openDurationMs=100),
                row("android.bluetooth.device.action.ACL_DISCONNECTED", 3000),
                row("android.bluetooth.device.action.ACL_CONNECTED", 4000)]
        self.assertFalse(classify(rows)["aclClosed"])
        self.assertTrue(last_acl(rows)["event"].endswith("ACL_CONNECTED"))


if __name__ == "__main__":
    unittest.main()
