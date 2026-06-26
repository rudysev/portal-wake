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
ASSET="portal-wake-${TAG}.apk"
cp "$APK" "$ASSET"
trap 'rm -f "$ASSET"' EXIT

echo "Publishing release $TAG with $ASSET ..."
gh release create "$TAG" "$ASSET" \
  --title "portal-wake $TAG" \
  --notes "Prebuilt portal-wake APK (on-device speech model bundled). Install with the one-click provisioner in provisioning/ — see provisioning/README.md."

echo "Done. The installer now downloads $ASSET from the latest release."
