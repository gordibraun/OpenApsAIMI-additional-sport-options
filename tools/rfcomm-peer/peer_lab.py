"""Bounded, peer-only diagnostic runner. Never opens a pump connection."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"
PACKAGE = "app.aaps.rfcommpeer"
DEVICES = {
    "watch": ("H631104000081G1A00V2", "OPWWE251", "34:2D:0D:34:ED:C2"),
    "phone": ("988a17424f4d4e584f", "SM-G955F", "7C:F0:E5:5F:5D:52"),
}
ROOT = Path.home() / "Downloads/aaps-debug/rfcomm-peer-20260928"


def adb(side, *args, timeout=30, check=True):
    result = subprocess.run([str(ADB), "-s", DEVICES[side][0], *args],
                            capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{side}: {args[:3]}: {result.stderr.strip()}")
    return result.stdout


def verify():
    for side, (_, model, _) in DEVICES.items():
        actual = adb(side, "shell", "getprop", "ro.product.model").strip()
        if actual != model:
            raise RuntimeError(f"Wrong {side}: {actual}")


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.write_text(data if isinstance(data, str) else json.dumps(data, indent=2, ensure_ascii=False))
    path.chmod(0o600)


def events(side):
    raw = adb(side, "shell", "run-as", PACKAGE, "cat", "files/events.jsonl", check=False)
    return [json.loads(line) for line in raw.splitlines() if line.startswith("{")]


def command(side, mode, trial, secure=False, delay_ms=0, spp=False):
    output = adb(side, "shell", "am", "start-foreground-service", "-n", PACKAGE + "/.PeerService",
                 "--es", "mode", mode, "--es", "id", trial,
                 "--es", "peer", DEVICES[side][2], "--ez", "secure", str(secure).lower(),
                 "--ez", "spp", str(spp).lower(),
                 "--ei", "delayMs", str(delay_ms))
    if "Error" in output or "Exception" in output:
        raise RuntimeError(output)
    return output


def snapshot(label):
    folder = ROOT / (label + "-" + str(time.time_ns() // 1000000))
    summary = {}
    for side in DEVICES:
        dump = adb(side, "shell", "dumpsys", "bluetooth_manager", timeout=60)
        save(folder / f"{side}-bluetooth.txt", dump)
        save(folder / f"{side}-crash.txt", adb(side, "logcat", "-b", "crash", "-d", "-v", "threadtime"))
        save(folder / f"{side}-battery.txt", adb(side, "shell", "dumpsys", "battery"))
        save(folder / f"{side}-power.txt", adb(side, "shell", "dumpsys", "power"))
        summary[side] = {
            "serial": DEVICES[side][0],
            "model": adb(side, "shell", "getprop", "ro.product.model").strip(),
            "sdk": adb(side, "shell", "getprop", "ro.build.version.sdk").strip(),
            "fingerprint": adb(side, "shell", "getprop", "ro.build.fingerprint").strip(),
            "screenTimeout": adb(side, "shell", "settings", "get", "system", "screen_off_timeout").strip(),
            "stayAwake": adb(side, "shell", "settings", "get", "global", "stay_on_while_plugged_in").strip(),
            "bluetoothSummary": dump.split("Enable log:")[0].strip(),
            "crashes": re.findall(r"Bluetooth crashed \d+ times", dump),
        }
    save(folder / "summary.json", summary)
    print(json.dumps({"folder": str(folder), "summary": summary}, ensure_ascii=False), flush=True)


def last_acl(rows):
    relevant = [r for r in rows if r["event"] in (
        "android.bluetooth.device.action.ACL_CONNECTED", "android.bluetooth.device.action.ACL_DISCONNECTED")]
    return relevant[-1] if relevant else None


def classify(rows):
    start = next((r for r in rows if r["event"] == "START"), None)
    opened = next((r for r in rows if r["event"] == "SOCKET_OPEN"), None)
    connected = [r for r in rows if r["event"].endswith("ACL_CONNECTED")]
    disconnected = last_acl(rows)
    return {
        "outcome": next((r.get("outcome") for r in reversed(rows) if r["event"] == "FINISHED"), "MISSING"),
        "openMs": opened.get("openDurationMs") if opened else None,
        # The broadcast can be delivered after connect() returns, so its absence
        # is inconclusive; it must at least belong to this attempt, not pairing.
        "freshAcl": bool(start and any(r["elapsedMs"] >= start["elapsedMs"] for r in connected)),
        "aclClosed": bool(disconnected and disconnected["event"].endswith("ACL_DISCONNECTED") and
                          opened and disconnected["elapsedMs"] >= opened["elapsedMs"]),
        "interactiveAtStart": start["interactive"] if start else None,
        "idleAtStart": start["deviceIdle"] if start else None,
        "pairingDuringTrial": any(r["event"] in (
            "UNEXPECTED_PAIRING", "android.bluetooth.device.action.PAIRING_REQUEST",
            "android.bluetooth.device.action.BOND_STATE_CHANGED") for r in rows),
    }


def cold_success(result):
    return (result["outcome"] == "OK_ZERO_BYTES" and result["freshAcl"] and
            result["aclClosed"] and not result["pairingDuringTrial"])


def verify_apk():
    local = Path(__file__).parent / "build/outputs/apk/debug/rfcomm-peer-debug.apk"
    digest = hashlib.sha256(local.read_bytes()).hexdigest()
    result = {"localSha256": digest}
    for side in DEVICES:
        path = adb(side, "shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
        installed = subprocess.run([str(ADB), "-s", DEVICES[side][0], "exec-out", "cat", path],
                                   capture_output=True, check=True, timeout=60).stdout
        actual = hashlib.sha256(installed).hexdigest()
        if actual != digest:
            raise RuntimeError(f"APK mismatch: {side}")
        result[side] = actual
    save(ROOT / ("installed-apk-" + digest[:12] + ".json"), result)
    print(json.dumps(result), flush=True)


def run(client, count, secure, screen_off, spp=False):
    if not 1 <= count <= 12:
        raise ValueError("Bounded series only")
    server = "phone" if client == "watch" else "watch"
    batch = f"{client}-{'secure' if secure else 'insecure'}-{'off' if screen_off else 'on'}-{'spp' if spp else 'custom'}-{int(time.time())}"
    folder = ROOT / batch
    results = []
    for side in DEVICES:
        command(side, "status", batch)
    if screen_off:
        for side in DEVICES:
            adb(side, "shell", "input", "keyevent", "223")
        time.sleep(60)
    try:
        for i in range(count):
            trial = f"{batch}-{i + 1}"
            # Do not count a new RFCOMM channel on an old ACL as a cold connection.
            for side in DEVICES:
                previous = last_acl(events(side))
                if previous and previous["event"].endswith("ACL_CONNECTED"):
                    raise RuntimeError(f"{side}: previous ACL still connected; stop instead of mislabelling cold trials")
            command(server, "server", trial, secure, spp=spp)
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                rows = [r for r in events(server) if r["id"] == trial]
                if any(r["event"] == "LISTENING" for r in rows):
                    break
                if any(r["event"] in ("ERROR", "FINISHED") for r in rows):
                    raise RuntimeError(f"Server not ready: {rows}")
                time.sleep(0.15)
            else:
                raise RuntimeError("Server did not start listening")
            command(client, "client", trial, secure, spp=spp)
            deadline = time.monotonic() + 38
            while time.monotonic() < deadline:
                trial_rows = {side: [r for r in events(side) if r["id"] == trial] for side in DEVICES}
                done = all(any(r["event"] == "FINISHED" for r in rows) for rows in trial_rows.values())
                disconnected = all(last_acl(rows) and last_acl(rows)["event"].endswith("ACL_DISCONNECTED")
                                   for rows in trial_rows.values())
                if done and disconnected:
                    break
                time.sleep(0.4)
            save(folder / f"{i + 1:02}.json", trial_rows)
            for side, rows in trial_rows.items():
                started = next((r for r in rows if r["event"] == "START"), None)
                if started is not None and (started.get("spp", False) != spp or started.get("secure") != secure):
                    raise RuntimeError(f"{side}: installed app did not apply the requested transport")
            item = {"id": trial, "client": client, "secure": secure, "spp": spp,
                    "devices": {side: classify(rows) for side, rows in trial_rows.items()}}
            results.append(item)
            save(folder / "results.json", results)
            print(json.dumps(item), flush=True)
            if not done:
                raise RuntimeError("Incomplete trial; no retry")
            if not all(cold_success(d) for d in item["devices"].values()):
                raise RuntimeError("Successful cold reconnect without pairing not confirmed on both devices; no retry")
            time.sleep(3)
    finally:
        for side in DEVICES:
            try:
                save(folder / f"{side}-events.json", events(side))
            except Exception as error:
                print(f"Capture failure: {side}: {error}", flush=True)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["snapshot", "verify-apk", "status", "pair", "run", "stop"])
    parser.add_argument("--label", default="baseline")
    parser.add_argument("--client", choices=list(DEVICES), default="watch")
    parser.add_argument("--count", type=int, default=5)
    parser.add_argument("--secure", action="store_true")
    parser.add_argument("--screen-off", action="store_true")
    parser.add_argument("--spp", action="store_true")
    args = parser.parse_args()
    verify()
    if args.action == "verify-apk":
        verify_apk()
    elif args.action == "snapshot":
        snapshot(args.label)
    elif args.action == "run":
        run(args.client, args.count, args.secure, args.screen_off, args.spp)
    elif args.action == "pair":
        print(command("watch", "pair", "pair-" + str(int(time.time()))))
    else:
        for side in DEVICES:
            print(side, command(side, args.action, "host-" + str(int(time.time()))))
            print(json.dumps(events(side)[-5:]))


if __name__ == "__main__":
    main()
