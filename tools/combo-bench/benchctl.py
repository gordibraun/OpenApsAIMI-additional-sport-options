#!/usr/bin/env python3
"""Provision/inspect the isolated diagnostic APK. Never sends pump commands."""

import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "app.aaps.combobench"
AAPS = "info.nightscout.androidaps"
ADB = os.environ.get("ADB", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))


def adb(serial, *args, check=True):
    return subprocess.run([ADB, "-s", serial, *args], text=True, capture_output=True,
                          timeout=30, check=check).stdout.strip()


def read(serial, name):
    return json.loads(adb(serial, "shell", "run-as", PACKAGE, "cat", f"files/{name}"))


def write(serial, name, value):
    # Stage generated JSON outside the app, then replace its private file atomically.
    temporary = f"/data/local/tmp/combo-bench-{uuid.uuid4().hex}.json"
    try:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / name
            path.write_text(json.dumps(value), encoding="utf-8")
            adb(serial, "push", str(path), temporary)
        adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files")
        adb(serial, "shell", "run-as", PACKAGE, "cp", temporary, f"files/{name}.new")
        adb(serial, "shell", "run-as", PACKAGE, "chmod", "600", f"files/{name}.new")
        adb(serial, "shell", "run-as", PACKAGE, "mv", f"files/{name}.new", f"files/{name}")
    finally:
        adb(serial, "shell", "rm", "-f", temporary, check=False)
    if read(serial, name) != value:
        raise RuntimeError("Provisioned data failed verification")


def verify_device(serial, expected):
    actual = adb(serial, "shell", "getprop", "ro.serialno")
    if actual != expected:
        raise RuntimeError(f"Wrong device: expected {expected}, got {actual}")


def pump_identity(phone):
    # Only identity fields leave the phone. Pairing keys are not read or copied.
    rows = adb(phone, "shell", "run-as", AAPS, "grep", "-E",
               "'name=\"combov2-(bt-address|pump-id)-key\"'",
               f"shared_prefs/{AAPS}_preferences.xml")
    root = ET.fromstring(f"<map>{rows}</map>")
    values = {node.attrib["name"]: node.text for node in root}
    return values["combov2-pump-id-key"], values["combov2-bt-address-key"]


def command(serial, action):
    return adb(serial, "shell", "am", "broadcast", "-n", f"{PACKAGE}/.BenchCommandReceiver",
               "--es", "command", action)


def enabled_state(phone):
    package = adb(phone, "shell", "dumpsys", "package", AAPS)
    match = re.search(r"^\s*User 0:.*\benabled=(\d+)", package, re.MULTILINE)
    if not match:
        raise RuntimeError("Cannot determine AAPS package state")
    return int(match.group(1))


def bluetooth_state(serial):
    # Vendor dumps can contain binary snoop data. Parse only the adapter state.
    data = subprocess.run([ADB, "-s", serial, "shell", "dumpsys", "bluetooth_manager"],
                          capture_output=True, timeout=15, check=True).stdout
    state = re.search(rb"(?m)^\s*state: (ON|OFF|TURNING_ON|TURNING_OFF|BLE_ON|BLE_TURNING_ON|BLE_TURNING_OFF)\s*$", data)
    if not state:
        raise RuntimeError("Cannot determine Bluetooth adapter state")
    return state[1].decode("ascii")


def set_bluetooth(serial, enabled, allow_ble_only=False):
    expected = "ON" if enabled else "OFF"
    accepted = {expected, "BLE_ON"} if not enabled and allow_ble_only else {expected}
    if bluetooth_state(serial) in accepted:
        return
    adb(serial, "shell", "cmd", "bluetooth_manager", "enable" if enabled else "disable")
    # The vendor wait-for-state command can fail while a valid transition is still in progress.
    deadline = time.monotonic() + 20
    while bluetooth_state(serial) not in accepted:
        if time.monotonic() >= deadline:
            raise RuntimeError("Bluetooth adapter state was not confirmed")
        time.sleep(0.25)


def validate_test_pump(args, target):
    if not args.off_body_confirmed or not args.pump_id:
        raise RuntimeError("Explicit off-body test pump identity is required")
    config = read(target, "config.json")
    pump, address = pump_identity(args.phone)
    if pump != f"PUMP_{args.pump_id}" or pump != config["pump"] or address != config["address"]:
        raise RuntimeError("Pump identity mismatch")
    state = read(target, "ownership.json")["state"]
    if state["owner"] != config["local"] or state.get("operation"):
        raise RuntimeError("Target is not the idle diagnostic owner")
    return config


def permit_deadline(target):
    # The APK checks its own clock; host/watch skew must not extend the permit.
    seconds = adb(target, "shell", "date", "+%s")
    if not re.fullmatch(r"[0-9]{10,11}", seconds):
        raise RuntimeError("Cannot determine target clock for the diagnostic permit")
    return int(seconds) * 1000 + 120_000


def arm(args, target):
    transport = probe_transport(args)
    timeout_seconds = probe_timeout(args)
    hold_seconds = probe_hold(args)
    config = validate_test_pump(args, target)
    if enabled_state(args.phone) != 3 or adb(args.phone, "shell", "pidof", AAPS, check=False):
        raise RuntimeError("AAPS must be temporarily disabled; force-stop alone is insufficient")
    write(target, "arm.json", dict(session=config["session"], pump=config["pump"], offBody=True,
                                   aapsStopped=True, aapsDisabled=True, transport=transport,
                                   timeoutSeconds=timeout_seconds, holdSeconds=hold_seconds,
                                   expiresAt=permit_deadline(target)))


def probe_transport(args):
    transport = getattr(args, "transport", "sdp")
    if transport not in ("sdp", "channel1", "sdp-authenticated", "channel1-authenticated"):
        raise RuntimeError("Unknown diagnostic transport")
    return transport


def probe_timeout(args):
    seconds = getattr(args, "probe_timeout_seconds", 8)
    if type(seconds) is not int or seconds not in (8, 20):
        raise RuntimeError("Only 8 or 20 seconds are allowed for a diagnostic socket")
    return seconds


def probe_hold(args):
    seconds = getattr(args, "probe_hold_seconds", 0)
    if type(seconds) is not int or seconds not in (0, 5):
        raise RuntimeError("Only 0 or 5 seconds are allowed for an open socket hold")
    if seconds and (probe_timeout(args) != 8 or not probe_transport(args).endswith("-authenticated")):
        raise RuntimeError("Socket hold requires the standard authenticated probe")
    return seconds


def probe_attempts(args, target, evidence):
    transport = probe_transport(args)
    timeout_seconds = probe_timeout(args)
    hold_seconds = probe_hold(args)
    old_result = read(target, "status.json").get("probe", {})
    attempts = 1 if timeout_seconds != 8 or transport in ("sdp-authenticated", "channel1-authenticated") else getattr(args, "attempts", 1)
    for index in range(attempts):
        if index:
            time.sleep(0.6)
        arm(args, target)
        print(command(target, "probe"), flush=True)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            status = read(target, "status.json")
            probe = status.get("probe", {})
            if probe.get("at") != old_result.get("at") and probe.get("stage") == "RFCOMM_PAIRING_BLOCKED":
                evidence["result"] = probe
                evidence["attempts"].append(probe)
                raise RuntimeError("Pairing was requested or changed; diagnostic ownership remains locked, no retry")
            if status.get("error"):
                raise RuntimeError(status["error"])
            if (probe.get("at") != old_result.get("at") and
                    probe.get("stage") in {"RFCOMM_OK_ZERO_BYTES", "RFCOMM_FAILED", "RFCOMM_TIMEOUT"} and
                    not status.get("ownership", {}).get("operation")):
                if probe.get("transport", "sdp") != transport:
                    raise RuntimeError("The APK used a different transport; update the bench APK")
                if probe.get("timeoutSeconds", 8) != timeout_seconds:
                    raise RuntimeError("The APK used a different deadline; update the bench APK")
                if probe.get("holdSeconds", 0) != hold_seconds:
                    raise RuntimeError("The APK used a different hold duration; update the bench APK")
                if hold_seconds and probe["stage"] == "RFCOMM_OK_ZERO_BYTES" and (
                        probe.get("holdCompleted") is not True or
                        not isinstance(probe.get("holdElapsedMs"), (int, float)) or
                        not 5000 <= probe["holdElapsedMs"] < 7000 or
                        probe.get("applicationBytesSent") != 0):
                    raise RuntimeError("The APK did not confirm the bounded zero-byte hold")
                evidence["result"] = probe
                evidence["attempts"].append(probe)
                old_result = probe
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("No completed diagnostic result received")
        if probe["stage"] == "RFCOMM_OK_ZERO_BYTES":
            break


def isolated_probe(args, target):
    transport = probe_transport(args)
    probe_timeout(args)
    probe_hold(args)
    attempts = getattr(args, "attempts", 1)
    if attempts not in range(1, 6):
        raise RuntimeError("Only one to five socket attempts are allowed")
    config = validate_test_pump(args, target)
    isolate_radio = getattr(args, "phone_bluetooth_off", False)
    if isolate_radio and (args.target != "watch" or bluetooth_state(args.phone) != "ON"):
        raise RuntimeError("Phone radio isolation requires a watch probe and an initially enabled phone radio")
    previous = enabled_state(args.phone)
    if previous not in (0, 1):
        raise RuntimeError("AAPS was already disabled; refusing to change its previous state")
    was_running = bool(adb(args.phone, "shell", "pidof", AAPS, check=False))
    evidence = dict(pump=config["pump"], target=args.target, startedAt=int(time.time() * 1000),
                    originalEnabledState=previous, originalRunning=was_running, attempts=[], transport=transport)
    try:
        print(adb(args.phone, "shell", "pm", "disable-user", "--user", "0", AAPS), flush=True)
        # The previous controller's socket and the pump's session need time to close.
        time.sleep(8)
        evidence["settlingDelaySeconds"] = 8
        if isolate_radio:
            if enabled_state(args.phone) != 3 or adb(args.phone, "shell", "pidof", AAPS, check=False):
                raise RuntimeError("AAPS isolation must be confirmed before changing its Bluetooth radio")
            set_bluetooth(args.phone, False, allow_ble_only=True)
            evidence["phoneBluetoothDuringProbe"] = bluetooth_state(args.phone)
            if evidence["phoneBluetoothDuringProbe"] not in ("OFF", "BLE_ON"):
                raise RuntimeError("Phone Classic Bluetooth is not isolated")
        probe_attempts(args, target, evidence)
    finally:
        try:
            adb(target, "shell", "run-as", PACKAGE, "rm", "-f", "files/arm.json")
            evidence["armRetired"] = True
        except subprocess.SubprocessError:
            evidence["armRetired"] = False
        if isolate_radio:
            try:
                set_bluetooth(args.phone, True)
                evidence["phoneBluetoothRestored"] = bluetooth_state(args.phone)
            except Exception as error:
                # A failed radio restoration must never skip restoration of the AAPS package.
                evidence["radioRestorationError"] = type(error).__name__
        # Restore the exact enabled state, even if the probe or its ADB channel failed.
        restore = "default-state" if previous == 0 else "enable"
        print(adb(args.phone, "shell", "pm", restore, "--user", "0", AAPS), flush=True)
        if enabled_state(args.phone) != previous:
            raise RuntimeError("AAPS restoration failed; manual recovery required")
        evidence["restoredEnabledState"] = previous
        if was_running:
            adb(args.phone, "shell", "am", "start", "-n", f"{AAPS}/app.aaps.MainActivity")
        evidence["finishedAt"] = int(time.time() * 1000)
        if args.evidence_dir:
            directory = Path(args.evidence_dir)
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"{args.target}-{evidence['startedAt']}.json"
            path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            path.chmod(0o600)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    if evidence.get("radioRestorationError"):
        raise RuntimeError("Phone Bluetooth restoration is unconfirmed; inspect the phone before continuing")


def pairing_arm(args, target):
    if args.target != "watch" or not getattr(args, "allow_replace_pairing", False):
        raise RuntimeError("Explicit approval to replace test pump pairing with the watch is required")
    config = validate_test_pump(args, target)
    if enabled_state(args.phone) != 3 or adb(args.phone, "shell", "pidof", AAPS, check=False):
        raise RuntimeError("AAPS must be disabled during pairing")
    write(target, "pairing-arm.json", dict(session=config["session"], pump=config["pump"],
          offBody=True, aapsDisabled=True, allowReplacePairing=True,
          expiresAt=permit_deadline(target)))


def manual_pairing_status(target):
    try:
        return read(target, "manual-pairing.json")
    except subprocess.CalledProcessError:
        if "manual-pairing.json" not in adb(target, "shell", "run-as", PACKAGE, "ls", "files").splitlines():
            return {}
        raise


def isolated_pair(args, target):
    if args.target != "watch" or not getattr(args, "allow_replace_pairing", False):
        raise RuntimeError("Explicit approval to replace test pump pairing with the watch is required")
    if getattr(args, "attempts", 1) not in range(1, 6):
        raise RuntimeError("Only one to five socket attempts are allowed")
    transport = probe_transport(args)
    config = validate_test_pump(args, target)
    previous = enabled_state(args.phone)
    if previous not in (0, 1):
        raise RuntimeError("AAPS was already disabled; refusing to change its previous state")
    was_running = bool(adb(args.phone, "shell", "pidof", AAPS, check=False))
    manual = getattr(args, "action", "") == "manual-pair"
    old_pair = (manual_pairing_status(target) if manual else read(target, "status.json").get("pairing", {})).get("id")
    evidence = dict(pump=config["pump"], target=args.target, startedAt=int(time.time() * 1000),
                    kind="manual-combo-pairing" if manual else "bluetooth-pairing-only", originalEnabledState=previous,
                    originalRunning=was_running, attempts=[], transport=transport)
    try:
        print(adb(args.phone, "shell", "pm", "disable-user", "--user", "0", AAPS), flush=True)
        time.sleep(8)
        if manual:
            print("AAPS isolated. Waiting for the user to start and enter the PIN on the watch (up to 10 minutes).", flush=True)
        else:
            pairing_arm(args, target)
            adb(target, "shell", "am", "start", "-n", f"{PACKAGE}/.BenchActivity")
            print(command(target, "pairing-start"), flush=True)
        deadline = time.monotonic() + (600 if manual else 210)
        last_stage = None
        while time.monotonic() < deadline:
            status = read(target, "status.json")
            pair = manual_pairing_status(target) if manual else status.get("pairing", {})
            if pair.get("id") and pair.get("id") != old_pair:
                evidence["pairing"] = pair
                if pair.get("stage") != last_stage:
                    print(json.dumps(pair, ensure_ascii=False), flush=True)
                    last_stage = pair.get("stage")
                if not pair.get("active", True):
                    if not manual and pair.get("stage") == "BONDED" and not status.get("ownership", {}).get("operation"):
                        probe_attempts(args, target, evidence)
                    break
            if status.get("error"):
                raise RuntimeError(status["error"])
            time.sleep(1)
        else:
            raise RuntimeError("Pairing outcome is unknown; do not automatically retry")
    finally:
        # Stop our listener even when the host loses an ADB link. Android bonding
        # itself may still be unresolved, which remains locked in the APK.
        try:
            command(target, "manual-pairing-stop" if manual else "pairing-stop")
            if manual:
                stop_deadline = time.monotonic() + 20
                while manual_pairing_status(target).get("active") and time.monotonic() < stop_deadline:
                    time.sleep(0.5)
                evidence["pairingAfterStop"] = manual_pairing_status(target)
                if evidence["pairingAfterStop"].get("active"):
                    adb(target, "shell", "am", "force-stop", PACKAGE)
                    evidence["benchForceStoppedForCleanup"] = True
            adb(target, "shell", "run-as", PACKAGE, "rm", "-f", "files/pairing-arm.json", "files/arm.json")
            evidence["permitsRetired"] = True
        except subprocess.SubprocessError:
            evidence["permitsRetired"] = False
        restore = "default-state" if previous == 0 else "enable"
        print(adb(args.phone, "shell", "pm", restore, "--user", "0", AAPS), flush=True)
        if enabled_state(args.phone) != previous:
            raise RuntimeError("AAPS restoration failed; manual recovery required")
        evidence["restoredEnabledState"] = previous
        if was_running:
            adb(args.phone, "shell", "am", "start", "-n", f"{AAPS}/app.aaps.MainActivity")
        evidence["finishedAt"] = int(time.time() * 1000)
        evidence["aapsPumpConnectionVerified"] = False
        if args.evidence_dir:
            directory = Path(args.evidence_dir)
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"pairing-{args.target}-{evidence['startedAt']}.json"
            path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            path.chmod(0o600)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["provision", "status", "discover", "refresh", "transfer", "probe", "arm", "isolated-probe", "isolated-pair", "manual-pair"])
    parser.add_argument("--phone", required=True)
    parser.add_argument("--watch", required=True)
    parser.add_argument("--phone-id", default="RRCX807YMBY")
    parser.add_argument("--watch-id", default="H631104000081G1A00V2")
    parser.add_argument("--target", choices=["phone", "watch"], default="phone")
    parser.add_argument("--pump-id")
    parser.add_argument("--off-body-confirmed", action="store_true")
    parser.add_argument("--allow-replace-pairing", action="store_true")
    parser.add_argument("--evidence-dir")
    parser.add_argument("--transport", choices=["sdp", "channel1", "sdp-authenticated", "channel1-authenticated"], default="sdp")
    parser.add_argument("--probe-timeout-seconds", type=int, choices=(8, 20), default=8,
                        help="Single-use socket deadline; 20 seconds forces one attempt, no automatic retry")
    parser.add_argument("--probe-hold-seconds", type=int, choices=(0, 5), default=0,
                        help="Single-use zero-byte hold after opening; requires authenticated transport and the 8-second deadline")
    parser.add_argument("--phone-bluetooth-off", action="store_true",
                        help="For an isolated watch probe: disable phone Classic Bluetooth and restore it; BLE-only may remain")
    parser.add_argument("--attempts", type=int, choices=range(1, 6), default=1,
                        help="Socket-only attempts within one AAPS isolation window; stop on first success")
    args = parser.parse_args()
    if args.phone_bluetooth_off and (args.action != "isolated-probe" or args.target != "watch"):
        parser.error("--phone-bluetooth-off requires isolated-probe --target watch")
    devices = {"phone": args.phone, "watch": args.watch}
    expected = {"phone": args.phone_id, "watch": args.watch_id}
    target = devices[args.target]

    if args.action == "status":
        result = {}
        for role, serial in devices.items():
            try:
                verify_device(serial, expected[role])
                result[role] = read(serial, "status.json")
            except (subprocess.SubprocessError, ValueError, RuntimeError) as error:
                result[role] = {"unavailable": str(error)}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    elif args.action == "provision":
        for role, serial in devices.items():
            verify_device(serial, expected[role])
        if not args.off_body_confirmed or not args.pump_id:
            raise RuntimeError("Explicit off-body test pump identity is required")
        pump, address = pump_identity(args.phone)
        if pump != f"PUMP_{args.pump_id}":
            raise RuntimeError("AAPS is paired to a different pump")
        statuses = {role: read(serial, "status.json") for role, serial in devices.items()}
        phone_node = statuses["phone"]["localNode"]
        watch_node = statuses["watch"]["localNode"]
        if not phone_node or not watch_node or phone_node == watch_node:
            raise RuntimeError("Distinct live Wear node IDs are required")
        for role, other in [("phone", "watch"), ("watch", "phone")]:
            if statuses[other]["localNode"] not in [node["id"] for node in statuses[role]["peers"]]:
                raise RuntimeError("The expected peer is not connected")
        for serial in devices.values():
            listing = adb(serial, "shell", "run-as", PACKAGE, "ls", "files")
            if any(name in listing.splitlines() for name in ["config.json", "ownership.json", "ownership.json.bak"]):
                raise RuntimeError("Refusing to overwrite an existing bench session")
        session = str(uuid.uuid4())
        for role, other in [("phone", "watch"), ("watch", "phone")]:
            config = dict(protocol=1, diagnosticOnly=True, session=session, pump=pump,
                          address=address, local=statuses[role]["localNode"], peer=statuses[other]["localNode"])
            ownership = {key: config[key] for key in ["session", "pump", "local", "peer"]}
            ownership["state"] = dict(owner=phone_node, generation=0)
            write(devices[role], "ownership.json", ownership)
            write(devices[role], "config.json", config)
        print(json.dumps(dict(session=session, pump=pump, address=address, owner="phone")))
    elif args.action in ("arm", "isolated-probe", "isolated-pair", "manual-pair"):
        verify_device(args.phone, args.phone_id)
        if args.target != "phone":
            verify_device(target, expected[args.target])
        if args.action == "arm":
            arm(args, target)
            print("One socket-only probe armed for 120 seconds; zero application bytes allowed")
        elif args.action == "isolated-probe":
            isolated_probe(args, target)
        else:
            isolated_pair(args, target)
    else:
        verify_device(target, expected[args.target])
        print(command(target, args.action))


if __name__ == "__main__":
    main()
