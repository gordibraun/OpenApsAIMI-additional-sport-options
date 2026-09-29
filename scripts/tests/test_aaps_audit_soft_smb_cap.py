import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("audit", Path(__file__).resolve().parents[1] / "aaps_audit_soft_smb_cap.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class SoftCapAuditTest(unittest.TestCase):
    def test_optimizer_penalty_only_curve_is_detected(self):
        logs = [f"Проверка варианта оптимизатора, не подача: {d} Е; оценка={200 + .5 * (1.125 - d) ** 2}" for d in (0, .3, .6, 1.2)]
        result = audit.optimizer_cost_audit({"consoleLog": logs})
        self.assertTrue(result["matches_penalty_only"])
        self.assertAlmostEqual(1.125, result["inferred_fixed_parameter"])
        self.assertAlmostEqual(200, result["constant_forecast_cost"])

    def test_varying_forecast_cost_does_not_match_penalty_only_curve(self):
        logs = [f"Проверка варианта оптимизатора, не подача: {d} Е; оценка={200 + d ** 3 + .5 * (1.125 - d) ** 2}" for d in (0, .3, .6, 1.2)]
        self.assertFalse(audit.optimizer_cost_audit({"consoleLog": logs})["matches_penalty_only"])

    def test_cap_requires_active_guard_evidence(self):
        self.assertIsNone(audit.parse_cap({"reason": "Final SMB: 0.30"}))
        self.assertIsNone(audit.parse_cap({"consoleLog": ["Защита от раннего перелива активна: лимит SMB=0,00U"]}))

    def test_locale_and_actual_cap_are_parsed_separately_from_final_request(self):
        result = {"consoleLog": ["Защита от раннего перелива активна: лимит SMB=0,30U"],
                  "reason": "SMB ограничен защитой от раннего перелива: 1,20 -> 0,30 (лимит 0,30U)", "units": 0.0}
        parsed = audit.parse_cap(result)
        self.assertEqual(1.2, parsed["before_cap"])
        self.assertEqual("explicit_limit", parsed["before_cap_source"])
        self.assertEqual(0.0, result["units"])

    def test_fallback_is_not_marked_as_observed_reduction(self):
        parsed = audit.parse_cap({"consoleLog": ["Защита от раннего перелива активна: лимит SMB=0.30U"], "reason": "Final SMB: 1.20 U"})
        self.assertEqual("executor_final", parsed["before_cap_source"])

    def test_cluster_counts_episodes_not_repeated_cycles(self):
        rows = [{"timestamp": n * audit.MINUTE} for n in (0, 5, 25, 60)]
        self.assertEqual([3, 1], list(map(len, audit.cluster(rows))))

    def test_missing_cgm_is_not_counted_as_normal_glucose(self):
        bg = audit.Timeline([{"timestamp": n * audit.MINUTE, "value": 110} for n in (0, 5, 60)])
        result = audit.window(bg, audit.Timeline([]), audit.Timeline([]), 0, 60)
        self.assertFalse(result["complete"])

    def test_followup_bolus_precedes_low_and_is_not_assumed_required(self):
        bg = audit.Timeline([{"timestamp": n * audit.MINUTE, "value": 65 if n >= 40 else 110} for n in range(0, 61, 5)])
        doses = audit.Timeline([{"timestamp": 20 * audit.MINUTE, "amount": 0.8, "type": "SMB"}])
        result = audit.window(bg, doses, audit.Timeline([]), 0, 60)
        self.assertTrue(result["complete"])
        self.assertEqual(65, result["min_bg"])
        self.assertFalse(result["low_before_new_bolus_or_carbs"])
        self.assertEqual(0.8, result["smb_u"])

    def test_high_before_any_new_treatment_is_distinguished(self):
        bg = audit.Timeline([{"timestamp": n * audit.MINUTE, "value": 185 if n >= 20 else 130} for n in range(0, 61, 5)])
        food = audit.Timeline([{"timestamp": 40 * audit.MINUTE, "amount": 10, "duration": 0}])
        result = audit.window(bg, audit.Timeline([]), food, 0, 60)
        self.assertTrue(result["high_before_new_bolus_or_carbs"])
        self.assertEqual(10, result["new_carbs_g"])


if __name__ == "__main__":
    unittest.main()
