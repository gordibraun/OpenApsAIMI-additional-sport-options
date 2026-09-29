import tempfile
import unittest
from contextlib import ExitStack
from io import StringIO
from types import SimpleNamespace
from unittest.mock import patch

import control_session as subject


class ControlSessionTest(unittest.TestCase):
    def valid(self, success=True):
        return dict(id="attempt", complete=True, mode="control-handshake-only", apkVersionCode=18,
                    therapyCommandsSent=0, serviceActivationsSent=0, success=success,
                    handshakeCompleted=success, disconnectWritten=success, socketClosed=True,
                    ownershipLocked=False, watchdogExpired=False, bondState=12)

    def test_success_requires_all_observations(self):
        subject.validate_result(self.valid(), "attempt")
        for key, bad in dict(id="old", complete=False, mode="probe", apkVersionCode=17,
                             therapyCommandsSent=1, serviceActivationsSent=1, handshakeCompleted=False,
                             disconnectWritten=False, socketClosed=False, ownershipLocked=True,
                             watchdogExpired=True, bondState=10).items():
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                subject.validate_result(dict(self.valid(), **{key: bad}), "attempt")

    def test_failed_single_attempt_is_not_success(self):
        subject.validate_result(self.valid(False), "attempt")

    def test_delayed_aaps_start_is_awaited_without_another_launch(self):
        with patch.object(subject.bench, "adb", side_effect=["", "", "22902"]) as adb, \
             patch.object(subject.time, "sleep") as sleep:
            self.assertTrue(subject.wait_for_aaps_process("phone"))
            self.assertEqual(3, adb.call_count)
            self.assertEqual(2, sleep.call_count)
            self.assertTrue(all(call.args == ("phone", "shell", "pidof", subject.bench.AAPS)
                                for call in adb.call_args_list))

    def test_aaps_start_wait_is_bounded_and_does_not_hide_failure(self):
        with patch.object(subject.bench, "adb", return_value="") as adb, \
             patch.object(subject.time, "monotonic", side_effect=[0, 0, 8]), \
             patch.object(subject.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "restart was not verified"):
                subject.wait_for_aaps_process("phone")
            self.assertEqual(2, adb.call_count)

    def test_restart_wait_accepts_only_explicit_confirmation(self):
        for text, accepted in [("RESTARTED\n", True), ("\n", False), ("", False), ("READY\n", False)]:
            with self.subTest(text=text), patch.object(subject.sys, "stdin", StringIO(text)) as stream, \
                 patch.object(subject.select, "select", return_value=([stream], [], [])) as select, \
                 patch("builtins.print"):
                if accepted:
                    subject.wait_for_pump_restart()
                else:
                    with self.assertRaises(RuntimeError):
                        subject.wait_for_pump_restart()
                select.assert_called_once_with([stream], [], [], 120)

    def test_restart_wait_timeout_never_consumes_future_input(self):
        with patch.object(subject.sys, "stdin") as stream, \
             patch.object(subject.select, "select", return_value=([], [], [])), patch("builtins.print"):
            with self.assertRaises(RuntimeError):
                subject.wait_for_pump_restart()
            stream.readline.assert_not_called()

    def test_phone_recovery_is_scoped_and_preserves_original_state(self):
        marker = "control-recovery-12345678-1234-1234-1234-123456789abc.json"
        default = subject.recovery_command(marker, 0, True)
        self.assertIn("sleep 240", default)
        self.assertIn("test -f files/" + marker, default)
        self.assertIn("pm default-state --user 0 " + subject.bench.AAPS, default)
        self.assertIn("am start", default)
        enabled = subject.recovery_command(marker, 1, False)
        self.assertIn("pm enable", enabled)
        self.assertNotIn("am start", enabled)
        self.assertNotIn("control-session-arm", default)
        self.assertNotIn("bluetooth", default)
        for bad_marker, state in [("unexpected;command", 0), (marker, 3)]:
            with self.assertRaises(RuntimeError):
                subject.recovery_command(bad_marker, state, True)

    def test_manual_wait_is_after_isolation_and_before_any_pump_command(self):
        for accepted in (True, False):
            with self.subTest(accepted=accepted), tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
                args = SimpleNamespace(phone="phone", watch="watch", evidence_dir=directory, wait_for_pump_restart=True)
                config = dict(session="session", pump="PUMP_41056642")
                events = []
                def adb(*args, **kwargs):
                    events.append(args)
                    return "versionCode=18" if "dumpsys" in args else "running"
                def ready():
                    events.append(("manual-confirmation",))
                    self.assertTrue(any("disable-user" in call for call in events))
                    self.assertFalse(any("pump-command" in call for call in events))
                    if not accepted:
                        raise RuntimeError("no confirmation")
                for target, name, options in [
                    (subject.ownership, "configs_for", dict(return_value=({"watch": "watch", "phone": "phone"}, {"watch": config}))),
                    (subject.ownership, "settled_owner", dict(return_value="phone")),
                    (subject.ownership, "move_owner", {}),
                    (subject.ownership, "wait_for_isolation", {}),
                    (subject.bench, "enabled_state", dict(return_value=0)),
                    (subject.bench, "adb", dict(side_effect=adb)),
                    (subject.bench, "validate_test_pump", dict(return_value=config)),
                    (subject.bench, "permit_deadline", dict(return_value=120000)),
                    (subject.bench, "write", {}),
                    (subject.bench, "read", dict(return_value=self.valid(False))),
                    (subject.bench, "command", dict(side_effect=lambda *a: events.append(("pump-command",)))),
                    (subject, "schedule_recovery", dict(return_value=("marker", "123"))),
                    (subject, "wait_for_pump_restart", dict(side_effect=ready)),
                    (subject.time, "sleep", {}),
                    (subject.uuid, "uuid4", dict(return_value="attempt"))
                ]:
                    stack.enter_context(patch.object(target, name, **options))
                stack.enter_context(patch("builtins.print"))
                if accepted:
                    subject.run(args)
                else:
                    with self.assertRaises(RuntimeError):
                        subject.run(args)
                self.assertEqual(int(accepted), events.count(("pump-command",)))
                self.assertTrue(any("default-state" in call for call in events))
                self.assertTrue(any("am" in call and "start" in call for call in events))

    def test_command_failure_restores_aaps_and_does_not_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(phone="phone", watch="watch", evidence_dir=directory)
            config = dict(session="session", pump="PUMP_41056642")
            with patch.object(subject.ownership, "configs_for", return_value=({"watch": "watch", "phone": "phone"}, {"watch": config})), \
                 patch.object(subject.ownership, "settled_owner", return_value="phone"), \
                 patch.object(subject.ownership, "move_owner") as move, \
                 patch.object(subject.ownership, "wait_for_isolation"), \
                 patch.object(subject.bench, "enabled_state", return_value=0), \
                 patch.object(subject.bench, "adb", side_effect=lambda *a, **kw: "versionCode=18" if "dumpsys" in a else "running") as adb, \
                 patch.object(subject.bench, "validate_test_pump", return_value=config), \
                 patch.object(subject.bench, "permit_deadline", return_value=120000), \
                 patch.object(subject.bench, "write"), \
                 patch.object(subject.bench, "command", side_effect=RuntimeError("failed")) as command, \
                 patch.object(subject.time, "sleep"), patch("builtins.print"):
                with self.assertRaises(RuntimeError):
                    subject.run(args)
                command.assert_called_once_with("watch", "control-session")
                self.assertEqual([call.args[-1] for call in move.call_args_list], ["watch", "phone"])
                self.assertTrue(any("default-state" in call.args for call in adb.call_args_list))
                self.assertTrue(any("am" in call.args and "start" in call.args for call in adb.call_args_list))


if __name__ == "__main__":
    unittest.main()
