#!/usr/bin/env python3
"""Score WakeBenchmark results.csv — openWakeWord file-inject recall / false-accept report.

Per category (folder name under the pushed corpus):
  positive / positive_clean   RECALL
  near_miss / negative_*      FALSE ACCEPTS
  background                  false-accepts per hour (FPPH)
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
    out.append("oWW FILE-INJECT BENCHMARK  (synthetic TTS — model behaviour, not acoustics)")
    out.append("=" * 72)

    for det in detectors:
        d = [r for r in rows if r["detector"] == det]
        by_cat = defaultdict(list)
        for r in d:
            by_cat[r["category"]].append(r)

        out.append("")
        out.append(f"### detector: {det}")

        for cat in sorted(by_cat):
            rs = by_cat[cat]
            hit = sum(1 for r in rs if r["fired"])
            if cat.startswith("positive") or cat == "positive":
                out.append(f"  {cat:17s} RECALL {hit}/{len(rs)} = {100*hit/len(rs):.1f}%")
                for r in rs:
                    if not r["fired"]:
                        out.append(f"      MISS: {r['file']} (peak={r['peak_score']:.3f})")
            elif cat == "background":
                total_ms = sum(r["duration_ms"] for r in rs)
                fires = sum(r["fire_count"] for r in rs)
                hours = total_ms / 3_600_000.0
                fpph = fires / hours if hours > 0 else 0.0
                out.append(f"  {cat:17s} {fires} false accept(s) over {total_ms/1000:.1f}s -> {fpph:.2f}/hr")
            else:
                out.append(f"  {cat:17s} FALSE ACCEPTS {hit}/{len(rs)}")
                for r in rs:
                    if r["fired"]:
                        out.append(f"      FIRED: {r['file']} (peak={r['peak_score']:.3f})")

    out.append("")
    out.append("-" * 72)
    out.append("Prefer the 2×2 matrix (portal-assistant tools/wakeword-bench/matrix/) for A/B/C/D deltas.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
