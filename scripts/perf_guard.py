#!/usr/bin/env python3
"""Compares a JMH JSON result (-rf json) against guard rules, and optionally against a previous result.

usage: perf_guard.py current.json [previous.json]

Two kinds of check, because shared CI machines are noisy:
  * RATIOS between benchmarks measured in the same run (a vectorised kernel against its scalar twin, a dirty-range upload against a full one):
    machine speed cancels out, so these have tight limits.
  * ABSOLUTE change against the previous nightly result, with a generous limit (ABSOLUTE_LIMIT), because two runs may land on different machines.
Exit status 1 if a check fails. Prints every figure, so a log shows the trend even when nothing fails.
"""
import json
import sys

# (numerator, denominator, maximum allowed ratio numerator/denominator, what it protects). The limits are far looser than the measured ratios in docs/;
# they exist to catch a lost optimisation, not noise. Time per operation: a ratio over 1 means the numerator is slower.
RATIOS = [
    ("MatrixKernelBench.multiplyBest", "MatrixKernelBench.multiplyScalar", 1.25, "the selected matrix kernel must not be slower than the scalar one"),
    ("MemBench.uploadDirtyOnePercent", "MemBench.uploadEverything", 0.60, "dirty-range upload of 1% must stay well under a full upload"),
    ("SortBench.radixOrderFloat", "SortBench.jdkSortPackedFloat", 1.50, "the radix order must stay in the range of the JDK sort it replaces"),
    ("SortBench.encodeHilbert3", "SortBench.encodeMorton3", 12.0, "Hilbert encode cost against Morton (measured 6 times since the table-driven rewrite: 35 ns against 5.8 ns, docs/BULK.md)"),
]
ABSOLUTE_LIMIT = 1.5  # fail when a benchmark is more than 50% slower than the previous result


def load(path):
    """{ 'Class.method[param=value,...]': score }, the parameters being part of the key."""
    with open(path, encoding="utf8") as f:
        data = json.load(f)
    out = {}
    for r in data:
        name = r["benchmark"].split(".")
        key = ".".join(name[-2:])
        params = r.get("params")
        if params:
            key += "[" + ",".join(f"{k}={v}" for k, v in sorted(params.items())) + "]"
        out[key] = r["primaryMetric"]["score"]
    return out


def variants(results, base):
    """The results of one benchmark: {parameter suffix: score}; the suffix is empty for a benchmark without parameters."""
    found = {}
    for key, score in results.items():
        if key == base:
            found[""] = score
        elif key.startswith(base + "["):
            found[key[len(base):]] = score
    return found


def main():
    cur = load(sys.argv[1])
    prev = load(sys.argv[2]) if len(sys.argv) > 2 else {}
    failed = False
    for num, den, limit, why in RATIOS:
        n, d = variants(cur, num), variants(cur, den)
        common = sorted(set(n) & set(d))
        if not common:
            print(f"MISSING  {num} / {den}: not in the result")
            failed = True
            continue
        for suffix in common:
            ratio = n[suffix] / d[suffix]
            ok = ratio <= limit
            failed |= not ok
            print(f"{'ok     ' if ok else 'FAILED '} {num} / {den}{suffix} = {ratio:.3f} (limit {limit}): {why}")
    for key in sorted(cur):
        if key in prev and prev[key] > 0:
            change = cur[key] / prev[key]
            ok = change <= ABSOLUTE_LIMIT
            failed |= not ok
            print(f"{'ok     ' if ok else 'FAILED '} {key}: {prev[key]:.4g} -> {cur[key]:.4g} ({change:.2f}x)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
