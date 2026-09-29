import unittest
from unittest.mock import patch

import collector_recovery as recovery


class CollectorRecoveryTest(unittest.TestCase):
    def test_ui_process_is_not_collector_restoration(self):
        state = recovery.parse_snapshot("(nothing)", "GATT Scanner Map", True)
        self.assertFalse(recovery.collector_is_restored(state))
        self.assertTrue(recovery.collector_is_stopped(state))

    def test_service_must_be_foreground(self):
        state = recovery.parse_snapshot(".BondProbeService", "GATT Scanner Map", True)
        self.assertFalse(recovery.collector_is_restored(state))
        self.assertFalse(recovery.collector_is_stopped(state))

    def test_active_sensor_connection_need_not_scan(self):
        state = recovery.parse_snapshot(".BondProbeService isForeground=true", "GATT Scanner Map", True)
        self.assertTrue(recovery.collector_is_restored(state))

    def test_missing_bluetooth_dump_is_not_confirmed_stop(self):
        state = recovery.parse_snapshot("(nothing)", "", False)
        self.assertFalse(recovery.collector_is_stopped(state))

    def test_registered_scanner_prevents_stop_confirmation(self):
        dump = "GATT Scanner Map\n  com.example.weartester (Registered)\nLE scans (started/stopped) : 5 / 4"
        state = recovery.parse_snapshot("(nothing)", dump, False)
        self.assertEqual(state["activeScans"], 1)
        self.assertFalse(recovery.collector_is_stopped(state))

    def test_restore_requests_service_start_not_just_activity(self):
        state = dict(running=True, service=True, foreground=True)
        with patch.object(recovery.bench, "adb") as adb, patch.object(recovery, "snapshot", return_value=state):
            self.assertEqual(recovery.restore("watch"), state)
        adb.assert_called_once_with("watch", "shell", "am", "start", "-n",
                                    recovery.COMPONENT, "--ez", "auto_start_probe", "true")

    def test_restore_times_out_if_only_process_restarts(self):
        state = dict(running=True, service=False, foreground=False)
        with patch.object(recovery.bench, "adb"), patch.object(recovery, "snapshot", return_value=state), \
                patch.object(recovery.time, "monotonic", side_effect=[0, 9]):
            with self.assertRaisesRegex(RuntimeError, "unconfirmed"):
                recovery.restore("watch")

    def test_fallback_restarts_service_even_if_process_exists(self):
        command = recovery.fallback_command()
        self.assertIn("sleep 25; am start", command)
        self.assertIn("--ez auto_start_probe true", command)
        self.assertNotIn("pidof", command)
        self.assertNotIn("force-stop", command)

    def probe_fixture(self):
        previous = dict(at=1790504122568, transport="channel1-authenticated", apkVersionCode=16)
        probe = dict(previous, at=1790504201419, stage="RFCOMM_FAILED", applicationBytesSent=0, timeoutSeconds=8)
        return previous, dict(probe=probe, ownership={})

    def test_fresh_watch_result_may_precede_host_pause_timestamp(self):
        previous, status = self.probe_fixture()
        with patch.object(recovery.time, "time", return_value=1790504202.071):
            self.assertEqual(recovery.completed_paused_probe(previous, status), status["probe"])

    def test_stale_or_invalid_watch_timestamp_is_rejected(self):
        previous, status = self.probe_fixture()
        for at in (previous["at"], previous["at"] - 1, None, True, "1790504201419"):
            with self.subTest(at=at):
                status["probe"]["at"] = at
                with self.assertRaisesRegex(RuntimeError, "fresh"):
                    recovery.completed_paused_probe(previous, status)

    def test_unsettled_or_pairing_probe_is_rejected(self):
        for changes in ({"ownership": {"operation": "pending"}}, {"ownership": None}, {"error": "failed"}):
            previous, status = self.probe_fixture()
            status.update(changes)
            with self.assertRaisesRegex(RuntimeError, "settle"):
                recovery.completed_paused_probe(previous, status)
        previous, status = self.probe_fixture()
        status["probe"]["stage"] = "RFCOMM_PAIRING_BLOCKED"
        with self.assertRaisesRegex(RuntimeError, "settle"):
            recovery.completed_paused_probe(previous, status)

    def test_changed_comparison_conditions_are_rejected(self):
        for key, value in (("timeoutSeconds", 20), ("transport", "sdp"), ("applicationBytesSent", 1), ("apkVersionCode", 15)):
            with self.subTest(key=key):
                previous, status = self.probe_fixture()
                status["probe"][key] = value
                with self.assertRaisesRegex(RuntimeError, "conditions"):
                    recovery.completed_paused_probe(previous, status)


if __name__ == "__main__":
    unittest.main()
