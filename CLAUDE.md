# CLAUDE.md — Portal-Wake

Full picture: `README.md`. Use the domain terms in `GLOSSARY.md` (handoff, stand down, reclaim, consumer, BORROWED…).

## What this is
Headless always-on wake-word listener for Meta Portal+ ("aloha", Android 9 / API 28), package `com.portal.wake`.
No icon/Activity/UI: boots a foreground service, owns the mic, runs on-device **Vosk**, hands the mic to another app
on a wake match. minSdk 28 / targetSdk 29 / compileSdk 36. **No GMS.** Deps: `androidx.core`, `com.portal:commons`
(composite build from sibling `../portal-commons`, in the **portal-apps** workspace with `portal-assistant`).

Routes: **jarvis** → plugin `portal-assistant` (`WakeContract`); **alexa** → native Alexa client `falcon` (`LISTEN` intent).

## Design rules — hard constraints
- **Extensibility:** wake words come from `WakeRegistry.discover()` — plugin apps (`WakeContract`) first, built-in
  defaults as fallback. New "hey X" = a plugin app declaring a receiver; the recognizer stays assistant-agnostic.
- **Reclaim by detection only.** After a handoff, reclaim the mic only when the consumer stops recording
  (`HandoffRecovery` fed by `AudioRecordingCallback`), and only while paused. No `WAKE_DONE`, no reclaim timer
  (turns can last minutes).
- **Never compare recording-config session ids with our `AudioRecord.getAudioSessionId()`** — on this Portal they
  don't match → self-detection loop.
- **Three stand-down triggers, one reclaim rule:** (1) wake match, (2) phone/VoIP call, (3) mic contention.
  Contention: while capturing, `AudioRecordingCallback` + pure `MicContentionDetector` spot a foreign session and run
  the same `standDown()` — no consumer cooperation; contract stays signal-free.
  - Detector seeds **only the first non-empty config** after capture start as "ours"; any unknown id after = foreign
    (a 2nd recorder, or a replacement flipping `[wake] → [consumer]`). `DEFAULT_SEED_MS` (~750 ms) is grace for our
    first config, not an adoption window.
  - Pausing our capture emits a transient empty config → reclaim is debounced (`RECLAIM_DEBOUNCE_MS`, re-checked
    against live configs).
  - Detection stand-down arms recovery directly in **BORROWED** (no later "consumer starts" edge).
- **Calls always win.** `MicArbiter` polls `AudioGate.inCall` (no mode-change event on API 28; prod adapter:
  `AudioManager.getMode() != MODE_NORMAL`) and re-reads it right before every capture start. During a call capture
  stays paused; a call mid-handoff blocks reclaim until it clears.
- **falcon:** leave running (never contends for the mic). **`com.millennium`** (native "Hey Alexa" detector) is the
  competitor: `MicLiberator` kills it, `setup.sh` `pm disable-user`s it.
- **Capture stream:** plain `VOICE_RECOGNITION`, no AGC/NoiseSuppressor/effects.
- **Accuracy = `WakeMatcher`, in `portal-commons`** (shared with portal-assistant). Tune from device `debug.txt`
  (`TUNING.md`). After any matcher change run `../portal-commons` `:commons:test` — portal-wake's `test` doesn't cover it.
- **Single mic slot:** never run alongside another always-on wake app.
- **Headless start-up:** `setup.sh` must start the service once (clears "stopped") so `BootReceiver` gets
  `BOOT_COMPLETED`.
- **Android 10 is unsupported — settled; don't add assistant-role/overlay/appop workarounds.** Evidence:
  `docs/agents/android-10.md`.

## Layout
- `service/` — Android lifecycle + adapters: `WakeService` (engine lifecycle, `AudioManagerGate`, `HandlerScheduler`,
  recording callback, `logSilencing`), `WakeHandoff` (match→consumer routing), `WakeNotification`, `BootReceiver`.
- `mic/` — runs on the JVM, unit-tested: calls Android only through `Scheduler`/`AudioGate` and the engine only
  through `CaptureController`. Its timing reads the engine's own `const`s (e.g. `WakeMicEngine.SILENCE_WINDOW_MS`,
  inlined at compile time), so it tracks the values the engine uses. `MicArbiter` = state machine: two pause reasons
  (call, handoff), single `reconcile()`. Internal: `HandoffRecovery` (reclaim rule), `MicContentionDetector`.
- `wake/` — this app's own (not shared): `WakeContract` (plugin contract; meta-data key strings are the wire
  contract, mirrored by plugins), `WakeSpec`, `WakeRegistry`, `WakeTarget`.
- `system/` — `MicLiberator` (frees mic slot), `Falcon` (alexa route).
- **portal-commons** (edits change both apps):
  - `com.portal:commons` (pure JVM): `PcmCapture`/`PcmDevice`, `CapturePolicy`, `PcmCaptureFormat`, `DebugLog`,
    `WakeMatcher`, `WakeWord`.
  - `com.portal:commons-android` (carries `vosk-android`): `AudioRecordPcmDevice`, `WakeMicEngine`, `WakeMicConfig`,
    `WakeModel`, `WakeEvent`. `WakeMicConfig.beforeMicAcquire` = mic-slot hook
    (portal-wake passes `MicLiberator.freeMic`).
- Tests: pure logic unit-tested; mic/service behavior is device-tested.

## Build / deploy
```bash
git submodule update --init --recursive   # from portal-apps workspace
# bundle a Vosk model: app/src/main/assets/model-en-us/README.md; SDK via ANDROID_HOME or local.properties; JDK 17/21
./gradlew check          # unit tests + Android Lint + detekt + ktlint; formatKotlin fixes style
./gradlew assembleDebug
./setup.sh               # install + grant mic + start headless + free mic slot
npx -y @meta-quest/hzdb adb shell "cat /sdcard/Android/data/com.portal.wake/files/debug.txt"
```

## Code quality
- All warnings fail the build (Lint, detekt, Kotlin `allWarningsAsErrors`). No baselines.
- Suppress inline only (`@SuppressLint`, `tools:ignore`, `//noinspection`, `@Suppress("RuleId")`), each with a reason comment.
- Max line length 120. No TODO/FIXME/STOPSHIP comments.
- Kotlin -Werror shows only "warnings found and -Werror specified": rerun with `--info` to see them.
- Before changing lint/detekt/hooks/CI/dependencies, read `docs/agents/tooling.md` (cross-repo identical files,
  version pins, required CI job names).

## Agent workflow
- Issues: local markdown under `.scratch/<feature>/` — `docs/agents/issue-tracker.md`.
- Triage: `Status:` line (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix) — `docs/agents/triage-labels.md`.
- Domain docs: `GLOSSARY.md` + `docs/adr/` — `docs/agents/domain.md`.
- `/grill-with-docs`: one question at a time, then STOP for the answer; start implementation only with explicit permission.
