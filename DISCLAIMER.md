# Disclaimer

**Portal-Wake is an independent, community-built project. It is not affiliated
with, authorized by, endorsed by, or sponsored by Meta Platforms, Inc.**

"Meta", "Meta Portal", and "Portal" are trademarks of Meta Platforms, Inc. They
are used here only to identify the hardware this app is compatible with
(nominative use). Portal-Wake is not a Meta product and ships no Meta code.

## Use at your own risk

Portal-Wake is a sideloaded app that you build and install yourself. Meta Portal
devices are discontinued and receive no official support. By installing and
running it you accept that:

- Installing and running third-party apps on a device may **void any remaining
  warranty** or violate the device's terms of use.
- Modifying a device or sideloading software always carries some risk. We are
  not aware of this app causing any harm, but **no outcome is guaranteed**.
- The app runs a foreground service that holds the microphone in order to listen
  for its wake word. You are responsible for deciding whether that is
  appropriate on your device.

The software is provided "AS IS", without warranty of any kind. To the maximum
extent permitted by law, the authors and contributors accept no liability for
any damage, data loss, or other harm arising from its use.

## Privacy

Portal-Wake has no analytics and no accounts, and performs no network
communication of its own. It captures microphone audio solely to detect its wake
word **on-device**: audio is processed locally in real time and is never
recorded, stored, or transmitted off the device. On a wake match it hands the
microphone to another app on the same device — what that app does with it is
governed by *that* app's own terms, not this project's. The `DebugLog` helper
writes a best-effort local log file (`files/debug.txt`) on the device only. No
personal data is collected by the project.

## Reporting issues

If you believe any content here infringes your rights, or you represent Meta and
have concerns, please open an issue or contact the maintainers; we will respond
promptly.
