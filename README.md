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
(~180 MB — the on-device speech model is bundled in), installs it, grants the microphone, and starts it.
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

## For developers — build from source

Portal-Wake builds against the shared **portal-commons** library, which Gradle expects to find right
next to this repo (at `../portal-commons`). Clone both side by side:

```bash
git clone https://github.com/rudysev/portal-commons.git
git clone https://github.com/rudysev/portal-wake.git
cd portal-wake
```

Building needs a JDK (17 or 21), the Android SDK, and the speech model — it's too large to keep in the
repo, so see `app/src/main/assets/model-en-us/README.md` for the one-time download. Then:

```bash
./gradlew assembleDebug
```

(If the model is missing the app still builds and runs — it just listens for nothing, no crash.)

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
