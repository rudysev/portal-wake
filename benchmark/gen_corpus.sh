#!/usr/bin/env bash
#
# Generate a SYNTHETIC wake-word benchmark corpus using macOS `say` + `afconvert`.
#
# Output: 16 kHz mono 16-bit PCM WAV (PcmCaptureFormat), grouped into labelled folders the
# on-device benchmark (WakeBenchmark instrumented test) scores by category:
#
#   positive_clean/     isolated "hey jarvis"          -> every detector SHOULD fire
#   positive_embedded/  "hey jarvis ..." in a sentence -> Vosk strict route rejects by design
#                                                          (clean-phrase gate); mww may fire.
#                                                          Reported separately, NOT counted
#                                                          against Vosk recall.
#   negative_hard/      soundalikes + bare "jarvis"    -> NONE should fire ("jarvis" alone must not!)
#   negative_general/   ordinary sentences             -> NONE should fire
#   background/         minutes of continuous speech   -> drives false-accepts-over-time
#
# NOTE ON FIDELITY: this is CLEAN synthetic TTS fed straight to the detectors (file injection).
# It measures MODEL behavior, not far-field acoustics (room/mic/reverb). microWakeWord was
# trained on Piper TTS, so treat mww RECALL on synthetic voices as optimistic. Real-voice,
# over-the-air testing on the gen1 Portal is the source of truth (see benchmark/README.md).
#
# Usage:  benchmark/gen_corpus.sh [OUT_DIR]   (default: benchmark/corpus)
# Must run with real audio access (not inside a restricted sandbox) or `say` yields silence.

set -euo pipefail

OUT="${1:-$(cd "$(dirname "$0")" && pwd)/corpus}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 16 kHz mono 16-bit LE PCM WAV — matches PcmCaptureFormat (SAMPLE_RATE=16000, mono, 16-bit).
FMT=(-f WAVE -d LEI16@16000 -c 1)

# Real, intelligible English voices only (skip the novelty voices like "Bells"/"Bad News").
# Filtered against what's actually installed so a missing voice is skipped, not an error.
CANDIDATE_VOICES=(Alex Samantha Fred Victoria Daniel Karen Moira Rishi Tessa Serena)
AVAIL="$(say -v '?' | awk '{print $1}')"
VOICES=()
for v in "${CANDIDATE_VOICES[@]}"; do
  if grep -qx "$v" <<<"$AVAIL"; then VOICES+=("$v"); fi
done
echo "voices: ${VOICES[*]}"

gen() { # gen <category> <index> <voice> <rate> <text>
  local cat="$1" idx="$2" voice="$3" rate="$4" text="$5"
  local base="${cat}/${idx}_${voice}_r${rate}"
  mkdir -p "$OUT/$cat"
  say -v "$voice" -r "$rate" -o "$TMP/x.aiff" "$text"
  afconvert "${FMT[@]}" "$TMP/x.aiff" "$OUT/${base}.wav"
}

rm -rf "$OUT"; mkdir -p "$OUT"

i=0
# --- positive_clean: isolated "hey jarvis", varied voices + speaking rates ------------------
for v in "${VOICES[@]}"; do
  for r in 160 190 220; do gen positive_clean "$((i++))" "$v" "$r" "hey jarvis"; done
done

# --- positive_embedded: wake inside a sentence (Vosk strict rejects; reported separately) ----
i=0
for v in "${VOICES[@]:0:4}"; do
  gen positive_embedded "$((i++))" "$v" 190 "hey jarvis what time is it"
  gen positive_embedded "$((i++))" "$v" 190 "um hey jarvis are you there"
done

# --- negative_hard: soundalikes + the must-not-fire bare keyword -----------------------------
HARD=("jarvis" "hey travis" "hey chris" "hey harris" "hey jasmine" \
      "hey jeremy" "hey james" "hey jervis" "hey Jerry" "jarvis jarvis" \
      "hey darkness" "have his" "hey service" "okay jarvis" "hi jarvis" \
      "a jarvis" "yo jarvis" "hey alexa" "jarvis are you there")
i=0
for t in "${HARD[@]}"; do
  v="${VOICES[$(( i % ${#VOICES[@]} ))]}"
  gen negative_hard "$((i++))" "$v" 190 "$t"
done

# --- negative_general: ordinary sentences that must not fire ---------------------------------
GEN=("what is the weather like today" "please turn off the living room lights" \
     "i think we should order pizza tonight" "the quick brown fox jumps over the lazy dog" \
     "can you play some jazz music" "set a timer for ten minutes" "good morning everyone")
i=0
for t in "${GEN[@]}"; do
  v="${VOICES[$(( i % ${#VOICES[@]} ))]}"
  gen negative_general "$((i++))" "$v" 190 "$t"
done

# --- background: minutes of continuous speech (proxy for ambient conversation/podcast) -------
# A long paragraph read by a couple voices. Drives the false-accepts-over-time number.
PARA="Artificial intelligence has transformed the way we interact with technology. \
From the earliest mechanical calculators to modern neural networks, each generation of \
machines has expanded what is possible. Consider how a simple thermostat gave way to \
smart homes that anticipate our needs. The history of computing is a story of abstraction, \
where complex operations are hidden behind simple interfaces. Every day, billions of \
devices exchange information across the globe, coordinating traffic, forecasting weather, \
and translating languages in real time. Yet with this power comes responsibility, for the \
systems we build reflect the values we encode into them. Researchers continue to debate how \
best to align these tools with human intent, balancing capability against caution. \
The road ahead is uncertain, but the pace of progress shows no sign of slowing."
i=0
for v in "${VOICES[@]:0:2}"; do
  say -v "$v" -r 185 -o "$TMP/bg.aiff" "$PARA $PARA"
  mkdir -p "$OUT/background"
  afconvert "${FMT[@]}" "$TMP/bg.aiff" "$OUT/background/${i}_${v}.wav"
  i=$((i+1))
done

# --- silence: pure quiet, must never fire ----------------------------------------------------
mkdir -p "$OUT/negative_general"
say -o "$TMP/s.aiff" "[[slnc 3000]]"
afconvert "${FMT[@]}" "$TMP/s.aiff" "$OUT/negative_general/silence.wav" || true

echo "=== corpus written to $OUT ==="
find "$OUT" -name '*.wav' | sed "s#$OUT/##" | sort
echo "counts:"; for d in "$OUT"/*/; do printf "  %-20s %d\n" "$(basename "$d")" "$(find "$d" -name '*.wav' | wc -l | tr -d ' ')"; done
TOTAL_SEC=$(find "$OUT" -name '*.wav' -exec afinfo {} \; 2>/dev/null | awk '/estimated duration/{s+=$3} END{printf "%.1f", s}')
echo "total audio: ${TOTAL_SEC}s"
