#!/usr/bin/env bash
#
# Build + install app + instrumented test, push labelled corpus, run WakeBenchmark (oWW + Vosk
# WAV-replay), pull results.csv, score.
#
# Usage:
#   benchmark/run_bench.sh [CORPUS_DIR]
#     CORPUS_DIR  dir of <category>/*.wav (16 kHz mono 16-bit). Default: benchmark/corpus
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
"$ADB" shell "mkdir -p $DEVICE_BENCH" || true
"$ADB" shell "rm -rf $DEVICE_BENCH/corpus" || true
"$ADB" push "$CORPUS" "$DEVICE_BENCH/corpus" >/dev/null
echo "pushed $("$ADB" shell "find $DEVICE_BENCH/corpus -name '*.wav' | wc -l" | tr -d ' \r') wav files"

echo "=== run WakeBenchmark (oww + vosk, file injection) ==="
"$ADB" shell am instrument -w \
  -e class "$PKG.benchmark.WakeBenchmark" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$HERE/last_run.log"

echo "=== pull results.csv ==="
"$ADB" pull "$DEVICE_BENCH/results.csv" "$HERE/results.csv"

echo "=== score ==="
python3 "$HERE/score.py" "$HERE/results.csv" | tee "$HERE/report.txt"
