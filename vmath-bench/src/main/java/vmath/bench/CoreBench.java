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
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;

/**
 * Baseline for the immutable value types. Run with {@code -prof gc}: every benchmark should report
 * {@code gc.alloc.rate.norm ≈ 0 B/op} once escape analysis scalar-replaces the short-lived values, except
 * the ones that return a value object (JMH keeps those alive, so they allocate by construction).
 * The {@code chain*} benchmarks return a primitive on purpose, to show the allocation-free case.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CoreBench {

    private static final int N = 1024;
    private static final int MASK = N - 1;

    private final Mat4f[] mats = new Mat4f[N];
    private final Vec3f[] vecs = new Vec3f[N];
    private final Quatf[] quats = new Quatf[N];
    private int i;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(42);
        for (int k = 0; k < N; k++) {
            Vec3f t = new Vec3f(f(r, -10, 10), f(r, -10, 10), f(r, -10, 10));
            Vec3f s = new Vec3f(f(r, 0.5f, 2), f(r, 0.5f, 2), f(r, 0.5f, 2));
            Quatf q = Quatf.fromAxisAngle(f(r, 0, 6), new Vec3f(f(r, -1, 1), f(r, 0.1f, 1), f(r, -1, 1)).normalize());
            quats[k] = q;
            vecs[k] = t;
            mats[k] = Mat4f.translationRotateScale(t, q, s);
        }
    }

    private static float f(SplittableRandom r, float lo, float hi) {
        return lo + (float) r.nextDouble() * (hi - lo);
    }

    private int next() {
        return i++ & MASK;
    }

    // ------------------------------------------------------------ returning values (allocates by construction)

    @Benchmark
    public Mat4f mat4Mul() {
        int k = next();
        return mats[k].mul(mats[(k + 1) & MASK]);
    }

    @Benchmark
    public Mat4f mat4Invert() {
        return mats[next()].invert();
    }

    @Benchmark
    public Mat4f mat4InvertAffine() {
        return mats[next()].invertAffine();
    }

    @Benchmark
    public Vec3f mat4TransformPosition() {
        int k = next();
        return mats[k].transformPosition(vecs[k]);
    }

    @Benchmark
    public Quatf quatSlerp() {
        int k = next();
        return quats[k].slerp(quats[(k + 7) & MASK], 0.37f);
    }

    // ------------------------------------------------------------ allocation-free chains (primitive result)

    @Benchmark
    public float chainVec3() {
        int k = next();
        return vecs[k].add(vecs[(k + 1) & MASK]).mul(2f).normalize().dot(vecs[(k + 2) & MASK].cross(vecs[k]));
    }

    @Benchmark
    public float chainMatVec() {
        int k = next();
        return mats[k].mul(mats[(k + 3) & MASK]).transformPosition(vecs[k]).length();
    }

    @Benchmark
    public float chainQuat() {
        int k = next();
        return quats[k].mul(quats[(k + 5) & MASK]).normalize().transform(vecs[k]).lengthSquared();
    }

    @Benchmark
    public float chainTrs() {
        int k = next();
        return Mat4f.translationRotateScale(vecs[k], quats[k], Vec3f.ONE).invertAffine().m30();
    }
}
