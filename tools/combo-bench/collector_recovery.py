"""Recovery helpers for an explicitly approved, bounded WearTester bench pause.

This module never stops a collector, changes pairing, or opens a pump connection.
"""

import re
import shlex
import subprocess
import time

import benchctl as bench

PACKAGE = "com.example.weartester"
COMPONENT = PACKAGE + "/.MainActivity"
START_ARGS = ("shell", "am", "start", "-n", COMPONENT, "--ez", "auto_start_probe", "true")


def parse_snapshot(services, bluetooth, running):
    marker = PACKAGE + " (Registered)"
    section = bluetooth.split(marker, 1)[1][:650] if marker in bluetooth else ""
    counts = re.search(r"LE scans \(started/stopped\)\s*:\s*(\d+) / (\d+)", section)
    return dict(running=running, service=".BondProbeService" in services,
                foreground="isForeground=true" in services,
                scanStateKnown="GATT Scanner Map" in bluetooth,
                scannerRegistered=bool(section),
                activeScans=int(counts[1]) - int(counts[2]) if counts else None)


def snapshot(watch):
    services = bench.adb(watch, "shell", "dumpsys", "activity", "services", PACKAGE)
    dump = subprocess.run([bench.ADB, "-s", watch, "shell", "dumpsys", "bluetooth_manager"],
                          capture_output=True, timeout=8, check=True).stdout.decode("utf-8", "replace")
    running = bool(bench.adb(watch, "shell", "pidof", PACKAGE, check=False))
    return dict(at=int(time.time() * 1000), **parse_snapshot(services, dump, running))


def collector_is_stopped(state):
    # Wear listeners can recreate the process without starting the sensor service.
    return state["scanStateKnown"] and not state["service"] and not state["scannerRegistered"]


def collector_is_restored(state):
    # No scan can be normal while a sensor GATT connection is active.
    return state["running"] and state["service"] and state["foreground"]


def completed_paused_probe(previous_probe, status):
    """Validate a fresh result on the watch clock; HCI must separately confirm scan overlap."""
    probe = status.get("probe", {})
    previous, current = previous_probe.get("at"), probe.get("at")
    if type(previous) is not int or type(current) is not int or current <= previous:
        raise RuntimeError("No fresh watch diagnostic result")
    if (not isinstance(status.get("ownership"), dict) or
            probe.get("stage") not in ("RFCOMM_OK_ZERO_BYTES", "RFCOMM_FAILED", "RFCOMM_TIMEOUT") or
            status.get("ownership", {}).get("operation") or status.get("error")):
        raise RuntimeError("Paused diagnostic did not settle")
    if (probe.get("transport") != previous_probe.get("transport") or
            probe.get("apkVersionCode") != previous_probe.get("apkVersionCode") or
            probe.get("applicationBytesSent") != 0 or probe.get("timeoutSeconds") != 8):
        raise RuntimeError("Paused diagnostic conditions differ")
    return probe


def restore(watch):
    bench.adb(watch, *START_ARGS)
    deadline = time.monotonic() + 8
    while True:
        state = snapshot(watch)
        if collector_is_restored(state):
            return state
        if time.monotonic() >= deadline:
            raise RuntimeError("WearTester foreground collector restoration is unconfirmed")
        time.sleep(0.25)


def fallback_command():
    # Do not gate this on pidof: a UI/listener process can exist without the collector.
    action = "sleep 25; " + shlex.join(START_ARGS[1:])
    return "nohup sh -c " + shlex.quote(action) + " >/dev/null 2>&1 < /dev/null &"
