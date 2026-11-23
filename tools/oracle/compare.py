#!/usr/bin/env python3
"""Compare two CoreOracleInstrumentedTest JSON results (baseline vs candidate).

Usage: compare.py BASE.json CANDIDATE.json
Exit 0 when every checkpoint hash matches, 1 on divergence. Always prints the
per-checkpoint speed ratio (wall ms/frame) so one run doubles as a benchmark.
"""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    return data, data["runs"][-1]["checkpoints"]


def main():
    base_meta, base = load(sys.argv[1])
    cand_meta, cand = load(sys.argv[2])
    ok = True
    total_base = total_cand = 0.0
    print(f"{base_meta['label']} vs {cand_meta['label']} ({base_meta['game']}, {base_meta['device']})")
    for b, c in zip(base, cand):
        same_state = b["state"] == c["state"]
        same_frame = b.get("frameHash") == c.get("frameHash")
        same_img = b.get("presented") == c.get("presented")
        ok &= same_state and same_frame
        total_base += b["wallMsPerFrame"]
        total_cand += c["wallMsPerFrame"]
        print(f"  frame {b['frame']:>6}: state {'OK ' if same_state else 'DIFF'} "
              f"frame {'OK ' if same_frame else 'DIFF'} "
              f"img {'OK ' if same_img else 'DIFF'} "
              f"ms/frame {b['wallMsPerFrame']:.3f} -> {c['wallMsPerFrame']:.3f}")
    if len(base) != len(cand):
        ok = False
        print(f"  checkpoint count differs: {len(base)} vs {len(cand)}")
    if total_cand:
        print(f"  speedup x{total_base / total_cand:.3f}  verdict {'MATCH' if ok else 'DIVERGED'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
