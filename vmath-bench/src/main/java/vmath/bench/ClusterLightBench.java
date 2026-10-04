package vmath.bench;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.lighting.ClusterGrid;
import vmath.lighting.ClusterLights;

/**
 * CPU reference of clustered light assignment: 1920 x 1080, 64-pixel tiles, 24 slices (30 x 17 x 24 = 12 240 clusters), lights scattered through the view
 * frustum in view space. {@code spotShare} is the fraction of spot lights. Run with {@code -prof gc}: the assignment should not allocate.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ClusterLightBench {

    @Param({"256", "1024", "4096"})
    public int lights;

    @Param({"0", "25"})
    public int spotPercent;

    /** Multiplies the range of every light: 1 is a stress scene (each light touches about 2 500 of the 12 240 clusters), 0.25 is closer to ordinary lights. */
    @Param({"0.25", "1"})
    public float rangeScale;

    private ClusterGrid grid;
    private ClusterLights assigner;

    @Setup
    public void setup() {
        grid = ClusterGrid.of(1.0f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, false);
        assigner = new ClusterLights();
        SplittableRandom r = new SplittableRandom(11);
        for (int i = 0; i < lights; i++) {
            float d = (float) Math.exp(Math.log(1.5) + r.nextDouble() * (Math.log(120) - Math.log(1.5)));
            float x = (float) (r.nextDouble() * 2.4 - 1.2) * d * grid.tanHalfFovX(), y = (float) (r.nextDouble() * 2.4 - 1.2) * d * grid.tanHalfFovY();
            float range = (float) (2 + r.nextDouble() * 10) * rangeScale;
            if (r.nextInt(100) < spotPercent) {
                assigner.addSpot(x, y, -d, (float) (r.nextDouble() - 0.5), (float) (r.nextDouble() - 0.5), -1f, 0.35f + (float) r.nextDouble() * 0.5f, range * 2.5f);
            } else {
                assigner.addPoint(x, y, -d, range);
            }
        }
    }

    @Benchmark
    public int assign() {
        assigner.assign(grid);
        return assigner.totalAssignments(grid);
    }
}
