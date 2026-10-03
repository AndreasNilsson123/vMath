package vmath;

import static vmath.Alloc.assertNoAllocation;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.anim.AnimationClip;
import vmath.anim.MorphTargets;
import vmath.anim.Pose;
import vmath.anim.QuantizedClip;
import vmath.anim.RootMotion;
import vmath.geo.Aabbf;
import vmath.geo.ConvexPolytope;
import vmath.physics.ContactManifold;
import vmath.physics.ContactSolver;
import vmath.physics.ManifoldBuilder;
import vmath.physics.MassProperties;
import vmath.physics.OdeIntegrator;
import vmath.physics.RigidBody;

/**
 * The allocation contract (see {@link AllocationContractTest}) for morph targets, root motion, quantized clips and the physics step: once set up, the per-frame calls allocate nothing.
 */
class AnimPhysicsAllocationTest {

    private static final int WARM = 30_000;
    private static final int CALLS = 60_000;

    private static AnimationClip clip() {
        float[] t = {0f, 1f, 2f, 3f, 4f};
        float[] pos = new float[15], rot = new float[20];
        for (int i = 0; i < 5; i++) {
            pos[3 * i] = 2f * t[i];
            pos[3 * i + 2] = 3f * t[i];
            float a = 0.25f * t[i];
            rot[4 * i + 1] = (float) Math.sin(a);
            rot[4 * i + 3] = (float) Math.cos(a);
        }
        return AnimationClip.builder(2).translation(0, t, pos).rotation(0, t, rot).build();
    }

    @Test
    void morphTargets() {
        int vertices = 500, targets = 12;
        SplittableRandom r = new SplittableRandom(3);
        MorphTargets.Builder b = MorphTargets.builder(vertices);
        for (int k = 0; k < targets; k++) {
            float[] d = new float[3 * vertices], n = new float[3 * vertices], tg = new float[3 * vertices];
            for (int i = 0; i < vertices; i++) {
                if (r.nextInt(3) == 0) {
                    for (int a = 0; a < 3; a++) {
                        d[3 * i + a] = (float) (r.nextDouble() - 0.5);
                        n[3 * i + a] = (float) (r.nextDouble() - 0.5) * 0.1f;
                    }
                }
            }
            b.target("t" + k, d, n, tg);
        }
        MorphTargets m = b.build();
        float[] base = new float[3 * vertices], baseN = new float[3 * vertices], out = new float[3 * vertices], outN = new float[3 * vertices], weights = new float[targets];
        for (int i = 0; i < vertices; i++) {
            base[3 * i] = i;
            baseN[3 * i + 1] = 1f;
        }
        for (int k = 0; k < targets; k++) {
            weights[k] = (float) r.nextDouble();
        }
        int[] index = new int[4];
        float[] w = new float[4];
        assertNoAllocation("MorphTargets.apply", WARM / 10, CALLS / 10, () -> m.apply(base, weights, out));
        assertNoAllocation("MorphTargets.applyNormals", WARM / 10, CALLS / 10, () -> m.applyNormals(baseN, weights, outN, true));
        assertNoAllocation("MorphTargets.activeTargets", WARM, CALLS, () -> m.activeTargets(weights, 0.01f, 4, index, w));
        assertNoAllocation("MorphTargets.boundsExpansion", WARM, CALLS, () -> m.boundsExpansion(weights));
    }

    @Test
    void rootMotionAndQuantizedClips() {
        AnimationClip clip = clip();
        RootMotion root = new RootMotion(clip, 0);
        float[] delta = new float[7];
        float[] time = {0f};
        assertNoAllocation("RootMotion.delta", WARM, CALLS, () -> {
            time[0] += 0.0137f;
            root.delta(time[0], time[0] + 0.05f, true, delta);
        });
        Pose pose = new Pose(2), reference = new Pose(2);
        new vmath.anim.ClipSampler(clip).sample(0f, false, reference);
        assertNoAllocation("RootMotion.strip", WARM, CALLS, () -> root.strip(pose, reference, RootMotion.Mode.TRANSLATION_XZ_AND_YAW));
        for (QuantizedClip.RotationFormat format : QuantizedClip.RotationFormat.values()) {
            QuantizedClip q = QuantizedClip.of(clip, format);
            float[] t = {0f};
            assertNoAllocation("QuantizedClip.sample " + format, WARM, CALLS, () -> {
                t[0] += 0.0173f;
                q.sample(t[0], true, pose);
            });
        }
    }

    @Test
    void physicsStep() {
        ManifoldBuilder builder = new ManifoldBuilder();
        ConvexPolytope a = ConvexPolytope.of(new Aabbf(-1, -1, -1, 1, 1, 1));
        ConvexPolytope b = ConvexPolytope.of(new Aabbf(-1, -1, -1, 1, 1, 1)).transformed(new vmath.core.Quatf(0, 0.2f, 0, 0.98f).normalize(), new vmath.core.Vec3f(0.3f, 1.9f, 0.2f));
        ContactManifold manifold = new ContactManifold();
        assertNoAllocation("ManifoldBuilder.polytopes", WARM / 3, CALLS / 3, () -> builder.polytopes(a, b, 0.02, manifold));

        RigidBody ground = new RigidBody();
        RigidBody body = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 1));
        body.setPose(0, 0.49, 0, 0, 0, 0, 1);
        ContactManifold m = new ContactManifold();
        m.setNormal(0, 1, 0);
        for (int i = 0; i < 4; i++) {
            m.add((i & 1) - 0.5, 0, (i >> 1) - 0.5, (i & 1) - 0.5, 0, (i >> 1) - 0.5, 0.01, i);
        }
        ContactSolver.Params params = new ContactSolver.Params();
        RigidBody[] as = {ground}, bs = {body};
        ContactManifold[] ms = {m};
        assertNoAllocation("ContactSolver.solveAll", WARM / 3, CALLS / 3, () -> {
            body.vx = 1;
            body.vy = -1;
            ContactSolver.solveAll(as, bs, ms, 1, params, 1.0 / 60);
        });

        RigidBody free = new RigidBody(MassProperties.box(0.5, 0.3, 0.2, 2));
        free.wx = 3;
        free.wy = 0.1;
        assertNoAllocation("RigidBody.integrate", WARM / 3, CALLS / 3, () -> {
            free.applyForce(0, -9.81, 0);
            free.integrate(1.0 / 240);
        });

        OdeIntegrator ode = new OdeIntegrator(3);
        double[] x = {1, 0, 0}, v = {0, 1, 0};
        for (OdeIntegrator.Method method : OdeIntegrator.Method.values()) {
            assertNoAllocation("OdeIntegrator.step " + method, WARM, CALLS, () -> ode.step(method, (tt, xx, vv, aa) -> {
                aa[0] = -xx[0];
                aa[1] = -xx[1];
                aa[2] = -xx[2];
            }, 0, 0.01, x, v));
        }
    }

    @Test
    void signedDistanceFieldQueriesAndMeshing() {
        vmath.geo.Sdf scene = vmath.geo.Sdfs.smoothUnion(vmath.geo.Sdfs.sphere(-0.4f, 0, 0, 0.7f), vmath.geo.Sdfs.subtract(vmath.geo.Sdfs.box(0.4f, 0, 0, 0.6f, 0.6f, 0.6f), vmath.geo.Sdfs.sphere(0.4f, 0, 0, 0.45f)), 0.3f);
        vmath.geo.Sdfs.Hit hit = new vmath.geo.Sdfs.Hit();
        float[] n = new float[3], p = new float[3], angle = {0f};
        assertNoAllocation("Sdfs.raycast", WARM, CALLS, () -> {
            angle[0] += 0.01f;
            vmath.geo.Sdfs.raycast(scene, -3, 0.2f * (float) Math.sin(angle[0]), 0.1f, 1, 0, 0, 0f, 10f, 128, 1e-4f, hit);
        });
        assertNoAllocation("Sdfs.normal and project", WARM, CALLS, () -> {
            vmath.geo.Sdfs.normal(scene, 0.3f, 0.9f, 0.1f, 1e-3f, n);
            vmath.geo.Sdfs.project(scene, 0.3f, 1.4f, 0.1f, 4, 1e-5f, 1e-3f, p);
        });
        vmath.geo.SurfaceNets mesher = new vmath.geo.SurfaceNets().normals(true).projection(1);
        assertNoAllocation("SurfaceNets.mesh", 300, 2000, () -> mesher.mesh(scene, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 24, 24, 24));
    }
}

