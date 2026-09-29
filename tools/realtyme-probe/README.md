# Realtyme host access probe

macOS native read-only diagnostic for the observed Roche cable `173a:2106`.
Requires exactly one matching vendor-defined HID device (usage page `0xff00`,
usage 1, 64-byte input). Opens non-exclusively for five seconds and counts
unsolicited input reports, without printing their payloads.

No output reports, feature requests, pump protocol, configuration writes,
driver installation, USB resets, or cloud upload. `IOHIDDeviceGetProperty`
reads host properties, not pump data. A successful open proves host access
to the cable, NOT reading a pump or compatibility with its application protocol.

Build:

```sh
mkdir -p tools/realtyme-probe/build
xcrun clang -Wall -Wextra -Werror -O2 tools/realtyme-probe/inspect.c -framework IOKit -framework CoreFoundation -o tools/realtyme-probe/build/inspect
```

Run: `tools/realtyme-probe/build/inspect`.
