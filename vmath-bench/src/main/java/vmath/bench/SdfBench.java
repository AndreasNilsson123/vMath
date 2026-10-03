package vmath.bench;

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
import vmath.geo.Sdf;
import vmath.geo.Sdfs;
import vmath.geo.SurfaceNets;

/**
 * Signed distance fields: one evaluation of a primitive and of a CSG scene (two spheres blended smoothly, a box with a spherical cavity, all moved by a rotation), a picking ray through the
 * scene, the normal at a point, and meshing the scene with surface nets on a grid of {@code cells}^3. Run with {@code -prof gc}: everything should report ~0 B/op.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SdfBench {

    @Param({"32", "64"})
    public int cells;

    private Sdf sphere;
    private Sdf scene;
    private SurfaceNets mesher;
    private SurfaceNets projecting;
    private Sdfs.Hit hit;
    private float[] normal;
    private float angle;

    @Setup
    public void setup() {
        sphere = Sdfs.sphere(0.1f, 0.2f, 0.3f, 1f);
        Sdf blob = Sdfs.smoothUnion(Sdfs.sphere(-0.4f, 0, 0, 0.7f), Sdfs.sphere(0.4f, 0.3f, 0, 0.6f), 0.4f);
        Sdf block = Sdfs.subtract(Sdfs.box(0, -0.6f, 0, 0.8f, 0.3f, 0.8f), Sdfs.sphere(0, -0.5f, 0, 0.5f));
        scene = Sdfs.rotate(Sdfs.union(blob, block), new vmath.core.Quatf(0.1f, 0.2f, 0.3f, 0.9f));
        mesher = new SurfaceNets();
        projecting = new SurfaceNets().projection(1).normals(true);
        hit = new Sdfs.Hit();
        normal = new float[3];
    }

    @Benchmark
    public float evalSphere() {
        angle += 0.001f;
        return sphere.distance(angle, 0.5f, -0.25f);
    }

    @Benchmark
    public float evalScene() {
        angle += 0.001f;
        return scene.distance(angle, 0.5f, -0.25f);
    }

    @Benchmark
    public boolean raycastScene() {
        angle += 0.001f;
        return Sdfs.raycast(scene, -3f, 0.3f * (float) Math.sin(angle), 0.2f, 1f, 0f, 0f, 0f, 10f, 128, 1e-4f, hit);
    }

    @Benchmark
    public boolean normalScene() {
        angle += 0.001f;
        return Sdfs.normal(scene, 0.3f + angle, 0.1f, 0.2f, 1e-3f, normal);
    }

    @Benchmark
    public int meshScene() {
        mesher.mesh(scene, -1.6f, -1.6f, -1.6f, 1.6f, 1.6f, 1.6f, cells, cells, cells);
        return mesher.triangleCount();
    }

    @Benchmark
    public int meshSceneProjectedWithNormals() {
        projecting.mesh(scene, -1.6f, -1.6f, -1.6f, 1.6f, 1.6f, 1.6f, cells, cells, cells);
        return projecting.triangleCount();
    }
}
