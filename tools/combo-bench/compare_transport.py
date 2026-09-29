#!/usr/bin/env python3
"""Off-body phone/watch/phone socket comparison; no pairing or application packets."""

import argparse
import json
from pathlib import Path
import time

import benchctl as bench


def configs_for(args):
    if not args.off_body_confirmed or args.pump_id != "41056642":
        raise RuntimeError("Only the confirmed off-body Combo 41056642 is allowed")
    devices = {"phone": args.phone, "watch": args.watch}
    configs = {}
    for role, serial in devices.items():
        bench.verify_device(serial, getattr(args, f"{role}_id"))
        configs[role] = bench.read(serial, "config.json")
    phone, watch = configs["phone"], configs["watch"]
    pump, address = bench.pump_identity(args.phone)
    for config in configs.values():
        if (config.get("protocol") != 1 or config.get("diagnosticOnly") is not True or
                config.get("pump") != pump or pump != "PUMP_41056642" or
                config.get("address") != address or address.upper() != "00:0E:2F:25:24:BC"):
            raise RuntimeError("Bench and AAPS must identify the same test pump")
    if (not phone.get("session") or phone["session"] != watch.get("session") or
            not phone.get("local") or not watch.get("local") or
            phone["local"] == watch["local"] or phone["peer"] != watch["local"] or
            watch["peer"] != phone["local"]):
        raise RuntimeError("Bench session or peer identities do not match")
    settled_owner(devices, configs)
    return devices, configs


def settled_owner(devices, configs):
    states = {}
    for role, serial in devices.items():
        record = bench.read(serial, "ownership.json")
        if any(record.get(key) != configs[role][key] for key in ("session", "pump", "local", "peer")):
            raise RuntimeError("Ownership record does not match the configured session")
        state = record["state"]
        if state.get("operation") or state.get("outbox"):
            raise RuntimeError("Diagnostic operation or transfer is not settled")
        states[role] = state
    first, second = states["phone"], states["watch"]
    if (not isinstance(first.get("generation"), int) or first["generation"] < 0 or
            first["generation"] != second.get("generation") or first["owner"] != second["owner"]):
        raise RuntimeError("Diagnostic owners or generations disagree")
    for role, config in configs.items():
        if config["local"] == first["owner"]:
            return role
    raise RuntimeError("Unknown diagnostic owner")


def pending_transfer_source(devices, configs, target):
    states = {}
    for role, serial in devices.items():
        record = bench.read(serial, "ownership.json")
        if any(record.get(key) != configs[role][key] for key in ("session", "pump", "local", "peer")):
            raise RuntimeError("Pending transfer record does not match the configured session")
        states[role] = record["state"]
        if states[role].get("operation"):
            raise RuntimeError("An unresolved operation cannot be recovered as a transfer")
    sources = [role for role, state in states.items() if state.get("outbox")]
    if len(sources) != 1:
        raise RuntimeError("Exactly one existing transfer is required for recovery")
    source = sources[0]
    grant, origin = states[source]["outbox"], configs[source]
    expected = dict(session=origin["session"], pump=origin["pump"],
                    **{"from": origin["local"], "to": origin["peer"]})
    previous, generation = grant.get("previousGeneration"), grant.get("generation")
    if (source == target or grant.get("to") != configs[target]["local"] or not grant.get("id") or
            any(grant.get(key) != value for key, value in expected.items()) or
            type(previous) is not int or previous < 0 or type(generation) is not int or generation != previous + 1 or
            states[source]["generation"] != generation or states[source]["owner"] != grant["to"]):
        raise RuntimeError("Pending transfer is inconsistent or has a different destination")
    peer = states[target]
    not_received = peer["generation"] == previous and peer["owner"] == grant["from"]
    received = peer["generation"] == generation and peer["owner"] == grant["to"] and peer.get("acceptedGrant") == grant
    if not (not_received or received):
        raise RuntimeError("The receiver conflicts with the pending transfer")
    return source


def move_owner(devices, configs, target):
    try:
        source = settled_owner(devices, configs)
        if source == target:
            return
        action = "transfer"
    except RuntimeError:
        source = pending_transfer_source(devices, configs, target)
        action = "refresh"
    # The APK durably revokes before sending a grant. Never edit ownership files here.
    bench.command(devices[source], action)
    started = time.monotonic()
    deadline, next_retry = started + 15, started + 3
    while time.monotonic() < deadline:
        try:
            if settled_owner(devices, configs) == target:
                return
        except RuntimeError:
            pass
        if time.monotonic() >= next_retry:
            if pending_transfer_source(devices, configs, target) != source:
                raise RuntimeError("Pending transfer source changed")
            # refresh retransmits the durable grant; it never creates another grant/generation.
            bench.command(devices[source], "refresh")
            next_retry = time.monotonic() + 3
        time.sleep(0.25)
    raise RuntimeError("Diagnostic transfer was not confirmed; no probe will start")


def conclusion(stages):
    successful = lambda index: stages[index].get("result", {}).get("stage") == "RFCOMM_OK_ZERO_BYTES"
    if not stages or not successful(0):
        return "INCONCLUSIVE_PHONE_BASELINE"
    if len(stages) != 3 or not successful(2):
        return "INCONCLUSIVE_PHONE_RETURN"
    if successful(1):
        return "SOCKETS_OPENED_ON_BOTH_AUTHORIZATION_NOT_TESTED"
    if stages[1].get("result", {}).get("stage") in ("RFCOMM_FAILED", "RFCOMM_TIMEOUT"):
        return "WATCH_SOCKET_FAILED_BETWEEN_SUCCESSFUL_PHONE_PROBES"
    return "INCONCLUSIVE_WATCH_OUTCOME"


def wait_for_isolation(phone):
    deadline = time.monotonic() + 8
    while True:
        if bench.enabled_state(phone) != 3:
            raise RuntimeError("AAPS package isolation was not confirmed")
        if not bench.adb(phone, "shell", "pidof", bench.AAPS, check=False):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("AAPS process did not stop; no probe will start")
        time.sleep(0.25)


def compare(args):
    if args.attempts not in range(1, 4):
        raise RuntimeError("Only one to three socket attempts per stage are allowed")
    devices, configs = configs_for(args)
    previous = bench.enabled_state(args.phone)
    if previous not in (0, 1):
        raise RuntimeError("AAPS was already disabled; refusing to change it")
    was_running = bool(bench.adb(args.phone, "shell", "pidof", bench.AAPS, check=False))
    evidence = dict(kind="phone-watch-phone-socket-only", pump="PUMP_41056642",
                    session=configs["phone"]["session"], startedAt=int(time.time() * 1000),
                    originalEnabledState=previous, originalRunning=was_running,
                    pairingChanged=False, applicationBytesSent=0, stages=[])
    failure = None
    try:
        bench.adb(args.phone, "shell", "pm", "disable-user", "--user", "0", bench.AAPS)
        wait_for_isolation(args.phone)
        deadline = time.monotonic() + 180
        for label, role in (("phone-before", "phone"), ("watch", "watch"), ("phone-after", "phone")):
            if time.monotonic() >= deadline:
                raise RuntimeError("Comparison deadline reached; no additional probe will start")
            move_owner(devices, configs, role)
            time.sleep(8)
            stage = dict(label=label, target=role, attempts=[])
            evidence["stages"].append(stage)
            print(f"Stage: {label}; socket only, zero application bytes", flush=True)
            bench.probe_attempts(args, devices[role], stage)
            print(f"Result: {stage['result']['stage']}", flush=True)
            if label == "phone-before" and stage["result"]["stage"] != "RFCOMM_OK_ZERO_BYTES":
                break
    except Exception as error:
        # Never include subprocess output or raw Android logs in this report.
        evidence["errorType"] = type(error).__name__
        failure = error
    finally:
        evidence["permitsRetired"] = {}
        for role, serial in devices.items():
            try:
                bench.adb(serial, "shell", "run-as", bench.PACKAGE, "rm", "-f", "files/arm.json")
                evidence["permitsRetired"][role] = True
            except Exception:
                evidence["permitsRetired"][role] = False
        try:
            move_owner(devices, configs, "phone")
            evidence["diagnosticOwnerAfter"] = "phone"
        except Exception as error:
            evidence["diagnosticOwnerAfter"] = "unconfirmed"
            evidence["ownershipCleanupErrorType"] = type(error).__name__
        # A failed watch/ownership cleanup must not skip restoration of phone AAPS.
        try:
            restore = "default-state" if previous == 0 else "enable"
            bench.adb(args.phone, "shell", "pm", restore, "--user", "0", bench.AAPS)
            restored = bench.enabled_state(args.phone)
            evidence["restoredEnabledState"] = restored
            if restored != previous:
                raise RuntimeError("AAPS restoration failed; manual recovery required")
            if was_running:
                bench.adb(args.phone, "shell", "am", "start", "-n", f"{bench.AAPS}/app.aaps.MainActivity")
        except Exception as error:
            evidence["restorationErrorType"] = type(error).__name__
            failure = error
        evidence["finishedAt"] = int(time.time() * 1000)
        evidence["conclusion"] = conclusion(evidence["stages"])
        evidence["aapsPumpConnectionVerified"] = False
        if args.evidence_dir:
            directory = Path(args.evidence_dir)
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"comparison-{evidence['startedAt']}.json"
            path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            path.chmod(0o600)
            print(f"Report: {path}", flush=True)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    if failure:
        raise failure
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--watch", required=True)
    parser.add_argument("--phone-id", default="RRCX807YMBY")
    parser.add_argument("--watch-id", default="H631104000081G1A00V2")
    parser.add_argument("--pump-id", required=True, choices=["41056642"])
    parser.add_argument("--off-body-confirmed", action="store_true")
    parser.add_argument("--attempts", type=int, choices=range(1, 4), default=3)
    parser.add_argument("--evidence-dir", required=True)
    parser.set_defaults(transport="sdp")
    compare(parser.parse_args())


if __name__ == "__main__":
    main()
