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

Not built: IK, morph targets, animation compression, dual-quaternion skinning, step/cubic interpolation inside `AnimationClip` (the glTF loader converts STEP and CUBICSPLINE curves to linear keys).
