#!/usr/bin/env python3
"""One explicitly approved, off-body control handshake on the watch's own pairing.

No service activation, therapy commands, key transfer, pairing or automatic retry.
"""
import argparse
import json
from pathlib import Path
import re
import select
import shlex
import sys
import time
import uuid

import benchctl as bench
import compare_transport as ownership

RESTART_WAIT_SECONDS = 120
RECOVERY_SECONDS = 240


def recovery_command(marker, previous, was_running):
    if previous not in (0, 1) or not re.fullmatch(r"control-recovery-[0-9a-f-]{36}\.json", marker):
        raise RuntimeError("Invalid recovery identity or original enabled state")
    path = "files/" + marker
    restore = "default-state" if previous == 0 else "enable"
    test = shlex.join(["run-as", bench.PACKAGE, "test", "-f", path])
    enable = shlex.join(["pm", restore, "--user", "0", bench.AAPS])
    start = shlex.join(["am", "start", "-n", f"{bench.AAPS}/app.aaps.MainActivity"])
    retire = shlex.join(["run-as", bench.PACKAGE, "rm", "-f", path])
    action = f"sleep {RECOVERY_SECONDS}; if {test}; then {enable}"
    if was_running:
        action += f" && {start}"
    action += f" && {retire}; fi"
    return "nohup sh -c " + shlex.quote(action) + " >/dev/null 2>&1 < /dev/null & echo $!"


def schedule_recovery(phone, attempt, previous, was_running):
    marker = f"control-recovery-{attempt}.json"
    bench.write(phone, marker, dict(id=attempt, originalEnabledState=previous, originalRunning=was_running))
    try:
        pid = bench.adb(phone, "shell", recovery_command(marker, previous, was_running))
        if not re.fullmatch(r"[1-9][0-9]*", pid):
            raise RuntimeError("Recovery process PID not confirmed")
        process = bench.adb(phone, "shell", "cat", f"/proc/{pid}/cmdline")
        if marker not in process or f"sleep {RECOVERY_SECONDS}" not in process:
            raise RuntimeError("Independent phone recovery process not confirmed")
        return marker, pid
    except Exception:
        bench.adb(phone, "shell", "run-as", bench.PACKAGE, "rm", "-f", "files/" + marker)
        raise


def wait_for_pump_restart():
    print("READY_FOR_MANUAL_PUMP_RESTART: AAPS isolated; send RESTARTED within 120 seconds. No pump connection yet.", flush=True)
    available, _, _ = select.select([sys.stdin], [], [], RESTART_WAIT_SECONDS)
    if not available or sys.stdin.readline().strip() != "RESTARTED":
        raise RuntimeError("Pump restart not confirmed in time; no connection attempted")


def validate_result(result, attempt):
    if result.get("id") != attempt or result.get("complete") is not True:
        raise RuntimeError("No complete result for this attempt")
    if (result.get("mode") != "control-handshake-only" or result.get("apkVersionCode") != 18 or
            result.get("therapyCommandsSent") != 0 or result.get("serviceActivationsSent") != 0):
        raise RuntimeError("Unexpected diagnostic mode or build")
    if result.get("success") is True and not (
            result.get("handshakeCompleted") is True and result.get("disconnectWritten") is True and
            result.get("socketClosed") is True and result.get("ownershipLocked") is False and
            result.get("watchdogExpired") is False and result.get("bondState") == 12):
        raise RuntimeError("Contradictory control-session success")


def wait_for_aaps_process(phone):
    # `am start` returns before Android necessarily creates the app process.
    deadline = time.monotonic() + 8
    while True:
        if bench.adb(phone, "shell", "pidof", bench.AAPS, check=False):
            return True
        if time.monotonic() >= deadline:
            raise RuntimeError("AAPS process restart was not verified")
        time.sleep(0.25)


def run(args):
    devices, configs = ownership.configs_for(args)
    previous_owner = ownership.settled_owner(devices, configs)
    previous = bench.enabled_state(args.phone)
    if previous not in (0, 1):
        raise RuntimeError("AAPS is already disabled; refusing to change its state")
    package = bench.adb(args.watch, "shell", "dumpsys", "package", bench.PACKAGE)
    if not re.search(r"\bversionCode=18\b", package):
        raise RuntimeError("The control-session watch build is not installed")
    was_running = bool(bench.adb(args.phone, "shell", "pidof", bench.AAPS, check=False))
    attempt = str(uuid.uuid4())
    evidence = dict(id=attempt, mode="control-handshake-only", startedAt=int(time.time() * 1000),
                    originalEnabledState=previous, originalRunning=was_running, originalOwner=previous_owner,
                    pump="PUMP_41056642", session=configs["watch"]["session"])
    directory = Path(args.evidence_dir)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    path = directory / f"control-session-{evidence['startedAt']}.json"

    def save():
        with path.open("w", encoding="utf-8") as output:
            path.chmod(0o600)
            json.dump(evidence, output, ensure_ascii=False, indent=2)

    save()
    recovery_marker = None
    recovery_started = None
    try:
        ownership.move_owner(devices, configs, "watch")
        if getattr(args, "wait_for_pump_restart", False):
            recovery_started = time.monotonic()
            recovery_marker, pid = schedule_recovery(args.phone, attempt, previous, was_running)
            evidence["phoneRecovery"] = dict(pid=pid, marker=recovery_marker, afterSeconds=RECOVERY_SECONDS)
            save()
        bench.adb(args.phone, "shell", "pm", "disable-user", "--user", "0", bench.AAPS)
        ownership.wait_for_isolation(args.phone)
        time.sleep(8)
        ownership.wait_for_isolation(args.phone)
        if getattr(args, "wait_for_pump_restart", False):
            wait_for_pump_restart()
            evidence["manualPumpRestartConfirmedAt"] = int(time.time() * 1000)
            save()
            ownership.wait_for_isolation(args.phone)
        config = bench.validate_test_pump(args, args.watch)
        permit = dict(id=attempt, session=config["session"], pump=config["pump"],
                      mode="control-handshake-only", expiresAt=bench.permit_deadline(args.watch),
                      offBody=True, aapsStopped=True, aapsDisabled=True,
                      ownWatchPairingOnly=True, noTherapyOrServices=True)
        bench.write(args.watch, "control-session-arm.json", permit)
        if recovery_started is not None and time.monotonic() - recovery_started >= RECOVERY_SECONDS - 90:
            raise RuntimeError("Too little isolation time remains; no connection attempted")
        ownership.wait_for_isolation(args.phone)
        bench.command(args.watch, "control-session")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                result = bench.read(args.watch, "control-session.json")
            except (ValueError, bench.subprocess.SubprocessError):
                time.sleep(1)
                continue
            if result.get("id") == attempt:
                evidence["result"] = result
                save()
                if result.get("complete") is True:
                    validate_result(result, attempt)
                    return evidence
            time.sleep(1)
        raise RuntimeError("Control-session outcome unknown; no automatic retry")
    finally:
        try:
            bench.adb(args.watch, "shell", "run-as", bench.PACKAGE, "rm", "-f", "files/control-session-arm.json")
            evidence["permitRetired"] = True
        except Exception as error:
            evidence["permitCleanupError"] = type(error).__name__
        # Restoration is independent of ownership and diagnostic errors.
        try:
            restore = "default-state" if previous == 0 else "enable"
            bench.adb(args.phone, "shell", "pm", restore, "--user", "0", bench.AAPS)
            if bench.enabled_state(args.phone) != previous:
                raise RuntimeError("AAPS enabled-state restoration was not verified")
            evidence["restoredEnabledState"] = previous
            if was_running:
                bench.adb(args.phone, "shell", "am", "start", "-n", f"{bench.AAPS}/app.aaps.MainActivity")
                evidence["aapsProcessRunning"] = wait_for_aaps_process(args.phone)
            if recovery_marker:
                bench.adb(args.phone, "shell", "run-as", bench.PACKAGE, "rm", "-f", "files/" + recovery_marker)
                evidence["phoneRecoveryRetiredAfterRestoration"] = True
        finally:
            try:
                ownership.move_owner(devices, configs, previous_owner)
                evidence["restoredOwner"] = previous_owner
            except Exception as error:
                evidence["ownerRestorationError"] = type(error).__name__
            evidence["aapsPumpConnectionVerified"] = False
            evidence["finishedAt"] = int(time.time() * 1000)
            save()
            print(json.dumps(evidence, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--watch", required=True)
    parser.add_argument("--phone-id", default="RRCX807YMBY")
    parser.add_argument("--watch-id", default="H631104000081G1A00V2")
    parser.add_argument("--pump-id", required=True, choices=["41056642"])
    parser.add_argument("--off-body-confirmed", action="store_true", required=True)
    parser.add_argument("--evidence-dir", required=True)
    parser.add_argument("--wait-for-pump-restart", action="store_true",
                        help="Isolate AAPS, then await explicit manual restart confirmation; abort after 120 seconds")
    run(parser.parse_args())
