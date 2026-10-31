#!/usr/bin/env bash
# Downloads what the hybrid wake detector needs, verifies each file against its SHA-256 and puts it where the build
# reads it. None of it is committed (see .gitignore). Re-running is cheap: a file already in place with the right hash
# is kept.
#
#   ./fetch-hybrid-deps.sh          the sherpa-onnx AAR (needed to compile) and the models (needed to run the hybrid)
#   ./fetch-hybrid-deps.sh --libs   the AAR only (CI: the build compiles without the models)
#
# What it fetches (all from GitHub releases):
#   - sherpa-onnx 2.0.1 Android AAR with ONNX Runtime linked statically into its JNI library, so it doesn't clash with
#     the libonnxruntime.so of onnxruntime-android, which the openWakeWord gate runs on -> app/libs/
#   - openWakeWord v0.5.1 models (the gate): melspectrogram, embedding_model, hey_jarvis_v0.1
#       -> app/src/main/assets/hybrid/oww/   (pre-trained weights: CC BY-NC-SA 4.0, non-commercial)
#   - sherpa-onnx KWS model sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (the keyword spotter): the int8
#     encoder/decoder/joiner and tokens.txt -> app/src/main/assets/hybrid/kws/
# An APK built without the models runs with the hybrid unavailable: Vosk alone decides, and debug.txt says so.
set -euo pipefail

cd "$(dirname "$0")"

SHERPA_VERSION="2.0.1"
SHERPA_RELEASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION"
OWW_RELEASE="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
KWS_MODEL="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
KWS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$KWS_MODEL.tar.bz2"
KWS_SHA256="f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
ASSETS="app/src/main/assets/hybrid"
# "<file in the tarball> <its SHA-256>": the files the app needs, each checked after extraction too.
KWS_FILES=(
  "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx 1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"
  "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a"
  "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"
  "tokens.txt fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"
)

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# fetch <url> <sha256> <destination>: downloads to a temp file, checks the hash, then moves it into place.
fetch() {
  local url=$1 want=$2 dest=$3 got
  if [[ -f "$dest" && "$(sha256 "$dest")" == "$want" ]]; then
    echo "ok       $dest"
    return
  fi
  mkdir -p "$(dirname "$dest")"
  echo "fetching $url"
  curl -fsSL --retry 3 -o "$dest.part" "$url"
  got=$(sha256 "$dest.part")
  if [[ "$got" != "$want" ]]; then
    rm -f "$dest.part"
    echo "SHA-256 mismatch for $url: expected $want, got $got" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "ok       $dest"
}

fetch "$SHERPA_RELEASE/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar" \
  07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575 \
  "app/libs/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar"

if [[ "${1:-}" == "--libs" ]]; then
  exit 0
fi

fetch "$OWW_RELEASE/melspectrogram.onnx" \
  ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f "$ASSETS/oww/melspectrogram.onnx"
fetch "$OWW_RELEASE/embedding_model.onnx" \
  70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f "$ASSETS/oww/embedding_model.onnx"
fetch "$OWW_RELEASE/hey_jarvis_v0.1.onnx" \
  94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb "$ASSETS/oww/hey_jarvis_v0.1.onnx"

kws_stale=0
for entry in "${KWS_FILES[@]}"; do
  read -r f want <<<"$entry"
  if [[ ! -f "$ASSETS/kws/$f" || "$(sha256 "$ASSETS/kws/$f")" != "$want" ]]; then
    kws_stale=1
  fi
done
if [[ $kws_stale == 1 ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  fetch "$KWS_URL" "$KWS_SHA256" "$tmp/kws.tar.bz2"
  mkdir -p "$ASSETS/kws"
  for entry in "${KWS_FILES[@]}"; do
    read -r f want <<<"$entry"
    tar -xjf "$tmp/kws.tar.bz2" -C "$tmp" "$KWS_MODEL/$f"
    if [[ "$(sha256 "$tmp/$KWS_MODEL/$f")" != "$want" ]]; then
      echo "SHA-256 mismatch for $KWS_MODEL/$f" >&2
      exit 1
    fi
    mv "$tmp/$KWS_MODEL/$f" "$ASSETS/kws/$f"
  done
fi
echo "ok       $ASSETS/kws/"
