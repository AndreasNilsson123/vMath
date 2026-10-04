package vmath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;
import vmath.anim.Skeleton;
import vmath.bulk.FrameDirtyRanges;
import vmath.bulk.QuatArray;
import vmath.bulk.SegmentFloatArray;
import vmath.bulk.TransformArray;
import vmath.bulk.VisibilitySet;
import vmath.bulk.Vec4fArray;
import vmath.core.ClipSpace;
import vmath.core.Quatf;
import vmath.core.Transformf;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.gl.ClusterLight;
import vmath.gl.ClusterLightGpu;
import vmath.gl.DispatchIndirect;
import vmath.gl.DispatchIndirectGpu;
import vmath.gl.DrawArraysIndirect;
import vmath.gl.DrawArraysIndirectGpu;
import vmath.gl.DrawElementsIndirect;
import vmath.gl.DrawElementsIndirectGpu;
import vmath.gpucull.CullObject;
import vmath.gpucull.CullObjectGpu;
import vmath.gltf.Gltf;
import vmath.mem.FreeListAllocator;
import vmath.mem.RingAllocator;
import vmath.mem.SlabAllocator;
import vmath.pack.GridQuantizer;
import vmath.pack.UvQuantizer;
import vmath.tex.TextureFormat;

/**
 * Argument checks, accessors and fallbacks that the larger tests do not reach (found by the coverage report, docs/technical-debt.md TD-18): each assertion is
 * the documented behaviour of a small method.
 */
class ApiEdgeCasesTest {

    // ---------------------------------------------------------------- the generated writers: both overloads write the same bytes

    private static void assertSameBytes(int size, java.util.function.Consumer<MemorySegment> toSegment, java.util.function.Consumer<ByteBuffer> toBuffer) {
        byte[] viaSegment = new byte[size + 8], viaBuffer = new byte[size + 8];
        toSegment.accept(MemorySegment.ofArray(viaSegment));
        ByteBuffer bb = ByteBuffer.wrap(viaBuffer).order(ByteOrder.nativeOrder());
        toBuffer.accept(bb);
        assertArrayEquals(viaSegment, viaBuffer);
        boolean any = false;
        for (byte b : viaSegment) {
            any |= b != 0;
        }
        assertTrue(any, "the writer wrote something");
    }

    @Test
    void everyGeneratedWriterWritesTheSameBytesToASegmentAndAByteBuffer() {
        DrawArraysIndirect arrays = new DrawArraysIndirect(3, 4, 5, 6);
        assertSameBytes((int) DrawArraysIndirectGpu.SIZE, s -> DrawArraysIndirectGpu.write(arrays, s, 8), b -> DrawArraysIndirectGpu.write(arrays, b, 8));
        DispatchIndirect dispatch = new DispatchIndirect(7, 8, 9);
        assertSameBytes((int) DispatchIndirectGpu.SIZE, s -> DispatchIndirectGpu.write(dispatch, s, 8), b -> DispatchIndirectGpu.write(dispatch, b, 8));
        DrawElementsIndirect elements = new DrawElementsIndirect(10, 11, 12, -13, 14);
        assertSameBytes((int) DrawElementsIndirectGpu.SIZE, s -> DrawElementsIndirectGpu.write(elements, s, 8), b -> DrawElementsIndirectGpu.write(elements, b, 8));
        ClusterLight light = new ClusterLight(new Vec4f(1f, 2f, 3f, 4f), new Vec4f(0f, 1f, 0f, 0.5f), new Vec4f(1f, 0.5f, 0.25f, 100f));
        assertSameBytes((int) ClusterLightGpu.SIZE, s -> ClusterLightGpu.write(light, s, 8), b -> ClusterLightGpu.write(light, b, 8));
        CullObject object = new CullObject(new Vec3f(-1f, -2f, -3f), 5, new Vec3f(1f, 2f, 3f), 1);
        assertSameBytes((int) CullObjectGpu.SIZE, s -> CullObjectGpu.write(object, s, 8), b -> CullObjectGpu.write(object, b, 8));
        // spot checks of the layout: DrawElementsIndirect is five tightly packed 32-bit values
        MemorySegment seg = MemorySegment.ofArray(new byte[20]);
        DrawElementsIndirectGpu.write(elements, seg, 0);
        assertEquals(-13, seg.get(ValueLayout.JAVA_INT_UNALIGNED, 12));
        assertEquals(14, seg.get(ValueLayout.JAVA_INT_UNALIGNED, 16));
    }

    // ---------------------------------------------------------------- the containers

    @Test
    void vec4QuatAndTransformArraysClearResizeAndRemove() {
        Vec4fArray v = new Vec4fArray(2);
        for (int i = 0; i < 5; i++) {
            v.add(i, 10 + i, 20 + i, 30 + i);
        }
        assertEquals(11f, v.y(1));
        assertEquals(5, v.size());
        assertSame(v.data(), v.data());
        assertThrows(IllegalArgumentException.class, () -> v.setSize(-1));
        assertThrows(IllegalArgumentException.class, () -> v.setSize(v.capacity() + 1));
        v.setSize(3);
        assertEquals(3, v.size());
        FloatBuffer fb = FloatBuffer.allocate(40);
        v.writeTo(fb, 4);
        assertEquals(10f, fb.get(5));
        assertEquals(2, v.removeSwap(0), "the last of the three moves into slot 0");
        assertEquals(2f, v.x(0));
        v.clear();
        assertEquals(0, v.size());
        assertThrows(IndexOutOfBoundsException.class, () -> v.y(0));

        QuatArray q = new QuatArray(2);
        for (int i = 0; i < 4; i++) {
            q.add(new Quatf(0f, 0f, 0f, 1f));
        }
        assertSame(q.data(), q.data());
        assertThrows(IllegalArgumentException.class, () -> q.setSize(q.capacity() + 1));
        q.setSize(2);
        assertEquals(1, q.removeSwap(0));
        q.clear();
        assertEquals(0, q.size());
        MemorySegment seg = MemorySegment.ofArray(new byte[16 * 3]);
        for (int i = 0; i < 12; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i, i);
        }
        q.readFrom(seg, 0, 16, ByteOrder.nativeOrder(), 3);
        assertEquals(3, q.size());
        assertEquals(4f, q.data()[4]);

        TransformArray t = new TransformArray(2);
        for (int i = 0; i < 3; i++) {
            t.add(new Transformf(new Vec3f(i, 0f, 0f), Quatf.IDENTITY, Vec3f.ONE));
        }
        assertEquals(3, t.size());
        t.set(1, new Transformf(new Vec3f(9f, 0f, 0f), Quatf.IDENTITY, Vec3f.ONE));
        assertEquals(9f, t.get(1).translation().x());
        assertThrows(IndexOutOfBoundsException.class, () -> t.set(3, Transformf.IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> t.setSize(-2));
        t.setSize(2);
        FloatBuffer tb = FloatBuffer.allocate(30);
        t.writeTo(tb, 2);
        assertEquals(9f, tb.get(2 + 10));
        assertEquals(1, t.removeSwap(0));
        assertEquals(9f, t.get(0).translation().x());
        t.clear();
        assertEquals(0, t.size());
    }

    @Test
    void normalizingAZeroOrNonFiniteQuaternionGivesTheIdentity() {
        QuatArray a = new QuatArray(3), b = new QuatArray(3), out = new QuatArray(3);
        a.add(0f, 0f, 0f, 0f);
        a.add(Float.NaN, 0f, 0f, 1f);
        a.add(0f, 0f, 0f, 1f);
        b.add(0f, 0f, 0f, 0f);
        b.add(0f, 0f, 0f, 0f);
        b.add(0f, 0f, 0f, 0f);
        QuatArray.nlerp(a, b, 0.5f, out);
        assertEquals(1f, out.get(0).w(), "two zero quaternions blend to the identity");
        assertEquals(0f, out.get(0).x());
        QuatArray.slerp(a, b, 0.5f, out);
        assertEquals(1f, out.get(0).w());
        a.normalizeAll();
        assertEquals(1f, a.get(0).w(), "a zero quaternion normalizes to the identity");
        assertEquals(1f, a.get(1).w(), "so does one with a NaN");
        assertEquals(0f, a.get(1).x());
        assertEquals(1f, a.get(2).w());
    }

    @Test
    void anotherSkeletonJointWithAZeroRotationGetsTheIdentity() {
        float[] bind = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f};
        Skeleton s = new Skeleton(new int[] {-1, 0}, bind, new String[] {"root", "child"});
        float[] local = s.bindLocal();
        assertEquals(1f, local[6], "the zero rotation of joint 0 became the identity");
        assertEquals(0f, local[3]);
        local[0] = 99f;
        assertEquals(0f, s.bindLocal()[0], "bindLocal returns a copy");
        assertEquals("root", s.name(0));
        assertEquals(1, s.indexOf("child"));
        assertEquals(-1, s.indexOf("missing"));
        assertEquals("Skeleton[2 joints, roots 1]", s.toString());
        Skeleton unnamed = new Skeleton(new int[] {-1}, new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f});
        assertEquals(null, unnamed.name(0));
        assertEquals(-1, unnamed.indexOf("x"));
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-1, 1}, bind));
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-2}, new float[10]));
    }

    @Test
    void frameDirtyRangesGrowAndCheckTheirArguments() {
        assertThrows(IllegalArgumentException.class, () -> new FrameDirtyRanges(10, 0));
        FrameDirtyRanges d = new FrameDirtyRanges(10, 2);
        assertEquals(10, d.capacity());
        d.ensureCapacity(300);
        assertEquals(300, d.capacity());
        d.markRange(250, 260);
        assertEquals(10, d.forSlot(0).count());
        assertEquals(10, d.forSlot(1).count());
        d.forSlot(0).clear();
        assertEquals(0, d.forSlot(0).count());
        assertEquals(10, d.forSlot(1).count(), "clearing one slot leaves the others");
        assertThrows(IndexOutOfBoundsException.class, () -> d.forSlot(-1));
    }

    @Test
    void segmentFloatArrayChecksItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SegmentFloatArray(0, 4));
        try (SegmentFloatArray a = SegmentFloatArray.ofTransform(2)) {
            assertEquals(10, a.floatsPerElement());
            assertEquals(40, a.elementBytes());
            assertThrows(IllegalArgumentException.class, () -> a.setSize(a.capacity() + 1));
            assertThrows(IllegalArgumentException.class, () -> a.setSize(-1));
            assertThrows(IllegalArgumentException.class, () -> a.ensureCapacity(Integer.MAX_VALUE));
            a.add(new float[10], 0);
            assertThrows(IndexOutOfBoundsException.class, () -> a.setFloat(0, 10, 1f));
            assertThrows(IndexOutOfBoundsException.class, () -> a.setFloat(0, -1, 1f));
            assertThrows(IndexOutOfBoundsException.class, () -> a.getFloat(1, 0));
            assertThrows(IllegalArgumentException.class, () -> a.copyFrom(new float[5], 1));
            assertThrows(IllegalArgumentException.class, () -> a.copyFrom(new float[50], -1));
            assertThrows(IllegalArgumentException.class, () -> a.copyTo(new float[5]));
            assertThrows(IllegalArgumentException.class, () -> a.get(0, new float[5], 0));
            a.setFloat(0, 3, 7f);
            assertEquals(7f, a.getFloat(0, 3));
        }
    }

    // ---------------------------------------------------------------- allocators

    @Test
    void allocatorsCheckTheirConstructorsAndReportTheirBacking() {
        MemorySegment seg = MemorySegment.ofArray(new byte[4096]);
        RingAllocator ring = new RingAllocator(seg, 2);
        assertEquals(4096, ring.capacity());
        assertSame(seg, ring.segment());
        assertEquals(64, ring.slice(ring.allocate(64, 16), 64).byteSize());
        assertEquals(0, ring.allocate(0, 1) >= 0 ? 0 : -1, "a zero-size request is allowed");
        assertThrows(IllegalArgumentException.class, () -> new RingAllocator(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RingAllocator(100, 0));
        assertThrows(IllegalArgumentException.class, () -> ring.allocate(-1, 1));
        ring.endFrame();
        ring.reset();
        assertEquals(0, ring.used());
        assertEquals(0, ring.outstandingFrames());
        assertThrows(IllegalStateException.class, () -> new RingAllocator(100, 1).slice(0, 10));
        assertEquals(null, new RingAllocator(100, 1).segment());

        FreeListAllocator list = new FreeListAllocator(seg, FreeListAllocator.Strategy.FIRST_FIT);
        assertEquals(4096, list.capacity());
        assertSame(seg, list.segment());
        long o = list.allocate(100, 16);
        assertEquals(100, list.slice(o).byteSize());
        assertThrows(IllegalArgumentException.class, () -> list.slice(o + 1));
        assertThrows(IllegalArgumentException.class, () -> new FreeListAllocator(-5, FreeListAllocator.Strategy.BEST_FIT));
        assertThrows(IllegalStateException.class, () -> new FreeListAllocator(100, FreeListAllocator.Strategy.BEST_FIT).slice(0));
        FreeListAllocator empty = new FreeListAllocator(0, FreeListAllocator.Strategy.BEST_FIT);
        assertEquals(FreeListAllocator.NONE, empty.allocate(1, 1));
        assertEquals(0, empty.blockCount());
        assertEquals(null, new FreeListAllocator(100, FreeListAllocator.Strategy.BEST_FIT).segment());

        SlabAllocator slab = new SlabAllocator(64, 10, seg);
        assertEquals(64, slab.blockSize());
        assertEquals(10, slab.blockCount());
        assertSame(seg, slab.segment());
        assertThrows(IllegalArgumentException.class, () -> new SlabAllocator(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SlabAllocator(8, -1));
        assertThrows(IllegalArgumentException.class, () -> new SlabAllocator(Long.MAX_VALUE / 2, 10));
        assertThrows(IllegalStateException.class, () -> new SlabAllocator(8, 2).slice(0));
        assertTrue(!new SlabAllocator(8, 2).isAllocated(-8));
        assertTrue(!new SlabAllocator(8, 2).isAllocated(16));
        assertEquals(null, new SlabAllocator(8, 2).segment());
    }

    // ---------------------------------------------------------------- quantizers

    @Test
    void quantizersExposeTheirParametersAndCheckTheirArguments() {
        UvQuantizer uv = new UvQuantizer(-1f, 2f, 3f, 6f, 12);
        assertEquals(12, uv.bits());
        assertEquals(-1f, uv.minU());
        assertEquals(2f, uv.minV());
        assertEquals(4f, uv.sizeU());
        assertEquals(4f, uv.sizeV());
        assertEquals(4095, uv.levels());
        assertEquals(0, uv.quantizeV(-100f), "values below the rectangle clamp");
        assertEquals(4095, uv.quantizeV(100f));
        assertEquals(0, new UvQuantizer(0f, 5f, 1f, 5f, 8).quantizeV(5f), "a rectangle with no height maps v to 0");
        assertThrows(IllegalArgumentException.class, () -> new UvQuantizer(0f, 0f, 1f, 1f, 0));
        assertThrows(IllegalArgumentException.class, () -> new UvQuantizer(0f, 0f, 1f, 1f, 17));
        assertThrows(IllegalArgumentException.class, () -> new UvQuantizer(0f, 0f, Float.POSITIVE_INFINITY, 1f, 8));
        assertThrows(IllegalArgumentException.class, () -> new UvQuantizer(0f, 1f, 1f, 0f, 8));

        Aabbf box = new Aabbf(0f, 0f, 0f, 2f, 4f, 8f);
        GridQuantizer g = GridQuantizer.of(box, 10);
        assertEquals(box, g.bounds());
        assertEquals(10, g.bits());
        assertEquals(1023, g.quantize(8f, 2));
        assertEquals(0, g.quantize(-1f, 1));
        assertThrows(IllegalArgumentException.class, () -> g.quantize(1f, 3));
        assertThrows(IllegalArgumentException.class, () -> GridQuantizer.of(box, 0));
        assertThrows(IllegalArgumentException.class, () -> GridQuantizer.uniform(box, 17));
    }

    @Test
    void meshValidateFindsWhatDirectWritesCanBreak() {
        vmath.mesh.Mesh m = vmath.mesh.Primitives.uvSphere(1f, 8, 4);
        assertEquals(null, m.validate());
        int[] idx = m.indices();
        int saved = idx[4];
        idx[4] = m.vertexCount() + 5;
        assertTrue(m.validate().contains("index 4"), m.validate());
        idx[4] = -1;
        assertTrue(m.validate().contains("outside"), m.validate());
        idx[4] = saved;
        assertEquals(null, m.validate());
        assertEquals(null, new vmath.mesh.Mesh().validate(), "an empty mesh is consistent");
        vmath.mesh.Mesh withStreams = vmath.mesh.Primitives.uvSphere(1f, 6, 3);
        withStreams.enableTangents();
        assertEquals(null, withStreams.validate());
    }

    // ---------------------------------------------------------------- the dirty and visibility sets together

    @Test
    void compactAndRemoveSwapAgreeOnThe0And1ElementCases() {
        Vec4fArray v = new Vec4fArray(2);
        v.add(1f, 1f, 1f, 1f);
        assertEquals(-1, v.removeSwap(0), "removing the only element moves nothing");
        v.add(2f, 2f, 2f, 2f);
        VisibilitySet none = new VisibilitySet(8);
        assertEquals(0, v.compact(none));
    }

    // ---------------------------------------------------------------- small accessors that no other test calls

    @Test
    void smallAccessorsOfTheLoaderTheMeshTheTexturesAndTheDepthBuffer() {
        Gltf g = Gltf.parse("{\"asset\":{\"version\":\"2.0\"},\"materials\":[{},{}],\"samplers\":[{}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8), uri -> new byte[0]);
        assertEquals(2, g.materialCount());
        assertEquals(1, g.samplerCount());
        assertEquals(0, g.textureCount());
        assertEquals(0, g.meshCount());

        vmath.mesh.Mesh m = vmath.mesh.Primitives.uvSphere(1f, 8, 4);
        m.enableTangents();
        assertTrue(m.hasTangents());
        m.disableTangents();
        assertTrue(!m.hasTangents());

        assertTrue(!TextureFormat.R8G8B8A8_UNORM.isCompressed());
        assertTrue(TextureFormat.BC7_UNORM.isCompressed());
        assertTrue(TextureFormat.BC1_RGB_SRGB.isCompressed());

        vmath.core.Mat4f proj = vmath.core.Mat4f.perspective(1f, 1f, 0.1f, 100f, ClipSpace.D3D);
        vmath.occlusion.DepthBuffer d = new vmath.occlusion.DepthBuffer(64, 64);
        d.begin(proj, 0.1f);
        // one big triangle that covers the whole view at z = -5, then a second one far off to the side (the pixels along the seam of a two-triangle quad are
        // not fully covered by either triangle, and an inner-conservative rasterizer records only fully covered pixels, so a single triangle is the clean case)
        float[] wall = {-30f, -30f, -5f, 30f, -30f, -5f, 0f, 40f, -5f, 100f, 100f, -5f, 101f, 100f, -5f, 100f, 101f, -5f};
        d.addTriangles(wall, 0, 2);
        d.finish();
        assertTrue(d.coveredPixels() > 0);
        assertTrue(d.isHidden(new Aabbf(-1f, -1f, -20f, 1f, 1f, -10f)), "a box behind the wall is hidden");
        assertTrue(!d.isHidden(new Aabbf(-1f, -1f, -4f, 1f, 1f, -3f)), "a box in front of it is not");

        vmath.gpucull.HiZPyramid pyramid = vmath.gpucull.HiZPyramid.fromDepth(new float[16], 4, 4, vmath.geo.DepthRange.REVERSED_ZERO_TO_ONE, false);
        assertEquals(vmath.geo.DepthRange.REVERSED_ZERO_TO_ONE, pyramid.depthRange());
    }
}
