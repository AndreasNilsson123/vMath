# Animation

`vmath.anim`: a scene-transform hierarchy and skeletal animation, in structure-of-arrays form with no per-node or per-joint objects.

## `TransformHierarchy`

Parents always precede children, so `update()` computes every world matrix in one forward pass. Only dirty nodes and their descendants
are recomputed, starting at the lowest dirty index. World matrices are the exact `T * R * S` matrix product down the chain (shear from
non-uniform scale included), stored in a `Mat4fArray` ready for upload. `remove(node)` removes the subtree, compacts, and returns a remap.
Tested against a double-precision oracle on random trees (up to 40 levels, non-uniform scale, random edits), including which nodes
were recomputed and that untouched matrices stay bit-identical.

Measured (`HierarchyBench`, 100k nodes, JDK 25, ~0 B/op): about 20 ns per recomputed node. All dirty 2.2 ms; a random 1% edit recomputes
97 866 nodes (in this tree almost everything descends from an earlier node) and takes 1.9 ms; 1% at the tail of the arrays recomputes 967 nodes
in 19 us; nothing dirty 2 ns.

## Skeletons, clips, sampling, blending, skinning

- `Skeleton` (parents first, bind pose, inverse bind matrices; rejects bad parent order and singular bind transforms), `Pose` (10 floats per joint).
- `AnimationClip` (flat key arrays, translation/rotation/scale tracks, linear and slerp interpolation, validated builder), `ClipSampler`
  (per-track cursor for sequential playback; identical results to random access).
- `Pose.lerp` (slerp along the shortest arc), `blendMasked` (layered), `makeAdditive`/`applyAdditive` (round-trips exactly).
- `Skinning.jointMatrices` (`world * inverseBind`), and the CPU reference `skinPositions`/`skinNormals` to check a GPU shader against;
  `packWeights` gives unorm8x4 weights that always sum to 255. A `mat4[]` joint buffer has a 64-byte stride in std140 and std430.

Tested: rest pose skins every vertex to itself, a rotated joint moves exactly its bound vertices (split weights blend linearly),
interpolation matches double precision, `q` and `-q` do not spin, and clip to joint matrices to skinned vertices matches a double oracle.

Measured (`AnimationBench`, JDK 25, ~0 B/op): for 64 / 128 joints with 30-key tracks on every channel: sampling 7.6 / 14.6 us, blending two poses
1.2 / 2.2 us, joint matrices 2.1 / 4.3 us, a full character (two samples, a blend, joint matrices) 21.9 / 42.4 us. CPU skinning of 10 000 vertices
takes about 150 us. Sampling dominates; a faster sampler is the obvious next step.

**Dual quaternion skinning.** Linear blending of matrices shrinks and pinches a mesh where a joint twists (the candy wrapper). `Skinning.jointDualQuaternions` turns the joint matrices into dual
quaternions (eight floats per joint: the rotation, then `0.5 * (t, 0) * rotation`), and `skinPositionsDualQuat` / `skinNormalsDualQuat` blend those: the four joint dual quaternions of a vertex are
brought into the hemisphere of the first one with a weight (so `q` and `-q` do not matter), summed with the weights, normalised, and applied as a rotation and a translation. The joints must be rigid
(a rotation and a translation): a scale in a joint matrix is dropped or distorts the rotation, so use linear blending for skeletons that scale. Tested: a vertex with one joint skins like linear
blending (positions and normals, random poses), two joints twisted by half a turn and weighted one half each keep their distance from the axis where linear blending collapses the vertex onto it, the
sign of a joint dual quaternion does not change the result, the conversion from matrices is checked on all four branches and against `RigidTransformf.fromMat4(...).toDualQuat()`, and the new methods
allocate nothing. Measured (`AnimationBench`, 10 000 vertices with four joints each): 342 us against 134 us for linear blending, about 2.6 times the cost (34 ns per vertex against 13 ns); converting
the joints takes 1.1 us for 64 joints and 2.1 us for 128, against 1.9 us and 3.9 us for the joint matrices themselves.

Not built: step/cubic interpolation inside `AnimationClip` (the glTF loader converts STEP and CUBICSPLINE curves to linear keys).

## Inverse kinematics: `IkSolver`

`IkSolver` (experimental) turns the rotations of a chain of joints in a `Pose` so that the tip reaches a target point in the space of `Skinning.worldMatrices`. It only changes
rotations, so the bone lengths stay as they are, and a target out of reach leaves the chain straight towards it. Each call returns the distance still left, which is the thing to check.

- `twoBone`: the exact solution for a three-joint limb (arm, leg), with an optional pole point that decides which way the middle joint bends.
- `fabrik` and `ccd`: any chain length, with an iteration limit and a tolerance. Neither is guaranteed to converge: in the tests with random chains and reachable targets, 64 FABRIK
  iterations were not enough for a chain whose target lay close to its root (1000 were), and one of 300 random chains still missed the target by more than 0.1% of the reach after 1000.
- `lookAt`: turns one joint so that a local axis points at a target, optionally keeping a second axis towards a world up direction, blended in by a weight (0 leaves the pose alone).

A chain is a list of joint indices in which each is the child of the one before. The solvers assume uniform scale along the chain and its ancestors. One instance owns its scratch
arrays (no allocation per solve, checked by the allocation contract test) and is not thread-safe. There are no joint limits or twist constraints yet. 
Measured (JMH `GeometryBench`, JDK 25, single thread, 2026-10-03, per solve): `twoBone` 472 ns ± 23 ns, `lookAt` 309 ns ± 8 ns, FABRIK on an 8-joint chain (up to 16 iterations, includes resetting the pose) 3.83 µs ± 0.16 µs, CCD on the same chain 7.17 µs ± 0.56 µs.

## Morph targets, root motion and compression

**`MorphTargets`** keeps blend shapes sparsely: a builder takes dense per-vertex position deltas (optionally normal and tangent deltas) and stores only the vertices whose delta exceeds a tolerance. `apply`
adds the weighted deltas to the base positions (`applyNormals` and `applyTangents` likewise, with optional renormalisation), `activeTargets` picks the largest weights for a GPU that blends a fixed number, and
`boundsExpansion` gives a bound on how far the mesh can grow, for culling. For the GPU there are three packings: dense, sparse (vertex id and a float delta) and sparse with snorm16 deltas and one scale per target
(`packSparseSnorm16`, `unpackSparseSnorm16` as the reference for the shader). Measured on 10 000 vertices, 8 targets, every third vertex touched: dense 960 000 bytes, sparse 426 752 bytes (26 672 entries), sparse
snorm16 266 720 bytes plus 32 bytes of scales.

**`RootMotion`** extracts the movement of a root joint over a time step in the frame of the root at the start of the step: `delta(t0, t1, loop, out)` gives translation and rotation, and chains across the loop point
(the part to the end, then the part from the start, carried into the frame the first ended in). `strip` removes what the game applies itself from the pose (horizontal or full translation, the yaw as the twist about the
vertical axis, or both), leaving the lean. It samples only the root joint (`ClipSampler.sampleJoint`): 547 ns per call on a 64-joint clip, down from 13.1 us when it sampled the whole pose twice.

**`ClipCompression`** reduces keys with Ramer-Douglas-Peucker per track: `reduce(clip, translationTolerance, rotationToleranceRadians, scaleTolerance)` drops every key whose interpolation between the kept neighbours is within the tolerance (greedy, not the minimum number of keys). For translation and scale the error stays within
the tolerance everywhere, because the difference of two piecewise-linear tracks has its largest value at a key; for rotation the guarantee holds at the original keys. `measure` samples both clips at an even grid and reports the largest translation, rotation and scale
difference, so the tolerance can be checked instead of trusted. **`QuantizedClip`** stores the result with 16-bit times (a fraction of the duration) and 16-bit values (a fraction of the range of each component over the track) and rotations in smallest-three form, in 32 bits
(`PACKED_32`: three 10-bit fractions and the index) or 64 bits (`PACKED_64`: three 20-bit fractions); `sample` decodes while sampling with the same cursors as `ClipSampler` and allocates nothing, `decode` gives an `AnimationClip` back.

Measured (a 60-joint clip with translation, rotation and scale on every joint, 301 keys each, 942 720 bytes as floats; the sizes are exact, the errors are the largest found over 3001 samples against the original):

| Step | Keys | Bytes | Of original | Largest error (translation / rotation rad / scale) |
|---|---|---|---|---|
| quantized only, `PACKED_32` | 54 180 | 405 240 | 43.0% | 0.00018 / 0.00133 / 0.000005 |
| quantized only, `PACKED_64` | 54 180 | 477 480 | 50.6% | 0.00018 / 0.000058 / 0.000005 |
| reduced, tolerances 0.001 / 0.0005 / 0.001 | 34 290 | 610 468 | 64.8% | 0.00100 / 0.00050 / 0.00035 |
| reduced + `PACKED_32` | 34 290 | 253 126 | 26.9% | 0.00107 / 0.00141 / 0.00035 |
| reduced, 0.005 / 0.002 / 0.005 | 14 487 | 260 848 | 27.7% | 0.00500 / 0.00200 / 0.00497 |
| reduced + `PACKED_32` | 14 487 | 111 088 | 11.8% | 0.00508 / 0.00285 / 0.00497 |
| reduced + `PACKED_64` | 14 487 | 136 544 | 14.5% | 0.00508 / 0.00202 / 0.00497 |
| reduced, 0.02 / 0.01 / 0.02 | 4 734 | 89 152 | 9.5% | 0.01998 / 0.00999 / 0.01890 |
| reduced + `PACKED_32` | 4 734 | 40 888 | 4.3% | 0.01998 / 0.01064 / 0.01890 |

The worst rotation error of the packed formats over 2 million random unit quaternions is 2.1e-3 rad for `PACKED_32` and 2.1e-6 rad for `PACKED_64`; a rotation tolerance below about 1e-3 rad therefore needs `PACKED_64`.
The errors of reduction and quantization add (the table shows the sum). Sampling a 64-joint clip with 30-key tracks (`AnimPhysicsBench`, JDK 25, ~0 B/op): floats 7.1 us, `PACKED_32` 9.2 us, `PACKED_64` 9.4 us:
decoding costs about a third more than reading floats, which is the price of the smaller memory traffic; whether that pays depends on whether the sampler is bound by memory, which this benchmark (one clip, in cache) does not
show. Offline `reduce` of that clip takes 1.0 ms and is meant for import time. Not built: curve fitting with cubic or spline keys (reduction keeps the clip's own linear keys), and a streaming decompressor for several clips at once.

