package vmath.gpucull;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.DynamicAabbTree;
import vmath.spatial.FrustumCuller;
import vmath.spatial.StaticBvh;
import vmath.spatial.BvhQuery;

/**
 * The copies of the six-plane box test that the library keeps for different data layouts must
 * agree on every box: the scalar kernel over {@link BoundsArray}, the static BVH, the dynamic
 * tree and the reference of the GPU culling shader over a {@link MemorySegment}.
 *
 * <p>The SIMD kernel is checked against the scalar one, bit for bit, in {@code vmath-simd}. The
 * inputs include boxes that touch a plane exactly, where a {@code >=} against a {@code >} would
 * show, and a box with NaN bounds.
 */
class CullCopiesAgreeTest {

    private final Random rnd = new Random(Long.getLong("vmath.seed", 4711L));

    private List<Aabbf> boxes(int n) {
        List<Aabbf> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float x = (rnd.nextFloat() * 2 - 1) * 30, y = (rnd.nextFloat() * 2 - 1) * 30, z = -rnd.nextFloat() * 120;
            float hx = 0.05f + rnd.nextFloat() * 3, hy = 0.05f + rnd.nextFloat() * 3, hz = 0.05f + rnd.nextFloat() * 3;
            out.add(new Aabbf(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz));
        }
        return out;
    }

    /** Boxes whose faces lie exactly on the planes of an orthographic frustum {@code [-1, 1] x [-1, 1] x [near, far]}. */
    private static List<Aabbf> touching() {
        List<Aabbf> out = new ArrayList<>();
        for (float s : new float[] {-3f, -1f, 1f}) {
            out.add(new Aabbf(s - 2f, -0.5f, -5f, s, 0.5f, -4f));  // a face on x = -1 or x = 1 from the outside or the inside
            out.add(new Aabbf(-0.5f, s - 2f, -5f, 0.5f, s, -4f));
        }
        out.add(new Aabbf(-0.5f, -0.5f, -0.1f - 0.9f, 0.5f, 0.5f, -0.5f));
        out.add(new Aabbf(-0.5f, -0.5f, -20f, 0.5f, 0.5f, -10f));
        out.add(new Aabbf(-0.5f, -0.5f, -30f, 0.5f, 0.5f, -20.0f));
        return out;
    }

    private static MemorySegment objects(List<Aabbf> boxes) {
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) (boxes.size() * CullObjectGpu.SIZE)]);
        for (int i = 0; i < boxes.size(); i++) {
            Aabbf b = boxes.get(i);
            CullObjectGpu.write(new CullObject(new Vec3f(b.minX(), b.minY(), b.minZ()), 0, new Vec3f(b.maxX(), b.maxY(), b.maxZ()), 0), seg, (long) i * CullObjectGpu.SIZE);
        }
        return seg;
    }

    private static MemorySegment view(Frustumf f, Mat4f viewProjection, int count, DepthRange range) {
        Vec4f[] planes = new Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) CullViewGpu.SIZE]);
        CullViewGpu.write(new CullView(planes, viewProjection, count, 1, 1, 1, 0.01f, range.ordinal(), 0), seg, 0);
        return seg;
    }

    private void agree(Frustumf f, Mat4f viewProjection, DepthRange range, List<Aabbf> boxes, String what) {
        int n = boxes.size();
        BoundsArray bounds = new BoundsArray(n);
        DynamicAabbTree tree = new DynamicAabbTree(0f, n);
        for (int i = 0; i < n; i++) {
            bounds.add(boxes.get(i));
            tree.insert(boxes.get(i), i);
        }
        VisibilitySet scalar = new VisibilitySet(n), bvh = new VisibilitySet(n), dynamic = new VisibilitySet(n);
        scalar.setAll(n);
        new FrustumCuller().cull(f, bounds, scalar);
        new BvhQuery(StaticBvh.build(bounds)).frustum(f, bounds, bvh);
        tree.newQuery().frustum(f, dynamic);
        MemorySegment view = view(f, viewProjection, n, range), objects = objects(boxes);
        for (int i = 0; i < n; i++) {
            boolean expected = scalar.get(i);
            String where = what + ", box " + i + " " + boxes.get(i);
            assertEquals(expected, bvh.get(i), "static BVH, " + where);
            assertEquals(expected, dynamic.get(i), "dynamic tree, " + where);
            assertEquals(expected, GpuCullReference.inFrustum(view, objects, i), "GPU reference, " + where);
        }
    }

    @Test
    void everyCopyOfTheBoxTestGivesTheSameAnswer() {
        for (ClipSpace space : ClipSpace.values()) {
            DepthRange range = DepthRange.of(space);
            for (int rep = 0; rep < 40; rep++) {
                Vec3f eye = new Vec3f(rnd.nextFloat() * 10 - 5, rnd.nextFloat() * 10 - 5, rnd.nextFloat() * 10 - 5);
                Mat4f vp = Mat4f.perspective(0.5f + rnd.nextFloat(), 0.8f + rnd.nextFloat(), 0.1f + rnd.nextFloat(), 80f + rnd.nextFloat() * 50, space)
                        .mul(Mat4f.lookAt(eye, new Vec3f(eye.x() + rnd.nextFloat() - 0.5f, eye.y() + rnd.nextFloat() - 0.5f, eye.z() - 1f), Vec3f.UNIT_Y));
                Frustumf f = Frustumf.fromViewProjection(vp, range);
                agree(f, vp, range, boxes(600), space + " perspective " + rep);
            }
            Mat4f vp = Mat4f.ortho(-1f, 1f, -1f, 1f, 1f, 20f, space);
            agree(Frustumf.fromViewProjection(vp, range), vp, range, touching(), space + " orthographic, boxes on the planes");
        }
    }

    @Test
    void aBoxWithNaNBoundsIsKeptByTheKernelAndTheShaderReference() {
        // the kernels and the shader keep what they cannot judge; the trees are not defined for NaN bounds (a NaN has no place in a sorted hierarchy), so they are not asked
        List<Aabbf> one = List.of(new Aabbf(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN));
        Mat4f vp = Mat4f.perspective(1f, 1f, 0.1f, 10f, ClipSpace.D3D);
        Frustumf f = Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE);
        BoundsArray bounds = new BoundsArray(1);
        bounds.add(one.get(0));
        VisibilitySet scalar = new VisibilitySet(1);
        scalar.setAll(1);
        new FrustumCuller().cull(f, bounds, scalar);
        assertEquals(true, scalar.get(0));
        assertEquals(true, GpuCullReference.inFrustum(view(f, vp, 1, DepthRange.ZERO_TO_ONE), objects(one), 0));
    }
}
