#!/usr/bin/env bash
#
# portal-wake one-click installer for the Meta Portal (macOS / Linux).
#
# Finds (or downloads) Android's adb, waits for a connected Portal, then installs the
# wake app, grants the microphone, frees the mic slot, and starts the headless service.
# No Android SDK, no build tools, no Node — just this script and a USB-C cable.
#
# Usage:
#   ./install.sh             install portal-wake on the connected Portal (downloads the latest release)
#   ./install.sh --local     install a locally built APK (the repo's debug build; pass a path to override)
#   ./install.sh --uninstall remove it and re-enable Meta's "Hey Alexa" detector
#   ./install.sh --status    show whether it's installed

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"   # absolute path to this script — survives the cd below (for --help)
cd "$SCRIPT_DIR"

# ----- pretty output ---------------------------------------------------------
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'; else B=; G=; Y=; R=; D=; N=; fi
step() { printf "%s==>%s %s\n" "$B" "$N" "$1"; }
ok()   { printf "  %s✓%s %s\n" "$G" "$N" "$1"; }
warn() { printf "  %s!%s %s\n" "$Y" "$N" "$1"; }
die()  { printf "%sERROR:%s %s\n" "$R" "$N" "$1" >&2; exit 1; }

# ----- load config -----------------------------------------------------------
[ -f config.env ] || die "config.env not found next to this script."
# shellcheck disable=SC1091
set -a; . ./config.env; set +a

# Local-install support (--local): the repo's standard debug build output, used when
# installing a build instead of downloading a release. Override with --local <apk>.
DEFAULT_BUILD_APK="$SCRIPT_DIR/../app/build/outputs/apk/debug/app-debug.apk"
LOCAL_APK=""

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
resolve_adb() {
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then ADB="$SCRIPT_DIR/platform-tools/adb"; return; fi
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
  step "Android platform-tools (adb) not found — downloading the official package from Google"
  local os zip url
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) die "Unsupported OS for auto-download. Install Android platform-tools and re-run." ;;
  esac
  url="https://dl.google.com/android/repository/platform-tools-latest-${os}.zip"
  zip="$SCRIPT_DIR/platform-tools.zip"
  curl -fL "$url" -o "$zip" || die "Download failed. Check your internet connection."
  unzip -oq "$zip" -d "$SCRIPT_DIR" || die "Could not unzip platform-tools."
  rm -f "$zip"
  [ -x "$SCRIPT_DIR/platform-tools/adb" ] || die "adb missing after download."
  ADB="$SCRIPT_DIR/platform-tools/adb"
  ok "platform-tools installed locally"
}
a() { "$ADB" "$@"; }

# ----- wait for an authorized device -----------------------------------------
wait_for_device() {
  step "Looking for your Portal"
  a start-server >/dev/null 2>&1
  local printed_plug=0 printed_auth=0
  while true; do
    local raw devs n state line
    raw="$(a devices)"   # query adb once per poll, then parse it for both the serial list and the state line
    devs="$(printf "%s\n" "$raw" | awk 'NR>1 && $2=="device"{print $1}')"
    n="$(printf "%s" "$devs" | grep -c . || true)"
    if [ "$n" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
      die "More than one device is connected. Unplug the others (or set ANDROID_SERIAL=<serial>) and re-run."
    elif [ "$n" = 1 ]; then
      ANDROID_SERIAL="$devs"; export ANDROID_SERIAL; state="device"
    else
      line="$(printf "%s\n" "$raw" | awk 'NR>1 && NF{print; exit}')"
      state="$(printf "%s" "$line" | awk '{print $2}')"
    fi
    case "$state" in
      device)
        local model; model="$(a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        ok "Connected: ${model:-device}"
        return ;;
      unauthorized)
        if [ "$printed_auth" = 0 ]; then
          printf "  %sOn the Portal screen, tap %sAllow%s (check \"Always allow from this computer\").%s\n" "$Y" "$B" "$N$Y" "$N"
          printed_auth=1
        fi ;;
      *)
        if [ "$printed_plug" = 0 ]; then
          printf "  %sPlug the Portal into this computer with a USB-C cable.%s\n" "$Y" "$N"
          printf "  %sOn the Portal: Settings > Debug > ADB Enabled.%s\n" "$D" "$N"
          printed_plug=1
        fi ;;
    esac
    sleep 2
  done
}

# ----- actions ---------------------------------------------------------------
# Portable SHA-256 of a file (macOS shasum / Linux sha256sum); empty if neither tool exists.
sha256() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else echo ""; fi
}

# Resolve the latest release's first .apk asset, printed as three lines: download URL, the
# version-stamped asset name (e.g. portal-wake-v2.1.0.apk), and its sha256 digest (the last may be
# empty for assets uploaded before GitHub recorded digests). An explicit RELEASE_APK_URL pins a
# build; otherwise we ask GitHub for the latest release on RELEASE_REPO. Non-zero if nothing
# resolves (e.g. offline) so the caller can fall back to a cached APK.
resolve_release_asset() {
  if [ -n "${RELEASE_APK_URL:-}" ]; then printf '%s\n%s\n\n' "$RELEASE_APK_URL" "$(basename "${RELEASE_APK_URL%%\?*}")"; return 0; fi
  [ -n "${RELEASE_REPO:-}" ] || return 1
  local json url digest
  json="$(curl -fsSL --retry 3 --retry-all-errors --retry-delay 2 --connect-timeout 30 \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${RELEASE_REPO}/releases/latest" 2>/dev/null)" || return 1
  [ -n "$json" ] || return 1
  url="$(printf '%s' "$json" | grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*\.apk"' | head -1 | sed 's/.*"\(https[^"]*\)".*/\1/')"
  [ -n "$url" ] || return 1
  digest="$(printf '%s' "$json" | grep -o '"digest"[[:space:]]*:[[:space:]]*"sha256:[0-9a-f]*"' | head -1 | sed 's/.*sha256:\([0-9a-f]*\).*/\1/')"
  printf '%s\n%s\n%s\n' "$url" "$(basename "${url%%\?*}")" "$digest"
}

install_app() {
  local apk
  if [ -n "$LOCAL_APK" ]; then
    [ -f "$LOCAL_APK" ] || die "Local build not found: $LOCAL_APK (run ./gradlew assembleDebug first, or pass a path: --local <apk>)."
    apk="$LOCAL_APK"
    step "Using local build: $apk"
  else
    # Cache the release under its real (version-stamped) name and reuse it ONLY when it matches the
    # latest asset name AND its sha256 matches the release digest — so re-running after a new release
    # downloads the update instead of reinstalling a stale cache, and a truncated/corrupt download is
    # caught. A cached APK is used as-is only when GitHub is unreachable. (Use --local to force a build.)
    local cachedir asset url name digest have
    cachedir="$(dirname "$APK_GLOB")"
    asset="$(resolve_release_asset || true)"
    url="$(printf '%s' "$asset" | sed -n 1p)"
    name="$(printf '%s' "$asset" | sed -n 2p)"
    digest="$(printf '%s' "$asset" | sed -n 3p)"
    if [ -n "$url" ] && [ -n "$name" ]; then
      apk="$cachedir/$name"
      have=""; [ -f "$apk" ] && have="$(sha256 "$apk")"
      if [ -f "$apk" ] && { [ -z "$digest" ] || [ -z "$have" ] || [ "$have" = "$digest" ]; }; then
        step "Latest portal-wake release already downloaded ($name) — using it"
      else
        step "Downloading the latest portal-wake release ($name — bundles the speech model, so it's large)"
        mkdir -p "$cachedir"
        curl -fSL --retry 3 --retry-all-errors --retry-delay 2 --connect-timeout 30 \
          -o "$apk.part" "$url" || die "Could not download the release APK. Check your connection."
        if [ -n "$digest" ] && [ -n "$(sha256 "$apk.part")" ] && [ "$(sha256 "$apk.part")" != "$digest" ]; then
          rm -f "$apk.part"; die "Downloaded APK failed checksum verification — re-run to retry."
        fi
        mv -f "$apk.part" "$apk"
        find "$cachedir" -maxdepth 1 -name '*.apk' ! -name "$name" -delete 2>/dev/null || true # prune stale copies
        ok "Downloaded $name"
      fi
    else
      apk="$(ls -t $APK_GLOB 2>/dev/null | head -1)"
      [ -n "$apk" ] || die "No cached APK in apks/ and couldn't reach GitHub to download one. Connect to the internet, drop a portal-wake APK in apks/, or use --local."
      warn "Couldn't reach GitHub — installing the cached APK ($(basename "$apk")); it may not be the latest."
    fi
  fi
  step "Installing portal-wake ($(basename "$apk"))"
  a install -r -d "$apk" >/dev/null 2>&1 && ok "Installed $PKG" || die "Install failed."
}

grant_mic() {
  step "Granting the microphone"
  a shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1
  ok "Microphone granted"
}

free_mic_slot() {
  # The Portal has a single handset-mic slot. Meta's native "Hey Alexa" detector
  # (com.millennium) holds it with an always-on listener and starves portal-wake, so
  # disable it if present. Uninstall re-enables it. (We never touch the Alexa client.)
  if a shell pm list packages "$MILLENNIUM_PKG" 2>/dev/null | tr -d '\r' | grep -q "package:$MILLENNIUM_PKG"; then
    step "Freeing the microphone (disabling Meta's \"Hey Alexa\" detector)"
    a shell pm disable-user --user 0 "$MILLENNIUM_PKG" >/dev/null 2>&1 \
      && ok "Disabled $MILLENNIUM_PKG" \
      || warn "Couldn't disable $MILLENNIUM_PKG — wake may be starved by the native \"Hey Alexa\" detector."
  else
    ok "No competing \"Hey Alexa\" detector on this device"
  fi
}

start_service() {
  # The app is headless (no icon). Kick the exported BootReceiver to start the
  # foreground service and clear Android's "stopped" state so it also comes up on
  # later reboots. -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES (reaches the fresh app).
  step "Starting portal-wake (it runs in the background — no icon)"
  # Then wait until the recognizer is actually listening before telling the user to
  # speak — the Vosk model + grammar take a few seconds to load, and a "hey jarvis"
  # said before that can be missed. The service logs "wake recognizer ready" once
  # it's listening; count existing lines first so a reinstall (debug.txt persists)
  # waits for a NEW one rather than matching a stale line.
  local log="/sdcard/Android/data/$PKG/files/debug.txt"
  local base; base=$(a shell "grep -c 'wake recognizer ready' $log 2>/dev/null" | tr -dc '0-9'); base=${base:-0}
  a shell "am broadcast -a $START_ACTION -n $RECEIVER -f 0x20" >/dev/null 2>&1
  local i=0
  while [ $i -lt 30 ]; do
    local n; n=$(a shell "grep -c 'wake recognizer ready' $log 2>/dev/null" | tr -dc '0-9'); n=${n:-0}
    [ "$n" -gt "$base" ] && { ok "Listening"; return; }
    sleep 1; i=$((i + 1))
  done
  warn "Started, but couldn't confirm the listener came up — give it a few seconds before saying \"hey jarvis\"."
}

do_install() {
  printf "%sportal-wake installer%s\n" "$B" "$N"
  printf "%sInstalls the always-on \"hey jarvis\" wake listener on your Portal and starts it.%s\n\n" "$D" "$N"
  resolve_adb
  wait_for_device
  install_app
  grant_mic
  free_mic_slot
  start_service
  printf "\n%s✓ Done. portal-wake is listening — say \"hey jarvis\" near the Portal.%s\n" "$G$B" "$N"
  printf "%sTo remove it: re-run with --uninstall (or double-click Uninstall-PortalWake).%s\n" "$D" "$N"
}

do_uninstall() {
  printf "%sportal-wake uninstaller%s\n\n" "$B" "$N"
  resolve_adb
  wait_for_device
  # Restore Meta's "Hey Alexa" detector if WE disabled it, so the device returns to its original state.
  # Detect with -d (disabled), the same way do_status does: a `disable-user` package can be hidden from
  # the default `pm list packages` on some Android builds, which would silently skip the re-enable.
  if a shell pm list packages -d "$MILLENNIUM_PKG" 2>/dev/null | tr -d '\r' | grep -q "package:$MILLENNIUM_PKG"; then
    step "Re-enabling Meta's \"Hey Alexa\" detector"
    a shell pm enable "$MILLENNIUM_PKG" >/dev/null 2>&1 && ok "Re-enabled $MILLENNIUM_PKG" || warn "Couldn't re-enable $MILLENNIUM_PKG — re-enable it from the Portal's app settings."
  fi
  step "Stopping and removing portal-wake"
  a shell am force-stop "$PKG" >/dev/null 2>&1
  a uninstall "$PKG" >/dev/null 2>&1 && ok "Uninstalled $PKG" || warn "portal-wake was not installed."
  printf "\n%s✓ Done. portal-wake removed; the stock \"Hey Alexa\" detector is back.%s\n" "$G$B" "$N"
}

do_status() {
  resolve_adb; wait_for_device
  step "Current state"
  printf "  portal-wake: %s\n" "$(a shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo installed || echo 'not installed')"
  printf "  Hey Alexa (%s): %s\n" "$MILLENNIUM_PKG" "$(a shell pm list packages -d "$MILLENNIUM_PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo disabled || echo enabled)"
}

case "${1:-}" in
  --uninstall|--restore|-u) do_uninstall ;;
  --status|-s)              do_status ;;
  --local|-l)               LOCAL_APK="${2:-$DEFAULT_BUILD_APK}"; do_install ;;
  --help|-h)                sed -n '2,16p' "$SELF" | sed 's/^# \{0,1\}//' ;;
  "")                       do_install ;;
  *)                        die "Unknown option: $1 (use --local, --uninstall, --status, or no argument)" ;;
esac
