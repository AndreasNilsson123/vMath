package vmath.gpucull;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;
import vmath.bulk.VisibilitySet;
import vmath.geo.DepthRange;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.GpuWriter;

/**
 * The CPU reference of a GPU-driven culling pass, written to read and write the same bytes a compute shader would: the view as a {@link CullView} std140 block, the objects as a
 * {@link CullObject} std430 array, the draws as an array of {@code DrawElementsIndirect} commands, and the surviving objects as a {@code uint} list.
 *
 * <p><b>What a pass does for one object</b> (one invocation per object on the GPU): test its box against the six frustum planes; unless the object is flagged
 * {@link #OBJECT_NO_OCCLUSION}, project the box (clip-space {@code w} above {@code nearW} for all eight corners, otherwise it crosses the camera plane and is visible), find its
 * screen rectangle and nearest depth and test them against the Hi-Z pyramid ({@link HiZPyramid#isHidden}); for a survivor take the next slot of its draw,
 * {@code slot = atomicAdd(commands[drawIndex].instanceCount, 1)}, and write the object index to {@code visible[commands[drawIndex].baseInstance + slot]}. A slot beyond the capacity that
 * was reserved for the draw is dropped and counted ({@link Counters#overflow}), as a shader would have to do rather than write out of bounds.
 *
 * <p><b>Order.</b> The reference visits objects in index order, so the instances of a draw are in ascending object order; on a GPU the order within a draw is whatever the atomics
 * produce. Compare as sets per draw. The result for every object, though, does not depend on the order.
 *
 * <p><b>NaN</b> in a box or plane is never a separation, so such an object is visible (as in the CPU culling predicates).
 *
 * <p><b>Two phases</b> (the contract documented on {@link vmath.occlusion.HiZ}): {@link #cullPhase1} draws the objects that were visible last frame, tested against the
 * frustum only; the caller renders them, builds the pyramid from that depth ({@link HiZPyramid#fromDepth}), then {@link #cullPhase2} tests every object against the new pyramid, draws
 * those that pass and were not drawn in phase 1, and records which objects are visible now (next frame's history).
 *
 * <p>The shader that does the same thing is {@link GpuCullGlsl#computeShader}; it compiles with glslang (see {@code ShaderCompileTest}) but has never run, and this class is what it is checked against by reading.
 *
 * <p><b>Thread safety.</b> Stateless: the passes are static methods. The {@link Counters}, the buffers and the sets you pass in are not synchronised,
 * so two threads must not share one of them.
 */
@Experimental("the buffer layout and the pass structure may change")
public final class GpuCullReference {

    private GpuCullReference() {
    }

    /** {@code CullObject.flags} bit: skip the Hi-Z test for this object. */
    public static final int OBJECT_NO_OCCLUSION = 1;
    /** {@code CullView.flags} bit: row 0 of the Hi-Z pyramid is the top of the screen. */
    public static final int VIEW_Y_DOWN = 1;

    /** What a pass did. */
    public static final class Counters {
        /** Counters that start at zero. */
        public Counters() {
        }

        /** Objects looked at. */
        public int objects;
        /** Objects that passed the frustum test. */
        public int inFrustum;
        /** Objects that passed the frustum test and were rejected by the Hi-Z test. */
        public int occluded;
        /** Objects written to an instance list. */
        public int drawn;
        /** Survivors dropped because the reserved slots of their draw were full. */
        public int overflow;
        final HizState hiz = new HizState(); // the view-projection matrix and the depth convention, read once per pass so that a pass allocates nothing

        /** Sets every counter to zero. */
        public void reset() {
            objects = 0;
            inFrustum = 0;
            occluded = 0;
            drawn = 0;
            overflow = 0;
        }
    }

    // ---------------------------------------------------------------- the three passes

    /** One pass over every object: frustum, then the Hi-Z test when {@code hzb} is not null. {@code commands}' instance counts are expected to be 0 on entry. */
    public static void cullSinglePass(MemorySegment view, MemorySegment objects, HiZPyramid hzb, DrawCommandBuffer commands, int[] drawCapacity, MemorySegment visible,
                                      Counters counters) {
        int n = GpuWriter.getInt(view, CullViewGpu.OFFSET_OBJECT_COUNT);
        counters.hiz.load(view, CullViewGpu.OFFSET_VIEW_PROJECTION, CullViewGpu.OFFSET_NEAR_W, CullViewGpu.OFFSET_DEPTH_MODE);
        for (int i = 0; i < n; i++) {
            counters.objects++;
            if (!inFrustum(view, objects, i)) {
                continue;
            }
            counters.inFrustum++;
            if (hzb != null && hidden(objects, i, hzb, counters.hiz)) {
                counters.occluded++;
                continue;
            }
            emit(objects, i, commands, drawCapacity, visible, counters);
        }
    }

    /**
     * Phase 1: every object that was visible last frame ({@code lastVisible}) and is in the frustum is drawn, without an occlusion test. The objects it draws are recorded in
     * {@code drawnPhase1}, which is cleared first.
     */
    public static void cullPhase1(MemorySegment view, MemorySegment objects, VisibilitySet lastVisible, VisibilitySet drawnPhase1, DrawCommandBuffer commands, int[] drawCapacity,
                                  MemorySegment visible, Counters counters) {
        int n = GpuWriter.getInt(view, CullViewGpu.OFFSET_OBJECT_COUNT);
        drawnPhase1.clearAll();
        for (int i = 0; i < n; i++) {
            counters.objects++;
            if (!lastVisible.get(i) || !inFrustum(view, objects, i)) {
                continue;
            }
            counters.inFrustum++;
            if (emit(objects, i, commands, drawCapacity, visible, counters)) {
                drawnPhase1.set(i);
            }
        }
    }

    /**
     * Phase 2: every object is tested against the frustum and the pyramid built from phase 1; those that pass are recorded in {@code visibleNow} (cleared first), and those that
     * were not drawn in phase 1 are drawn now.
     */
    public static void cullPhase2(MemorySegment view, MemorySegment objects, HiZPyramid hzb, VisibilitySet drawnPhase1, VisibilitySet visibleNow, DrawCommandBuffer commands,
                                  int[] drawCapacity, MemorySegment visible, Counters counters) {
        int n = GpuWriter.getInt(view, CullViewGpu.OFFSET_OBJECT_COUNT);
        counters.hiz.load(view, CullViewGpu.OFFSET_VIEW_PROJECTION, CullViewGpu.OFFSET_NEAR_W, CullViewGpu.OFFSET_DEPTH_MODE);
        visibleNow.clearAll();
        for (int i = 0; i < n; i++) {
            counters.objects++;
            if (!inFrustum(view, objects, i)) {
                continue;
            }
            counters.inFrustum++;
            if (hzb != null && hidden(objects, i, hzb, counters.hiz)) {
                counters.occluded++;
                continue;
            }
            visibleNow.set(i);
            if (!drawnPhase1.get(i)) {
                emit(objects, i, commands, drawCapacity, visible, counters);
            }
        }
    }

    // ---------------------------------------------------------------- the pieces a shader has too

    private static float f(MemorySegment s, long offset) {
        return GpuWriter.getFloat(s, offset);
    }

    /** The six-plane box test, with the positive vertex of each plane; NaN never rejects. */
    static boolean inFrustum(MemorySegment view, MemorySegment objects, int i) {
        long o = (long) i * CullObjectGpu.SIZE;
        float minX = f(objects, o + CullObjectGpu.OFFSET_MIN), minY = f(objects, o + CullObjectGpu.OFFSET_MIN + 4), minZ = f(objects, o + CullObjectGpu.OFFSET_MIN + 8);
        float maxX = f(objects, o + CullObjectGpu.OFFSET_MAX), maxY = f(objects, o + CullObjectGpu.OFFSET_MAX + 4), maxZ = f(objects, o + CullObjectGpu.OFFSET_MAX + 8);
        for (int p = 0; p < 6; p++) {
            long po = CullViewGpu.OFFSET_PLANES + (long) p * CullViewGpu.STRIDE_PLANES;
            float nx = f(view, po), ny = f(view, po + 4), nz = f(view, po + 8), d = f(view, po + 12);
            float px = nx >= 0f ? maxX : minX, py = ny >= 0f ? maxY : minY, pz = nz >= 0f ? maxZ : minZ;
            if (nx * px + ny * py + nz * pz + d < 0f) {
                return false;
            }
        }
        return true;
    }

    /** The Hi-Z test of one object, as described on the class. */
    static boolean hidden(MemorySegment objects, int i, HiZPyramid hzb, HizState state) {
        long o = (long) i * CullObjectGpu.SIZE;
        if ((GpuWriter.getInt(objects, o + CullObjectGpu.OFFSET_FLAGS) & OBJECT_NO_OCCLUSION) != 0) {
            return false;
        }
        return hiddenBox(state, f(objects, o + CullObjectGpu.OFFSET_MIN), f(objects, o + CullObjectGpu.OFFSET_MIN + 4), f(objects, o + CullObjectGpu.OFFSET_MIN + 8),
                f(objects, o + CullObjectGpu.OFFSET_MAX), f(objects, o + CullObjectGpu.OFFSET_MAX + 4), f(objects, o + CullObjectGpu.OFFSET_MAX + 8), hzb);
    }

    /**
     * The Hi-Z test of a box: project its eight corners with the view's matrix (a corner with clip {@code w} not above {@code nearW}, or NaN, makes the box untestable and so visible),
     * take the screen rectangle and the nearest depth, and ask the pyramid. The view data comes preloaded in {@code state}.
     */
    static boolean hiddenBox(HizState state, float x0, float y0, float z0, float x1, float y1, float z1, HiZPyramid hzb) {
        float[] m = state.m;
        float nearW = state.nearW;
        DepthRange range = state.range;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float nearest = Float.POSITIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            float x = (c & 1) == 0 ? x0 : x1, y = (c & 2) == 0 ? y0 : y1, z = (c & 4) == 0 ? z0 : z1;
            float cx = m[0] * x + m[4] * y + m[8] * z + m[12], cy = m[1] * x + m[5] * y + m[9] * z + m[13];
            float cz = m[2] * x + m[6] * y + m[10] * z + m[14], cw = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (!(cw > nearW)) {
                return false; // crosses the camera plane or is behind it, or NaN: not testable, so visible
            }
            float inv = 1f / cw;
            float nx = cx * inv, ny = cy * inv;
            minX = Math.min(minX, nx);
            maxX = Math.max(maxX, nx);
            minY = Math.min(minY, ny);
            maxY = Math.max(maxY, ny);
            nearest = Math.min(nearest, HiZPyramid.farness(cz * inv, range));
        }
        return hzb.isHidden(minX, minY, maxX, maxY, nearest);
    }

    /** Takes the next slot of the object's draw and writes the object index there; false when the slots reserved for the draw are full. */
    private static boolean emit(MemorySegment objects, int i, DrawCommandBuffer commands, int[] drawCapacity, MemorySegment visible, Counters counters) {
        int draw = GpuWriter.getInt(objects, (long) i * CullObjectGpu.SIZE + CullObjectGpu.OFFSET_DRAW_INDEX);
        int slot = commands.instanceCount(draw);
        if (slot >= drawCapacity[draw]) {
            counters.overflow++;
            return false;
        }
        commands.setInstanceCount(draw, slot + 1);
        GpuWriter.putInt(visible, 4L * (commands.baseInstance(draw) + slot), i);
        counters.drawn++;
        return true;
    }
}
