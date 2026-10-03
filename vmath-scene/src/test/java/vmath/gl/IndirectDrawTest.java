package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Mat4x3f;
import vmath.core.Rnd;
import vmath.core.Vec3f;

/** The indirect draw and dispatch structs: their layout against the GL and Vulkan specifications, the GLSL text, and the buffer writers read back word by word. */
class IndirectDrawTest {

    final Rnd rnd = Rnd.create();

    private static int word(MemorySegment s, long offset) {
        return GpuWriter.getInt(s, offset);
    }

    // ------------------------------------------------------------ layout

    @Test
    void sizesAndOffsetsAreTheSpecificationValues() {
        // glDrawArraysIndirect / VkDrawIndirectCommand: count, instanceCount, first, baseInstance
        assertEquals(16, DrawArraysIndirectGpu.SIZE);
        assertEquals(0, DrawArraysIndirectGpu.OFFSET_COUNT);
        assertEquals(4, DrawArraysIndirectGpu.OFFSET_INSTANCE_COUNT);
        assertEquals(8, DrawArraysIndirectGpu.OFFSET_FIRST);
        assertEquals(12, DrawArraysIndirectGpu.OFFSET_BASE_INSTANCE);
        // glDrawElementsIndirect / VkDrawIndexedIndirectCommand: count, instanceCount, firstIndex, baseVertex, baseInstance
        assertEquals(20, DrawElementsIndirectGpu.SIZE);
        assertEquals(0, DrawElementsIndirectGpu.OFFSET_COUNT);
        assertEquals(4, DrawElementsIndirectGpu.OFFSET_INSTANCE_COUNT);
        assertEquals(8, DrawElementsIndirectGpu.OFFSET_FIRST_INDEX);
        assertEquals(12, DrawElementsIndirectGpu.OFFSET_BASE_VERTEX);
        assertEquals(16, DrawElementsIndirectGpu.OFFSET_BASE_INSTANCE);
        // glDispatchComputeIndirect / VkDispatchIndirectCommand
        assertEquals(12, DispatchIndirectGpu.SIZE);
        assertEquals(0, DispatchIndirectGpu.OFFSET_X);
        assertEquals(4, DispatchIndirectGpu.OFFSET_Y);
        assertEquals(8, DispatchIndirectGpu.OFFSET_Z);
        for (var layout : List.of(DrawArraysIndirectGpu.LAYOUT, DrawElementsIndirectGpu.LAYOUT, DispatchIndirectGpu.LAYOUT)) {
            assertEquals(0, layout.paddingBytes(), "tightly packed: no padding anywhere");
            assertEquals(4, layout.alignment());
        }
        assertEquals(DrawCommandBuffer.Kind.ARRAYS.bytes(), DrawArraysIndirectGpu.SIZE);
        assertEquals(DrawCommandBuffer.Kind.ELEMENTS.bytes(), DrawElementsIndirectGpu.SIZE);
        assertEquals(DrawCommandBuffer.Kind.DISPATCH.bytes(), DispatchIndirectGpu.SIZE);
    }

    @Test
    void std430AndScalarLayoutsAgreeBecauseEveryMemberIsAFourByteScalar() {
        var elements = new GlslType.Struct("Probe", List.of(
                new GlslType.Member("count", GlslType.UINT), new GlslType.Member("instanceCount", GlslType.UINT),
                new GlslType.Member("firstIndex", GlslType.UINT), new GlslType.Member("baseVertex", GlslType.INT),
                new GlslType.Member("baseInstance", GlslType.UINT)));
        for (GpuLayout rule : List.of(GpuLayout.STD430, GpuLayout.SCALAR)) {
            var layout = elements.layout(rule);
            assertEquals(20, layout.size(), rule.toString());
            assertEquals(12, layout.offsetOf("baseVertex"), rule.toString());
        }
    }

    @Test
    void theGlslDeclarationsNameTheMembersWithTheRightTypes() {
        assertTrue(DrawArraysIndirectGpu.GLSL.contains("struct DrawArraysIndirect"), DrawArraysIndirectGpu.GLSL);
        assertTrue(DrawArraysIndirectGpu.GLSL.contains("uint count;") && DrawArraysIndirectGpu.GLSL.contains("uint baseInstance;"), DrawArraysIndirectGpu.GLSL);
        assertTrue(DrawElementsIndirectGpu.GLSL.contains("int baseVertex;"), "baseVertex is signed: " + DrawElementsIndirectGpu.GLSL);
        assertTrue(DrawElementsIndirectGpu.GLSL.contains("uint firstIndex;"), DrawElementsIndirectGpu.GLSL);
        assertTrue(DispatchIndirectGpu.GLSL.contains("uint x;") && DispatchIndirectGpu.GLSL.contains("uint z;"), DispatchIndirectGpu.GLSL);
    }

    @Test
    void generatedRecordWritersAndTheBufferWriteTheSameBytes() {
        for (int i = 0; i < Rnd.N; i++) {
            DrawElementsIndirect c = new DrawElementsIndirect((int) rnd.range(0, 1e6), (int) rnd.range(0, 1000), (int) rnd.range(0, 1e6),
                    (int) rnd.range(-1e5, 1e5), (int) rnd.range(0, 1e4));
            MemorySegment viaGenerated = MemorySegment.ofArray(new byte[20]);
            DrawElementsIndirectGpu.write(c, viaGenerated, 0);
            MemorySegment viaBuffer = MemorySegment.ofArray(new byte[20]);
            new DrawCommandBuffer(viaBuffer, DrawCommandBuffer.Kind.ELEMENTS, false).add(c);
            assertEquals(-1L, viaGenerated.mismatch(viaBuffer), "the two writers must agree byte for byte");
        }
    }

    // ------------------------------------------------------------ the command buffer

    @Test
    void commandsLandAtTheirStrideAndReadBack() {
        for (boolean pad : new boolean[] {false, true}) {
            for (DrawCommandBuffer.Kind kind : DrawCommandBuffer.Kind.values()) {
                long stride = DrawCommandBuffer.stride(kind, pad);
                assertEquals(pad ? ((kind.bytes() + 15) / 16) * 16 : kind.bytes(), stride);
                int n = 50;
                MemorySegment seg = MemorySegment.ofArray(new byte[(int) (stride * n)]);
                DrawCommandBuffer buf = new DrawCommandBuffer(seg, kind, pad);
                assertEquals(n, buf.capacity());
                assertEquals(stride, buf.stride());
                int[][] expect = new int[n][5];
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < 5; k++) {
                        expect[i][k] = (int) rnd.range(-1e6, 1e6);
                    }
                    int index = switch (kind) {
                        case ARRAYS -> buf.addArrays(expect[i][0], expect[i][1], expect[i][2], expect[i][3]);
                        case ELEMENTS -> buf.addElements(expect[i][0], expect[i][1], expect[i][2], expect[i][3], expect[i][4]);
                        case DISPATCH -> buf.addDispatch(expect[i][0], expect[i][1], expect[i][2]);
                    };
                    assertEquals(i, index);
                    assertEquals(i * stride, buf.byteOffset(i));
                }
                assertEquals(n, buf.count());
                assertEquals((n - 1) * stride + kind.bytes(), buf.usedBytes());
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < kind.bytes() / 4; k++) {
                        assertEquals(expect[i][k], word(seg, i * stride + 4L * k), kind + " command " + i + " word " + k + (pad ? " (padded)" : ""));
                    }
                }
                buf.clear();
                assertEquals(0, buf.count());
                assertEquals(0, buf.usedBytes());
            }
        }
    }

    @Test
    void paddingBytesAreNeverTouched() {
        byte[] raw = new byte[32 * 4];
        java.util.Arrays.fill(raw, (byte) 0x5A);
        MemorySegment seg = MemorySegment.ofArray(raw);
        DrawCommandBuffer buf = new DrawCommandBuffer(seg, DrawCommandBuffer.Kind.ELEMENTS, true);
        assertEquals(32, buf.stride());
        for (int i = 0; i < 4; i++) {
            buf.addElements(1, 2, 3, 4, 5);
        }
        for (int i = 0; i < 4; i++) {
            for (int b = 20; b < 32; b++) {
                assertEquals((byte) 0x5A, raw[i * 32 + b], "padding byte " + b + " of command " + i + " was overwritten");
            }
        }
    }

    @Test
    void instanceCountCanBeReadAndZeroedToHideADraw() {
        MemorySegment seg = MemorySegment.ofArray(new byte[16 * 8]);
        DrawCommandBuffer buf = new DrawCommandBuffer(seg, DrawCommandBuffer.Kind.ARRAYS, false);
        int a = buf.addArrays(36, 12, 0, 0);
        int b = buf.addArrays(12, 7, 36, 12);
        assertEquals(12, buf.instanceCount(a));
        buf.setInstanceCount(a, 0);
        assertEquals(0, buf.instanceCount(a));
        assertEquals(7, buf.instanceCount(b));
        assertEquals(2, buf.count(), "the draw count does not change");
        assertEquals(36, word(seg, 0), "the other words of the command are untouched");
        assertThrows(IndexOutOfBoundsException.class, () -> buf.instanceCount(2));
        DrawCommandBuffer dispatch = new DrawCommandBuffer(MemorySegment.ofArray(new byte[48]), DrawCommandBuffer.Kind.DISPATCH, false);
        dispatch.addDispatch(1, 2, 3);
        assertThrows(UnsupportedOperationException.class, () -> dispatch.instanceCount(0));
        assertThrows(UnsupportedOperationException.class, () -> dispatch.setInstanceCount(0, 1));
    }

    @Test
    void errorsAreReportedNotIgnored() {
        DrawCommandBuffer small = new DrawCommandBuffer(MemorySegment.ofArray(new byte[32]), DrawCommandBuffer.Kind.ARRAYS, false);
        assertEquals(2, small.capacity());
        small.addArrays(1, 1, 0, 0);
        small.add(new DrawArraysIndirect(2, 2, 2, 2));
        assertThrows(IllegalStateException.class, () -> small.addArrays(3, 3, 3, 3), "full");
        assertThrows(IllegalStateException.class, () -> small.addElements(1, 1, 0, 0, 0), "an arrays buffer does not take elements commands");
        assertThrows(IllegalStateException.class, () -> small.addDispatch(1, 1, 1));
        DrawCommandBuffer empty = new DrawCommandBuffer(MemorySegment.ofArray(new byte[4]), DrawCommandBuffer.Kind.DISPATCH, false);
        assertEquals(0, empty.capacity());
        assertThrows(IllegalStateException.class, () -> empty.addDispatch(1, 1, 1));
    }

    // ------------------------------------------------------------ instance data

    @Test
    void translationOnlyInstanceEqualsTheGeneralWriter() {
        for (int i = 0; i < Rnd.N; i++) {
            Vec3f t = rnd.nextVec3f();
            int user = (int) rnd.range(0, 1e6);
            MemorySegment a = MemorySegment.ofArray(new byte[(int) InstanceWriter.STRIDE * 2]);
            MemorySegment b = MemorySegment.ofArray(new byte[(int) InstanceWriter.STRIDE * 2]);
            InstanceWriter.write(a, 1, Mat4x3f.translation(t), user);
            InstanceWriter.writeTranslation(b, 1, t.x(), t.y(), t.z(), user);
            assertEquals(-1L, a.mismatch(b), "same bytes");
        }
    }

    @Test
    void visibleTranslationsMatchTheElementwiseWriter() {
        int n = 1000;
        vmath.bulk.BoundsArray bounds = new vmath.bulk.BoundsArray(n);
        vmath.bulk.VisibilitySet visible = new vmath.bulk.VisibilitySet(n);
        for (int i = 0; i < n; i++) {
            Vec3f c = rnd.nextVec3f();
            float h = (float) rnd.range(0.1, 2);
            bounds.add(c.x() - h, c.y() - h, c.z() - h, c.x() + h, c.y() + h, c.z() + h);
            if (rnd.range(0, 1) < 0.3) {
                visible.set(i);
            }
        }
        MemorySegment a = MemorySegment.ofArray(new byte[(int) InstanceWriter.STRIDE * (n + 3)]);
        MemorySegment b = MemorySegment.ofArray(new byte[(int) InstanceWriter.STRIDE * (n + 3)]);
        int written = InstanceWriter.writeVisibleTranslations(a, 2, visible, bounds);
        int k = 2;
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            Vec3f c = bounds.get(i).center();
            InstanceWriter.writeTranslation(b, k++, c.x(), c.y(), c.z(), i);
        }
        assertEquals(visible.count(), written);
        assertEquals(-1L, a.mismatch(b), "same bytes as one writeTranslation per visible object");
        assertEquals(0, InstanceWriter.writeVisibleTranslations(a, 0, new vmath.bulk.VisibilitySet(n), bounds), "nothing visible writes nothing");
    }

    @Test
    void instanceRowsReproduceTheTransformInTheShader() {
        for (int i = 0; i < Rnd.N; i++) {
            Mat4x3f m = Mat4x3f.fromMat4(rnd.nextTrsMat4f());
            int user = (int) rnd.range(0, 1e6);
            int index = (int) rnd.range(0, 10);
            MemorySegment seg = MemorySegment.ofArray(new byte[(int) InstanceWriter.STRIDE * 11]);
            InstanceWriter.write(seg, index, m, user);
            long base = index * InstanceWriter.STRIDE;
            Vec3f p = rnd.nextVec3f();
            // the shader's vec3(dot(row0, p1), dot(row1, p1), dot(row2, p1)) with p1 = (p, 1)
            float[] out = new float[3];
            for (int r = 0; r < 3; r++) {
                float x = GpuWriter.getFloat(seg, base + 16L * r), y = GpuWriter.getFloat(seg, base + 16L * r + 4);
                float z = GpuWriter.getFloat(seg, base + 16L * r + 8), t = GpuWriter.getFloat(seg, base + 16L * r + 12);
                out[r] = x * p.x() + y * p.y() + z * p.z() + t;
            }
            Vec3f expected = m.transformPosition(p);
            assertEquals(expected.x(), out[0], 1e-4f);
            assertEquals(expected.y(), out[1], 1e-4f);
            assertEquals(expected.z(), out[2], 1e-4f);
            assertEquals(user, word(seg, base + InstanceWriter.OFFSET_USER_DATA));
        }
        assertEquals(64, InstanceWriter.STRIDE);
        assertEquals(48, InstanceWriter.TRANSFORM_BYTES);
        Mat4f unused = Mat4f.IDENTITY;
        assertEquals(unused, Mat4f.IDENTITY);
    }

    @Test
    void boxInstancesMapTheUnitCubeOntoTheBounds() {
        int n = 300;
        vmath.bulk.BoundsArray bounds = new vmath.bulk.BoundsArray(n);
        vmath.bulk.VisibilitySet visible = new vmath.bulk.VisibilitySet(n);
        for (int i = 0; i < n; i++) {
            Vec3f lo = rnd.nextVec3f(), size = new Vec3f((float) rnd.range(0.1, 5), (float) rnd.range(0.1, 5), (float) rnd.range(0.1, 5));
            bounds.add(lo.x(), lo.y(), lo.z(), lo.x() + size.x(), lo.y() + size.y(), lo.z() + size.z());
            if (i % 3 != 0) {
                visible.set(i);
            }
        }
        MemorySegment a = MemorySegment.ofArray(new byte[(int) InstanceWriter.STRIDE * (n + 2)]);
        int written = InstanceWriter.writeVisibleBoxes(a, 2, visible, bounds);
        assertEquals(visible.count(), written);
        long k = 2;
        for (int i = 0; i < n; i++) {
            if (!visible.get(i)) {
                continue;
            }
            long base = k * InstanceWriter.STRIDE;
            float sx = bounds.maxX(i) - bounds.minX(i), sy = bounds.maxY(i) - bounds.minY(i), sz = bounds.maxZ(i) - bounds.minZ(i);
            Vec3f center = new Vec3f((bounds.minX(i) + bounds.maxX(i)) * 0.5f, (bounds.minY(i) + bounds.maxY(i)) * 0.5f, (bounds.minZ(i) + bounds.maxZ(i)) * 0.5f);
            // the shader's rule: world = (dot(row0, p), dot(row1, p), dot(row2, p)) with p = (position, 1); check the corners of the unit cube
            for (int c = 0; c < 8; c++) {
                float px = (c & 1) == 0 ? -0.5f : 0.5f, py = (c & 2) == 0 ? -0.5f : 0.5f, pz = (c & 4) == 0 ? -0.5f : 0.5f;
                float wx = GpuWriter.getFloat(a, base) * px + GpuWriter.getFloat(a, base + 4) * py + GpuWriter.getFloat(a, base + 8) * pz + GpuWriter.getFloat(a, base + 12);
                float wy = GpuWriter.getFloat(a, base + 16) * px + GpuWriter.getFloat(a, base + 20) * py + GpuWriter.getFloat(a, base + 24) * pz + GpuWriter.getFloat(a, base + 28);
                float wz = GpuWriter.getFloat(a, base + 32) * px + GpuWriter.getFloat(a, base + 36) * py + GpuWriter.getFloat(a, base + 40) * pz + GpuWriter.getFloat(a, base + 44);
                assertEquals(center.x() + px * sx, wx, 1e-5f);
                assertEquals(center.y() + py * sy, wy, 1e-5f);
                assertEquals(center.z() + pz * sz, wz, 1e-5f);
            }
            assertEquals(i, word(a, base + InstanceWriter.OFFSET_USER_DATA), "the user data is the object index");
            k++;
        }
        assertEquals(0, GpuWriter.getInt(a, 0), "the first two instances are untouched");
        assertEquals(0, InstanceWriter.writeVisibleBoxes(a, 0, new vmath.bulk.VisibilitySet(n), bounds), "nothing visible writes nothing");
        // one box written directly
        InstanceWriter.writeBox(a, 0, 1f, 2f, 3f, 4f, 5f, 6f, 77);
        assertEquals(4f, GpuWriter.getFloat(a, 0), 0f);
        assertEquals(3f, GpuWriter.getFloat(a, 44), 0f);
        assertEquals(77, word(a, InstanceWriter.OFFSET_USER_DATA));
    }
}
