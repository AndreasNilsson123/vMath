# Determinism: what is bit-reproducible and what is not (DET-1)

For lockstep networking, replays and golden files the same program must give the same bytes on every machine. Most of the library does; this page says where it does not,
and `-Dvmath.deterministic=true` removes the one difference that the library itself introduces.

## The switch

```
java -Dvmath.deterministic=true ...
```

Every kernel selector (`MatrixKernels.best()`, `FrustumKernels.best()` and any selector built with `KernelSelector`) then returns the scalar kernel, whatever providers exist and even if
`-Dvmath.matrixKernel=simd` or `-Dvmath.frustumKernel=simd` names one (that is reported once through the logger `vmath.kernel`). Only `true` (any case) turns it on; the property is read on every call
(`KernelSelector.isDeterministic()`), so the switch can be set from the command line or at the top of `main`. Kernels that were already chosen, for example the one that `Mat4fArray.multiply` picks
when its class is initialised, keep the choice they made: set the property before the first use, as with `-Dvmath.matrixKernel`.

Why it is needed: the SIMD matrix kernel uses fused multiply-add and a different order of summation, so its products differ from the scalar ones in the last bit (documented in `docs/BULK.md`), and
which kernel runs depends on whether the machine has `jdk.incubator.vector` and the `vmath-simd` module. Without the switch the same program can give different bytes on two machines.

Tested (`DeterministicModeTest` in `vmath-simd`, with the SIMD provider on the class path and resolved): the SIMD kernel is the default, the switch turns it off for both selectors, a named
provider does not override it, and 2 000 products come out as the exact bits of the scalar kernel. `MatrixKernelSelectionTest` checks the same on the test providers.

## What is reproducible

- All arithmetic in `float` and `double` that is plain `+ - * /` and `sqrt`: Java has had strict floating point on every platform since JDK 17.
- `Math.fma` (used by `DoubleDouble` and `Expansions`): the specification says the result is correctly rounded, with or without a hardware instruction.
- `StrictMath`, where the library uses it (none of the maths classes depend on it, so the next point applies).
- Everything built on those with a fixed order of operations: the scalar kernels, the value types, the BVH build, the sorts, the solvers (they run in a fixed order for a fixed input).

## What is not (and what to do)

- **`Math.sin`, `cos`, `tan`, `atan`, `atan2`, `asin`, `acos`, `exp`, `log`, `pow`, `cbrt`, `sinh`, `cosh`, `tanh`, `hypot`**: the specification allows 1 ulp of error and a JVM may use
  different intrinsics on different CPUs, so two machines may differ in the last bit. About a hundred classes of the library call them (the rotation constructors, the projections, the
  geodesics, the noise, the camera). If the bytes must match across machines, run every machine on the same JVM and CPU family, or round results to a coarser grid before hashing them. A
  `StrictMath` mode is not offered: it would change the speed of the whole library and the library does not know which calls the caller needs to match.
- **`FastMath`** (`docs/FASTMATH.md`): polynomial approximations of fixed arithmetic, so the same on every machine, but its errors are larger than `Math`'s; it is reproducible, not exact.
- **The vector kernels without the switch** (above). The frustum kernel of `vmath-simd` may change from the scalar to the vector code during a run (`SimdWarmUp`); both give the same bits, so only the time changes. The matrix kernel is chosen once and never switched for this reason.
- **Parallel kernels** (`ParallelFrustumKernel`: the chunks are disjoint bitset words, `PrefixSum`: integer sums): the results are the same as the serial ones; the time and thread
  interleaving are not. The code that you run inside such a task is yours.
- **Hash iteration order, `System.nanoTime`, `Random` without a seed, the GPU**: not the library's, but they break a replay as well. The library's own generators take a seed (`Noise` is a pure function of its seed and coordinates; the tests use `SplittableRandom` with fixed seeds). GPU results (the shaders of the plans) follow the driver's rounding and are not covered by this page.

## Not checked

There is one machine here. "The same bytes on a machine with and without the vector module" is tested by the equivalence above (the scalar kernel against the selector under the switch), not by
running two machines.
