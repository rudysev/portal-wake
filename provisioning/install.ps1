# portal-wake one-click installer for the Meta Portal (Windows).
#
# Finds (or downloads) Android's adb, waits for a connected Portal, then installs the
# wake app, grants the microphone, frees the mic slot, and starts the headless service.
# No Android SDK, no build tools, no Node — just this script and a USB-C cable.
#
# Usage:
#   .\install.ps1             install portal-wake on the connected Portal (downloads the latest release)
#   .\install.ps1 -Local      install a locally built APK (the repo's debug build; -Apk <path> to override)
#   .\install.ps1 -Uninstall  remove it and re-enable Meta's "Hey Alexa" detector
#   .\install.ps1 -Status     show whether it's installed
param([switch]$Uninstall, [switch]$Status, [switch]$Local, [string]$Apk)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# Stock Windows PowerShell 5.1 defaults to old TLS and an IE-based HTML parser, both
# of which break HTTPS downloads from Google / GitHub. Force TLS 1.2 + basic parsing.
try {
  [Net.ServicePointManager]::SecurityProtocol =
      [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {}
$PSDefaultParameterValues['Invoke-WebRequest:UseBasicParsing'] = $true

function Step($m){ Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m){ Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "  [!] $m" -ForegroundColor Yellow }
function Die($m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ----- load config.env -------------------------------------------------------
if (-not (Test-Path config.env)) { Die "config.env not found next to this script." }
$cfg = @{}
Get-Content config.env | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
    $i = $line.IndexOf("="); $val = $line.Substring($i+1).Trim().Trim('"').Trim("'")
    $cfg[$line.Substring(0,$i).Trim()] = $val
  }
}

# Local-install support (-Local): the repo's standard debug build output. -Apk overrides the path.
$LocalApk = if ($Local) { if ($Apk) { $Apk } else { Join-Path $ScriptDir "..\app\build\outputs\apk\debug\app-debug.apk" } } else { "" }

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
function Resolve-Adb {
  $bundled = Join-Path $ScriptDir "platform-tools\adb.exe"
  if (Test-Path $bundled) { return $bundled }
  $onPath = (Get-Command adb -ErrorAction SilentlyContinue)
  if ($onPath) { return $onPath.Source }
  Step "Android platform-tools (adb) not found - downloading the official package from Google"
  $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  $zip = Join-Path $ScriptDir "platform-tools.zip"
  Invoke-WebRequest -Uri $url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $ScriptDir -Force
  Remove-Item $zip
  if (-not (Test-Path $bundled)) { Die "adb missing after download." }
  Ok "platform-tools installed locally"
  return $bundled
}
$ADB = Resolve-Adb
function A { & $ADB @args }

# ----- wait for an authorized device -----------------------------------------
function Wait-Device {
  Step "Looking for your Portal"
  A start-server | Out-Null
  $plug=$false; $auth=$false
  while ($true) {
    $raw = @(A devices | Select-Object -Skip 1)   # query adb once per poll, then filter it twice below
    $devs = @($raw | Where-Object { $_ -match "^\S+\s+device\b" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devs.Count -gt 1 -and -not $env:ANDROID_SERIAL) { Die "More than one device is connected. Unplug the others and re-run." }
    if ($devs.Count -eq 1) { $env:ANDROID_SERIAL = $devs[0]; $state = "device" }
    else {
      $line = ($raw | Where-Object { $_.Trim() } | Select-Object -First 1)
      $state = if ($line) { ($line -split "\s+")[1] } else { "" }
    }
    switch ($state) {
      "device" { $model = "$(A shell getprop ro.product.model)".Trim(); Ok "Connected: $model"; return }
      "unauthorized" { if (-not $auth) { Warn "On the Portal screen, tap Allow (check 'Always allow from this computer')."; $auth=$true } }
      default { if (-not $plug) { Warn "Plug the Portal into this PC via USB-C. On the Portal: Settings > Debug > ADB Enabled."; $plug=$true } }
    }
    Start-Sleep -Seconds 2
  }
}

# ----- actions ---------------------------------------------------------------
# SHA-256 (lowercase hex) of a file.
function Get-Sha256 { param($Path) (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower() }

# Resolve the latest release's first .apk asset as an object: Url, version-stamped Name, and sha256
# Digest (Digest may be "" for assets uploaded before GitHub recorded one). An explicit
# RELEASE_APK_URL pins a build; otherwise ask GitHub for the latest release on RELEASE_REPO. Returns
# $null when nothing resolves (e.g. offline) so the caller can fall back to a cached APK.
function Resolve-ReleaseAsset {
  if ($cfg["RELEASE_APK_URL"]) {
    $u = $cfg["RELEASE_APK_URL"]
    return [pscustomobject]@{ Url = $u; Name = (Split-Path -Leaf (($u -split '\?')[0])); Digest = "" }
  }
  if (-not $cfg["RELEASE_REPO"]) { return $null }
  try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$($cfg["RELEASE_REPO"])/releases/latest" `
      -Headers @{ "User-Agent" = "portal-wake-installer"; "Accept" = "application/vnd.github+json" }
    $asset = $rel.assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1
    if (-not $asset) { return $null }
    $digest = ""
    if ($asset.digest -and $asset.digest -like "sha256:*") { $digest = $asset.digest.Substring(7) }
    return [pscustomobject]@{ Url = $asset.browser_download_url; Name = $asset.name; Digest = $digest }
  } catch { return $null }
}

function Install-App {
  if ($LocalApk) {
    if (-not (Test-Path $LocalApk)) { Die "Local build not found: $LocalApk (run .\gradlew assembleDebug first, or pass -Apk <path>)." }
    $apk = Get-Item $LocalApk
    Step "Using local build: $($apk.FullName)"
  } else {
    # Cache the release under its real (version-stamped) name and reuse it ONLY when it matches the
    # latest asset name AND its sha256 matches the release digest - so re-running after a new release
    # downloads the update instead of reinstalling a stale cache, and a corrupt download is caught.
    # A cached APK is used as-is only when GitHub is unreachable. (Use -Local to force a build.)
    $dir = Split-Path -Parent $cfg["APK_GLOB"]
    $asset = Resolve-ReleaseAsset
    if ($asset -and $asset.Url -and $asset.Name) {
      $dest = Join-Path $dir $asset.Name
      $fresh = (Test-Path $dest) -and ((-not $asset.Digest) -or ((Get-Sha256 $dest) -eq $asset.Digest))
      if ($fresh) {
        Step "Latest portal-wake release already downloaded ($($asset.Name)) - using it"
      } else {
        Step "Downloading the latest portal-wake release ($($asset.Name), ~180 MB - bundles the speech model)"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $part = "$dest.part"
        $oldPref = $ProgressPreference; $ProgressPreference = "SilentlyContinue"  # the progress bar cripples large downloads on Windows PowerShell 5.1
        try { Invoke-WebRequest -Uri $asset.Url -OutFile $part } finally { $ProgressPreference = $oldPref }
        if ($asset.Digest -and (Get-Sha256 $part) -ne $asset.Digest) {
          Remove-Item -Force $part; Die "Downloaded APK failed checksum verification - re-run to retry."
        }
        Move-Item -Force $part $dest
        Get-ChildItem -Path (Join-Path $dir "*.apk") -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne $asset.Name } | Remove-Item -Force -ErrorAction SilentlyContinue  # prune stale copies
        Ok "Downloaded $($asset.Name)"
      }
      $apk = Get-Item $dest
    } else {
      $apk = Get-ChildItem -Path $cfg["APK_GLOB"] -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
      if (-not $apk) { Die "No cached APK in apks\ and couldn't reach GitHub to download one. Connect to the internet, drop a portal-wake APK in apks\, or use -Local." }
      Warn "Couldn't reach GitHub - installing the cached APK ($($apk.Name)); it may not be the latest."
    }
  }
  Step "Installing portal-wake ($($apk.Name))"
  A install -r -d $apk.FullName | Out-Null
  Ok "Installed $($cfg["PKG"])"
}

function Grant-Mic {
  Step "Granting the microphone"
  A shell pm grant $cfg["PKG"] android.permission.RECORD_AUDIO | Out-Null
  Ok "Microphone granted"
}

function Free-MicSlot {
  $m = $cfg["MILLENNIUM_PKG"]
  if ((A shell pm list packages $m) -match "package:$m") {
    Step "Freeing the microphone (disabling Meta's 'Hey Alexa' detector)"
    A shell pm disable-user --user 0 $m | Out-Null; Ok "Disabled $m"
  } else { Ok "No competing 'Hey Alexa' detector on this device" }
}

function Start-WakeService {
  Step "Starting portal-wake (it runs in the background - no icon)"
  # Wait until the recognizer is actually listening before telling the user to speak — the
  # Vosk model + grammar take a few seconds to load, and a "hey jarvis" said before that can
  # be missed. The service logs "wake recognizer ready" once it's listening; count existing
  # lines first so a reinstall (debug.txt persists) waits for a NEW one, not a stale line.
  $log = "/sdcard/Android/data/$($cfg["PKG"])/files/debug.txt"
  # Count "wake recognizer ready" lines, robust to the file not existing yet (first run): a missing
  # file makes adb/grep return an empty or blank-line array, and feeding that array straight to [int]
  # throws "Cannot convert System.Object[] to System.Int32". Collapse to a scalar, strip non-digits,
  # and treat "no number" as 0.
  function Count-Ready {
    $digits = ((A shell "grep -c 'wake recognizer ready' $log 2>/dev/null") -join "`n") -replace '\D',''
    if ($digits) { [int]$digits } else { 0 }
  }
  $base = Count-Ready
  A shell "am broadcast -a $($cfg["START_ACTION"]) -n $($cfg["RECEIVER"]) -f 0x20" | Out-Null
  for ($i = 0; $i -lt 30; $i++) {
    $n = Count-Ready
    if ($n -gt $base) { Ok "Listening"; return }
    Start-Sleep -Seconds 1
  }
  Warn "Started, but couldn't confirm the listener came up - give it a few seconds before saying ""hey jarvis""."
}

if ($Status) {
  Wait-Device
  Step "Current state"
  $inst = (A shell pm list packages $cfg["PKG"]) -match "package:"
  Write-Host "  portal-wake: $(if ($inst) {'installed'} else {'not installed'})"
  exit 0
}

if ($Uninstall) {
  Write-Host "portal-wake uninstaller`n"
  Wait-Device
  $m = $cfg["MILLENNIUM_PKG"]
  # Restore Meta's "Hey Alexa" detector if WE disabled it, so the device returns to its original state.
  # Detect with -d (disabled): a disable-user package can be hidden from the default `pm list packages`
  # on some Android builds, which would silently skip the re-enable.
  if ((A shell pm list packages -d $m) -match "package:$m") {
    Step "Re-enabling Meta's 'Hey Alexa' detector"
    A shell pm enable $m | Out-Null; Ok "Re-enabled $m"
  }
  Step "Stopping and removing portal-wake"
  A shell am force-stop $cfg["PKG"] | Out-Null
  A uninstall $cfg["PKG"] | Out-Null
  Write-Host "`n[ok] Done. portal-wake removed; the stock 'Hey Alexa' detector is back." -ForegroundColor Green
  exit 0
}

Write-Host "portal-wake installer" -ForegroundColor White
Write-Host "Installs the always-on 'hey jarvis' wake listener on your Portal and starts it.`n" -ForegroundColor DarkGray
Wait-Device
Install-App
Grant-Mic
Free-MicSlot
Start-WakeService
Write-Host "`n[ok] Done. portal-wake is listening - say 'hey jarvis' near the Portal." -ForegroundColor Green
Write-Host "To remove it: run install.ps1 -Uninstall (or double-click Uninstall-PortalWake)." -ForegroundColor DarkGray
