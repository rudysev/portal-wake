# portal-wake on-device file-inject benchmark

Replays labelled WAVs through **openWakeWord** with no microphone (`WakeBenchmark`
instrumented test). Prefer the shared **2×2 matrix** in
`portal-assistant/tools/wakeword-bench/matrix/` (cell **C**) so Mac and Portal use the
same clip list.

```bash
# Standalone (category folders of 16 kHz mono WAVs):
./benchmark/run_bench.sh [CORPUS_DIR]

# Matrix cell C (recommended):
cd ../portal-assistant/tools/wakeword-bench
./matrix/run_C_portal_file.sh matrix/clips_smoke.txt
```
