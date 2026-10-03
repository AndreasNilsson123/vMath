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
import vmath.core.Rebase;
import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Camera-relative conversion of a whole scene ({@link Rebase}, 100 000 positions or matrices), the shift of data that is already relative,
 * and the WGS-84 conversions per call.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LargeWorldBench {

    private static final int N = 100_000;

    private double[] worldPositions = new double[3 * N];
    private double[] worldMatrices = new double[16 * N];
    private float[] positions = new float[3 * N];
    private float[] matrices = new float[16 * N];
    private final Vec3d origin = new Vec3d(6_400_000.0, 10.0, -20.0);
    private final Vec3d shift = new Vec3d(1024.0, 0.0, 0.0);
    private Vec3d ecef;
    private double lat = 0.8, lon = 0.3;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(8);
        for (int i = 0; i < N; i++) {
            for (int c = 0; c < 3; c++) {
                worldPositions[3 * i + c] = origin.x() + r.nextDouble() * 100.0;
            }
            for (int c = 0; c < 16; c++) {
                worldMatrices[16 * i + c] = r.nextDouble();
            }
            worldMatrices[16 * i + 12] += origin.x();
        }
        ecef = Wgs84.toEcef(lat, lon, 100.0);
    }

    @Benchmark
    public float positions() {
        Rebase.positions(worldPositions, 0, origin, positions, 0, N);
        return positions[0];
    }

    @Benchmark
    public float matrices() {
        Rebase.matrices(worldMatrices, 0, origin, matrices, 0, N);
        return matrices[0];
    }

    @Benchmark
    public float shiftPositions() {
        Rebase.shiftPositions(positions, 0, N, shift);
        return positions[0];
    }

    @Benchmark
    public Vec3d toEcef() {
        return Wgs84.toEcef(lat, lon, 100.0);
    }

    @Benchmark
    public double toGeodetic() {
        return Wgs84.toGeodetic(ecef).latitude();
    }
}
