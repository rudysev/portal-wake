#!/usr/bin/env bash
#
# Build + install portal-wake + instrumented WakeBenchmark (oWW file inject), push labelled
# corpus, run, pull results.csv, score.
#
# Usage:
#   benchmark/run_bench.sh [CORPUS_DIR]
#     CORPUS_DIR  dir of <category>/*.wav (16 kHz mono 16-bit). Default: benchmark/corpus
#
# Prefer the assistant matrix cell C for the shared 2×2 clip list:
#   portal-assistant/tools/wakeword-bench/matrix/run_C_portal_file.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CORPUS="${1:-$HERE/corpus}"

export JAVA_HOME="${JAVA_HOME:-/usr/local/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"

PKG="com.portal.wake"
DEVICE_BENCH="/sdcard/Android/data/$PKG/files/bench"

[ -d "$CORPUS" ] || { echo "corpus not found: $CORPUS (run benchmark/gen_corpus.sh, or pass a corpus dir)"; exit 1; }

echo "=== build + install app + instrumented test ==="
( cd "$ROOT" && ./gradlew --console=plain installDebug installDebugAndroidTest )

echo "=== push corpus -> $DEVICE_BENCH/corpus ==="
"$ADB" shell "rm -rf $DEVICE_BENCH/corpus" || true
"$ADB" shell "mkdir -p $DEVICE_BENCH/corpus" || true
for catdir in "$CORPUS"/*; do
  [ -d "$catdir" ] || continue
  cat=$(basename "$catdir")
  "$ADB" push "$catdir" "$DEVICE_BENCH/corpus/$cat" >/dev/null
done
echo "pushed $("$ADB" shell "find $DEVICE_BENCH/corpus -name '*.wav' | wc -l" | tr -d ' \r') wav files"

echo "=== run WakeBenchmark (oww file injection) ==="
"$ADB" shell am instrument -w \
  -e class "$PKG.benchmark.WakeBenchmark" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$HERE/last_run.log"

echo "=== pull results.csv ==="
"$ADB" pull "$DEVICE_BENCH/results.csv" "$HERE/results.csv"

echo "=== score ==="
python3 "$HERE/score.py" "$HERE/results.csv" | tee "$HERE/report.txt"
