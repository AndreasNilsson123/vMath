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
import vmath.core.Predicates;

/**
 * The exact predicates over 1 024 cases each, in three kinds of input: random points (the filter decides), exactly degenerate points (collinear, coplanar, concyclic,
 * cospherical: always the exact stage) and the plain double determinant for comparison. Divide the score by 1 024 for the cost of one call.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PredicatesBench {

    private static final int N = 1024;
    // per case: 15 doubles are enough for insphere (5 points of 3); orient2d uses the first 6, orient3d 12, incircle 8
    private double[][] random, degenerate;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(3);
        random = new double[N][15];
        degenerate = new double[N][15];
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < 15; k++) {
                random[i][k] = r.nextDouble() * 2 - 1;
            }
            // exactly degenerate in every predicate: all points on integer lattice lines/planes/circles/spheres is not possible for one set of numbers at once, so use
            // points of the integer plane z = 0 on a line y = x for orient2d/orient3d, and lattice points of the sphere of radius 3 and the circle of radius 5 for the rest
            double[][] sphere = {{3, 0, 0}, {-3, 0, 0}, {0, 3, 0}, {0, -3, 0}, {0, 0, 3}, {0, 0, -3}, {1, 2, 2}, {-1, 2, 2}, {1, -2, 2}, {1, 2, -2}, {2, 1, 2}, {2, 2, 1}};
            int[] pick = new int[5];
            for (int k = 0; k < 5; k++) {
                boolean fresh;
                do {
                    pick[k] = r.nextInt(sphere.length);
                    fresh = true;
                    for (int q = 0; q < k; q++) {
                        fresh &= pick[q] != pick[k];
                    }
                } while (!fresh);
                System.arraycopy(sphere[pick[k]], 0, degenerate[i], 3 * k, 3);
            }
        }
    }

    // the planar kinds read x and y of the first points; for the degenerate set these are points of a sphere, which are not collinear, so use dedicated lattice data below
    @Benchmark
    public double orient2dRandom() {
        double s = 0;
        for (double[] c : random) {
            s += Predicates.orient2d(c[0], c[1], c[2], c[3], c[4], c[5]);
        }
        return s;
    }

    @Benchmark
    public double orient2dPlain() {
        double s = 0;
        for (double[] c : random) {
            s += (c[0] - c[4]) * (c[3] - c[5]) - (c[1] - c[5]) * (c[2] - c[4]);
        }
        return s;
    }

    @Benchmark
    public double orient2dCollinear() {
        double s = 0;
        for (int i = 0; i < N; i++) {
            double t = i & 7;
            s += Predicates.orient2d(0.1 * t, 0.1 * t, 3.5, 3.5, 7.25, 7.25); // exactly collinear (a diagonal), not representable multiples of 0.1: coordinates equal in x and y
        }
        return s;
    }

    @Benchmark
    public double orient3dRandom() {
        double s = 0;
        for (double[] c : random) {
            s += Predicates.orient3d(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11]);
        }
        return s;
    }

    @Benchmark
    public double orient3dCoplanar() {
        double s = 0;
        for (double[] c : degenerate) {
            s += Predicates.orient3d(c[0], c[1], 0, c[3], c[4], 0, c[6], c[7], 0, c[9], c[10], 0); // all in the plane z = 0
        }
        return s;
    }

    @Benchmark
    public double incircleRandom() {
        double s = 0;
        for (double[] c : random) {
            s += Predicates.incircle(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
        }
        return s;
    }

    @Benchmark
    public double incircleConcyclic() {
        double s = 0;
        double[][] circle = {{3, 4}, {4, 3}, {5, 0}, {0, 5}, {-3, 4}, {-4, -3}, {0, -5}, {3, -4}};
        for (int i = 0; i < N; i++) {
            double[] a = circle[i & 7], b = circle[(i + 2) & 7], c = circle[(i + 4) & 7], d = circle[(i + 5) & 7];
            s += Predicates.incircle(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]);
        }
        return s;
    }

    @Benchmark
    public double insphereRandom() {
        double s = 0;
        for (double[] c : random) {
            s += Predicates.insphere(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14]);
        }
        return s;
    }

    @Benchmark
    public double insphereCospherical() {
        double s = 0;
        for (double[] c : degenerate) {
            s += Predicates.insphere(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14]);
        }
        return s;
    }
}
