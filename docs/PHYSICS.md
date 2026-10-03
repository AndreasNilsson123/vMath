# Physics math: `vmath.physics`

The maths a rigid-body engine needs, not an engine: mass properties, integrators, contact manifolds and a contact solver. There is no broadphase, no islands, no sleeping, no joints and no continuous collision
detection here; you bring the bodies and the pairs, the package gives the numbers (the broadphase is `vmath.spatial`, the narrowphase queries are `vmath.geo`). All types work in `double`.
Nothing in this package is `@Experimental`: it is under the compatibility check from the start.

## Mass properties: `MassProperties`

An immutable mass, centre of mass and inertia tensor (about the centre of mass, in the body frame).

- Closed forms for `sphere`, `hollowSphere`, `box` (half extents), `ellipsoid`, `cylinder`, `capsule` and `cone` along an axis. The cone's origin is halfway along its height, the apex at `+axis`.
- `ofMesh(positions, indices, triangleCount, density)`: the exact volume integral of a closed triangle mesh by summing signed tetrahedra (Mirtich, Eberly). It rejects inside-out meshes (negative volume) and
  degenerate ones. `ofPoints`: a point-mass cloud.
- `transformed(rotation, translation)`, `translatedInertia(offset)` (parallel axis theorem), `combine(parts, rotations, translations)` for compounds, `withMass`, `momentAbout(axis point)`,
  `principalAxes()` (Jacobi eigen-decomposition: the principal moments and the rotation to the principal frame) and `of(...)`, which checks that the tensor is physically possible (positive principal moments
  that satisfy the triangle inequality).

Tested: every closed form against a numerical integration of the solid on a low-discrepancy grid, the mesh integral against the closed form for a box mesh (exact), against numerical integration for a random hull, and against the sphere for ever finer tessellations,
the parallel axis theorem and compounds against the integral of the union, principal axes against the diagonalised tensor.

## Integrators: `RigidBody` and `OdeIntegrator`

`RigidBody` is a struct of public fields (position, orientation quaternion, linear and angular velocity, force and torque accumulators, damping) with the operations on it: `applyForce`,
`applyForceAtPoint`, `applyTorque`, `applyImpulse`, `applyImpulseAtPoint`, `applyAngularImpulse`, `pointVelocity`, `kineticEnergy`, `angularMomentum`, `worldInverseInertia` and `rotationMatrix`.
`makeStatic()` (or a body made without mass properties) gives infinite mass; static bodies take impulses without moving.

`integrate(dt)` is semi-implicit Euler for the linear part and, for the angular part, the **implicit gyroscopic step** of Catto (2015): one Newton step of `I (w' - w) + dt w' x (I w') = dt tau` in the body frame, which is
stable for the fast tumbling of elongated bodies where explicit Euler gains energy without bound. The orientation is advanced with the exact quaternion exponential of the angular velocity and renormalised, so it does
not drift off the unit sphere.

Measured (a box with half extents 0.5, 0.3, 0.2 and mass 2, spinning at 3 rad/s about its intermediate axis with a small perturbation, no forces, 10 simulated seconds; the largest deviation over the run, relative to
the start):

| Step | Energy | Length of the angular momentum |
|---|---|---|
| 1/60 s | 4.1e-2 | 2.1e-2 |
| 1/120 s | 2.1e-2 | 1.1e-2 |
| 1/240 s | 1.1e-2 | 5.3e-3 |
| 1/480 s | 5.3e-3 | 2.7e-3 |

The error halves when the step halves: the method is first order, and it is dissipative by design (energy is lost, not gained). If you need the tumble to be accurate rather than stable, use a smaller step or `OdeIntegrator`.
`RigidBodyTest` checks free flight against the exact semi-implicit formula, a constant angular velocity against the exact rotation, the precession of an axially symmetric body against theory, an asymmetric tumble against an RK4 reference of the Euler equations, and that the explicit step blows up where the implicit one is stable.

`OdeIntegrator(dimension)` steps a general second-order system `x'' = a(t, x, x')` in place with `EXPLICIT_EULER`, `SEMI_IMPLICIT_EULER`, `VELOCITY_VERLET` or `RK4`, without allocating (it owns its scratch arrays).
The tests measure the convergence order of each on a harmonic oscillator (1, 1, 2 and 4 within 0.25) and the energy over 100 periods (explicit Euler gains without bound, the symplectic methods stay within O(dt) and O(dt^2), RK4 loses little).

## Contact manifolds: `ManifoldBuilder` and `ContactManifold`

`ManifoldBuilder.polytopes(a, b, margin, out)` builds the contact manifold of two `ConvexPolytope`s (the facets and edges this needs were added to `ConvexPolytope`: `facetCount`, `facetPlane`, `facetVertex`,
`edgeCount`, `edgeStart`, `edgeEnd`) in the standard way:

1. **SAT** over the facet normals of both and the cross products of the edge pairs; the axis of the least penetration is the contact normal, with a bias towards faces so that the result does not flicker between a face
   and an edge contact for nearly parallel boxes.
2. A **face contact** clips the incident facet against the side planes of the reference facet (Sutherland-Hodgman), keeps the points that are below the reference plane (or within the speculative `margin`), and reduces
   the polygon to at most four points: the deepest, the farthest from it, then the two that make the largest area on either side.
   If nothing is left after clipping (a deep penetration of a general hull, where the incident facet lies outside the side planes of the reference facet), the vertex of the incident hull that reaches deepest below
   the reference plane becomes the single contact. A seed sweep found this: the manifold had been empty for two overlapping random hulls (`ManifoldBuilderTest.aDeepPenetrationWhoseIncidentFacetClipsAwayStillHasAContact`).
3. An **edge contact** takes the supporting edges (of the parallel edges, the ones that reach furthest along the axis) and the closest points of the two segments, one point.

`margin` makes the contact speculative: a manifold is also produced for bodies up to `margin` apart, with a negative depth, so a solver can stop them before they touch. `shapes(a, b, out)` is the fallback for
any pair of `ConvexShape`s (spheres, capsules, boxes) through `Gjk.penetration`: one point.

`ContactManifold` holds up to 4 points (the point on each body, the depth, a feature id), the normal from A to B and, per point, the accumulated impulses. `warmStartFrom(previous, distance)` carries the impulses
of last frame's manifold over to the points with the same feature id, or else the nearest point within `distance` (each old point is used once), so a stack stays put.

Tested: the patch of a box on a box is four points at the corners of the overlap; separated, touching and speculative cases; crossed edges give one point; a box turned on a box reduces to four points; 600
random box pairs and 200 random convex hulls agree with GJK/EPA on whether they overlap and, for boxes, on depth and normal; warm starting keeps the impulses of the same feature through a small movement.

## Contact solver: `ContactSolver`

Sequential impulses (Catto), the algorithm of most real-time engines. `solveAll(a, b, manifolds, count, params, dt)` solves a set of manifolds together; `solve` is the same for one manifold. In order:

1. `prepare`: velocity targets per point (restitution from the approach speed before any solving, and only above `restitutionThreshold`; a gap lets the bodies approach by exactly the gap in one step, so a
   speculative contact never stops bodies that are still apart), and the warm start with last frame's impulses.
2. `sweep`, `params.iterations` times over **all** manifolds: friction first (two tangents, a friction pyramid: each tangent impulse is clamped to `mu` times the accumulated normal impulse of the point), then the normal
   impulse, accumulated and clamped to be non-negative (contacts push and never pull).
3. `correct`: the penetration beyond `slop` is removed with **split impulses**: bias velocities (`RigidBody.bvx` and so on) that move the bodies for the next step only and are then discarded by `integrate`, so the
   correction adds no energy to the real velocity. `Params.splitImpulse = false` gives plain Baumgarte, where the correction goes into the velocity.

Why all manifolds together: solving the manifolds one after another, each to convergence, does not hold a stack. The first version of this solver did that, and in a test with two boxes on the ground the whole stack
crept sideways at 2.6 cm/s forever (the plain Baumgarte velocity bias made the bottom box roll), and with the split impulse but still one manifold at a time the stack collapsed (the upper box flew off): the reaction of the upper
contact undid the lower one every frame, and nothing hid it any more. Both are now tests (`ContactSolverTest.twoBoxesStackAndStayPut`).

`Params` has `restitution` (0), `friction` (0.5), `beta` (0.2), `slop` (0.005), `restitutionThreshold` (1.0), `iterations` (10) and `splitImpulse` (true); `combineFriction` (geometric mean) and `combineRestitution` (the
larger) merge the values of two materials. Static helpers (`tangentBasis`, `effectiveMass`, `relativeNormalVelocity`, `solveNormal`, `solveFriction`, `apply`) are public for custom solvers.

Tested against closed forms: elastic and inelastic head-on collisions give the textbook velocities and conserve momentum (and energy when elastic); an impact at an off-centre point gives the impulse of the
effective mass including the rotation; positional correction and speculative contacts give the expected speeds; friction removes exactly `mu g dt` of horizontal velocity per frame (and stops a slow slide at once).
With the manifold builder and `solveAll`: a box dropped on the ground settles at the sink `slop + g dt^2 / beta` (the position at which one frame of bias cancels one frame of gravity, 18.6 mm at 60 Hz) and a
box sliding at 3 m/s with `mu = 0.5` stops after the distance `v^2 / (2 mu g)` to within 0.15 m; two stacked boxes stay within 5 mm of where they were put; a tilted box falls flat.

## Measured cost and allocation

`AnimPhysicsBench` (JMH, JDK 25, one thread, `-prof gc`; all report about 0 B/op, and `AnimPhysicsAllocationTest` enforces it):

| Operation | Time |
|---|---|
| `ManifoldBuilder.polytopes`, two overlapping boxes (8 vertices, 6 facets, 12 edges each) | about 9 us |
| `ContactSolver.solveAll`, one box on the ground, 4 points, 10 iterations | 9.5 us |
| `RigidBody.integrate`, with the implicit gyroscopic step | 119 ns |

The manifold builder is the heavy one: the SAT tests 12 facet axes and 144 edge pairs for two boxes, and nothing here prunes them (a hill-climbing separating axis search would, and is not built). The solver cost is
about 80 ns per constraint evaluation; the world-frame inverse inertia is cached per orientation, which took the same benchmark from 17.3 us to 9.5 us, and the first version of `integrate` allocated 1392 bytes per call
for its small arrays before it was written out in scalars.

## Not built

Broadphase integration, islands and sleeping, joints and motors (hinge, ball, slider), continuous collision detection, soft bodies, friction cones (the pyramid is used), rolling and spinning friction, a
block solver for the contact points of one manifold, and a hill-climbing SAT. A solver for joint constraints would use the same `prepare`/`sweep` structure.
