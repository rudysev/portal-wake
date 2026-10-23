# CLAUDE.md — Portal-Wake

Onboarding for an agent picking up this project. Read `README.md` for the full picture; this is the
fast version. Domain vocabulary (handoff, stand down, reclaim, consumer, BORROWED, …) is defined in
`GLOSSARY.md`; use those terms.

## What this is

A **headless, always-on wake-word listener** for the Meta Portal+ (model "aloha", Android 9 / API 28),
package `com.portal.wake`. No launcher icon, no Activity, no UI. It boots a foreground service, owns
the mic, runs an on-device **Vosk** recognizer, and on a wake match hands the mic off to another app.
Wake detection is small and standalone, and wake words are **discovered at runtime** instead of
hard-coded.

minSdk 28 / targetSdk 29 / compileSdk 36. **No Google Mobile Services.** Deps: `androidx.core`,
`com.alphacephei:vosk-android`, and `com.portal:commons` — wired in via a Gradle composite build from the
sibling `../portal-commons`. This repo lives in the **portal-apps** workspace (the superproject that holds
`portal-wake`, `portal-commons`, and `portal-assistant` side by side as submodules).

Built-in routes: **jarvis** → the registered handler `portal-assistant` (via the `WakeContract` plugin
API); **alexa** → the native Alexa client `falcon` (via its `LISTEN` intent). Recovery is
**detection-based** (no "done" signal), and Portal-Wake **stands down for phone/VoIP calls**.

## Design rules — don't break these

- **Extensibility is the point.** Wake words come from `WakeRegistry.discover()`: runtime-discovered
  plugin apps (the `WakeContract` API) first, built-in defaults as fallback. To add "hey X", a *plugin
  app* declares a receiver — never edit the recognizer. Don't re-hard-code assistants here.
- **Recover by detection, never by a signal or timer.** After a hand-off Portal-Wake reclaims the mic
  only once the consumer stops recording (`HandoffRecovery` fed by an `AudioRecordingCallback`), and only
  while it is itself paused. There is no `WAKE_DONE` and no reclaim timer (a turn may be minutes long).
  Do NOT identify a foreign recorder by comparing recording-config session ids against our own `AudioRecord` —
  on this Portal those ids don't match, so that approach self-detects and loops. The callback acts only
  while paused.
- **Three stand-down triggers, one reclaim rule.** Capture pauses for (1) a wake match, (2) a phone/VoIP
  call, and (3) **mic contention detected automatically**: while capturing, the same
  `AudioRecordingCallback` + the pure `MicContentionDetector` spot a foreign recording session and run the
  same `standDown()` as a handoff — no broadcast or cooperation from the consumer (covers a foreground tap
  AND uncooperative apps). The contract stays signal-free. The detector **seeds only the first non-empty
  config** after capture starts as "ours" (comparing `AudioRecord.getAudioSessionId()` directly is
  unreliable on this Portal — the ids don't match), then treats any unknown id as foreign — a 2nd recorder
  OR, on this single-mic device, a **replacement** that flips the list `[wake] → [consumer]`. The `~750 ms`
  `DEFAULT_SEED_MS` is just the grace to wait for our own first config (invisible-wake), not a window that
  adopts every id.
  Two device-won subtleties: pausing our own capture emits a transient empty-config callback, so reclaim is
  **debounced** (`RECLAIM_DEBOUNCE_MS`, re-checked against live configs) to avoid re-grabbing instantly; and
  a detection stand-down arms recovery directly in **BORROWED** (the consumer is already recording — unlike
  a wake handoff there is no later "consumer starts" edge to see).
- **Stand down for calls; never fight them.** `MicArbiter` polls its `AudioGate.inCall` seam (no mode-change
  event on API 28; the production adapter in `WakeService` reads `AudioManager.getMode() != MODE_NORMAL`) and
  also re-reads it live immediately before every capture start, so a call always wins the mic. While a call
  holds, capture is paused (the shared session releases the mic) and not re-grabbed until the call ends.
  Handoff is the arbiter's other pause reason, so a call arriving mid-handoff blocks reclaim until it
  clears. Even Meta's own millennium fails this — it detects the call but keeps re-grabbing, churning it; portal-wake doesn't.
- **Don't kill falcon; do suppress millennium.** `falcon` (Alexa client) never contends for our handset
  mic (verified) and the **alexa** route drives it via its `LISTEN` intent — leave it running.
  `com.millennium` is the native **"Hey Alexa" detector** (Vosk on the handset mic) that *is* the
  competitor, so `MicLiberator` kills it and `setup.sh` `pm disable-user`s it (so native "Hey Alexa" is
  off — we own that word).
- **No AGC / NoiseSuppressor on the capture stream** — plain `VOICE_RECOGNITION`, no effects (the proven
  config).
- **Accuracy lives in `WakeMatcher` — now in `portal-commons` (`com.portal:commons`), not this repo.** A pure
  function, fully unit-tested; tune the precision/recall dials there from on-device `debug.txt` logs (see
  `TUNING.md`). `WakeMatcher` + `WakeMatcherTest` moved to `../portal-commons/commons/…/com/portal/commons/audio/`,
  so **`portal-wake`'s `./gradlew test` no longer runs them** — re-run `../portal-commons` `:commons:test` after
  any matcher change. It's shared, so a change affects **both** portal-wake and portal-assistant's detectors.
- **Single mic slot.** Only one app records at a time. Two always-on `VOICE_RECOGNITION` listeners can't
  share it — don't run Portal-Wake alongside another always-on wake app (the `portal-assistant` hand-off
  replaces the need for one).
- **Headless start-up.** No icon means `setup.sh` must start the service once (clears the "stopped"
  state) so `BootReceiver` gets `BOOT_COMPLETED` thereafter. Keep it that way.
- **Android 10 is unsupported for hands-free wake — don't re-litigate it.** This design relies on Android 9's
  first-come-first-served mic. API 29 added *concurrent capture + silencing*, and the Portal gen2 (model
  "Portal", codename "omni", Android 10) build **silences a sideloaded background foreground-service's mic even
  as the sole capturer** — verified on device (`dumpsys audio` → `pack:com.portal.wake … silenced:true`); only the
  resumed top Activity records. Every sideloadable escape was tested on real hardware and **failed**: the
  `VoiceInteractionService` assistant role, a `TYPE_APPLICATION_OVERLAY` window (the portal-assistant "orange
  bar"), and forcing the `RECORD_AUDIO` appop. The only real fix is a privileged/system install
  (`/system/priv-app` + `CAPTURE_AUDIO_HOTWORD`), blocked on a locked retail unit (`ro.secure=1`, no
  `adb root`/`remount`). Independently corroborated by starbrightlab/immortal#11 (a sibling gen2 unit,
  codename "cipher", same `QKQ1.210213.001` build family). The `WakeService.logSilencing`
  diagnostic is what proved this and is kept for re-checking on an unlocked device. Do not add an assistant
  role / overlay / appop "fix" back — they don't work.

## Layout

`service/` = Android lifecycle + adapters (WakeService = engine lifecycle + match→consumer routing + the thin
Android adapters behind the arbiter's seams — `AudioManagerGate`, `HandlerScheduler`, the recording callback;
WakeNotification = the foreground-service notification; BootReceiver).
`mic/` = mic arbitration, Android-free and unit-tested. `MicArbiter` = the **mic-arbitration state machine**:
it owns handoff/reclaim + call stand-down + foreign-mic contention, holds the two pause reasons (call,
handoff) and the single `reconcile()` that starts/pauses capture, drives the engine through a
`CaptureController` seam and reads Android only via injected `Scheduler`/`AudioGate` seams. Its `internal`
helpers: `HandoffRecovery` = reclaim rule; `MicContentionDetector` = foreign-recording-session detector.
The **wake-detection core** lives in `portal-commons` so `portal-assistant` can reuse it for its foreground
detector (see the shared-code note below): `WakeMicEngine` = mic + capture over `PcmCaptureSession`, configured via
`WakeMicConfig` + `WakeDetectors.vosk()` = the Vosk detector, one `internal` module behind that factory
[model load + grammar + warm-up + pre-ready buffer/replay + idle-reset + grammar hot-swap; honours the engine's
handoff cooldown; tested through `WakeDetector`]; `WakeMatcher` = accuracy gate [tested]; `WakeWord`. `WakeMicConfig.beforeMicAcquire` is the mic-slot hook —
portal-wake passes `MicLiberator.freeMic`, the assistant passes none.
`wake/` = the extensibility layer (WakeContract = the published plugin contract [tested], WakeSpec
= builds a WakeWord from a plugin's named wake meta-data [tested], WakeRegistry = discovery, WakeTarget =
word↔handler).
`system/` — `MicLiberator` frees the mic slot; `Falcon` drives the native Alexa client via its `LISTEN`
intent (the **alexa** route). **Shared code lives in `portal-commons` (the sibling
`../portal-commons`): the pure-JVM `com.portal:commons` (`PcmCaptureSession`/`PcmDevice` the capture thread
+ device lifecycle, `PcmCaptureFormat`, `DebugLog` `files/debug.txt` log, **plus the pure wake-decision
`WakeMatcher` [tested] + `WakeWord`**), plus the Android-library `com.portal:commons-android`
(`com.portal.commons.audio.AudioRecordPcmDevice` the shared mic shell, **plus the Vosk detection core
`WakeMicEngine` / `WakeMicConfig` / `WakeDetector` / `WakeDetectors.vosk()` [tested]** —
`commons-android` carries the `vosk-android` dep) — both pulled in via Gradle composite build, and **both
consumed by `portal-wake` AND `portal-assistant`** (so an edit to the shared wake classes changes both apps'
detectors). The wake plugin contract
(`WakeContract`) and its `WakeSpec` builder of the named wake meta-data are **this app's own** (`wake/`), not
shared: the literal wire strings (the meta-data keys) are the contract, so plugins like portal-assistant mirror them.** Pure logic (MicArbiter,
HandoffRecovery, MicContentionDetector, WakeContract, WakeSpec, WakeRegistry's spec/same-wake-set rules) is
unit-tested; mic/service behavior is device-tested;
`commons` has its own unit tests (incl. PcmCaptureSession).

## Build / deploy

```bash
git submodule update --init --recursive   # from the portal-apps workspace: pull commons + the apps
# bundle a Vosk model first — see app/src/main/assets/model-en-us/README.md
# point Gradle at your Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT or local.properties sdk.dir),
# and build with a JDK 17 or 21 toolchain:
./gradlew check          # verify: unit tests + Android Lint + ktlint (kotlinter); formatKotlin fixes style
./gradlew assembleDebug
./setup.sh   # install + grant mic + start headless + free the mic slot; also enables .githooks
npx -y @meta-quest/hzdb adb shell "cat /sdcard/Android/data/com.portal.wake/files/debug.txt"
```

## Hooks

`git config core.hooksPath .githooks` (done by `./setup.sh`). `pre-commit` runs the `ktlint` CLI on staged
`.kt`/`.kts` files and `shellcheck` on staged shell scripts (`*.sh`, `*.command`, `.githooks/*`), and rejects on
findings (never auto-fixes; `brew install ktlint shellcheck`). For ktlint it refuses a CLI other than ktlint 1.8.0, the
version kotlinter 5.7.0 bundles (`gradle/libs.versions.toml`), so bump both together. `pre-push` runs
`./gradlew check`. `--no-verify` is the deliberate escape hatch. ktlint config is `.editorconfig` only.

## CI

`.github/workflows/ci.yml` runs `./gradlew check assembleDebug` on every push to `main`, on `workflow_dispatch`, and
when a green `portal-commons` run dispatches `portal-commons-green`. It checks out `portal-wake` and
`portal-commons@main` side by side so that `includeBuild("../portal-commons")` resolves.

A `shellcheck` job lints every tracked shell script (the `provisioning/` installer that ships to end users,
`setup.sh`, `scripts/`, the hooks) with the runner's preinstalled shellcheck. shellcheck isn't part of
`./gradlew check`: the hook and this job cover it. `.shellcheckrc` makes it follow sourced files
(`config.env`, `keyprov.sh`) relative to each script, so a variable set in one file and read in another isn't
reported as unused. `.githooks/pre-commit` and `.shellcheckrc` are identical copies in portal-commons,
portal-wake and portal-assistant; change all of them together.

A separate `build-health` job runs `./gradlew buildHealth` (the `com.autonomousapps.dependency-analysis` plugin)
and fails on any dependency advice: unused, used-but-undeclared or wrongly-configured dependencies. It is not part
of `check` or the hooks, because the advice changes rarely and the analysis is slow. It analyses only this build's
`:app`; the included `../portal-commons` build has its own job. Run `./gradlew buildHealth` yourself after changing
dependencies; the report is at `build/reports/dependency-analysis/build-health-report.txt`.

## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Grilling (/grill-with-docs)

Ask one question at a time, then STOP and wait for the user's answer. Don't answer your own questions, don't generate the next round ahead of the answer, and don't begin implementation without the user's explicit permission.
