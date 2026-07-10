#!/usr/bin/env python3
"""Score WakeBenchmark results.csv — per-detector recall / false-accept report (oWW + Vosk).

Per category:
  positive_clean     RECALL (fired / total) — the primary metric
  positive_embedded  fire rate (informational; embedded wakes are harder)
  negative_hard      FALSE ACCEPTS (bare "jarvis" firing is a hard failure)
  negative_general   FALSE ACCEPTS
  background         false-accepts per hour (FPPH)

[peak_score] in the CSV supports offline threshold sweeps via offdevice_bench.py report.
"""
import csv
import sys
from collections import defaultdict


def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            r["fired"] = r["fired"].strip().lower() == "true"
            r["fire_count"] = int(r["fire_count"] or 0)
            r["duration_ms"] = int(r["duration_ms"] or 0)
            r["peak_score"] = float(r.get("peak_score") or 0)
            rows.append(r)
    return rows


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "results.csv"
    rows = load(path)
    detectors = sorted({r["detector"] for r in rows})
    out = []
    out.append("=" * 72)
    out.append("oWW vs Vosk BENCHMARK  (synthetic TTS, file injection — model behaviour, not acoustics)")
    out.append("=" * 72)

    for det in detectors:
        d = [r for r in rows if r["detector"] == det]
        by_cat = defaultdict(list)
        for r in d:
            by_cat[r["category"]].append(r)

        out.append("")
        out.append(f"### detector: {det}")

        clean = by_cat.get("positive_clean", [])
        if clean:
            hit = sum(1 for r in clean if r["fired"])
            out.append(f"  positive_clean   RECALL {hit}/{len(clean)} = {100*hit/len(clean):.1f}%")
            for r in clean:
                if not r["fired"]:
                    out.append(f"      MISS: {r['file']} (peak={r['peak_score']:.3f})")

        emb = by_cat.get("positive_embedded", [])
        if emb:
            hit = sum(1 for r in emb if r["fired"])
            out.append(f"  positive_embedded  fired {hit}/{len(emb)} (informational)")

        for cat in ("negative_hard", "negative_general"):
            neg = by_cat.get(cat, [])
            if not neg:
                continue
            fa = [r for r in neg if r["fired"]]
            out.append(f"  {cat:17s} FALSE ACCEPTS {len(fa)}/{len(neg)}")
            for r in fa:
                flag = "  <-- bare 'jarvis' MUST NOT fire" if "jarvis" in r["file"].lower() and "hey" not in r["file"].lower() else ""
                out.append(f"      FIRED: {r['file']} (peak={r['peak_score']:.3f}){flag}")

        bg = by_cat.get("background", [])
        if bg:
            total_ms = sum(r["duration_ms"] for r in bg)
            fires = sum(r["fire_count"] for r in bg)
            hours = total_ms / 3_600_000.0
            fpph = fires / hours if hours > 0 else 0.0
            out.append(f"  background       {fires} false accept(s) over {total_ms/1000:.1f}s -> {fpph:.2f}/hr")

    out.append("")
    out.append("-" * 72)
    out.append("Synthetic TTS overstates recall; validate on-device with real voices. See benchmark/README.md.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
