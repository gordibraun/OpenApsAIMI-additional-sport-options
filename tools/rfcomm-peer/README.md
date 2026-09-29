# Independent RFCOMM peer control

Debug-only Bluetooth Classic test for OnePlus OPWWE251 and the spare Galaxy
S8+ SM-G955F. This APK has no ComboCtl, AAPS, Wear data layer, Internet access,
pump protocol or therapy commands. Only the two recorded device addresses
are permitted; the pump and the regular companion phone are not permitted.
It sends zero application bytes and opens an independent RFCOMM UUID by default.
`--spp` selects the standard Serial Port UUID also used by ComboCtl, but still
only between these Android peers. It does not connect to or impersonate a pump.

An explicit `pair` command requests standard Android pairing with the spare
phone; confirm matching codes on both devices. Trial commands require an
existing bond and stop their sockets if a new pairing event is observed.
Do not add either device through the Wear companion setup: that is not needed.

Host commands require USB ADB, verify device models, and use a DUMP-protected
foreground service. Sockets have a 25-second deadline; the observer service
stops after 45 minutes if the host disappears. Partial wake locks are bounded
to 28 seconds. No radio resets, pump connections or health-app changes occur.

Build: `./gradlew :tools:rfcomm-peer:testDebugUnitTest :tools:rfcomm-peer:assembleDebug :tools:rfcomm-peer:lintDebug`.
Host tests: `python3 -m unittest discover -s tools/rfcomm-peer -p 'test_*.py'`.

`peer_lab.py snapshot` saves private pre/post state and crash logs.
`peer_lab.py verify-apk` compares installed APK bytes on both devices.
`peer_lab.py status` starts the peer-only observer on both devices.
`peer_lab.py pair` requests pairing from the watch to the spare phone.
`peer_lab.py run --client watch --count 5` performs a bounded series.
Use `--secure` to test secure sockets and `--screen-off` for 60 seconds with
the screens off before the series. Screen off is NOT proof of deep sleep;
interactive/deviceIdle/powerSave are recorded separately. Charging can prevent
natural deep idle. Do not report this test as an off-charger Doze test.
`peer_lab.py stop` stops only this diagnostic service on both devices.

A socket success does not prove a new radio connection. The runner requires
targeted ACL_CONNECTED/ACL_DISCONNECTED broadcasts and refuses to count a
channel on an old ACL as a cold reconnect. These are Android observations,
not an independent capture of the radio. No clocks are compared across devices.
After any incomplete or ambiguous attempt, stop and inspect instead of retrying
automatically. Pump compatibility and repeatability remain separate questions.

`btsnooz_summary.py INPUT --address ADDRESS --output JSON` decodes the S8's
btsnooz v2 section offline using the existing target-filtered HCI analyzer.
Only complete HCI commands/events are analyzed, never ACL payloads or keys.
Record indexes are retained. Its missing loss counter is reported as unknown,
not zero; capture-clock timestamps must not be mixed with another device clock.
