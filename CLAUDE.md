# CLAUDE.md — Portal-Wake

Onboarding for an agent picking up this project. Read `README.md` for the full picture; this is the
fast version.

## What this is

A **headless, always-on wake-word listener** for the Meta Portal+ (model "aloha", Android 9 / API 28),
package `com.portal.wake`. No launcher icon, no Activity, no UI. It boots a foreground service, owns
the mic, runs an on-device **two-stage wake detector** (openWakeWord → Vosk), and on a wake match hands
the mic off to another app. Wake detection is small and standalone, and wake words are **discovered at
runtime** instead of hard-coded.

minSdk 28 / targetSdk 29 / compileSdk 36. **No Google Mobile Services.** Deps: `androidx.core`,
`com.alphacephei:vosk-android`, `com.microsoft.onnxruntime:onnxruntime-android`, and `com.portal:commons`
— wired in via a Gradle composite build from the sibling `../portal-commons`. This repo lives in the **portal-apps** workspace (the superproject that holds
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
  Do NOT identify a foreigner by comparing recording-config session ids against our own `AudioRecord` —
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
- **Stand down for calls; never fight them.** `WakeService` polls the audio mode (no mode-change event on
  API 28) and feeds `CallGate` (`AudioManager.getMode() != MODE_NORMAL`) into the pure `CaptureGate`; while
  a call holds, capture is paused (the shared session releases the mic) and not re-grabbed until the call
  ends. Handoff is the gate's other pause reason, so a call arriving mid-handoff blocks reclaim until it
  clears. Even Meta's own millennium fails this — it detects the call but keeps re-grabbing, churning it; portal-wake doesn't.
- **Don't kill falcon; do suppress millennium.** `falcon` (Alexa client) never contends for our handset
  mic (verified) and the **alexa** route drives it via its `LISTEN` intent — leave it running.
  `com.millennium` is the native **"Hey Alexa" detector** (Vosk on the handset mic) that *is* the
  competitor, so `MicLiberator` kills it and `setup.sh` `pm disable-user`s it (so native "Hey Alexa" is
  off — we own that word).
- **No AGC / NoiseSuppressor on the capture stream** — plain `VOICE_RECOGNITION`, no effects (the proven
  config).
- **openWakeWord is stage 1; Vosk *verifies* it. It is an AND, not an OR — don't turn it back into one.**
  `WakeDetectors.twoStage()` runs openWakeWord at a deliberately **loose 0.30** and then re-decodes the same
  2 s window with a phrase-constrained Vosk grammar; a candidate fires only if Vosk decodes the phrase (or
  scores ≥0.85, where stage 1 is the more reliable of the two). Measured on this device: Vosk alone 57%
  recall / 0 FA; openWakeWord alone @0.30 92% but **3 confirmed false accepts** during meeting playback;
  the cascade 85–90% at **0 FA**. Those false accepts scored 0.451–0.477, *inside* the band genuine
  far-field speech occupies — no single threshold separates them, which is the whole reason for a second
  stage. Configuring `oww()` and `vosk()` as two parallel detectors instead is an **OR**: their false
  accepts add up. Full evidence in `../../hey-jarvis/` (`RESULTS.md`, `PHASE_A.md`–`PHASE_C.md`).
- **Keep the audit clips — a score you can't listen to is not evidence.** On a debug build the cascade
  writes the 2 s window behind every fire (`wake_`), every stage-1 candidate it declined (`rej_`) and every
  sub-threshold near-miss (`near_`) to `files/clips/`, named `<prefix>_<wakeId>_<MMdd-HHmmss>_p<score×100>.wav`.
  `debug.txt` alone cannot tell a false accept from someone genuinely saying the phrase, and that distinction
  is what every tuning decision here turned on — openWakeWord's **0.998 on ordinary conversation**, the
  measurement that killed the bypass, was only understood because the audio could be replayed. Gated on
  `FLAG_DEBUGGABLE`, so an evaluation build captures with no extra step and a release build never records the
  household. The write happens on `WakeClipRecorder`'s own thread, **not** the capture thread: a `rej_` clip
  follows a 100–300 ms stage-2 decode that is already spending the `VERIFY_BUDGET_MS` budget, so an inline
  write would drop audio *and* corrupt the latency measurement that budget exists to make. Don't move it back.
- **Two thresholds, two scales — don't merge them.** `com.portal.wake.min_confidence` is a **Vosk per-word
  confidence** (a real wake decodes at ~1.00) and only applies on the Vosk-primary path;
  `com.portal.wake.score_threshold` is the **openWakeWord probability** (stage 1), default 0.30. Mapping one
  onto the other would silently tighten stage 1 to a value on the wrong scale and cost the recall the
  cascade exists to buy. Stage 1 is a recall filter; **precision comes from stage 2**, so raising the
  stage-1 threshold mostly just loses far-field wakes.
- **A phrase with no openWakeWord model stays Vosk-primary.** openWakeWord can only detect a phrase it has
  a classifier for. Portal-wake bundles "hey jarvis" and "hey alexa"; a plugin ships its own via
  `com.portal.wake.model`. Everything else is detected by grammar decode exactly as before openWakeWord
  existed — so the plugin API keeps working unchanged for a plugin that ships no model. `OwwModelResolver`
  makes the split and `WakeRouting` enforces it: **each id has exactly one detector that may hand off**, and
  the other's fire is a logged `shadow:` line (a free live A/B). The Vosk detector is only configured when
  some phrase actually needs it — each Vosk consumer loads its own model.
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

`service/` = lifecycle + orchestration (WakeService = engine lifecycle + match→consumer routing + the thin
Android adapters; MicArbiter = the **mic-arbitration state machine** [Android-free, [tested]]: it owns
handoff/reclaim + call stand-down + foreign-mic contention and the single `reconcile()` that starts/pauses
capture per the CaptureGate, driving the engine through a `CaptureController` seam and reading Android only
via injected `Scheduler`/`AudioGate` seams; WakeNotification = the foreground-service notification; BootReceiver).
`audio/` = `CallGate` = in-call gate [tested] — the **only** class still here. The **wake-detection
core** moved to `portal-commons` so `portal-assistant` can reuse it for its foreground detector (see the
shared-code note below): `WakeMicEngine` = mic + capture over `PcmCaptureSession`, configured via
`WakeMicConfig` + `WakeDetectors.twoStage()`; `TwoStageWakeDetector` = the cascade [tested] over
`OpenWakeWordDetector` (stage 1, ONNX) + `VoskPhraseVerifier` (stage 2), with `TwoStagePolicy`/`TwoStageTuning`
the pure decision + measured thresholds [tested]; `VoskWakeDetector` = the Vosk-primary path for phrases with
no model [pre-ready `PcmRingBuffer` + handoff cooldown + idle-reset]; `WakeRecognizer` = Vosk boundary
[warm-up + ready-gate]; `WakeMatcher` = Vosk accuracy gate [tested]; `WakeWord`. `WakeMicConfig.beforeMicAcquire` is the mic-slot hook —
portal-wake passes `MicLiberator.freeMic`, the assistant passes none.
`wake/` = the extensibility layer (WakeContract = the published plugin contract [tested], WakeSpec
= builds a WakeWord from a plugin's named wake meta-data [tested], WakeRegistry = discovery, WakeTarget = word↔handler,
OwwModelResolver = splits the wake set into cascade-owned vs Vosk-primary + decides when a change needs an
engine rebuild rather than an in-place grammar swap [tested], HandoffRecovery =
reclaim rule [tested], MicContentionDetector = foreign-recording-session detector [tested], CaptureGate =
two-reason capture coordinator [call + handoff; tested]).
`system/` — `MicLiberator` frees the mic slot; `Falcon` drives the native Alexa client via its `LISTEN`
intent (the **alexa** route). **Shared code lives in `portal-commons` (the sibling
`../portal-commons`): the pure-JVM `com.portal:commons` (`PcmCaptureSession`/`PcmDevice` the capture thread
+ device lifecycle, `PcmCaptureFormat`, `DebugLog` `files/debug.txt` log, **plus the pure wake-decision
`WakeMatcher` [tested] + `WakeWord`**), plus the Android-library `com.portal:commons-android`
(`com.portal.commons.audio.AudioRecordPcmDevice` the shared mic shell, **plus the Vosk detection core
`WakeRecognizer` + `WakeMicEngine` / `WakeMicConfig` / `WakeDetectors` / `VoskWakeDetector` [tested]** —
`commons-android` carries the `vosk-android` dep) — both pulled in via Gradle composite build, and **both
consumed by `portal-wake` AND `portal-assistant`** (so an edit to the shared wake classes changes both apps'
detectors). The wake plugin contract
(`WakeContract`) and its `WakeSpec` builder of the named wake meta-data are **this app's own** (`wake/`), not
shared: the literal wire strings (the meta-data keys) are the contract, so plugins like portal-assistant mirror them.** Pure logic (WakeMatcher, CallGate,
HandoffRecovery, CaptureGate, WakeSpec) is unit-tested; mic/service behavior is device-tested;
`commons` has its own unit tests (incl. PcmCaptureSession).

## Build / deploy

```bash
git submodule update --init --recursive   # from the portal-apps workspace: pull commons + the apps
# bundle a Vosk model first — see app/src/main/assets/model-en-us/README.md
# point Gradle at your Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT or local.properties sdk.dir),
# and build with a JDK 17 or 21 toolchain:
./gradlew assembleDebug
./setup.sh   # install + grant mic + start headless + free the mic slot
npx -y @meta-quest/hzdb adb shell "cat /sdcard/Android/data/com.portal.wake/files/debug.txt"
# the audio behind each decision (debug builds only — see the audit-clips rule):
npx -y @meta-quest/hzdb adb pull /sdcard/Android/data/com.portal.wake/files/clips <dest-dir>
```
