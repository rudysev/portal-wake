# openWakeWord stress benchmark

Replay labelled WAV clips through the **on-device** `OpenWakeWordDetector` (file injection, no mic) and
score recall / false-accepts. Use this to stress-test and tune `com.portal.wake.min_confidence` /
`WakeWord.scoreThreshold` before shipping.

Synthetic TTS (macOS `say`) measures **model behaviour**, not far-field Portal acoustics. Validate tuning
on a real gen1 Portal with live speech after picking a threshold here.

## Corpus (`corpus/`)

Committed smoke corpus (~50 clips, ~8 MB):

| folder | meaning |
|--------|---------|
| `positive_clean/` | isolated "hey jarvis" — should fire |
| `positive_embedded/` | wake inside a sentence — harder |
| `negative_hard/` | soundalikes + bare "jarvis" — must not fire |
| `negative_general/` | ordinary speech + silence |
| `background/` | long speech loops — false-accepts per hour |

Regenerate locally: `benchmark/gen_corpus.sh` (requires macOS `say` + `afconvert`).

## On-device run

```bash
# Device connected (adb / hzdb). Uses benchmark/corpus by default.
benchmark/run_bench.sh

# Custom corpus + labelled manifest (threshold sweep via offdevice_bench.py report):
benchmark/run_bench.sh /path/to/corpus /path/to/manifest.jsonl
```

Produces (gitignored): `results.csv`, `report.txt`, `last_run.log`.

`score.py` reports recall, false accepts, and background FPPH at the **shipped** threshold (0.5).
Each row also carries `peak_score` for offline threshold sweeps.

## Off-device run (host, optional)

Fast iteration over a large labelled manifest using the Python `openwakeword` package:

```bash
pip install openwakeword soundfile
python3 benchmark/offdevice_bench.py score \
  --manifest <corpus>/manifest.jsonl \
  --oww-model ../portal-commons/commons-android/src/main/assets/oww/hey_jarvis_v0.1.onnx \
  --oww-res ../portal-commons/commons-android/src/main/assets/oww \
  --out benchmark/results_offdevice.csv
python3 benchmark/offdevice_bench.py report --csv benchmark/results_offdevice.csv
```

## Tuning workflow

1. Run `benchmark/run_bench.sh` after detector changes.
2. Check `report.txt` — recall on `positive_clean`, false accepts on negatives, FPPH on `background`.
3. If recall is low, lower `min_confidence` / `scoreThreshold` (try 0.35–0.45).
4. If negatives or background fire, raise threshold or inspect `debug.txt` near-miss logs on device.
5. Re-run until recall ≥95% on clean positives with acceptable FPPH.

## Implementation

- `app/src/androidTest/.../WakeBenchmark.kt` — instrumented test, WAV replay
- `OpenWakeWordFactory.kt` — jarvis classifier at shipped threshold
- Detector emits `onScore` every step; harness records per-clip peak for sweeps
