import contextlib
import copy
import io
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import benchctl
import compare_transport as comparison


OK = "RFCOMM_OK_ZERO_BYTES"
FAIL = "RFCOMM_FAILED"


class TransportComparisonTest(unittest.TestCase):
    def setUp(self):
        self.args = SimpleNamespace(phone="p", watch="w", phone_id="phone-id", watch_id="watch-id",
                                    pump_id="41056642", off_body_confirmed=True, attempts=3,
                                    evidence_dir=None, transport="sdp")
        self.devices = {"phone": "p", "watch": "w"}
        common = dict(protocol=1, diagnosticOnly=True, session="s", pump="PUMP_41056642", address="00:0E:2F:25:24:BC")
        self.configs = dict(phone=dict(common, local="pn", peer="wn"), watch=dict(common, local="wn", peer="pn"))

    def states(self, owner="pn", generation=1):
        return {role: dict(config, state=dict(owner=owner, generation=generation)) for role, config in self.configs.items()}

    def test_refuses_other_pump_or_unconfirmed_off_body_before_access(self):
        with patch.object(benchctl, "adb") as adb:
            self.args.off_body_confirmed = False
            with self.assertRaises(RuntimeError):
                comparison.configs_for(self.args)
            self.args.off_body_confirmed = True
            self.args.pump_id = "other"
            with self.assertRaises(RuntimeError):
                comparison.configs_for(self.args)
            adb.assert_not_called()

    def test_config_validation_includes_aaps_identity_and_both_serials(self):
        def read(serial, name):
            role = "phone" if serial == "p" else "watch"
            return self.configs[role] if name == "config.json" else self.states()[role]
        with patch.object(benchctl, "verify_device") as verify, patch.object(benchctl, "read", side_effect=read), \
                patch.object(benchctl, "pump_identity", return_value=("PUMP_41056642", "00:0E:2F:25:24:BC")):
            self.assertEqual(self.devices, comparison.configs_for(self.args)[0])
            verify.assert_any_call("p", "phone-id")
            verify.assert_any_call("w", "watch-id")
            self.configs["watch"]["session"] = "old"
            with self.assertRaises(RuntimeError):
                comparison.configs_for(self.args)

    def test_rejects_incomplete_or_disagreeing_ownership(self):
        mutations = [dict(operation="pending"), dict(outbox={"id": "transfer"}),
                     dict(owner="wn"), dict(generation=2), dict(owner="unknown")]
        for mutation in mutations:
            states = self.states()
            states["watch"]["state"].update(mutation)
            with self.subTest(mutation=mutation), patch.object(benchctl, "read", side_effect=list(states.values())):
                with self.assertRaises(RuntimeError):
                    comparison.settled_owner(self.devices, self.configs)

    def test_ownership_record_is_bound_to_config(self):
        for key in ("session", "pump", "local", "peer"):
            states = self.states()
            states["phone"][key] = "wrong"
            with self.subTest(key=key), patch.object(benchctl, "read", side_effect=list(states.values())):
                with self.assertRaises(RuntimeError):
                    comparison.settled_owner(self.devices, self.configs)

    def test_transfer_uses_revoke_protocol_not_file_writes(self):
        with patch.object(comparison, "settled_owner", side_effect=["phone", RuntimeError("pending"), "watch"]), \
                patch.object(benchctl, "command") as command, patch.object(benchctl, "write") as write, \
                patch.object(comparison.time, "sleep"):
            comparison.move_owner(self.devices, self.configs, "watch")
            command.assert_called_once_with("p", "transfer")
            write.assert_not_called()

    def test_unconfirmed_transfer_stops(self):
        with patch.object(comparison, "settled_owner", return_value="phone"), \
                patch.object(benchctl, "command"), patch.object(comparison.time, "monotonic", side_effect=[0, 16]):
            with self.assertRaisesRegex(RuntimeError, "not confirmed"):
                comparison.move_owner(self.devices, self.configs, "watch")

    def pending_states(self, received=False):
        states = self.states()
        grant = dict(id="same-grant", session="s", pump="PUMP_41056642", previousGeneration=1,
                     generation=2, **{"from": "pn", "to": "wn"})
        states["phone"]["state"].update(owner="wn", generation=2, outbox=grant)
        if received:
            states["watch"]["state"].update(owner="wn", generation=2, acceptedGrant=grant)
        return states

    def test_pending_transfer_recovers_lost_grant_and_lost_ack(self):
        for received in (False, True):
            states = self.pending_states(received)
            with self.subTest(received=received), patch.object(benchctl, "read", side_effect=list(states.values())):
                self.assertEqual(comparison.pending_transfer_source(self.devices, self.configs, "watch"), "phone")

    def test_pending_transfer_rejects_operation_wrong_peer_and_conflict(self):
        for mutation in (dict(operation="unknown"), dict(owner="other"), dict(generation=4), dict(outbox={"id": "another"})):
            states = self.pending_states()
            states["watch"]["state"].update(mutation)
            with self.subTest(mutation=mutation), patch.object(benchctl, "read", side_effect=list(states.values())):
                with self.assertRaises(RuntimeError):
                    comparison.pending_transfer_source(self.devices, self.configs, "watch")

    def test_pending_transfer_cannot_redirect_or_change_session(self):
        states = self.pending_states()
        with patch.object(benchctl, "read", side_effect=list(states.values())):
            with self.assertRaises(RuntimeError):
                comparison.pending_transfer_source(self.devices, self.configs, "phone")
        states["phone"]["state"]["outbox"]["session"] = "stale"
        with patch.object(benchctl, "read", side_effect=list(states.values())):
            with self.assertRaises(RuntimeError):
                comparison.pending_transfer_source(self.devices, self.configs, "watch")

    def test_resume_uses_existing_grant_not_new_transfer(self):
        with patch.object(comparison, "settled_owner", side_effect=[RuntimeError("pending"), "watch"]), \
                patch.object(comparison, "pending_transfer_source", return_value="phone"), \
                patch.object(benchctl, "command") as command, patch.object(benchctl, "write") as write:
            comparison.move_owner(self.devices, self.configs, "watch")
            command.assert_called_once_with("p", "refresh")
            write.assert_not_called()

    def test_link_gap_retransmits_without_incrementing_generation(self):
        with patch.object(comparison, "settled_owner", side_effect=["phone", RuntimeError("gap"), "watch"]), \
                patch.object(comparison, "pending_transfer_source", return_value="phone"), \
                patch.object(benchctl, "command") as command, patch.object(benchctl, "write") as write, \
                patch.object(comparison.time, "monotonic", side_effect=[0, 1, 4, 4, 5]), \
                patch.object(comparison.time, "sleep"):
            comparison.move_owner(self.devices, self.configs, "watch")
            self.assertEqual([call.args for call in command.call_args_list], [("p", "transfer"), ("p", "refresh")])
            write.assert_not_called()

    def test_disabled_package_can_take_time_to_stop_its_process(self):
        with patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", side_effect=["123", "123", ""]), \
                patch.object(comparison.time, "sleep") as sleep:
            comparison.wait_for_isolation("p")
            self.assertEqual(2, sleep.call_count)

    def test_persistent_process_or_enabled_package_cannot_pass_isolation(self):
        with patch.object(benchctl, "enabled_state", return_value=3), \
                patch.object(benchctl, "adb", return_value="123"), \
                patch.object(comparison.time, "monotonic", side_effect=[0, 9]):
            with self.assertRaises(RuntimeError):
                comparison.wait_for_isolation("p")
        with patch.object(benchctl, "enabled_state", return_value=0), patch.object(benchctl, "adb") as adb:
            with self.assertRaises(RuntimeError):
                comparison.wait_for_isolation("p")
            adb.assert_not_called()

    def run_comparison(self, outcomes, previous=0, cleanup_failure=False):
        enabled = [previous]
        probed = []
        calls = []

        def adb(serial, *args, **kwargs):
            calls.append((serial, *args))
            if args[:3] == ("shell", "pm", "disable-user"):
                enabled[0] = 3
            elif args[:3] in (("shell", "pm", "default-state"), ("shell", "pm", "enable")):
                enabled[0] = previous
            elif "rm" in args and cleanup_failure:
                raise subprocess.TimeoutExpired("adb", 30)
            return "123" if "pidof" in args and enabled[0] != 3 else ""

        def probe(args, serial, stage):
            outcome = outcomes[len(probed)]
            probed.append(serial)
            if isinstance(outcome, Exception):
                raise outcome
            stage["result"] = dict(stage=outcome, applicationBytesSent=0)
            stage["attempts"].append(copy.deepcopy(stage["result"]))

        with patch.object(comparison, "configs_for", return_value=(self.devices, self.configs)), \
                patch.object(comparison, "move_owner") as move, \
                patch.object(benchctl, "enabled_state", side_effect=lambda serial: enabled[0]), \
                patch.object(benchctl, "adb", side_effect=adb), patch.object(benchctl, "probe_attempts", side_effect=probe), \
                patch.object(comparison.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            try:
                result = comparison.compare(self.args)
            except Exception as error:
                result = error
            return result, probed, calls, move.call_args_list

    def test_watch_failure_still_checks_phone_return_and_restores_aaps(self):
        result, probed, calls, moves = self.run_comparison([OK, FAIL, OK])
        self.assertEqual(["p", "w", "p"], probed)
        self.assertEqual("WATCH_SOCKET_FAILED_BETWEEN_SUCCESSFUL_PHONE_PROBES", result["conclusion"])
        self.assertEqual(0, result["restoredEnabledState"])
        self.assertIn(("p", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS), calls)
        self.assertEqual("phone", moves[-1].args[-1])

    def test_failed_initial_baseline_does_not_probe_watch(self):
        result, probed, _, _ = self.run_comparison([FAIL])
        self.assertEqual(["p"], probed)
        self.assertEqual("INCONCLUSIVE_PHONE_BASELINE", result["conclusion"])

    def test_failed_return_baseline_does_not_claim_watch_rejection(self):
        result, _, _, _ = self.run_comparison([OK, FAIL, FAIL])
        self.assertEqual("INCONCLUSIVE_PHONE_RETURN", result["conclusion"])

    def test_success_does_not_claim_authorization_or_pump_ownership(self):
        result, _, _, _ = self.run_comparison([OK, OK, OK])
        self.assertEqual("SOCKETS_OPENED_ON_BOTH_AUTHORIZATION_NOT_TESTED", result["conclusion"])
        self.assertFalse(result["pairingChanged"])
        self.assertFalse(result["aapsPumpConnectionVerified"])

    def test_unknown_watch_result_aborts_without_more_probes_but_restores_aaps(self):
        result, probed, calls, _ = self.run_comparison([OK, RuntimeError("unknown outcome")])
        self.assertIsInstance(result, RuntimeError)
        self.assertEqual(["p", "w"], probed)
        self.assertIn(("p", "shell", "pm", "default-state", "--user", "0", benchctl.AAPS), calls)

    def test_permit_cleanup_failure_does_not_skip_aaps_restoration(self):
        result, _, calls, _ = self.run_comparison([OK, FAIL, OK], previous=1, cleanup_failure=True)
        self.assertEqual(dict(phone=False, watch=False), result["permitsRetired"])
        self.assertEqual(1, result["restoredEnabledState"])
        self.assertIn(("p", "shell", "pm", "enable", "--user", "0", benchctl.AAPS), calls)

    def test_preexisting_disabled_aaps_is_not_touched(self):
        result, probed, calls, _ = self.run_comparison([], previous=3)
        self.assertIsInstance(result, RuntimeError)
        self.assertEqual([], probed)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
