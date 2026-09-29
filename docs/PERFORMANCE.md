# Performance contract

1. Single objects are immutable values (escape analysis today, Valhalla later).
2. Anything that scales with entity count (more than ~64 elements) lives in `float[]` / `MemorySegment` SoA storage,
   with zero per-element objects.
3. Hot-path APIs allocate nothing. Every kernel gets a JMH benchmark, and `-prof gc` must show 0 B/op.
4. Culling and other predicates are conservative (never a false negative), with documented error bounds.
5. Every public operation documents its NaN/degenerate-input behaviour.

## Measuring

```
./gradlew :vmath-bench:jmh -Pjmh.args="-prof gc CoreBench"
```

`gc.alloc.rate.norm` is bytes allocated per operation. Benchmarks that return a value object allocate that object by
construction (JMH must keep it alive). The `chain*` benchmarks return a primitive, which is the case that shows
whether escape analysis removed the intermediates.

## Findings

Baseline, JDK 25, Ryzen 5 5600H, 1 fork, short run (indicative only):

| Benchmark | ns/op | B/op | Note |
|---|---|---|---|
| `chainVec3` (add, mul, normalize, cross, dot) | 6.4 | **0** | intermediates scalar-replaced |
| `chainQuat` (mul, normalize, transform) | 16.2 | **0** | |
| `chainTrs` (TRS build + invertAffine + read) | 10.2 | **0** | |
| `chainMatVec` (`Mat4f.mul` then `transformPosition`) | 32.1 -> 24 | 80 -> **0** | fixed by splitting `mul` (see below) |
| `mat4Mul` | 23.3 | 80 | result object (expected) |
| `mat4Invert` | 31.1 | 80 | result object (expected) |
| `mat4InvertAffine` | 20.0 | 80 | result object (expected) |
| `mat4TransformPosition` | 7.6 | 24 | result object (expected) |
| `quatSlerp` | 69.2 | (result) | |

**Vec3/Quat/affine chains are allocation-free, so the immutable-value design holds up there.**

**`Mat4f.mul` was the exception, and is now fixed.** In `chainMatVec` the product is a 64-byte matrix that is consumed
immediately, yet it allocated (80 B/op). The cause: `mul` (16 outputs x 4 multiply-adds) compiled to ~630 bytecodes, above
HotSpot's `FreqInlineSize` (325), so it was never inlined and its result could not be scalar-replaced. Confirmed with
`-XX:FreqInlineSize=2000` (0 B/op). Fix: `mul` is now four calls to a small `mulColumn` helper (~125 bytecodes each), and
`chainMatVec` measures 0 B/op and ~24 ns (was 32 ns). `mat4Mul` itself still returns a `Mat4f`, which JMH keeps alive.

Rule of thumb that came out of this: **keep every hot value-returning method under ~300 bytecodes**, splitting wide
constructors into small helpers that return small records. Check with `javap -c -p` after adding one.
`invert` (~700 bytecodes) is deliberately left alone, since it is rarely on a per-object hot path. Under `-Pvalhalla`
this class of problem mostly disappears (measured below, but not entirely: `invert` is still not scalarized).

## Valhalla measured: value records on JDK 28

`CoreBench` with `-prof gc`, once as plain records on JDK 25 and once with `-Pvalhalla` (real `value record`s, JDK 28-ea+17,
mainline preview build), same machine, 10 iterations, so read the ratios rather than the digits:

| Benchmark | plain records (JDK 25) | value records (JDK 28) |
|---|---|---|
| `mat4Mul` | 27.1 ns, 80 B/op | 24.3 ns, **0 B/op** |
| `mat4InvertAffine` | 20.4 ns, 80 B/op | **11.9 ns, 0 B/op** |
| `mat4TransformPosition` | 7.0 ns, 24 B/op | 5.6 ns, **0 B/op** |
| `quatSlerp` | 67.4 ns, 32 B/op | 68.0 ns, **0 B/op** |
| `mat4Invert` (large method) | 32.0 ns, 80 B/op | 46.8 ns, **80 B/op** (slower, still allocates) |
| chains (`chainVec3`, `chainQuat`, `chainTrs`, `chainMatVec`) | 6.4 / 16.1 / 10.0 / 22.5 ns, 0 B/op | 7.2 / 16.4 / 11.4 / 26.3 ns, 0 B/op (about 10% slower) |

So the value-type promise holds for the operations that used to allocate their result: the single-call cost drops to zero
allocation, and the ones that were bound by that allocation get faster (`invertAffine` by about 40%). It is not free everywhere:
the big `invert` method neither avoids the allocation nor keeps its speed, and chains that escape analysis already handled are a
little slower. This is an early-access preview build (and the JDK 25 side is a different JVM version), so treat it as a first
data point; rerun it as the build matures.

## Why the flat culling kernel was slow, and what fixed it

Diagnosis with `CullKernelBench` (per pass over 100k boxes, microseconds):

| Variant | us | Note |
|---|---|---|
| min accumulation across planes (old kernel) | 1366 | six passes, `Math.min` into one scratch array |
| ternary instead of `Math.min` | 2595 | C2 does **not** vectorize the ternary |
| fused loop over all six planes (locals) | 1270 | C2 does **not** vectorize the fused loop |
| **two-pass** (one array per plane, then min) | **500** | C2 vectorizes both loops; memory-bound |
| pack bitset, `>= 0 ? 1 : 0` | 129 | |
| pack bitset via the float sign bit | 63 | rejected: makes -0.0 and NaN ambiguous |

Lessons: (1) `Math.min`/`Math.max` vectorize, ternaries do not; (2) simple single-purpose loops vectorize, big fused ones do not;
(3) the two-pass form is then limited by memory traffic (~19 GB/s of L2/L3), which only a fused kernel that reads each
array once can beat. The scalar kernel therefore takes the two-pass form (about 30% faster overall), and the Vector API
kernel in `vmath-simd` is the fused form (3.4x faster than scalar at 1M objects, see `docs/CULLING.md`).

## Why the dynamic tree's frustum query trailed the static BVH

`DynamicTreeBench` (100k objects, one frustum, `frustumQueryStaticReference` is the same scene in a SAH-built `StaticBvh`):

| Variant | frustum query, us | accept-all walk, us | Note |
|---|---|---|---|
| static BVH (reference) | 89 | 62 | depth-first layout, whole subtree = one contiguous range |
| dynamic tree, insertion order | 750 | 2000-4200 | nodes scattered by insertion order |
| dynamic tree after `optimize()` | 380 | 1760 | depth-first renumbering, same shape |
| interleaving the links into one `int[]` | no gain (3600 vs 2000) | | tried first, kept for the handle slot only: padding raised the footprint |
| plane coherency (test the previous failing plane first) | 603 vs 747 unoptimized, 397 vs 379 optimized | | inside the noise once nodes are ordered; reverted |

Findings: (1) the cost is per-node, not per-array: about 10 ns a node when nodes are scattered, about 8 ns when ordered, against
0.6 ns per object for the static tree, which accepts a fully-inside subtree with one loop over a contiguous range and no
per-node branch. (2) `optimize()` (depth-first renumbering, allocation-free, handles unchanged) halves the query. (3) The rest of
the gap is structural: the ranges a static tree uses cannot be kept valid while nodes are being inserted and removed.
Recommendation: static geometry in a `StaticBvh`, movers in a `DynamicAabbTree`, and call `optimize()` after bulk changes.
Update costs are unchanged by the handle table: about 0.1 us for a move inside the fat box, 3 us for a reinsert, 0 B/op.

## Parallel culling

`ParallelCullBench`, 1M objects, SIMD kernel, 12 logical cores: 2.25 ms serial, 1.56 ms with 2 chunks, 0.99 ms with 4, 0.76 ms
with 8 (3x). The kernel is memory-bound, so it flattens before the core count. The driver allocates nothing; the
`ThreadPoolExecutor` allocates about 50 B per handed-out chunk.
