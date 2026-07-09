#!/usr/bin/env python3
"""
Off-device openWakeWord benchmark — peak-score based, threshold-sweepable.

Scores the bundled hey_jarvis classifier over a labelled corpus manifest on the host (same ONNX
math as on-device). Use alongside run_bench.sh for fast iteration when tuning score thresholds.

Usage:
  python3 offdevice_bench.py score \\
      --manifest <corpus>/manifest.jsonl \\
      --oww-model <path>/hey_jarvis_v0.1.onnx \\
      --oww-res <path>/oww_resources \\
      --out results_offdevice.csv

  python3 offdevice_bench.py report --csv results_offdevice.csv

  python3 offdevice_bench.py merge --results results.csv --manifest manifest.jsonl --out merged.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from collections import defaultdict

SR = 16000
CHUNK = 1280


def load_pcm16(path):
    import soundfile as sf

    a, sr = sf.read(path, dtype="int16")
    if a.ndim > 1:
        a = a[:, 0]
    if sr != SR:
        raise ValueError(f"{path}: expected {SR} Hz, got {sr}")
    return a


def clip_seconds(n_samples: int) -> float:
    return n_samples / SR


class OwwScorer:
    def __init__(self, model_path: str, res_dir: str, oww_pkg_path: str | None):
        if oww_pkg_path:
            sys.path.insert(0, oww_pkg_path)
        from openwakeword.model import Model

        self.m = Model(
            wakeword_models=[model_path],
            melspec_model_path=os.path.join(res_dir, "melspectrogram.onnx"),
            embedding_model_path=os.path.join(res_dir, "embedding_model.onnx"),
            inference_framework="onnx",
        )
        self.key = list(self.m.models.keys())[0]

    def peak(self, pcm) -> float:
        import numpy as np

        self.m.reset()
        best = 0.0
        for i in range(0, len(pcm), CHUNK):
            c = pcm[i : i + CHUNK]
            if len(c) < CHUNK:
                c = np.pad(c, (0, CHUNK - len(c)))
            best = max(best, float(self.m.predict(c)[self.key]))
        return best


def cmd_score(args):
    rows = [json.loads(l) for l in open(args.manifest) if l.strip()]
    if args.limit:
        rows = rows[: args.limit]
    scorer = OwwScorer(args.oww_model, args.oww_res, args.oww_pkg)
    mode = "a" if args.append and os.path.exists(args.out) else "w"
    with open(args.out, mode, newline="") as fh:
        w = csv.writer(fh)
        if mode == "w":
            w.writerow(["detector", "path", "category", "condition", "snr", "voice", "label", "seconds", "score"])
        for i, r in enumerate(rows):
            pcm = load_pcm16(r["path"])
            score = scorer.peak(pcm)
            w.writerow([
                "oww", os.path.basename(r["path"]), r.get("category"), r.get("condition"),
                r.get("snr"), r.get("voice"), r.get("label"), f"{clip_seconds(len(pcm)):.3f}", f"{score:.4f}",
            ])
            if (i + 1) % 100 == 0:
                print(f"  {i+1}/{len(rows)}", file=sys.stderr)
    print(f"wrote {args.out}")


def _is_normal(cond: str, snr) -> bool:
    if cond in ("clean", "reverb", "quiet", "def", None, ""):
        return True
    try:
        return int(float(snr)) >= 10
    except (TypeError, ValueError):
        return False


def cmd_report(args):
    by_det = defaultdict(list)
    for r in csv.DictReader(open(args.csv)):
        r["score"] = float(r["score"])
        r["label"] = int(r["label"])
        by_det[r["detector"]].append(r)

    for det, rows in by_det.items():
        pos = [r for r in rows if r["category"] == "positive"]
        pos_normal = [r for r in pos if _is_normal(r["condition"], r["snr"])]
        near = [r for r in rows if r["category"] == "near_miss"]
        bg = [r for r in rows if r["category"] in ("background", "silence")]
        bg_secs = sum(float(r["seconds"]) for r in bg)

        print(f"\n{'='*72}\nDETECTOR: {det}   (positives {len(pos)}; normal {len(pos_normal)}; "
              f"near_miss {len(near)}; background {len(bg)} / {bg_secs/60:.1f} min)")

        thresholds = [0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98, 0.99]
        print(f"  {'thr':>5} {'recall_all':>11} {'recall_norm':>12} {'nearFA':>8} {'bg/hr':>7}")
        best_op = None
        for t in thresholds:
            def rate(rs):
                return (sum(r["score"] >= t for r in rs) / len(rs)) if rs else 0.0
            rec_all = rate(pos)
            rec_norm = rate(pos_normal)
            near_fa = rate(near)
            bg_fires = sum(r["score"] >= t for r in bg)
            bg_ph = (bg_fires / bg_secs * 3600) if bg_secs else 0.0
            print(f"  {t:>5.2f} {rec_all:>10.1%} {rec_norm:>11.1%} {near_fa:>7.1%} {bg_ph:>7.2f}")
            if rec_norm >= 0.95 and best_op is None:
                best_op = (t, rec_norm, near_fa, bg_ph)

        if best_op:
            t, rec, nfa, bgph = best_op
            print(f"  -> lowest thr with >=95% NORMAL recall: {t:.2f} "
                  f"(recall {rec:.1%}, near_miss FA {nfa:.1%}, bg {bgph:.2f}/hr)")
        else:
            print("  -> never reaches 95% normal recall in the swept range")

        t = 0.5
        cond = defaultdict(lambda: [0, 0])
        for r in pos:
            k = r["condition"] if r["condition"] not in (None, "", "def") else "clean"
            if r["condition"] == "noisy":
                k = f"noisy{r['snr']}"
            cond[k][0] += r["score"] >= t
            cond[k][1] += 1
        print(f"  recall by condition @thr={t}: " +
              ", ".join(f"{k} {c[0]}/{c[1]}" for k, c in sorted(cond.items())))

        if near:
            phrase = defaultdict(lambda: [0, 0])
            for r in near:
                parts = r["path"].split("_")
                ph = parts[1].replace("-", " ") if len(parts) > 1 else "?"
                phrase[ph][0] += r["score"] >= t
                phrase[ph][1] += 1
            print(f"  near_miss fire-rate by phrase @thr={t}:")
            for ph, c in sorted(phrase.items(), key=lambda x: -x[1][0] / max(1, x[1][1])):
                print(f"      {c[0]/c[1]:5.0%}  ({c[0]:3d}/{c[1]:3d})  {ph}")


def cmd_merge(args):
    man = {}
    for l in open(args.manifest):
        if not l.strip():
            continue
        r = json.loads(l)
        man[os.path.basename(r["path"])] = r

    with open(args.out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["detector", "path", "category", "condition", "snr", "voice", "label", "seconds", "score"])
        for r in csv.DictReader(open(args.results)):
            m = man.get(r["file"], {})
            score = float(r.get("peak_score", 0) or 0)
            w.writerow([
                r["detector"], r["file"],
                m.get("category", r.get("category")), m.get("condition"), m.get("snr"), m.get("voice"),
                m.get("label", 1 if m.get("category") == "positive" else 0),
                f"{int(r.get('duration_ms', 0))/1000:.3f}", f"{score:.4f}",
            ])
    print(f"wrote {args.out}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    mg = sub.add_parser("merge")
    mg.add_argument("--results", required=True)
    mg.add_argument("--manifest", required=True)
    mg.add_argument("--out", required=True)
    mg.set_defaults(func=cmd_merge)

    s = sub.add_parser("score")
    s.add_argument("--manifest", required=True)
    s.add_argument("--oww-model", required=True)
    s.add_argument("--oww-res", required=True)
    s.add_argument("--oww-pkg", default=None)
    s.add_argument("--out", required=True)
    s.add_argument("--limit", type=int, default=0)
    s.add_argument("--append", action="store_true")
    s.set_defaults(func=cmd_score)

    r = sub.add_parser("report")
    r.add_argument("--csv", required=True)
    r.set_defaults(func=cmd_report)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
