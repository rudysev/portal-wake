#!/usr/bin/env bash
# Drives and reads the hybrid wake detector prototype on a Portal over adb (see README "Hybrid wake detector").
#
#   scripts/hybrid-evidence.sh detector hybrid|vosk   switch the detector now (persists across reboots)
#   scripts/hybrid-evidence.sh shadow on|off          run the Vosk shadow beside the hybrid, or not
#   scripts/hybrid-evidence.sh mark                   someone said "Hey Jarvis" and nothing woke: save the last 30 s
#   scripts/hybrid-evidence.sh pull [dir]             copy evidence.jsonl, the clips (via run-as, debug build only)
#                                                     and debug.txt to dir (default ./hybrid-evidence-<UTC time>)
#   scripts/hybrid-evidence.sh summary <dir|file>     count candidates, wakes and disagreements in a pulled log
#
# Pick the Portal with ANDROID_SERIAL when more than one device is attached. `summary` needs jq.
set -euo pipefail

PKG="com.portal.wake"
FILES="/sdcard/Android/data/$PKG/files"
ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB=(adb -s "$ANDROID_SERIAL")
fi

usage() {
  sed -n '2,12p' "$0" >&2
  exit 2
}

broadcast() {
  "${ADB[@]}" shell am broadcast -a "$1" -p "$PKG" "${@:2}" >/dev/null
  echo "sent $1 ${*:2}"
}

summary() {
  local log=$1
  if [[ -d "$log" ]]; then
    log="$log/hybrid/evidence.jsonl"
  fi
  [[ -f "$log" ]] || {
    echo "no evidence log at $log" >&2
    exit 1
  }
  jq -rs '
    def count(f): map(select(f)) | length;
    (map(select(.type == "candidate"))) as $c
    | (map(select(.type == "vosk_wake"))) as $v
    | (map(select(.type == "disagreement"))) as $d
    | (map(select(.type == "stats"))) as $s
    | [
        "span:        \(.[0].at) .. \(.[-1].at)",
        "detector:    \(map(select(.type == "session" or .type == "detector")) | map(.detector // (.change | sub("^detector → "; ""))) | join(" → "))",
        "candidates:  \($c | length) (gate opened)",
        "hybrid wakes: \($c | count(.woke)) (by sherpa \($c | count(.wokeBy == "sherpa")), by Vosk window \($c | count(.wokeBy == "vosk-window")))",
        "rejected:    \($c | count(.woke | not)) (rival keyword \($c | count(.rival != null)))",
        "Vosk wakes:  decided \($v | count(.role == "decided")), shadow \($v | count(.role == "shadow"))",
        "disagree:    Vosk shadow only \($d | count(.kind == "vosk-only")), hybrid only \($d | count(.kind == "hybrid-only"))",
        "marked miss: \(count(.type == "miss_mark"))",
        "cost:        Vosk \($s | map(.voskMsMean) | (add // 0) / ([length, 1] | max) | floor) ms, gate \($s | map(.gateMsMean) | (add // 0) / ([length, 1] | max) | floor) ms per frame (mean of minutes); dropped frames \($s | map(.droppedFrames) | add // 0)",
        "",
        "wakes and disagreements (UTC):",
        (.[] | select((.type == "candidate" and .woke) or .type == "disagreement" or .type == "miss_mark")
          | if .type == "candidate" then "  \(.at)  hybrid wake by \(.wokeBy)  gate=\(.gatePeak * 100 | floor)%  sherpa=\(.spotted)  vosk=\(.vosk)  \(.clip // "")"
            elif .type == "disagreement" then "  \(.at)  disagreement \(.kind)  \(.candidateVerdict // "no candidate")"
            else "  \(.at)  marked miss  \(.clip // "")" end)
      ] | .[]
  ' "$log"
}

case "${1:-}" in
  detector)
    [[ "${2:-}" == hybrid || "${2:-}" == vosk ]] || usage
    broadcast com.portal.wake.action.SET_DETECTOR --es detector "$2"
    ;;
  shadow)
    case "${2:-}" in
      on) broadcast com.portal.wake.action.SET_DETECTOR --ez shadow true ;;
      off) broadcast com.portal.wake.action.SET_DETECTOR --ez shadow false ;;
      *) usage ;;
    esac
    ;;
  mark)
    broadcast com.portal.wake.action.MARK_MISS
    ;;
  pull)
    dir=${2:-"hybrid-evidence-$(date -u +%Y%m%dT%H%M%SZ)"}
    mkdir -p "$dir"
    if ! "${ADB[@]}" exec-out run-as "$PKG" tar -cf - -C files hybrid | tar -xf - -C "$dir"; then
      echo "could not copy files/hybrid (run-as needs the debug build)" >&2
    fi
    "${ADB[@]}" pull "$FILES/debug.txt" "$dir/" || true
    echo "pulled to $dir"
    if command -v jq >/dev/null 2>&1 && [[ -f "$dir/hybrid/evidence.jsonl" ]]; then
      summary "$dir"
    fi
    ;;
  summary)
    [[ -n "${2:-}" ]] || usage
    summary "$2"
    ;;
  *)
    usage
    ;;
esac
