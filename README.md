# Portal-Wake

A tiny, headless, always-on **wake-word listener** for the Meta Portal+ (1st gen, model "aloha",
Android 9 / API 28). It owns the microphone, runs an on-device speech recognizer, and — when it
hears a wake phrase — hands the mic off to whichever app should take the turn. It has **no launcher
icon and no UI**: it starts on boot and lives entirely in the background.

Portal-Wake is a small, standalone wake-detection service, so **any app can register its own "hey X"**
rather than wake words being hard-coded.

<p align="center">
  <a href="https://buymeacoffee.com/linuxbarista"><img src="docs/img/bmc-button.png" alt="Buy Me A Coffee" width="200"></a>
</p>

## Install

The easy way — no building, no command line:

1. On the Portal, open **Settings → Debug** and turn on **ADB Enabled**.
2. Connect the Portal to your computer with a **USB‑C cable**.
3. [Download this repository as a ZIP](https://github.com/rudysev/portal-wake/archive/refs/heads/main.zip)
   and unzip it.
4. Open the `provisioning` folder and double-click **`Install-PortalWake.command`** (macOS) or
   **`Install-PortalWake.bat`** (Windows).
5. When the Portal asks **"Allow USB debugging?"**, tap **Allow**.

The installer handles everything else: it downloads Android's `adb` if needed, downloads the app
(a large download — the on-device speech model is bundled in), installs it, grants the microphone, and starts it.
The app has **no icon**; it runs in the background and comes back on every reboot. Say **"hey jarvis"**
near the Portal. To remove it, double-click **`Uninstall-PortalWake`** — this also re-enables Meta's
built-in "Hey Alexa" detector, returning the Portal to its original state.

See [`provisioning/README.md`](provisioning/README.md) for details and troubleshooting (including the
Windows "unblock files" step).

## Plugins — adding a "hey X" without touching this app

Wake words are **discovered at runtime**, not hard-coded. Any installed app becomes a wake plugin by
declaring an exported receiver that responds to `com.portal.wake.action.WAKE` and carries its wake word
as **named meta-data** — one field per setting. Example — an app that adds "hey jarvis":

```xml
<receiver android:name=".WakeHandoffReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.portal.wake.action.WAKE" />
    </intent-filter>
    <meta-data android:name="com.portal.wake.phrase"         android:value="hey jarvis" />
    <meta-data android:name="com.portal.wake.min_confidence" android:value="0.55" />
    <!-- optional: com.portal.wake.id  (defaults to the keyword, here "jarvis") -->
</receiver>
```

- **`phrase`** (required) — the full spoken phrase. Portal-Wake takes the **last word as the keyword**
  and the **word before it as the lead** ("hey", "hi", …). The lead a user must say therefore lives in
  *your* phrase, not in Portal-Wake — so `hi bob` works just as well, and a single word like `computer`
  registers with no required lead.
- **`min_confidence`** (optional, default ~0.5) — the keyword-confidence floor; above the baseline it
  enables the strict (precise) matching used for hand-off words like jarvis. A missing or malformed value
  falls back to the default (and is logged); it never disables the wake word.
- **`id`** (optional) — the value reported back on a match; defaults to the keyword.
- **One receiver = one wake word.** To register several, declare several receivers — each is discovered
  independently.
- **Your app must record after a wake.** Portal-Wake hands you the mic and takes it back only once it has
  seen you record and then stop — there is no "done" signal and no timeout. So the plugin needs
  `RECORD_AUDIO` and must open the mic on every wake, even briefly. A plugin that never records leaves
  Portal-Wake deaf until the service is restarted.

## For developers — build from source

Portal-Wake builds against the shared **portal-commons** library, which Gradle expects to find right
next to this repo (at `../portal-commons`). Clone both side by side:

```bash
git clone https://github.com/rudysev/portal-commons.git
git clone https://github.com/rudysev/portal-wake.git
cd portal-wake
```

Building needs a JDK (17 or 21), the Android SDK, and the speech model — it's too large to keep in the
repo, so see `app/src/main/assets/model-en-us/README.md` for the one-time download. The hybrid wake detector's
sherpa-onnx library and models come from GitHub releases, pinned by SHA-256 (see
[Hybrid wake detector](#hybrid-wake-detector-prototype)). Then:

```bash
./fetch-hybrid-deps.sh
./gradlew assembleDebug
```

(If the model is missing the app still builds and runs — it just listens for nothing, no crash. Without the hybrid's
models it runs with Vosk alone; without the sherpa-onnx library, `./fetch-hybrid-deps.sh --libs`, it doesn't build.)

Verify a change with `./gradlew check` (unit tests, Android Lint, detekt and ktlint; `./gradlew formatKotlin` fixes
style). Turn on the repo's git hooks once per clone with `git config core.hooksPath .githooks`: `pre-commit` runs the
ktlint and detekt CLIs on staged Kotlin files and shellcheck on staged shell scripts, and `pre-push` runs
`./gradlew check`. Install the CLIs with `brew install ktlint shellcheck`, plus detekt 2.0.0-alpha.6's `detekt-cli`
from
[the release zip](https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip)
on your `PATH` (Homebrew only has detekt 1.x).

`./setup.sh` installs your local build on a connected Portal (grants the mic, frees the slot, starts the
headless service); `./setup.sh --restore` re-enables the native "Hey Alexa" detector (com.millennium) and
stops it. To publish a build for the one-click installer above, `scripts/cut-release.sh <tag>` uploads the
APK to a GitHub Release.

## Hybrid wake detector (prototype)

A second wake detector for "hey jarvis", run on the Portal to collect real-voice evidence before it could replace
Vosk. It runs beside the Vosk detector inside the same engine, on the same `VOICE_RECOGNITION` capture, and hands off
exactly as Vosk does. **It is the default in this build**; one adb command switches back to Vosk alone.

- **Gate:** openWakeWord `hey_jarvis` scores every 80 ms; a score ≥ 0.3 opens a **candidate**.
- **Two checks** on the ~2 s window ending 400 ms later, on their own thread:
  - sherpa-onnx's keyword spotter with **competing keywords** (`app/src/main/assets/hybrid/hey_jarvis_compete.txt`:
    HEY JARVIS plus OK/Okay/Hi Jarvis and Hey Jervis);
  - the production **Vosk** grammar and `WakeMatcher` gates on that window alone.
- **Wake** when the spotter heard HEY JARVIS and nothing else, or when Vosk accepts the window and the spotter heard
  no rival. A spotter confirmation wakes at once, ~1.0 s after the phrase ends (median on a Portal+); one that needs
  the Vosk check wakes ~2.3 s after it. A command run on with no pause ("Hey Jarvis, what's…") is not a target.
- **Vosk shadow** (on by default): the Vosk-only detector keeps running on every frame and its decisions are logged
  but not acted on, so every utterance has both verdicts. When a hybrid wake hands off, the shadow's decode in flight
  is finalized first, so it isn't cut off.
- If the gate or spotter can't load (a build without the models), Vosk decides alone and `debug.txt` says
  `hybrid unavailable`.

Cost on a gen-1 Portal+ (measured): the capture thread (Vosk shadow) ~60 % of one core and the gate thread ~34 %, so
about one core of eight, with no dropped frames. With the shadow off the Vosk share goes away; Vosk-only mode runs no
hybrid at all.

### Switching and marking

[`scripts/hybrid-evidence.sh`](scripts/hybrid-evidence.sh) wraps the adb broadcasts (only `adb shell` may send them:
the receiver requires `DUMP`). A change applies at the next frame and persists across reboots:

```bash
scripts/hybrid-evidence.sh detector vosk     # fall back to Vosk alone, instantly
scripts/hybrid-evidence.sh detector hybrid   # the prototype (default)
scripts/hybrid-evidence.sh shadow off        # stop the Vosk shadow (saves ~45 % of a core)
scripts/hybrid-evidence.sh mark              # "I said Hey Jarvis and nothing woke": saves the last 30 s
```

The raw broadcast is `adb shell am broadcast -a com.portal.wake.action.SET_DETECTOR -p com.portal.wake --es detector
vosk` (or `--ez shadow false`), and `-a com.portal.wake.action.MARK_MISS` for a mark. Set `ANDROID_SERIAL` to pick
the Portal when more than one device is attached.

### The evidence and how to read it

Everything lives in the app's internal storage, `/data/data/com.portal.wake/files/hybrid/`, which other apps can't
read (`debug.txt` stays in `/sdcard/Android/data/com.portal.wake/files/`). adb reaches it only through `run-as`, so
pulling it needs the debug build `setup.sh` installs:

- `evidence.jsonl`: one JSON object per line, each with `type`, `at` (ISO-8601 UTC) and `atMs`; rotated to `.1` at
  20 MB.
  - `candidate`: `gatePeak`, `spotted` (the spotter's keywords), `rival`, `vosk` (the window check: `wake`,
    `near-miss: <reason>`, `none`, `unavailable`), `voskTranscript`, `woke`, `wokeBy` (`sherpa` / `vosk-window`),
    `spotterMs`, `voskMs`, `clip`.
  - `vosk_wake`: `role` `decided` (Vosk mode) or `shadow`, `atHandoff`, `transcript`, `clip`.
  - `disagreement`: `kind` `vosk-only` (the shadow woke and the hybrid didn't: a hybrid miss or a Vosk false wake)
    or `hybrid-only`, with the nearest candidate's verdict. Settled 8 s after the utterance.
  - `miss_mark`, `stats` (once a minute: per-frame cost, dropped frames), `session` and `detector` (changes).
- `clips/`: the audio behind each record, as 16 kHz mono WAV: `cand_*` / `cand-woke_*` (4 s ending where the
  checked window ends), `vosk-shadow_*` / `vosk-woke_*` (5 s), `miss_*` (30 s). Capped at 200 MB in all, oldest
  deleted first. **Debug builds only**: a release build writes the log without audio.

Pull and summarise:

```bash
scripts/hybrid-evidence.sh pull ~/portal-evidence/day1   # copies hybrid/ (via run-as) and debug.txt, then the summary
scripts/hybrid-evidence.sh summary ~/portal-evidence/day1
```

The summary (needs `jq`) counts candidates, hybrid wakes by path, rejections, Vosk shadow wakes, disagreements and
marks, and lists every wake, disagreement and mark with its clip. To judge a day: every `hybrid wake` you didn't say
is a false wake (the bar is about 3 or fewer per 24 h); every `vosk-only` disagreement and every `marked miss` is
worth a listen, since that is where the hybrid may have missed a real "Hey Jarvis".

The audio is the household's: it stays in the app's private storage on the device, out of other apps' reach, and in
whatever directory you pull it to.

The openWakeWord models are CC BY-NC-SA 4.0 (non-commercial) and sherpa-onnx is Apache-2.0; `fetch-hybrid-deps.sh`
downloads them, nothing third-party is committed.

## Disclaimer

Portal-Wake is an independent community project — **not affiliated with, endorsed by, or sponsored by
Meta**. "Meta Portal" and "Portal" are trademarks of Meta Platforms, Inc., used here only to identify
compatible hardware. It is a sideloaded app for discontinued devices and is **use-at-your-own-risk** (may
void warranty; no guarantees). It listens to the microphone for its wake word **on-device only** — nothing
is recorded or sent anywhere. See [DISCLAIMER.md](DISCLAIMER.md) for the full text and privacy notes.
