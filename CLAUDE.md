# CLAUDE.md — Portal-Wake

Onboarding for an agent picking up this project. Read `README.md` for the full picture; this is the
fast version.

## What this is

A **headless, always-on wake-word listener** for the Meta Portal+ (model "aloha", Android 9 / API 28),
package `com.portal.wake`. No launcher icon, no Activity, no UI. It boots a foreground service, owns
the mic, runs an on-device **openWakeWord** neural detector, and on a wake match hands the mic off to
another app. Wake detection is small and standalone, and wake words are **discovered at runtime** instead of
hard-coded.

minSdk 28 / targetSdk 29 / compileSdk 36. **No Google Mobile Services.** Deps: `androidx.core`,
`onnxruntime-android` (via `com.portal:commons-android`), and `com.portal:commons` — wired in via a Gradle
composite build from the sibling `../portal-commons`.

Built-in routes: **jarvis** → the registered handler `portal-assistant` (via the `WakeContract` plugin
API); **alexa** → the native Alexa client `falcon` (via its `LISTEN` intent). Recovery is
**detection-based** (no "done" signal), and Portal-Wake **stands down for phone/VoIP calls**.

## Design rules — don't break these

- **Extensibility is the point.** Wake words come from `WakeRegistry.discover()`: runtime-discovered
  plugin apps (the `WakeContract` API) first, built-in defaults as fallback. To add "hey X", a *plugin
  app* declares a receiver **and ships an openWakeWord `.onnx` model** — never edit the detector here.
- **Recover by detection, never by a signal or timer.** After a hand-off Portal-Wake reclaims the mic
  only once the consumer stops recording (`HandoffRecovery` fed by an `AudioRecordingCallback`), and only
  while it is itself paused. There is no `WAKE_DONE` and no reclaim timer.
- **Three stand-down triggers, one reclaim rule.** Capture pauses for (1) a wake match, (2) a phone/VoIP
  call, and (3) **mic contention detected automatically** via `MicContentionDetector`.
- **Stand down for calls; never fight them.** `WakeService` polls the audio mode and feeds `CallGate`.
- **Don't kill falcon; do suppress millennium.** `falcon` (Alexa client) never contends for our handset
  mic. `com.millennium` is the native "Hey Alexa" detector — `MicLiberator` kills it.
- **No AGC / NoiseSuppressor on the capture stream** — plain `VOICE_RECOGNITION`, no effects.
- **Classifier score threshold** is per wake word via `WakeWord.scoreThreshold` / `com.portal.wake.min_confidence` (openWakeWord probability in [0, 1], default 0.5 — not an ASR confidence).
  (default 0.5). Tune from on-device `debug.txt` logs (`wake detected → jarvis [oww p=…]`).
- **Single mic slot.** Only one app records at a time.
- **Headless start-up.** No icon means `setup.sh` must start the service once so `BootReceiver` gets
  `BOOT_COMPLETED` thereafter.
- **Android 10 is unsupported for hands-free wake — don't re-litigate it.** See `WakeService.logSilencing`.

## Layout

`service/` = WakeService, MicArbiter, WakeNotification, BootReceiver.
`audio/` = `CallGate` only.
`wake/` = WakeContract, WakeSpec, WakeRegistry, WakeTarget, OwwHeadResolver, HandoffRecovery,
MicContentionDetector, CaptureGate.
`system/` = MicLiberator, Falcon.

**Shared code in `portal-commons`:** `PcmCaptureSession`, `PcmCaptureFormat`, `DebugLog`, `WakeWord`,
`OpenWakeWordDetector`, `WakeMicEngine`, `WakeDetector`, `FireCooldown` — consumed by portal-wake and
portal-assistant.

## Build / deploy

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./setup.sh
npx -y @meta-quest/hzdb adb shell "cat /sdcard/Android/data/com.portal.wake/files/debug.txt"
```

ONNX models ship in `portal-commons/commons-android/src/main/assets/oww/` — no separate download.
