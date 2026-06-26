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
function Resolve-ReleaseApkUrl {
  if ($cfg["RELEASE_APK_URL"]) { return $cfg["RELEASE_APK_URL"] }
  if (-not $cfg["RELEASE_REPO"]) { return $null }
  try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$($cfg["RELEASE_REPO"])/releases/latest" `
      -Headers @{ "User-Agent" = "portal-wake-installer"; "Accept" = "application/vnd.github+json" }
    return ($rel.assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1).browser_download_url
  } catch { return $null }
}

function Install-App {
  if ($LocalApk) {
    if (-not (Test-Path $LocalApk)) { Die "Local build not found: $LocalApk (run .\gradlew assembleDebug first, or pass -Apk <path>)." }
    $apk = Get-Item $LocalApk
    Step "Using local build: $($apk.FullName)"
    Step "Installing portal-wake ($($apk.Name))"
    A install -r -d $apk.FullName | Out-Null
    Ok "Installed $($cfg["PKG"])"
    return
  }
  $apk = Get-ChildItem -Path $cfg["APK_GLOB"] -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $apk) {
    $url = Resolve-ReleaseApkUrl
    if (-not $url) { Die "No local APK in apks\ and couldn't find a release to download. Connect to the internet, or drop a portal-wake APK in the apks\ folder." }
    Step "Downloading the latest portal-wake release (~180 MB - it bundles the speech model)"
    $dir = Split-Path -Parent $cfg["APK_GLOB"]
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $dest = Join-Path $dir "portal-wake.apk"
    Invoke-WebRequest -Uri $url -OutFile $dest
    $apk = Get-Item $dest
    Ok "Downloaded $($apk.Name)"
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
  A shell "am broadcast -a $($cfg["START_ACTION"]) -n $($cfg["RECEIVER"]) -f 0x20" | Out-Null
  Ok "Started"
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
