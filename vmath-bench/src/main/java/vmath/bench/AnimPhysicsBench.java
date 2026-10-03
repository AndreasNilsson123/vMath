package vmath.bench;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.anim.AnimationClip;
import vmath.anim.ClipCompression;
import vmath.anim.ClipSampler;
import vmath.anim.MorphTargets;
import vmath.anim.Pose;
import vmath.anim.QuantizedClip;
import vmath.anim.RootMotion;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.ConvexPolytope;
import vmath.physics.ContactManifold;
import vmath.physics.ContactSolver;
import vmath.physics.ManifoldBuilder;
import vmath.physics.MassProperties;
import vmath.physics.RigidBody;

/**
 * Morph targets, compressed clips, root motion and the physics step. Run with {@code -prof gc}: everything but {@code compress} should report ~0 B/op.
 *
 * <ul>
 *   <li>{@code morphApply}: 10 000 vertices, 8 targets of which every third vertex is touched, all weights non-zero</li>
 *   <li>{@code sampleClip}, {@code sampleQuantized32}, {@code sampleQuantized64}: one 64-joint character, 30 keys on every track</li>
 *   <li>{@code rootDelta}: root motion of one frame step</li>
 *   <li>{@code compress}: offline reduction of that clip (milliseconds, once at import time)</li>
 *   <li>{@code manifoldBoxes}: SAT and clipping of two overlapping boxes into a contact manifold</li>
 *   <li>{@code solveBoxOnGround}: the solver (10 sweeps, four points) for one box on the ground</li>
 *   <li>{@code integrate}: one rigid body step with the implicit gyroscopic term</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AnimPhysicsBench {

    private static final int VERTICES = 10_000, TARGETS = 8, JOINTS = 64, KEYS = 30;

    private MorphTargets morph;
    private float[] base, out, weights;
    private AnimationClip clip;
    private ClipSampler sampler;
    private QuantizedClip q32, q64;
    private Pose pose;
    private RootMotion root;
    private float[] delta = new float[7];
    private float time;
    private ManifoldBuilder builder;
    private ConvexPolytope boxA, boxB;
    private ContactManifold manifold;
    private RigidBody ground, box, free;
    private RigidBody[] as, bs;
    private ContactManifold[] ms;
    private ContactSolver.Params params;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(1);
        MorphTargets.Builder mb = MorphTargets.builder(VERTICES);
        for (int k = 0; k < TARGETS; k++) {
            float[] d = new float[3 * VERTICES];
            for (int i = 0; i < VERTICES; i += 3) {
                for (int a = 0; a < 3; a++) {
                    d[3 * i + a] = (float) (r.nextDouble() - 0.5);
                }
            }
            mb.target("t" + k, d);
        }
        morph = mb.build();
        base = new float[3 * VERTICES];
        out = new float[3 * VERTICES];
        weights = new float[TARGETS];
        java.util.Arrays.fill(weights, 0.5f);

        AnimationClip.Builder cb = AnimationClip.builder(JOINTS);
        float[] t = new float[KEYS];
        for (int i = 0; i < KEYS; i++) {
            t[i] = i / 30f;
        }
        for (int j = 0; j < JOINTS; j++) {
            float[] pos = new float[3 * KEYS], rot = new float[4 * KEYS];
            for (int i = 0; i < KEYS; i++) {
                pos[3 * i] = (float) Math.sin(t[i] * (1 + j % 5)) * 0.1f;
                pos[3 * i + 1] = j * 0.01f;
                float a = (float) Math.sin(t[i] * 2 + j) * 0.5f;
                rot[4 * i + 1] = (float) Math.sin(a / 2);
                rot[4 * i + 3] = (float) Math.cos(a / 2);
            }
            cb.translation(j, t, pos).rotation(j, t, rot);
        }
        clip = cb.build();
        sampler = new ClipSampler(clip);
        q32 = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32);
        q64 = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_64);
        pose = new Pose(JOINTS);
        root = new RootMotion(clip, 0);

        builder = new ManifoldBuilder();
        boxA = ConvexPolytope.of(new Aabbf(-1, -1, -1, 1, 1, 1));
        boxB = ConvexPolytope.of(new Aabbf(-1, -1, -1, 1, 1, 1)).transformed(new Quatf(0, 0.2f, 0, 0.98f).normalize(), new Vec3f(0.3f, 1.9f, 0.2f));
        manifold = new ContactManifold();

        ground = new RigidBody();
        box = new RigidBody(MassProperties.box(0.5, 0.5, 0.5, 1));
        box.setPose(0, 0.49, 0, 0, 0, 0, 1);
        ContactManifold m = new ContactManifold();
        m.setNormal(0, 1, 0);
        for (int i = 0; i < 4; i++) {
            m.add((i & 1) - 0.5, 0, (i >> 1) - 0.5, (i & 1) - 0.5, 0, (i >> 1) - 0.5, 0.01, i);
        }
        as = new RigidBody[] {ground};
        bs = new RigidBody[] {box};
        ms = new ContactManifold[] {m};
        params = new ContactSolver.Params();
        free = new RigidBody(MassProperties.box(0.5, 0.3, 0.2, 2));
        free.wx = 3;
        free.wy = 0.1;
    }

    @Benchmark
    public float[] morphApply() {
        morph.apply(base, weights, out);
        return out;
    }

    @Benchmark
    public Pose sampleClip() {
        time += 0.0167f;
        sampler.sample(time, true, pose);
        return pose;
    }

    @Benchmark
    public Pose sampleQuantized32() {
        time += 0.0167f;
        q32.sample(time, true, pose);
        return pose;
    }

    @Benchmark
    public Pose sampleQuantized64() {
        time += 0.0167f;
        q64.sample(time, true, pose);
        return pose;
    }

    @Benchmark
    public float[] rootDelta() {
        time += 0.0167f;
        root.delta(time, time + 0.0167f, true, delta);
        return delta;
    }

    @Benchmark
    public AnimationClip compress() {
        return ClipCompression.reduce(clip, 0.001f, 0.001f, 0.001f);
    }

    @Benchmark
    public boolean manifoldBoxes() {
        return builder.polytopes(boxA, boxB, 0.02, manifold);
    }

    @Benchmark
    public RigidBody solveBoxOnGround() {
        box.vx = 1;
        box.vy = -1;
        ContactSolver.solveAll(as, bs, ms, 1, params, 1.0 / 60);
        return box;
    }

    @Benchmark
    public RigidBody integrate() {
        free.applyForce(0, -9.81, 0);
        free.integrate(1.0 / 240);
        return free;
    }
}
