#!/usr/bin/env python3
"""Byte-level state comparison with a learned noise mask.

Usage: compare_states.py LABEL BASE_A_DIR BASE_B_DIR CANDIDATE_DIR

Each directory holds `<label>-r0-f<frame>.state` files pulled by run_oracle.sh with KEEP=1.
Bytes that differ between the two baseline runs (host clock leaks such as RTC seeds or
timestamps) are masked; the candidate must match baseline A on every other byte.
Exit 0 on match, 1 on divergence.
"""
import glob
import os
import re
import sys


def states(directory, label):
    found = {}
    for path in glob.glob(os.path.join(directory, f"{label}-r0-f*.state")):
        match = re.search(r"-f(\d+)\.state$", path)
        found[int(match.group(1))] = path
    return found


def main():
    label, base_a, base_b, cand = sys.argv[1:5]
    a, b, c = states(base_a, label), states(base_b, label), states(cand, label)
    ok = True
    for frame in sorted(a):
        if frame not in b or frame not in c:
            print(f"  frame {frame}: missing state")
            ok = False
            continue
        da, db, dc = (open(p, "rb").read() for p in (a[frame], b[frame], c[frame]))
        if not (len(da) == len(db) == len(dc)):
            print(f"  frame {frame}: size differs {len(da)} {len(db)} {len(dc)}")
            ok = False
            continue
        # Noise is masked per aligned 8-byte word: a leaked host timestamp can share
        # some bytes between the two baseline runs by chance.
        noisy_words = {i >> 3 for i, (x, y) in enumerate(zip(da, db)) if x != y}
        noise = len(noisy_words)
        real = [i for i, (x, z) in enumerate(zip(da, dc)) if x != z and (i >> 3) not in noisy_words]
        status = "OK" if not real else f"DIFF {len(real)} bytes, first at 0x{real[0]:x}"
        print(f"  frame {frame}: noise {noise} words masked, {status}")
        ok &= not real
    print(f"  verdict {'MATCH' if ok else 'DIVERGED'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
