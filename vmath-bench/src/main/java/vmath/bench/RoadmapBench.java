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
import vmath.camera.PhysicalCamera;
import vmath.camera.PreethamSky;
import vmath.camera.SolarPosition;
import vmath.core.ClipSpace;
import vmath.core.Mat2f;
import vmath.core.Mat3x2f;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec2f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.BoundingVolumes;
import vmath.geo.Capsulef;
import vmath.geo.KDop;
import vmath.geo.Obbf;
import vmath.geo.Spheref;
import vmath.util.DebugLines;
import vmath.util.Ibl;
import vmath.util.SphericalHarmonics;

/** The geometry, camera, lighting and debug-draw additions: the time of one call (each benchmark states what a call covers). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RoadmapBench {

    private float[] cloud1000, cloud10000;
    private KDop kdopA, kdopB;
    private Mat4f projection, view;
    private Mat2f m2;
    private Mat3x2f t2;
    private Vec2f v2 = new Vec2f(1.5f, -2f);
    private final float[] coefficients = new float[27], rotated = new float[27];
    private final double[] out3 = new double[3], dfg = new double[2];
    private final DebugLines lines = new DebugLines(8192);
    private PreethamSky sky;
    private final PhysicalCamera camera = PhysicalCamera.fullFrame(50, 2.8, 1.0 / 125, 100, 4);
    private double jd = SolarPosition.julianDay(2024, 6, 21, 12, 0, 0);
    private Quatf rotation = Quatf.fromAxisAngle(0.8f, new Vec3f(0.3f, 0.5f, 0.8f).normalize());
    private final float[] lut = new float[32 * 32 * 2];

    private static float[] cloud(SplittableRandom r, int n) {
        float[] p = new float[3 * n];
        for (int i = 0; i < n; i++) {
            double x, y, z;
            do {
                x = r.nextDouble() * 2 - 1;
                y = r.nextDouble() * 2 - 1;
                z = r.nextDouble() * 2 - 1;
            } while (x * x + y * y + z * z > 1);
            p[3 * i] = (float) x;
            p[3 * i + 1] = (float) y;
            p[3 * i + 2] = (float) z;
        }
        return p;
    }

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(3);
        cloud1000 = cloud(r, 1000);
        cloud10000 = cloud(r, 10000);
        kdopA = KDop.of(14, cloud1000, 1000);
        float[] shifted = cloud(r, 1000);
        for (int i = 0; i < shifted.length; i++) {
            shifted[i] += 0.8f;
        }
        kdopB = KDop.of(14, shifted, 1000);
        projection = Mat4f.perspective(1.0f, 1.6f, 0.1f, 500f, ClipSpace.VULKAN);
        view = Mat4f.lookAt(new Vec3f(1, 2, 3), new Vec3f(0, 0, 0), new Vec3f(0, 1, 0));
        m2 = new Mat2f(1.5f, 0.3f, -0.4f, 2.0f);
        t2 = Mat3x2f.translationRotateScale(new Vec2f(3, 4), 0.7f, new Vec2f(2, 1.5f));
        SphericalHarmonics.project((x, y, z, o) -> {
            o[0] = 1 + x;
            o[1] = 1 + 0.5 * y;
            o[2] = 1 + z * z;
        }, 8, coefficients);
        sky = new PreethamSky(3, 0.8);
    }

    // ------------------------------------------------------------ bounding volumes

    @Benchmark
    public Spheref minimumSphere1000() {
        return BoundingVolumes.minimumSphere(cloud1000, 1000);
    }

    @Benchmark
    public Spheref minimumSphere10000() {
        return BoundingVolumes.minimumSphere(cloud10000, 10000);
    }

    @Benchmark
    public Obbf pcaBox1000() {
        return BoundingVolumes.pcaBox(cloud1000, 1000);
    }

    @Benchmark
    public KDop kdop14Of1000() {
        return KDop.of(14, cloud1000, 1000);
    }

    @Benchmark
    public boolean kdop14Overlaps() {
        return kdopA.overlaps(kdopB);
    }

    @Benchmark
    public Obbf transformedBox() {
        return BoundingVolumes.transformedBox(new Aabbf(-1, -2, -3, 4, 5, 6), view);
    }

    // ------------------------------------------------------------ matrices

    @Benchmark
    public Mat4f invertGeneral() {
        return projection.invert();
    }

    @Benchmark
    public Mat4f invertProjection() {
        return projection.invertProjection();
    }

    @Benchmark
    public Mat4f.ShearDecomposition decomposeWithShear() {
        return view.decomposeWithShear();
    }

    @Benchmark
    public Mat4f.Trs decomposePlain() {
        return view.decompose();
    }

    @Benchmark
    public Mat2f mat2Invert() {
        return m2.invert();
    }

    @Benchmark
    public Vec2f mat3x2TransformPosition() {
        return t2.transformPosition(v2);
    }

    @Benchmark
    public Mat3x2f mat3x2Mul() {
        return t2.mul(t2);
    }

    @Benchmark
    public Mat3x2f mat3x2Invert() {
        return t2.invert();
    }

    // ------------------------------------------------------------ lighting

    @Benchmark
    public double shEvaluate() {
        SphericalHarmonics.evaluate(coefficients, 0.3, 0.5, 0.8, out3);
        return out3[0];
    }

    @Benchmark
    public double shIrradiance() {
        SphericalHarmonics.irradiance(coefficients, 0.3, 0.5, 0.8, out3);
        return out3[0];
    }

    @Benchmark
    public float shRotate() {
        SphericalHarmonics.rotate(coefficients, rotation, rotated);
        return rotated[0];
    }

    @Benchmark
    public float shProject16() {
        float[] c = rotated;
        SphericalHarmonics.project((x, y, z, o) -> {
            o[0] = 1 + x;
            o[1] = 1 + 0.5 * y;
            o[2] = 1 + z * z;
        }, 16, c);
        return c[0];
    }

    @Benchmark
    public double dfg256() {
        Ibl.dfg(0.5, 0.5, 256, dfg);
        return dfg[0];
    }

    @Benchmark
    public float brdfLut32x32x128() {
        Ibl.brdfLut(32, 128, lut);
        return lut[0];
    }

    @Benchmark
    public double prefilterGgx1024() {
        Ibl.prefilterGgx((x, y, z, o) -> {
            o[0] = 1 + x;
            o[1] = 1 + y;
            o[2] = 1 + z;
        }, 0, 1, 0, 0.5, 1024, out3);
        return out3[0];
    }

    @Benchmark
    public double preethamSkyRgb() {
        sky.rgb(0.3, 0.8, 0.2, 0.5, 0.7, 0.1, out3);
        return out3[0];
    }

    // ------------------------------------------------------------ camera and sun

    @Benchmark
    public double physicalCameraDepthOfField() {
        return camera.nearFocusLimit() + camera.farFocusLimit() + camera.circleOfConfusion(12.0);
    }

    @Benchmark
    public double solarPosition() {
        return SolarPosition.position(jd, 48.0, 11.0).elevation();
    }

    @Benchmark
    public double solarDay() {
        return SolarPosition.day(Math.floor(jd - 0.5) + 0.5, 48.0, 11.0, SolarPosition.SUNRISE_SUNSET).length();
    }

    // ------------------------------------------------------------ debug lines

    @Benchmark
    public int debugBoxSphereCapsule() {
        lines.clear();
        lines.box(-1, -1, -1, 1, 1, 1).sphere(0, 0, 0, 1f, 24).capsule(Capsulef.of(new Vec3f(0, 0, 0), new Vec3f(0, 2, 0), 0.5f), 16);
        return lines.lineCount();
    }
}
