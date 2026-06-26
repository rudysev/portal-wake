# Install portal-wake on your Portal

No building, no command line, no developer tools. You just need the Portal, a USB‑C
cable, and a computer.

## Steps

1. **On the Portal:** open **Settings → Debug** and turn on **ADB Enabled**.
2. **Connect** the Portal to your computer with a **USB‑C cable**.
3. **Double-click** the installer for your computer:
   - **macOS:** `Install-PortalWake.command`
   - **Windows:** `Install-PortalWake.bat`
4. When the Portal screen shows **"Allow USB debugging?"**, tap **Allow** (tick
   "Always allow from this computer").
5. Wait for **"Done."** — then say **"hey jarvis"** near the Portal.

The installer does everything else automatically: it downloads Android's `adb` if you
don't have it, downloads the app (about 180 MB — it includes the on‑device speech
model), installs it, grants the microphone, frees the microphone slot, and starts it.
The app has **no icon** — it runs in the background and starts again on every reboot.

## To remove it

Double-click **`Uninstall-PortalWake`** (`.command` on macOS, `.bat` on Windows). That
removes portal-wake and re-enables the Portal's built-in "Hey Alexa".

## Notes & troubleshooting

- **Windows "blocked files":** Windows marks files downloaded from the internet as
  blocked. If a script won't run, right-click it → **Properties** → tick **Unblock** →
  **OK**, then try again.
- **macOS "unidentified developer":** if double-clicking is blocked, right-click
  `Install-PortalWake.command` → **Open** → **Open**.
- **"More than one device is connected":** unplug other Android devices and re-run.
- **Advanced:** the scripts (`install.sh` / `install.ps1`) also accept `--local`
  (`-Local`) to install a locally built APK, plus `--uninstall` (`-Uninstall`) and
  `--status` (`-Status`). `--local` uses the repo's debug build (`app/build/.../app-debug.apk`),
  or pass a path (`--local <apk>` / `-Apk <path>`); you can also drop an `.apk` into an
  `apks/` folder next to the scripts. Otherwise the latest published release is
  downloaded. Settings live in `config.env`.
