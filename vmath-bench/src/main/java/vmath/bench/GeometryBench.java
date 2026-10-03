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
import vmath.anim.IkSolver;
import vmath.anim.Pose;
import vmath.anim.Skeleton;
import vmath.geo.Aabbf;
import vmath.geo.Capsulef;
import vmath.geo.ConvexHull;
import vmath.geo.ConvexPolytope;
import vmath.geo.ConvexShape;
import vmath.geo.ConvexShapes;
import vmath.geo.Gjk;
import vmath.geo.Polygons;
import vmath.geo.Sat;
import vmath.core.Vec3f;

/** Convex hull, polygon triangulation, SAT, GJK/EPA and the IK solvers: the time of one call (hull, triangulation, SAT) or of one call; the two IK chain solvers include resetting the pose to bind. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GeometryBench {

    private float[] cloud100, cloud1000, cloud10000, star100, star1000;
    private int[] triangles;
    private ConvexPolytope hullA, hullB, hullBMoved;
    private ConvexShape hullBFar;
    private Skeleton skeleton;
    private ConvexShape box, capsule, sphereNear, sphereFar;
    private final Gjk gjk = new Gjk();
    private final Gjk.Result result = new Gjk.Result();
    private IkSolver ik;
    private Pose pose;
    private int[] chain;

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

    private static float[] star(SplittableRandom r, int n) {
        float[] p = new float[2 * n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n, rad = 1 + 0.5 * (r.nextDouble() - 0.5) * (i % 2 == 0 ? 1 : 2);
            p[2 * i] = (float) (rad * Math.cos(a));
            p[2 * i + 1] = (float) (rad * Math.sin(a));
        }
        return p;
    }

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(7);
        cloud100 = cloud(r, 100);
        cloud1000 = cloud(r, 1000);
        cloud10000 = cloud(r, 10000);
        star100 = star(r, 100);
        star1000 = star(r, 1000);
        triangles = new int[3 * 1000];
        hullA = ConvexPolytope.of(cloud(r, 40), 40);
        hullB = ConvexPolytope.of(cloud(r, 40), 40);
        hullBMoved = hullB.transformed(vmath.core.Quatf.IDENTITY, new Vec3f(0.6f, 0.2f, 0.1f));
        hullBFar = ConvexShapes.translated(hullB, 4, 0, 0);
        box = ConvexShapes.of(new Aabbf(-1, -1, -1, 1, 1, 1));
        capsule = ConvexShapes.of(Capsulef.of(new Vec3f(1.2f, 0f, 0f), new Vec3f(2f, 1f, 0.5f), 0.4f));
        sphereNear = ConvexShapes.sphere(0.9, 0.1, 0.2, 0.7);
        sphereFar = ConvexShapes.sphere(5, 0, 0, 1);
        int n = 8;
        int[] parents = new int[n];
        float[] bind = new float[10 * n];
        for (int j = 0; j < n; j++) {
            parents[j] = j - 1;
            bind[10 * j + 3 + 3] = 1f;
            bind[10 * j + 7] = bind[10 * j + 8] = bind[10 * j + 9] = 1f;
            bind[10 * j] = j == 0 ? 0f : 1f;
        }
        skeleton = new Skeleton(parents, bind);
        pose = new Pose(skeleton);
        pose.setToBind(skeleton);
        ik = new IkSolver(skeleton);
        chain = new int[n];
        for (int i = 0; i < n; i++) {
            chain[i] = i;
        }
    }

    @Benchmark
    public ConvexHull hull100() {
        return ConvexHull.of(cloud100, 100);
    }

    @Benchmark
    public ConvexHull hull1000() {
        return ConvexHull.of(cloud1000, 1000);
    }

    @Benchmark
    public ConvexHull hull10000() {
        return ConvexHull.of(cloud10000, 10000);
    }

    @Benchmark
    public int triangulate100() {
        return Polygons.triangulate(star100, 100, triangles);
    }

    @Benchmark
    public int triangulate1000() {
        return Polygons.triangulate(star1000, 1000, triangles);
    }

    @Benchmark
    public double satPolytopes() {
        return Sat.separation(hullA, hullBMoved, axis);
    }

    private final double[] axis = new double[3];

    @Benchmark
    public double gjkDistanceBoxCapsule() {
        return gjk.distance(box, capsule, result);
    }

    @Benchmark
    public double gjkDistancePolytopes() {
        return gjk.distance(hullA, hullBFar, result);
    }

    @Benchmark
    public boolean gjkIntersectsPolytopes() {
        return gjk.intersects(hullA, hullBMoved);
    }

    @Benchmark
    public boolean epaBoxSphere() {
        return gjk.penetration(box, sphereNear, result);
    }

    @Benchmark
    public boolean epaPolytopes() {
        return gjk.penetration(hullA, hullBMoved, result);
    }

    @Benchmark
    public double gjkDistanceFarSpheres() {
        return gjk.distance(sphereNear, sphereFar, result);
    }

    @Benchmark
    public float ikTwoBone() {
        return ik.twoBone(pose, 0, 1, 2, 1.2f, 0.9f, 0.3f, 0f, 1f, 2f);
    }

    @Benchmark
    public float ikFabrik8() {
        pose.setToBind(skeleton);
        return ik.fabrik(pose, chain, 8, 3f, 4f, 1f, 16, 1e-4f);
    }

    @Benchmark
    public float ikCcd8() {
        pose.setToBind(skeleton);
        return ik.ccd(pose, chain, 8, 3f, 4f, 1f, 16, 1e-4f);
    }

    @Benchmark
    public float ikLookAt() {
        return ik.lookAt(pose, 3, 1f, 0f, 0f, 3f, 1f, 2f, 1f);
    }
}
