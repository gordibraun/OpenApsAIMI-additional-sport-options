import contextlib
import io
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import benchctl


class BenchControlTest(unittest.TestCase):
    def setUp(self):
        self.args = SimpleNamespace(phone="phone", target="watch", off_body_confirmed=True,
                                    pump_id="123", evidence_dir=None)
        self.config = dict(session="s", pump="PUMP_123", address="00:00:00:00:00:01", local="w")

    def test_adapter_state_parser_ignores_binary_snoop_tail(self):
        with patch.object(benchctl.subprocess, "run", return_value=SimpleNamespace(stdout=b"  state: ON\n\xff\x80")):
            self.assertEqual(benchctl.bluetooth_state("watch"), "ON")

    def test_hold_validation_happens_before_device_access(self):
        with patch.object(benchctl, "adb") as adb, patch.object(benchctl, "validate_test_pump") as validate:
            for value in (-1, 1, 6, 100, True, 5.0, "5"):
                self.args.probe_hold_seconds = value
                with self.assertRaises(RuntimeError):
                    benchctl.isolated_probe(self.args, "watch")
            self.args.probe_hold_seconds = 5
            with self.assertRaisesRegex(RuntimeError, "authenticated"):
                benchctl.isolated_probe(self.args, "watch")
            self.args.transport = "channel1-authenticated"
            self.args.probe_timeout_seconds = 20
            with self.assertRaisesRegex(RuntimeError, "authenticated"):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_not_called()
            validate.assert_not_called()

    def test_hold_is_bound_to_single_use_permit(self):
        self.args.transport = "channel1-authenticated"
        self.args.probe_hold_seconds = 5
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", return_value=""), \
                patch.object(benchctl, "permit_deadline", return_value=123_000), \
                patch.object(benchctl, "write") as write:
            benchctl.arm(self.args, "watch")
            self.assertEqual(write.call_args.args[2]["holdSeconds"], 5)
            self.assertEqual(write.call_args.args[2]["timeoutSeconds"], 8)

    def test_hold_refuses_old_apk_that_ignores_option(self):
        self.args.transport = "channel1-authenticated"
        self.args.probe_hold_seconds = 5
        with self.assertRaisesRegex(RuntimeError, "different hold"):
            self.run_attempts([{"probe": {"at": 1, "transport": self.args.transport, "stage": "RFCOMM_OK_ZERO_BYTES"}}])

    def test_hold_requires_duration_completion_and_zero_bytes(self):
        self.args.transport = "channel1-authenticated"
        self.args.probe_hold_seconds = 5
        good = dict(at=1, transport=self.args.transport, stage="RFCOMM_OK_ZERO_BYTES", holdSeconds=5,
                    holdCompleted=True, holdElapsedMs=5000, applicationBytesSent=0)
        self.assertEqual(self.run_attempts([{"probe": good}]), 1)
        for changes in ({"holdElapsedMs": 4999}, {"holdElapsedMs": 7000}, {"holdElapsedMs": None},
                        {"holdCompleted": False}, {"applicationBytesSent": 1}):
            with self.subTest(changes=changes), self.assertRaisesRegex(RuntimeError, "bounded zero-byte"):
                self.run_attempts([{"probe": dict(good, **changes)}])

    def test_connect_failure_does_not_require_a_completed_hold_or_retry(self):
        self.args.transport = "channel1-authenticated"
        self.args.probe_hold_seconds = 5
        self.assertEqual(self.run_attempts([{"probe": dict(at=1, transport=self.args.transport,
                         stage="RFCOMM_TIMEOUT", holdSeconds=5, holdCompleted=False)}]), 1)

    def test_adapter_state_parser_rejects_unknown_state(self):
        with patch.object(benchctl.subprocess, "run", return_value=SimpleNamespace(stdout=b"unavailable")):
            with self.assertRaises(RuntimeError):
                benchctl.bluetooth_state("watch")

    def test_adapter_state_parser_accepts_ble_transitions(self):
        for state in ("BLE_TURNING_ON", "BLE_TURNING_OFF", "BLE_ON", "TURNING_ON", "TURNING_OFF"):
            with self.subTest(state=state), patch.object(benchctl.subprocess, "run", return_value=SimpleNamespace(stdout=f" state: {state}\n".encode())):
                self.assertEqual(benchctl.bluetooth_state("watch"), state)

    def test_radio_waits_through_ble_states_without_repeating_command(self):
        with patch.object(benchctl, "bluetooth_state", side_effect=["ON", "TURNING_OFF", "BLE_ON", "BLE_TURNING_OFF", "OFF"]), \
                patch.object(benchctl, "adb") as adb, patch.object(benchctl.time, "sleep"):
            benchctl.set_bluetooth("watch", False)
            adb.assert_called_once_with("watch", "shell", "cmd", "bluetooth_manager", "disable")

    def test_radio_restores_on_from_ble_transition(self):
        with patch.object(benchctl, "bluetooth_state", side_effect=["BLE_TURNING_OFF", "OFF", "BLE_TURNING_ON", "ON"]), \
                patch.object(benchctl, "adb") as adb, patch.object(benchctl.time, "sleep"):
            benchctl.set_bluetooth("watch", True)
            adb.assert_called_once_with("watch", "shell", "cmd", "bluetooth_manager", "enable")

    def test_radio_wait_is_bounded(self):
        with patch.object(benchctl, "bluetooth_state", return_value="OFF"), \
                patch.object(benchctl, "adb"), patch.object(benchctl.time, "monotonic", side_effect=[0, 21]):
            with self.assertRaisesRegex(RuntimeError, "not confirmed"):
                benchctl.set_bluetooth("watch", True)

    def test_classic_isolation_accepts_ble_only_but_full_shutdown_does_not(self):
        with patch.object(benchctl, "bluetooth_state", side_effect=["ON", "TURNING_OFF", "BLE_ON"]), \
                patch.object(benchctl, "adb") as adb, patch.object(benchctl.time, "sleep"):
            benchctl.set_bluetooth("phone", False, allow_ble_only=True)
            adb.assert_called_once_with("phone", "shell", "cmd", "bluetooth_manager", "disable")
        with patch.object(benchctl, "bluetooth_state", return_value="BLE_ON"), \
                patch.object(benchctl, "adb"), patch.object(benchctl.time, "monotonic", side_effect=[0, 21]):
            with self.assertRaises(RuntimeError):
                benchctl.set_bluetooth("phone", False)

    def test_radio_isolation_cannot_target_phone_or_preexisting_off_radio(self):
        self.args.phone_bluetooth_off = True
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "bluetooth_state", return_value="OFF"), patch.object(benchctl, "adb") as adb:
            with self.assertRaises(RuntimeError):
                benchctl.isolated_probe(self.args, "watch")
            self.args.target = "phone"
            with self.assertRaises(RuntimeError):
                benchctl.isolated_probe(self.args, "phone")
            adb.assert_not_called()

    def test_failed_radio_restoration_does_not_skip_aaps_restoration(self):
        self.args.phone_bluetooth_off = True
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "bluetooth_state", side_effect=["ON", "OFF"]), \
                patch.object(benchctl, "enabled_state", side_effect=[0, 3, 0]), \
                patch.object(benchctl, "adb", return_value="") as adb, \
                patch.object(benchctl, "set_bluetooth", side_effect=[None, RuntimeError("radio failed")]) as radio, \
                patch.object(benchctl, "probe_attempts"), patch.object(benchctl.time, "sleep"), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "Bluetooth restoration"):
                benchctl.isolated_probe(self.args, "watch")
            self.assertEqual([call.args for call in radio.call_args_list], [("phone", False), ("phone", True)])
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)

    def test_phone_radio_must_not_change_while_aaps_is_running(self):
        self.args.phone_bluetooth_off = True
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "bluetooth_state", return_value="ON"), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="") as adb, \
                patch.object(benchctl, "set_bluetooth") as radio, \
                patch.object(benchctl, "probe_attempts") as probe, patch.object(benchctl.time, "sleep"), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "AAPS isolation"):
                benchctl.isolated_probe(self.args, "watch")
            self.assertNotIn(("phone", False), [call.args for call in radio.call_args_list])
            probe.assert_not_called()
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)

    def test_force_stop_is_not_sufficient_for_arm(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "write") as write:
            with self.assertRaises(RuntimeError):
                benchctl.arm(self.args, "watch")
            write.assert_not_called()

    def test_disabled_package_with_running_process_cannot_arm(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", return_value="1234"), \
                patch.object(benchctl, "write") as write:
            with self.assertRaises(RuntimeError):
                benchctl.arm(self.args, "watch")
            write.assert_not_called()

    def test_disabled_package_creates_short_lived_bound_permit(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", return_value=""), \
                patch.object(benchctl, "permit_deadline", return_value=1_120_000), \
                patch.object(benchctl, "write") as write:
            benchctl.arm(self.args, "watch")
            permit = write.call_args.args[2]
            self.assertEqual(permit["session"], "s")
            self.assertEqual(permit["pump"], "PUMP_123")
            self.assertTrue(permit["aapsDisabled"])
            self.assertEqual(permit["expiresAt"], 1_120_000)

    def test_permit_uses_target_clock_despite_host_skew(self):
        with patch.object(benchctl, "adb", return_value="1790461703") as adb, \
                patch.object(benchctl.time, "time", return_value=1790461705):
            self.assertEqual(benchctl.permit_deadline("watch"), 1790461823000)
            adb.assert_called_once_with("watch", "shell", "date", "+%s")

    def test_invalid_target_clock_cannot_create_permit(self):
        for value in ("", "date failed", "-1", "123.5", "1790461703\n1790461704"):
            with self.subTest(value=value), patch.object(benchctl, "adb", return_value=value):
                with self.assertRaisesRegex(RuntimeError, "target clock"):
                    benchctl.permit_deadline("watch")

    def test_preexisting_disabled_state_is_not_changed(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb") as adb:
            with self.assertRaises(RuntimeError):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_not_called()

    def test_arm_failure_restores_default_state(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="") as adb, \
                patch.object(benchctl, "read", return_value={}), \
                patch.object(benchctl, "arm", side_effect=RuntimeError("arm failed")), \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "arm failed"):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)
            adb.assert_any_call("watch", "shell", "run-as", benchctl.PACKAGE, "rm", "-f", "files/arm.json")

    def test_probe_failure_still_restores_enabled_state(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=1), \
                patch.object(benchctl, "adb", return_value="") as adb, \
                patch.object(benchctl, "read", side_effect=[{}, {"error": "probe failed"}]), \
                patch.object(benchctl, "arm"), patch.object(benchctl, "command"), \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "probe failed"):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_any_call("phone", "shell", "pm", "enable", "--user", "0", benchctl.AAPS)

    def test_successful_probe_restores_originally_running_app(self):
        result = {"probe": {"at": 123, "stage": "RFCOMM_OK_ZERO_BYTES"}, "ownership": {"owner": "w"}}
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="1234") as adb, \
                patch.object(benchctl, "read", side_effect=[{}, result]), \
                patch.object(benchctl, "arm"), patch.object(benchctl, "command"), \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            benchctl.isolated_probe(self.args, "watch")
            adb.assert_any_call("phone", "shell", "am", "start", "-n", f"{benchctl.AAPS}/app.aaps.MainActivity")

    def test_mismatched_pump_is_rejected(self):
        with patch.object(benchctl, "read", return_value=self.config), \
                patch.object(benchctl, "pump_identity", return_value=("PUMP_999", self.config["address"])):
            with self.assertRaisesRegex(RuntimeError, "Pump identity mismatch"):
                benchctl.validate_test_pump(self.args, "watch")

    def run_attempts(self, results):
        self.args.attempts = 5
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="") as adb, \
                patch.object(benchctl, "read", side_effect=[{}, *results]), \
                patch.object(benchctl, "arm") as arm, patch.object(benchctl, "command") as command, \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            benchctl.isolated_probe(self.args, "watch")
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)
            self.assertEqual(arm.call_count, command.call_count)
            return command.call_count

    def test_retry_stops_at_first_success(self):
        self.assertEqual(self.run_attempts([
            {"probe": {"at": 1, "stage": "RFCOMM_FAILED"}},
            {"probe": {"at": 2, "stage": "RFCOMM_OK_ZERO_BYTES"}}
        ]), 2)

    def test_retry_has_a_fixed_upper_bound(self):
        self.assertEqual(self.run_attempts([
            {"probe": {"at": i, "stage": "RFCOMM_FAILED"}} for i in range(5)
        ]), 5)

    def test_unknown_operation_is_not_retried(self):
        with self.assertRaisesRegex(RuntimeError, "unknown operation"):
            self.run_attempts([{"error": "unknown operation"}])

    def test_authenticated_transport_never_automatically_retries(self):
        for transport in ("sdp-authenticated", "channel1-authenticated"):
            with self.subTest(transport=transport):
                self.args.transport = transport
                self.assertEqual(self.run_attempts([
                    {"probe": {"at": 1, "stage": "RFCOMM_FAILED", "transport": transport}}
                ]), 1)

    def test_longer_deadline_never_automatically_retries(self):
        self.args.probe_timeout_seconds = 20
        self.assertEqual(self.run_attempts([
            {"probe": {"at": 1, "stage": "RFCOMM_TIMEOUT", "timeoutSeconds": 20}}
        ]), 1)

    def test_old_apk_cannot_silently_substitute_eight_seconds(self):
        self.args.probe_timeout_seconds = 20
        with self.assertRaisesRegex(RuntimeError, "different deadline"):
            self.run_attempts([{"probe": {"at": 1, "stage": "RFCOMM_TIMEOUT"}}])

    def test_invalid_deadline_is_rejected_before_device_access(self):
        for seconds in (None, True, 8.0, "20", -1, 0, 9, 21, 10**9):
            with self.subTest(seconds=seconds), patch.object(benchctl, "adb") as adb, \
                    patch.object(benchctl, "validate_test_pump") as validate:
                self.args.probe_timeout_seconds = seconds
                with self.assertRaisesRegex(RuntimeError, "Only 8 or 20"):
                    benchctl.isolated_probe(self.args, "watch")
                adb.assert_not_called()
                validate.assert_not_called()

    def test_longer_deadline_is_written_only_to_single_use_permit(self):
        self.args.probe_timeout_seconds = 20
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", return_value=""), \
                patch.object(benchctl, "permit_deadline", return_value=123_000), \
                patch.object(benchctl, "write") as write:
            benchctl.arm(self.args, "watch")
            self.assertEqual(write.call_args.args[1], "arm.json")
            self.assertEqual(write.call_args.args[2]["timeoutSeconds"], 20)

    def test_pairing_change_is_recorded_before_error_and_never_retried(self):
        evidence = {"attempts": []}
        result = {"at": 1, "stage": "RFCOMM_PAIRING_BLOCKED", "stopReason": "PAIRING_REQUESTED"}
        status = {"probe": result, "ownership": {"operation": "locked"}, "error": "locked"}
        with patch.object(benchctl, "read", side_effect=[{}, status]), \
                patch.object(benchctl, "arm") as arm, patch.object(benchctl, "command") as command, \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "Pairing was requested"):
                benchctl.probe_attempts(self.args, "watch", evidence)
            self.assertEqual(evidence["attempts"], [result])
            self.assertEqual(evidence["result"], result)
            self.assertEqual(arm.call_count, 1)
            self.assertEqual(command.call_count, 1)

    def test_pairing_change_still_restores_phone_app(self):
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="1234") as adb, \
                patch.object(benchctl, "read", side_effect=[{}, {"probe": {"at": 1, "stage": "RFCOMM_PAIRING_BLOCKED"}}]), \
                patch.object(benchctl, "arm"), patch.object(benchctl, "command"), \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "Pairing was requested"):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)
            adb.assert_any_call("phone", "shell", "am", "start", "-n", f"{benchctl.AAPS}/app.aaps.MainActivity")

    def test_invalid_attempt_limit_does_not_touch_devices(self):
        self.args.attempts = 6
        with patch.object(benchctl, "adb") as adb:
            with self.assertRaises(RuntimeError):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_not_called()

    def test_direct_channel_cannot_be_reported_by_old_sdp_apk(self):
        self.args.transport = "channel1"
        with self.assertRaisesRegex(RuntimeError, "different transport"):
            self.run_attempts([{"probe": {"at": 1, "stage": "RFCOMM_OK_ZERO_BYTES"}}])

    def test_unknown_transport_does_not_touch_devices(self):
        self.args.transport = "secure"
        with patch.object(benchctl, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "Unknown diagnostic transport"):
                benchctl.isolated_probe(self.args, "watch")
            adb.assert_not_called()

    def test_pairing_requires_separate_approval_before_any_device_action(self):
        with patch.object(benchctl, "adb") as adb, patch.object(benchctl, "validate_test_pump") as validate:
            for action in (benchctl.pairing_arm, benchctl.isolated_pair):
                with self.assertRaisesRegex(RuntimeError, "Explicit approval"):
                    action(self.args, "watch")
            adb.assert_not_called()
            validate.assert_not_called()

    def test_pairing_refuses_phone_as_target(self):
        self.args.allow_replace_pairing = True
        self.args.target = "phone"
        with patch.object(benchctl, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "Explicit approval"):
                benchctl.isolated_pair(self.args, "phone")
            adb.assert_not_called()

    def test_pairing_permit_requires_disabled_aaps(self):
        self.args.allow_replace_pairing = True
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "write") as write:
            with self.assertRaisesRegex(RuntimeError, "must be disabled"):
                benchctl.pairing_arm(self.args, "watch")
            write.assert_not_called()

    def test_pairing_permit_is_separate_and_short_lived(self):
        self.args.allow_replace_pairing = True
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", return_value=""), \
                patch.object(benchctl, "permit_deadline", return_value=1_120_000), \
                patch.object(benchctl, "write") as write:
            benchctl.pairing_arm(self.args, "watch")
            self.assertEqual(write.call_args.args[1], "pairing-arm.json")
            self.assertTrue(write.call_args.args[2]["allowReplacePairing"])
            self.assertEqual(write.call_args.args[2]["expiresAt"], 1_120_000)

    def pairing_result(self, result, failure=None):
        self.args.allow_replace_pairing = True
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="1234") as adb, \
                patch.object(benchctl, "read", side_effect=[{}, result]), \
                patch.object(benchctl, "pairing_arm", side_effect=failure), \
                patch.object(benchctl, "probe_attempts") as probe, \
                patch.object(benchctl, "command") as command, \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            if failure:
                with self.assertRaisesRegex(RuntimeError, "arm failed"):
                    benchctl.isolated_pair(self.args, "watch")
            else:
                benchctl.isolated_pair(self.args, "watch")
            command.assert_any_call("watch", "pairing-stop")
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)
            adb.assert_any_call("phone", "shell", "am", "start", "-n", f"{benchctl.AAPS}/app.aaps.MainActivity")
            return probe.call_count

    def test_cancelled_pairing_restores_aaps_without_probe(self):
        self.assertEqual(self.pairing_result({"pairing": {"id": "new", "stage": "CANCELLED", "active": False}}), 0)

    def test_completed_bond_can_only_do_socket_probe(self):
        self.assertEqual(self.pairing_result({"pairing": {"id": "new", "stage": "BONDED", "active": False}}), 1)

    def test_unknown_pairing_outcome_does_not_probe(self):
        self.assertEqual(self.pairing_result({"pairing": {"id": "new", "stage": "UNRESOLVED", "active": False},
                                              "ownership": {"operation": "unknown"}}), 0)

    def test_pairing_arm_failure_restores_aaps(self):
        self.assertEqual(self.pairing_result({}, RuntimeError("arm failed")), 0)

    def test_manual_pairing_waits_for_user_and_never_auto_starts_or_probes(self):
        self.args.action = "manual-pair"
        self.args.allow_replace_pairing = True
        result = {"id": "manual", "active": False, "stage": "PAIRED"}
        with patch.object(benchctl, "validate_test_pump", return_value=self.config), \
                patch.object(benchctl, "enabled_state", return_value=0), \
                patch.object(benchctl, "adb", return_value="") as adb, \
                patch.object(benchctl, "read", return_value={}), \
                patch.object(benchctl, "manual_pairing_status", side_effect=[{}, result, result, result]), \
                patch.object(benchctl, "pairing_arm") as arm, \
                patch.object(benchctl, "probe_attempts") as probe, \
                patch.object(benchctl, "command") as command, \
                patch.object(benchctl.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            benchctl.isolated_pair(self.args, "watch")
            arm.assert_not_called()
            probe.assert_not_called()
            command.assert_called_once_with("watch", "manual-pairing-stop")
            adb.assert_any_call("phone", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS)


if __name__ == "__main__":
    unittest.main()
