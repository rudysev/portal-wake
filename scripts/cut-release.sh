#!/usr/bin/env bash
#
# Build the portal-wake APK and publish it as a GitHub Release asset, so the
# one-click installer in provisioning/ can download it. The APK (~180 MB, with the
# speech model bundled) is too large to commit, so a Release is how it ships.
#
# Requires the GitHub CLI (gh), authenticated against the repo: `gh auth login`.
# Build a debug APK locally first by having a JDK (17/21) + Android SDK configured.
#
# Usage: scripts/cut-release.sh v1.0.0
set -euo pipefail

TAG="${1:?usage: cut-release.sh <tag>, e.g. v1.0.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
APK="app/build/outputs/apk/debug/app-debug.apk"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) not found — install it and run 'gh auth login'." >&2; exit 1; }

echo "Building $APK ..."
./gradlew assembleDebug
[ -f "$APK" ] || { echo "Build did not produce $APK" >&2; exit 1; }

# Name the asset with the tag so the installer's "latest .apk asset" lookup finds it.
# Two copies of the same APK: the tag-stamped name the provisioning installer looks up ("latest .apk
# asset" — keep it FIRST so it stays the first asset), and a stable name so the Immortal store's
# releases/latest/download/portal-wake.apk URL resolves for every release.
ASSET="portal-wake-${TAG}.apk"
STABLE="portal-wake.apk"
cp "$APK" "$ASSET"
cp "$APK" "$STABLE"
trap 'rm -f "$ASSET" "$STABLE"' EXIT

echo "Publishing release $TAG with $ASSET ..."
gh release create "$TAG" "$ASSET" "$STABLE" \
  --title "portal-wake $TAG" \
  --notes "Prebuilt portal-wake APK (on-device speech model bundled). Install with the one-click provisioner in provisioning/ — see provisioning/README.md."

# Regenerate version.json (read live by the Immortal store via the catalog's versionUrl) so update
# detection sees this release without a catalog edit.
VC="$(grep -Eo 'versionCode *= *[0-9]+' app/build.gradle.kts | grep -Eo '[0-9]+' | head -1)"
[ -n "$VC" ] || { echo "Could not read versionCode from app/build.gradle.kts" >&2; exit 1; }
printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "note": "Read live by the Immortal store (catalog versionUrl) to detect updates. scripts/cut-release.sh regenerates this on every release; do not edit by hand."\n}\n' \
  "$VC" "${TAG#v}" > version.json
git add version.json
git diff --cached --quiet || { git commit -m "Update version.json for $TAG"; git push; }

echo "Done. The installer downloads $ASSET from the latest release; the Immortal store tracks $STABLE + version.json."
