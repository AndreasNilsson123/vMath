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
import vmath.core.FastMath;

/**
 * {@code FastMath} against {@code Math} (cast to float), each over the same 4 096 arguments, summing the results so that nothing is optimised away. Divide the score by
 * 4 096 for the cost of one call. The arguments are in the ranges a renderer uses: angles within a few turns, unit-range values for acos, moderate values for exp and log.
 *
 * <p>{@code invSqrtMath} and {@code invSqrtFast} measure the bit-pattern reciprocal square root that was tried and removed (it was twice as slow as
 * {@code 1f / (float) Math.sqrt(x)}, docs/FASTMATH.md); {@code invSqrtFast} is kept here as the recorded experiment.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class FastMathBench {

    private static final int N = 4096;
    private float[] angle, unit, moderate, positive, ys, xs;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(11);
        angle = new float[N];
        unit = new float[N];
        moderate = new float[N];
        positive = new float[N];
        ys = new float[N];
        xs = new float[N];
        for (int i = 0; i < N; i++) {
            angle[i] = (float) ((r.nextDouble() - 0.5) * 20);
            unit[i] = (float) (r.nextDouble() * 2 - 1);
            moderate[i] = (float) ((r.nextDouble() - 0.5) * 20);
            positive[i] = (float) (Math.exp((r.nextDouble() - 0.5) * 14));
            ys[i] = (float) (r.nextDouble() - 0.5);
            xs[i] = (float) (r.nextDouble() - 0.5);
        }
    }

    @Benchmark
    public float sinMath() {
        float s = 0;
        for (float v : angle) {
            s += (float) Math.sin(v);
        }
        return s;
    }

    @Benchmark
    public float sinFast() {
        float s = 0;
        for (float v : angle) {
            s += FastMath.sin(v);
        }
        return s;
    }

    @Benchmark
    public float cosMath() {
        float s = 0;
        for (float v : angle) {
            s += (float) Math.cos(v);
        }
        return s;
    }

    @Benchmark
    public float cosFast() {
        float s = 0;
        for (float v : angle) {
            s += FastMath.cos(v);
        }
        return s;
    }

    @Benchmark
    public float atan2Math() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            s += (float) Math.atan2(ys[i], xs[i]);
        }
        return s;
    }

    @Benchmark
    public float atan2Fast() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            s += FastMath.atan2(ys[i], xs[i]);
        }
        return s;
    }

    @Benchmark
    public float acosMath() {
        float s = 0;
        for (float v : unit) {
            s += (float) Math.acos(v);
        }
        return s;
    }

    @Benchmark
    public float acosFast() {
        float s = 0;
        for (float v : unit) {
            s += FastMath.acos(v);
        }
        return s;
    }

    @Benchmark
    public float expMath() {
        float s = 0;
        for (float v : moderate) {
            s += (float) Math.exp(v);
        }
        return s;
    }

    @Benchmark
    public float expFast() {
        float s = 0;
        for (float v : moderate) {
            s += FastMath.exp(v);
        }
        return s;
    }

    @Benchmark
    public float logMath() {
        float s = 0;
        for (float v : positive) {
            s += (float) Math.log(v);
        }
        return s;
    }

    @Benchmark
    public float logFast() {
        float s = 0;
        for (float v : positive) {
            s += FastMath.log(v);
        }
        return s;
    }

    @Benchmark
    public float invSqrtMath() {
        float s = 0;
        for (float v : positive) {
            s += 1f / (float) Math.sqrt(v);
        }
        return s;
    }

    /** The bit-pattern estimate and two Newton steps: the experiment, kept so the measurement can be repeated. */
    @Benchmark
    public float invSqrtFast() {
        float s = 0;
        for (float v : positive) {
            float half = 0.5f * v;
            float y = Float.intBitsToFloat(0x5F375A86 - (Float.floatToRawIntBits(v) >> 1));
            y = y * (1.5f - half * y * y);
            y = y * (1.5f - half * y * y);
            s += y;
        }
        return s;
    }
}
