# Android 10 (Portal gen2) is unsupported for hands-free wake — settled

The design relies on Android 9's first-come-first-served mic. API 29 added concurrent capture + silencing; the
Portal gen2 build (model "Portal", codename "omni", Android 10) **silences a sideloaded background
foreground-service's mic even as the sole capturer** — verified: `dumpsys audio` →
`pack:com.portal.wake … silenced:true`. Only the resumed top Activity records.

Tested on real hardware and **failed**:
- `VoiceInteractionService` assistant role
- `TYPE_APPLICATION_OVERLAY` window (the portal-assistant "orange bar")
- forcing the `RECORD_AUDIO` appop

Only real fix: privileged/system install (`/system/priv-app` + `CAPTURE_AUDIO_HOTWORD`), blocked on locked retail
units (`ro.secure=1`, no `adb root`/`remount`). Corroborated by starbrightlab/immortal#11 (gen2 "cipher", same
`QKQ1.210213.001` build family).

`logSilencing` in `WakeService.kt` is the diagnostic that proved this; keep it for re-checking on an unlocked device.
