package vmath.bench;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.geo.Curves;
import vmath.util.Noise;
import vmath.util.Rng;
import vmath.util.Sequences;
import vmath.util.Spring;

/** Random numbers, noise, springs and curves: the time of one call (each benchmark makes 1 024 calls and JMH divides by that). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class UtilBench {

    private static final int N = 1024;
    private final Rng rng = new Rng(1);
    private final SplittableRandom splittable = new SplittableRandom(1);
    private double[] xs;
    private final float[] p = new float[3];
    private final double[] d = new double[3];
    private float[] cp;
    private final float[] out = new float[3];
    private final Spring spring = new Spring();
    private float[] poisson;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(5);
        xs = new double[N];
        for (int i = 0; i < N; i++) {
            xs[i] = r.nextDouble() * 100;
        }
        cp = new float[3 * 8];
        for (int i = 0; i < cp.length; i++) {
            cp[i] = (float) (r.nextDouble() * 10);
        }
        poisson = new float[2 * 4000];
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public long rngNextLong() {
        long s = 0;
        for (int i = 0; i < N; i++) {
            s += rng.nextLong();
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public long splittableRandomNextLong() {
        long s = 0;
        for (int i = 0; i < N; i++) {
            s += splittable.nextLong();
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double rngNextDouble() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += rng.nextDouble();
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double rngNextGaussian() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += rng.nextGaussian();
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float rngOnUnitSphere() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            rng.onUnitSphere(p, 0);
            s += p[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float rngCosineHemisphere() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            rng.cosineHemisphere(0.6f, 0f, 0.8f, p, 0);
            s += p[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float sobol2() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            Sequences.sobol2(i, p, 0);
            s += p[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double halton3() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Sequences.halton(i, 1);
        }
        return s;
    }

    @Benchmark
    public int poissonDisk() {
        return Sequences.poissonDisk(rng, 100, 100, 2.0f, 30, poisson);
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double value3() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.value3(xs[i], xs[(i + 1) & (N - 1)], 0.5, 1);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double perlin2() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.perlin2(xs[i], xs[(i + 1) & (N - 1)], 1);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double perlin3() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.perlin3(xs[i], xs[(i + 1) & (N - 1)], 0.5, 1);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double perlin4() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.perlin4(xs[i], xs[(i + 1) & (N - 1)], 0.5, 0.25, 1);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double simplex2() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.simplex2(xs[i], xs[(i + 1) & (N - 1)], 1);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double simplex3() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.simplex3(xs[i], xs[(i + 1) & (N - 1)], 0.5, 1);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double worley2() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            Noise.worley2(xs[i], xs[(i + 1) & (N - 1)], 1, 1.0, d);
            s += d[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double worley3() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            Noise.worley3(xs[i], xs[(i + 1) & (N - 1)], 0.5, 1, 1.0, d);
            s += d[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double fbmPerlin3Octaves4() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += Noise.fbm3(Noise.Kind.PERLIN, xs[i], xs[(i + 1) & (N - 1)], 0.5, 1, 4, 2.0, 0.5);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double curl3() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            Noise.curl3(xs[i], xs[(i + 1) & (N - 1)], 0.5, 1, d);
            s += d[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double springUpdate() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += spring.update(1.0, 10.0, 0.6 + (i & 3) * 0.3, 0.016);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public double springCritical() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            s += spring.update(1.0, 10.0, 1.0, 0.016);
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float cubicBezier3() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            Curves.bezier(cp, 0, 4, 3, (i & 1023) / 1024.0, out, 0);
            s += out[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float bezierDegree7() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            Curves.bezier(cp, 0, 8, 3, (i & 1023) / 1024.0, out, 0);
            s += out[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float catmullRomCentripetal() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            Curves.catmullRom(cp, 0, 8, 3, false, 0.5, (i & 1023) / 1024.0 * 7, out, 0);
            s += out[0];
        }
        return s;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public float bSpline() {
        float s = 0;
        for (int i = 0; i < N; i++) {
            Curves.bSpline(cp, 0, 8, 3, false, (i & 1023) / 1024.0 * 5, out, 0);
            s += out[0];
        }
        return s;
    }
}
