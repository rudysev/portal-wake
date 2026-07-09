#!/usr/bin/env bash
#
# Build + install the app and instrumented benchmark, push the labelled corpus to the device, run the
# openWakeWord WAV-replay harness (WakeBenchmark), pull results.csv, then score.
#
# WAV replay needs no microphone — works on gen2 "omni" (mic silenced) and gen1 "aloha".
#
# Usage:
#   benchmark/run_bench.sh [CORPUS_DIR] [MANIFEST_JSONL]
#     CORPUS_DIR      dir of <category>/*.wav (16 kHz mono 16-bit). Default: benchmark/corpus
#     MANIFEST_JSONL  optional labelled manifest for threshold-sweep reporting via offdevice_bench.py

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CORPUS="${1:-$HERE/corpus}"
MANIFEST="${2:-}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")}"

PKG="com.portal.wake"
DEVICE_BENCH="/sdcard/Android/data/$PKG/files/bench"

[ -d "$CORPUS" ] || { echo "corpus not found: $CORPUS (run benchmark/gen_corpus.sh, or pass a corpus dir)"; exit 1; }

echo "=== build + install app + instrumented test ==="
( cd "$ROOT" && ./gradlew --console=plain installDebug installDebugAndroidTest )

echo "=== push corpus -> $DEVICE_BENCH/corpus ==="
"$ADB" shell "mkdir -p $DEVICE_BENCH" || true
"$ADB" shell "rm -rf $DEVICE_BENCH/corpus" || true
"$ADB" push "$CORPUS" "$DEVICE_BENCH/corpus" >/dev/null
echo "pushed $("$ADB" shell "find $DEVICE_BENCH/corpus -name '*.wav' | wc -l" | tr -d ' \r') wav files"

echo "=== run WakeBenchmark (openWakeWord, file injection) ==="
"$ADB" shell am instrument -w \
  -e class "$PKG.benchmark.WakeBenchmark" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$HERE/last_run.log"

echo "=== pull results.csv ==="
"$ADB" pull "$DEVICE_BENCH/results.csv" "$HERE/results.csv"

echo "=== score ==="
if [ -n "$MANIFEST" ]; then
  python3 "$HERE/offdevice_bench.py" merge --results "$HERE/results.csv" --manifest "$MANIFEST" --out "$HERE/results_device_merged.csv"
  python3 "$HERE/offdevice_bench.py" report --csv "$HERE/results_device_merged.csv" | tee "$HERE/report_device.txt"
else
  python3 "$HERE/score.py" "$HERE/results.csv" | tee "$HERE/report.txt"
fi
