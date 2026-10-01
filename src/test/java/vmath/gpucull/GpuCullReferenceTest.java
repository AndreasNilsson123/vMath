package vmath.gpucull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.GpuWriter;

class GpuCullReferenceTest {

    private final Random rnd = new Random(Long.getLong("vmath.seed", 31337L));

    // ---------------------------------------------------------------- scene construction

    private record Conv(ClipSpace space, DepthRange range, boolean yDown) {
    }

    /** The graphics APIs: the HiZPyramid's yDown is whether row 0 is at NDC y = +1 (D3D); GL and a Vulkan flipped projection have row 0 at NDC y = -1. */
    private static final Conv[] CONVENTIONS = {new Conv(ClipSpace.OPENGL, DepthRange.NEGATIVE_ONE_TO_ONE, false), new Conv(ClipSpace.VULKAN, DepthRange.ZERO_TO_ONE, false),
            new Conv(ClipSpace.D3D, DepthRange.ZERO_TO_ONE, true)};

    private Mat4f projection(Conv c, boolean reversed) {
        return reversed ? Mat4f.perspectiveReversedZ(1.0f, 16f / 9f, 0.1f, c.space()) : Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 200f, c.space());
    }

    private List<Aabbf> randomBoxes(int n, float xyRange, float zNear, float zFar) {
        List<Aabbf> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float z = -(float) Math.exp(Math.log(zNear) + rnd.nextDouble() * (Math.log(zFar) - Math.log(zNear)));
            float x = (rnd.nextFloat() * 2 - 1) * xyRange * -z * 0.6f, y = (rnd.nextFloat() * 2 - 1) * xyRange * -z * 0.4f;
            float hx = 0.1f + rnd.nextFloat() * 3f * (-z / 50f + 0.2f), hy = 0.1f + rnd.nextFloat() * 3f * (-z / 50f + 0.2f), hz = 0.1f + rnd.nextFloat() * 2f;
            boxes.add(new Aabbf(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz));
        }
        return boxes;
    }

    private static MemorySegment objects(List<Aabbf> boxes, int[] draw, int[] flags) {
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) (boxes.size() * CullObjectGpu.SIZE)]);
        for (int i = 0; i < boxes.size(); i++) {
            Aabbf b = boxes.get(i);
            CullObjectGpu.write(new CullObject(new Vec3f(b.minX(), b.minY(), b.minZ()), draw[i], new Vec3f(b.maxX(), b.maxY(), b.maxZ()), flags == null ? 0 : flags[i]), seg,
                    (long) i * CullObjectGpu.SIZE);
        }
        return seg;
    }

    private static MemorySegment view(Mat4f viewProjection, int objectCount, HiZPyramid hzb, DepthRange range, boolean yDown, float nearW) {
        Frustumf f = Frustumf.fromViewProjection(viewProjection, range);
        Vec4f[] planes = new Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) CullViewGpu.SIZE]);
        CullViewGpu.write(new CullView(planes, viewProjection, objectCount, hzb == null ? 1 : hzb.width(0), hzb == null ? 1 : hzb.height(0), hzb == null ? 1 : hzb.levels(), nearW,
                range.ordinal(), yDown ? 0 : 0), seg, 0);
        return seg;
    }

    /** Draws: {@code draws} commands with the given capacity each, instance counts zero, base instances laid out one after the other. */
    private static DrawCommandBuffer commands(int[] capacity) {
        DrawCommandBuffer cb = new DrawCommandBuffer(MemorySegment.ofArray(new byte[capacity.length * 20]), DrawCommandBuffer.Kind.ELEMENTS, false);
        int base = 0;
        for (int d = 0; d < capacity.length; d++) {
            cb.addElements(36, 0, d * 36, 0, base);
            base += capacity[d];
        }
        return cb;
    }

    private static int total(int[] capacity) {
        int t = 0;
        for (int c : capacity) {
            t += c;
        }
        return t;
    }

    private static TreeSet<Integer> instances(DrawCommandBuffer cb, MemorySegment visible, int draw) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int k = 0; k < cb.instanceCount(draw); k++) {
            assertTrue(out.add(GpuWriter.getInt(visible, 4L * (cb.baseInstance(draw) + k))), "an object appears once per draw");
        }
        return out;
    }

    // ---------------------------------------------------------------- layouts

    @Test
    void theRecordsHaveTheLayoutsTheSpecificationsGive() {
        // std430: vec3 (12 bytes, 16-aligned), uint packed into the slot after it, twice
        assertEquals(32, CullObjectGpu.SIZE);
        assertEquals(0, CullObjectGpu.OFFSET_MIN);
        assertEquals(12, CullObjectGpu.OFFSET_DRAW_INDEX);
        assertEquals(16, CullObjectGpu.OFFSET_MAX);
        assertEquals(28, CullObjectGpu.OFFSET_FLAGS);
        // std140: float[6] of vec4 has a 16-byte stride, then the mat4 (16-aligned), then scalars one after the other, the whole rounded up to 16
        assertEquals(0, CullViewGpu.OFFSET_PLANES);
        assertEquals(16, CullViewGpu.STRIDE_PLANES);
        assertEquals(96, CullViewGpu.OFFSET_VIEW_PROJECTION);
        assertEquals(160, CullViewGpu.OFFSET_OBJECT_COUNT);
        assertEquals(176, CullViewGpu.OFFSET_NEAR_W);
        assertEquals(180, CullViewGpu.OFFSET_DEPTH_MODE);
        assertEquals(184, CullViewGpu.OFFSET_FLAGS);
        assertEquals(192, CullViewGpu.SIZE);
    }

    // ---------------------------------------------------------------- frustum

    @Test
    void theFrustumTestAgreesWithTheFrustumClass() {
        for (Conv c : CONVENTIONS) {
            for (boolean reversed : new boolean[] {false, true}) {
                if (reversed && c.space() == ClipSpace.OPENGL) {
                    continue;
                }
                Mat4f vp = projection(c, reversed);
                DepthRange range = reversed ? DepthRange.REVERSED_ZERO_TO_ONE : c.range();
                Frustumf frustum = Frustumf.fromViewProjection(vp, range);
                List<Aabbf> boxes = randomBoxes(3000, 1.6f, 0.02f, 400f);
                int[] draw = new int[boxes.size()];
                MemorySegment obj = objects(boxes, draw, null);
                int[] capacity = {boxes.size()};
                DrawCommandBuffer cb = commands(capacity);
                MemorySegment visible = MemorySegment.ofArray(new byte[4 * total(capacity)]);
                GpuCullReference.Counters counters = new GpuCullReference.Counters();
                GpuCullReference.cullSinglePass(view(vp, boxes.size(), null, range, c.yDown(), 1e-4f), obj, null, cb, capacity, visible, counters);
                TreeSet<Integer> expected = new TreeSet<>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (frustum.intersects(boxes.get(i))) {
                        expected.add(i);
                    }
                }
                assertEquals(expected, instances(cb, visible, 0), c + " reversed " + reversed);
                assertEquals(boxes.size(), counters.objects);
                assertEquals(expected.size(), counters.inFrustum);
                assertEquals(expected.size(), counters.drawn);
                assertTrue(expected.size() > 100 && expected.size() < boxes.size(), "a mix of visible and culled objects: " + expected.size());
            }
        }
    }

    @Test
    void survivorsGoToTheirOwnDrawAndFullDrawsDropAndCountTheRest() {
        Mat4f vp = projection(CONVENTIONS[1], false);
        Frustumf frustum = Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE);
        List<Aabbf> boxes = randomBoxes(2000, 1.0f, 1f, 150f);
        int draws = 5;
        int[] draw = new int[boxes.size()];
        int[] visibleOf = new int[draws];
        List<TreeSet<Integer>> expected = new ArrayList<>();
        for (int d = 0; d < draws; d++) {
            expected.add(new TreeSet<>());
        }
        for (int i = 0; i < boxes.size(); i++) {
            draw[i] = rnd.nextInt(draws);
            if (frustum.intersects(boxes.get(i))) {
                expected.get(draw[i]).add(i);
                visibleOf[draw[i]]++;
            }
        }
        MemorySegment obj = objects(boxes, draw, null);
        // enough room: every draw lists exactly its visible objects
        int[] roomy = new int[draws];
        java.util.Arrays.fill(roomy, boxes.size());
        DrawCommandBuffer cb = commands(roomy);
        MemorySegment visible = MemorySegment.ofArray(new byte[4 * total(roomy)]);
        GpuCullReference.Counters counters = new GpuCullReference.Counters();
        GpuCullReference.cullSinglePass(view(vp, boxes.size(), null, DepthRange.ZERO_TO_ONE, false, 1e-4f), obj, null, cb, roomy, visible, counters);
        for (int d = 0; d < draws; d++) {
            assertEquals(expected.get(d), instances(cb, visible, d), "draw " + d);
        }
        assertEquals(0, counters.overflow);
        // too little room: the first ones fit, the others are counted, no draw lists more than it reserved
        int[] tight = new int[draws];
        int expectedOverflow = 0;
        for (int d = 0; d < draws; d++) {
            tight[d] = Math.max(1, visibleOf[d] / 2);
            expectedOverflow += visibleOf[d] - Math.min(tight[d], visibleOf[d]);
        }
        DrawCommandBuffer cb2 = commands(tight);
        MemorySegment visible2 = MemorySegment.ofArray(new byte[4 * total(tight)]);
        GpuCullReference.Counters c2 = new GpuCullReference.Counters();
        GpuCullReference.cullSinglePass(view(vp, boxes.size(), null, DepthRange.ZERO_TO_ONE, false, 1e-4f), obj, null, cb2, tight, visible2, c2);
        assertEquals(expectedOverflow, c2.overflow);
        for (int d = 0; d < draws; d++) {
            assertEquals(Math.min(tight[d], visibleOf[d]), cb2.instanceCount(d));
            assertEquals(new TreeSet<>(expected.get(d).headSet(expected.get(d).stream().skip(cb2.instanceCount(d)).findFirst().orElse(Integer.MAX_VALUE))), instances(cb2, visible2, d),
                    "the first objects of the draw fit");
        }
    }

    // ---------------------------------------------------------------- occlusion

    /** A depth image of occluder rectangles in NDC at conventional depths, converted to the convention of {@code range}; also returns the farness image (larger is farther). */
    private float[][] depthImage(int w, int h, DepthRange range, int rects) {
        float[] api = new float[w * h], farness = new float[w * h];
        java.util.Arrays.fill(farness, 1f);
        for (int r = 0; r < rects; r++) {
            int x0 = rnd.nextInt(w), y0 = rnd.nextInt(h), x1 = Math.min(w, x0 + 1 + rnd.nextInt(w / 2)), y1 = Math.min(h, y0 + 1 + rnd.nextInt(h / 2));
            float f = 0.5f + rnd.nextFloat() * 0.49f; // occluders far enough that boxes in front of them exist; farness in (0.5, 0.99)
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    farness[y * w + x] = Math.min(farness[y * w + x], f);
                }
            }
        }
        for (int i = 0; i < w * h; i++) {
            api[i] = switch (range) {
                case ZERO_TO_ONE -> farness[i];
                case NEGATIVE_ONE_TO_ONE -> farness[i] * 2f - 1f;
                case REVERSED_ZERO_TO_ONE -> 1f - farness[i];
            };
        }
        return new float[][] {api, farness};
    }

    /** Independent projection of a box in double precision: ndc rectangle, nearest farness in the convention, and whether the box is wholly in front of the camera plane. */
    private static double[] projectBox(Mat4f vp, Aabbf b, DepthRange range) {
        double minX = 1e30, minY = 1e30, maxX = -1e30, maxY = -1e30, nearest = 1e30;
        boolean allFront = true;
        for (int c = 0; c < 8; c++) {
            double x = (c & 1) == 0 ? b.minX() : b.maxX(), y = (c & 2) == 0 ? b.minY() : b.maxY(), z = (c & 4) == 0 ? b.minZ() : b.maxZ();
            double cx = vp.m00() * x + vp.m10() * y + vp.m20() * z + vp.m30(), cy = vp.m01() * x + vp.m11() * y + vp.m21() * z + vp.m31();
            double cz = vp.m02() * x + vp.m12() * y + vp.m22() * z + vp.m32(), cw = vp.m03() * x + vp.m13() * y + vp.m23() * z + vp.m33();
            if (!(cw > 1e-4)) {
                allFront = false;
                continue;
            }
            minX = Math.min(minX, cx / cw);
            maxX = Math.max(maxX, cx / cw);
            minY = Math.min(minY, cy / cw);
            maxY = Math.max(maxY, cy / cw);
            double z01 = cz / cw;
            nearest = Math.min(nearest, range == DepthRange.NEGATIVE_ONE_TO_ONE ? (z01 + 1) * 0.5 : range == DepthRange.ZERO_TO_ONE ? z01 : 1 - z01);
        }
        return new double[] {minX, minY, maxX, maxY, nearest, allFront ? 1 : 0};
    }

    private static boolean exactlyHidden(float[] farness, int w, int h, boolean yDown, double[] p) {
        double x0 = Math.max(p[0], -1), x1 = Math.min(p[2], 1), y0 = Math.max(p[1], -1), y1 = Math.min(p[3], 1);
        if (p[2] < -1 || p[0] > 1 || p[3] < -1 || p[1] > 1) {
            return false;
        }
        int px0 = cl((int) Math.floor((x0 * 0.5 + 0.5) * w), w), px1 = cl((int) Math.floor((x1 * 0.5 + 0.5) * w), w);
        double ya = yDown ? 0.5 - y1 * 0.5 : y0 * 0.5 + 0.5, yb = yDown ? 0.5 - y0 * 0.5 : y1 * 0.5 + 0.5;
        int py0 = cl((int) Math.floor(ya * h), h), py1 = cl((int) Math.floor(yb * h), h);
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                if (!(farness[y * w + x] < p[4])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int cl(int v, int n) {
        return v < 0 ? 0 : Math.min(v, n - 1);
    }

    @Test
    void anOccludedObjectIsReallyHiddenInEveryConvention() {
        long hidden = 0, exact = 0, wrong = 0, kept = 0;
        for (Conv c : CONVENTIONS) {
            for (boolean reversed : new boolean[] {false, true}) {
                if (reversed && c.space() == ClipSpace.OPENGL) {
                    continue;
                }
                DepthRange range = reversed ? DepthRange.REVERSED_ZERO_TO_ONE : c.range();
                Mat4f vp = projection(c, reversed);
                int w = 128, h = 72;
                for (int scene = 0; scene < 6; scene++) {
                    float[][] images = depthImage(w, h, range, 20);
                    HiZPyramid hzb = HiZPyramid.fromDepth(images[0], w, h, range, c.yDown());
                    List<Aabbf> boxes = randomBoxes(1500, 1.0f, 0.3f, 190f);
                    int[] flags = new int[boxes.size()];
                    for (int i = 0; i < flags.length; i++) {
                        flags[i] = rnd.nextInt(10) == 0 ? GpuCullReference.OBJECT_NO_OCCLUSION : 0;
                    }
                    MemorySegment obj = objects(boxes, new int[boxes.size()], flags);
                    int[] capacity = {boxes.size()};
                    DrawCommandBuffer cb = commands(capacity);
                    MemorySegment visible = MemorySegment.ofArray(new byte[4 * boxes.size()]);
                    GpuCullReference.Counters counters = new GpuCullReference.Counters();
                    GpuCullReference.cullSinglePass(view(vp, boxes.size(), hzb, range, c.yDown(), 1e-4f), obj, hzb, cb, capacity, visible, counters);
                    TreeSet<Integer> drawn = instances(cb, visible, 0);
                    Frustumf frustum = Frustumf.fromViewProjection(vp, range);
                    for (int i = 0; i < boxes.size(); i++) {
                        boolean inFrustum = frustum.intersects(boxes.get(i));
                        double[] p = projectBox(vp, boxes.get(i), range);
                        boolean exactHidden = inFrustum && p[5] == 1 && (flags[i] == 0) && exactlyHidden(images[1], w, h, c.yDown(), p);
                        boolean referenceHidden = inFrustum && !drawn.contains(i);
                        if (referenceHidden) {
                            hidden++;
                            if (!exactHidden) {
                                wrong++;
                            }
                        }
                        if (exactHidden) {
                            exact++;
                            if (!referenceHidden) {
                                kept++;
                            }
                        }
                        if (!inFrustum) {
                            assertFalse(drawn.contains(i), "an object outside the frustum is never drawn");
                        }
                        if (flags[i] != 0 && inFrustum) {
                            assertTrue(drawn.contains(i), "an object flagged NO_OCCLUSION is only frustum tested");
                        }
                    }
                    assertEquals(counters.inFrustum - counters.occluded, counters.drawn);
                }
            }
        }
        System.out.printf("GPUCULL hidden by the reference %d, hidden exactly (rectangle test, per pixel) %d, kept although hidden %d, wrong %d%n", hidden, exact, kept, wrong);
        assertEquals(0, wrong, "the reference must never hide an object that is not hidden");
        assertTrue(hidden > 1000, "enough occlusion in the sample: " + hidden);
    }

    @Test
    void anObjectCrossingTheCameraPlaneIsNeverOcclusionTested() {
        Conv c = CONVENTIONS[1];
        Mat4f vp = projection(c, false);
        int w = 64, h = 36;
        float[] near = new float[w * h]; // everything covered by a wall at depth 0.001: nothing behind it could be seen
        java.util.Arrays.fill(near, 0.001f);
        HiZPyramid hzb = HiZPyramid.fromDepth(near, w, h, DepthRange.ZERO_TO_ONE, false);
        List<Aabbf> boxes = new ArrayList<>();
        boxes.add(new Aabbf(-1, -1, -50, 1, 1, -40));   // far behind the wall: hidden
        boxes.add(new Aabbf(-1, -1, -3, 1, 1, 3));      // contains the camera: visible
        boxes.add(new Aabbf(-1, -1, -0.5f, 1, 1, 5));   // reaches 0.5 in front of the camera and extends behind it: visible
        MemorySegment obj = objects(boxes, new int[3], null);
        int[] capacity = {3};
        DrawCommandBuffer cb = commands(capacity);
        MemorySegment visible = MemorySegment.ofArray(new byte[12]);
        GpuCullReference.Counters counters = new GpuCullReference.Counters();
        GpuCullReference.cullSinglePass(view(vp, 3, hzb, DepthRange.ZERO_TO_ONE, false, 1e-4f), obj, hzb, cb, capacity, visible, counters);
        TreeSet<Integer> drawn = instances(cb, visible, 0);
        assertFalse(drawn.contains(0), "behind the wall");
        assertTrue(drawn.contains(1) && drawn.contains(2), "boxes that cross the camera plane are kept");
    }

    // ---------------------------------------------------------------- two phases

    @Test
    void thePhasesTogetherDrawEverythingASinglePassWouldAndRecordTheHistory() {
        Conv c = CONVENTIONS[1];
        Mat4f vp = projection(c, false);
        int w = 128, h = 72;
        for (int scene = 0; scene < 10; scene++) {
            float[][] images = depthImage(w, h, DepthRange.ZERO_TO_ONE, 20);
            HiZPyramid hzb = HiZPyramid.fromDepth(images[0], w, h, DepthRange.ZERO_TO_ONE, false);
            List<Aabbf> boxes = randomBoxes(1200, 1.0f, 0.3f, 190f);
            int n = boxes.size();
            MemorySegment obj = objects(boxes, new int[n], null);
            MemorySegment viewSeg = view(vp, n, hzb, DepthRange.ZERO_TO_ONE, false, 1e-4f);
            int[] capacity = {n};
            // the single pass, as the reference of what must be visible
            DrawCommandBuffer single = commands(capacity);
            MemorySegment singleVisible = MemorySegment.ofArray(new byte[4 * n]);
            GpuCullReference.cullSinglePass(viewSeg, obj, hzb, single, capacity, singleVisible, new GpuCullReference.Counters());
            TreeSet<Integer> wanted = instances(single, singleVisible, 0);
            // last frame's history: a random part of what is visible plus some objects that are not
            VisibilitySet last = new VisibilitySet(n);
            for (int i = 0; i < n; i++) {
                if (wanted.contains(i) ? rnd.nextInt(10) < 7 : rnd.nextInt(10) == 0) {
                    last.set(i);
                }
            }
            DrawCommandBuffer cb1 = commands(capacity), cb2 = commands(capacity);
            MemorySegment vis1 = MemorySegment.ofArray(new byte[4 * n]), vis2 = MemorySegment.ofArray(new byte[4 * n]);
            VisibilitySet drawn1 = new VisibilitySet(n), visibleNow = new VisibilitySet(n);
            GpuCullReference.Counters c1 = new GpuCullReference.Counters(), c2 = new GpuCullReference.Counters();
            GpuCullReference.cullPhase1(viewSeg, obj, last, drawn1, cb1, capacity, vis1, c1);
            GpuCullReference.cullPhase2(viewSeg, obj, hzb, drawn1, visibleNow, cb2, capacity, vis2, c2);
            TreeSet<Integer> phase1 = instances(cb1, vis1, 0), phase2 = instances(cb2, vis2, 0);
            Frustumf frustum = Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE);
            TreeSet<Integer> expected1 = new TreeSet<>();
            for (int i = 0; i < n; i++) {
                if (last.get(i) && frustum.intersects(boxes.get(i))) {
                    expected1.add(i);
                }
            }
            assertEquals(expected1, phase1, "phase 1 draws the objects that were visible and are in the frustum");
            assertTrue(java.util.Collections.disjoint(phase1, phase2), "nothing is drawn twice");
            TreeSet<Integer> union = new TreeSet<>(phase1);
            union.addAll(phase2);
            assertTrue(union.containsAll(wanted), "everything that passes the test against the new pyramid is drawn in one of the phases");
            TreeSet<Integer> now = new TreeSet<>();
            for (int i = visibleNow.nextSetBit(0); i >= 0; i = visibleNow.nextSetBit(i + 1)) {
                now.add(i);
            }
            assertEquals(wanted, now, "the history for the next frame is exactly what passes the new pyramid");
            for (int i : phase2) {
                assertTrue(wanted.contains(i), "phase 2 only draws what passes the new pyramid");
            }
        }
    }
}
