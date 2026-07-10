# Portal-Wake

A tiny, headless, always-on **wake-word listener** for the Meta Portal+ (1st gen, model "aloha",
Android 9 / API 28). It owns the microphone, runs an on-device **openWakeWord** neural detector, and —
when it hears a wake phrase — hands the mic off to whichever app should take the turn. It has **no launcher
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

The installer handles everything else: it downloads Android's `adb` if needed, installs the app (the
openWakeWord ONNX models are bundled in the APK), grants the microphone, and starts it.
The app has **no icon**; it runs in the background and comes back on every reboot. Say **"hey jarvis"**
near the Portal. To remove it, double-click **`Uninstall-PortalWake`** — this also re-enables Meta's
built-in "Hey Alexa" detector, returning the Portal to its original state.

See [`provisioning/README.md`](provisioning/README.md) for details and troubleshooting (including the
Windows "unblock files" step).

## Plugins — adding a "hey X" without touching this app

Wake words are **discovered at runtime**, not hard-coded. Any installed app becomes a wake plugin by
declaring an exported receiver that responds to `com.portal.wake.action.WAKE` and carries its wake word
as **named meta-data** — one field per setting.

Portal-Wake detects wake phrases with **openWakeWord** — a per-phrase neural classifier (`.onnx`), not a
general speech recognizer. Built-in **"hey jarvis"** and **"hey alexa"** ship with bundled models; any
**custom** phrase needs a model you train and ship inside your plugin APK.

Example — an app that adds "hey jarvis":

```xml
<receiver android:name=".WakeHandoffReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.portal.wake.action.WAKE" />
    </intent-filter>
    <meta-data android:name="com.portal.wake.phrase"         android:value="hey jarvis" />
    <meta-data android:name="com.portal.wake.min_confidence" android:value="0.5" />
    <!-- optional: com.portal.wake.id  (defaults to the keyword, here "jarvis") -->
</receiver>
```

Example — a **custom** wake word (requires your own model):

```xml
<receiver android:name=".WakeHandoffReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.portal.wake.action.WAKE" />
    </intent-filter>
    <meta-data android:name="com.portal.wake.phrase"         android:value="hey computer" />
    <meta-data android:name="com.portal.wake.min_confidence" android:value="0.5" />
    <meta-data android:name="com.portal.wake.model"         android:value="oww/hey_computer.onnx" />
</receiver>
```

Put the `.onnx` file at that asset path inside **your** app (`app/src/main/assets/oww/hey_computer.onnx`).
Portal-Wake loads it from your APK at runtime — no change to portal-wake itself.

### Training a custom `.onnx` model

openWakeWord models are small ONNX classifiers (~1 MB) trained for **one phrase**. Built-in
`"hey jarvis"` / `"hey alexa"` heads ship inside `portal-commons` (`assets/oww/`). Any other phrase
needs a classifier **you** train and ship in **your plugin APK**.

1. **Train** — follow the [openWakeWord training guide](https://github.com/dscripka/openWakeWord#training-models)
   (Python notebook in the upstream repo), or use the hosted trainer at
   [openwakeword.com](https://openwakeword.com/). Export the resulting `.onnx` **classifier head only**
   (do not re-ship `melspectrogram.onnx` / `embedding_model.onnx` — those shared stages are already
   bundled in the Portal apps via commons).
2. **Include in your plugin** — put the file under your app's assets, e.g.
   `app/src/main/assets/oww/hey_computer.onnx`.
3. **Declare it** — set `com.portal.wake.model` to that asset path (see example above).
4. **Dynamic load** — on start (and when packages change), portal-wake's `WakeRegistry` discovers
   plugin receivers; `OwwHeadResolver` opens your APK's assets via `createPackageContext` and builds an
   openWakeWord head for each phrase that has model bytes. No rebuild of portal-wake is required —
   install/update your plugin and the new wake word becomes detectable.

Tune detection with `com.portal.wake.min_confidence` (0.0–1.0, default 0.5): lower = more sensitive,
higher = fewer false triggers. Every fire logs its score to `debug.txt`
(`wake detected → jarvis [oww p=0.872]` at `/sdcard/Android/data/com.portal.wake/files/debug.txt`).

### Meta-data reference

- **`phrase`** (required) — the full spoken phrase. Portal-Wake takes the **last word as the keyword**
  and the **word before it as the lead** ("hey", "hi", …). A single word like `computer` registers with
  no required lead.
- **`min_confidence`** (optional, default 0.5) — the openWakeWord **detection threshold** for this phrase.
- **`model`** (required for custom phrases) — path to your `.onnx` classifier inside **your app's assets**
  (e.g. `oww/hey_computer.onnx`). Omit for built-in "hey jarvis" / "hey alexa" — those models ship in
  portal-wake.
- **`id`** (optional) — the value reported back on a match; defaults to the keyword.
- **One receiver = one wake word.** To register several, declare several receivers.

## For developers — build from source

Portal-Wake builds against the shared **portal-commons** library, which Gradle expects to find right
next to this repo (at `../portal-commons`). Clone both side by side:

```bash
git clone https://github.com/rudysev/portal-commons.git
git clone https://github.com/rudysev/portal-wake.git
cd portal-wake
```

Building needs a JDK (17 or 21) and the Android SDK. The openWakeWord models are bundled in
`portal-commons` — no separate download step:

```bash
./gradlew assembleDebug
```

`./setup.sh` installs your local build on a connected Portal (grants the mic, frees the slot, starts the
headless service); `./setup.sh --restore` re-enables the native "Hey Alexa" detector (com.millennium) and
stops it. To publish a build for the one-click installer above, `scripts/cut-release.sh <tag>` uploads the
APK to a GitHub Release.

## Disclaimer

Portal-Wake is an independent community project — **not affiliated with, endorsed by, or sponsored by
Meta**. "Meta Portal" and "Portal" are trademarks of Meta Platforms, Inc., used here only to identify
compatible hardware. It is a sideloaded app for discontinued devices and is **use-at-your-own-risk** (may
void warranty; no guarantees). It listens to the microphone for its wake word **on-device only** — nothing
is recorded or sent anywhere. See [DISCLAIMER.md](DISCLAIMER.md) for the full text and privacy notes.
