package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Mat3f;
import vmath.core.Mat4f;
import vmath.core.Mat4x3f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec2f;
import vmath.core.Vec3f;
import vmath.core.Vec3i;
import vmath.core.Vec4f;

/** The classes generated from the sample records ({@code CameraBlock}, {@code LightData}, ...): constants, layout, GLSL and the writers, read back byte by byte. */
class GpuStructTest {

    final Rnd rnd = Rnd.create();

    private static float f(MemorySegment s, long off) {
        return GpuWriter.getFloat(s, off);
    }

    // ------------------------------------------------------------ constants and layout

    @Test
    void generatedConstantsMatchTheHandWorkedLayouts() {
        assertEquals(0, CameraBlockGpu.OFFSET_VIEW);
        assertEquals(64, CameraBlockGpu.OFFSET_PROJ);
        assertEquals(128, CameraBlockGpu.OFFSET_POSITION);
        assertEquals(140, CameraBlockGpu.OFFSET_TIME);
        assertEquals(144, CameraBlockGpu.SIZE);
        assertEquals(16, CameraBlockGpu.ALIGNMENT);

        assertEquals(0, LightDataGpu.OFFSET_POSITION);
        assertEquals(12, LightDataGpu.OFFSET_RADIUS);
        assertEquals(16, LightDataGpu.OFFSET_COLOR);
        assertEquals(32, LightDataGpu.OFFSET_CASCADES);
        assertEquals(16, LightDataGpu.STRIDE_CASCADES);
        assertEquals(96, LightDataGpu.OFFSET_ORIENTATION);
        assertEquals(112, LightDataGpu.SIZE);

        assertEquals(0, ParticleGpu.OFFSET_POSITION);
        assertEquals(12, ParticleGpu.OFFSET_LIFE);
        assertEquals(16, ParticleGpu.OFFSET_VELOCITY);
        assertEquals(28, ParticleGpu.OFFSET_FLAGS);
        assertEquals(32, ParticleGpu.SIZE);
        assertEquals(0, ParticleGpu.LAYOUT.paddingBytes(), "std430 packs the particle with no padding");

        assertEquals(48, PlacementGpu.OFFSET_CELL);
        assertEquals(60, PlacementGpu.SIZE);
        assertEquals(4, PlacementGpu.ALIGNMENT);
        assertEquals(60, BatchGpu.STRIDE_ITEMS);
        assertEquals(180, BatchGpu.OFFSET_COUNT);
        assertEquals(184, BatchGpu.SIZE);
    }

    @Test
    void generatedGlslMatchesTheRecord() {
        assertEquals("struct Particle {\n    vec3 position;\n    float life;\n    vec3 velocity;\n    uint flags;\n};",
                ParticleGpu.GLSL);
        assertTrue(LightDataGpu.GLSL.contains("vec4 cascades[4];"), LightDataGpu.GLSL);
        assertTrue(LightDataGpu.GLSL.contains("vec4 orientation;"), "a quaternion is a vec4");
        assertTrue(PlacementGpu.GLSL.contains("mat4x3 model;") && PlacementGpu.GLSL.contains("ivec3 cell;"), PlacementGpu.GLSL);
        assertTrue(BatchGpu.GLSL.contains("Placement items[3];"), BatchGpu.GLSL);
        assertTrue(MatrixBlockGpu.GLSL.contains("mat3 normal;") && MatrixBlockGpu.GLSL.contains("vec2 uvScale;"));
    }

    // ------------------------------------------------------------ writers

    @Test
    void cameraBlockRoundTrips() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(CameraBlockGpu.SIZE + 32, 16);
            for (int trial = 0; trial < 200; trial++) {
                Mat4f view = rnd.nextTrsMat4f(), proj = Mat4f.perspective(1f, 1.5f, 0.1f, 100f, ClipSpace.D3D);
                Vec3f pos = rnd.nextVec3f();
                float time = (float) rnd.range(0, 100);
                CameraBlockGpu.write(new CameraBlock(view, proj, pos, time), seg, 16);
                float[] expectedView = new float[16], expectedProj = new float[16];
                view.writeTo(expectedView, 0);
                proj.writeTo(expectedProj, 0);
                for (int i = 0; i < 16; i++) {
                    assertEquals(expectedView[i], f(seg, 16 + CameraBlockGpu.OFFSET_VIEW + 4L * i), "view[" + i + "]");
                    assertEquals(expectedProj[i], f(seg, 16 + CameraBlockGpu.OFFSET_PROJ + 4L * i), "proj[" + i + "]");
                }
                assertEquals(pos.x(), f(seg, 16 + CameraBlockGpu.OFFSET_POSITION));
                assertEquals(pos.z(), f(seg, 16 + CameraBlockGpu.OFFSET_POSITION + 8));
                assertEquals(time, f(seg, 16 + CameraBlockGpu.OFFSET_TIME));
            }
        }
    }

    @Test
    void lightDataWritesArraysAndQuaternions() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(LightDataGpu.SIZE, 16);
            Vec4f[] cascades = new Vec4f[4];
            for (int i = 0; i < 4; i++) {
                cascades[i] = rnd.nextVec4f();
            }
            Quatf q = rnd.nextUnitQuatf();
            LightData light = new LightData(new Vec3f(1f, 2f, 3f), 9f, new Vec4f(0.1f, 0.2f, 0.3f, 0.4f), cascades, q);
            LightDataGpu.write(light, seg, 0);
            assertEquals(2f, f(seg, 4));
            assertEquals(9f, f(seg, LightDataGpu.OFFSET_RADIUS), "the float sits in the vec3's fourth slot");
            assertEquals(0.4f, f(seg, LightDataGpu.OFFSET_COLOR + 12));
            for (int i = 0; i < 4; i++) {
                long base = LightDataGpu.OFFSET_CASCADES + i * LightDataGpu.STRIDE_CASCADES;
                assertEquals(cascades[i].x(), f(seg, base));
                assertEquals(cascades[i].w(), f(seg, base + 12));
            }
            assertEquals(q.x(), f(seg, LightDataGpu.OFFSET_ORIENTATION));
            assertEquals(q.w(), f(seg, LightDataGpu.OFFSET_ORIENTATION + 12), "quaternion order is x, y, z, w");
            // the array length is part of the type
            assertThrows(IllegalArgumentException.class, () -> LightDataGpu.write(
                    new LightData(Vec3f.ZERO, 0f, Vec4f.ZERO, new Vec4f[3], Quatf.IDENTITY), seg, 0));
        }
    }

    @Test
    void particleUsesStd430AndAnUnsignedInt() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ParticleGpu.SIZE * 2, 16);
            for (int i = 0; i < 2; i++) {
                ParticleGpu.write(new Particle(new Vec3f(i, i + 1f, i + 2f), 0.5f * i, new Vec3f(7f, 8f, 9f), 0xFFFF_FFF0 + i),
                        seg, i * ParticleGpu.SIZE);
            }
            assertEquals(1f, f(seg, ParticleGpu.SIZE));
            assertEquals(0.5f, f(seg, ParticleGpu.SIZE + ParticleGpu.OFFSET_LIFE));
            assertEquals(9f, f(seg, ParticleGpu.SIZE + ParticleGpu.OFFSET_VELOCITY + 8));
            assertEquals(0xFFFF_FFF1, GpuWriter.getInt(seg, ParticleGpu.SIZE + ParticleGpu.OFFSET_FLAGS));
        }
    }

    @Test
    void matricesAreWrittenColumnByColumnWithTheLayoutsStride() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(MatrixBlockGpu.SIZE, 16);
            Mat3f normal = rnd.nextDenseMat3f();
            Mat4x3f model = Mat4x3f.fromMat4(rnd.nextTrsMat4f());
            MatrixBlockGpu.write(new MatrixBlock(normal, model, new Vec2f(0.5f, 2f)), seg, 0);
            // std140: each mat3 column is a vec4 slot, column c at +16c
            assertEquals(normal.m00(), f(seg, MatrixBlockGpu.OFFSET_NORMAL));
            assertEquals(normal.m12(), f(seg, MatrixBlockGpu.OFFSET_NORMAL + 16 + 8));
            assertEquals(normal.m22(), f(seg, MatrixBlockGpu.OFFSET_NORMAL + 32 + 8));
            // mat4x3: four columns, translation in the last
            assertEquals(model.m30(), f(seg, MatrixBlockGpu.OFFSET_MODEL + 48));
            assertEquals(model.m32(), f(seg, MatrixBlockGpu.OFFSET_MODEL + 48 + 8));
            assertEquals(2f, f(seg, MatrixBlockGpu.OFFSET_UV_SCALE + 4));
        }
    }

    @Test
    void scalarLayoutPacksMatricesTightlyAndNestsStructArrays() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(BatchGpu.SIZE, 4);
            Placement[] items = new Placement[3];
            for (int i = 0; i < 3; i++) {
                items[i] = new Placement(Mat4x3f.translation(i + 1f, i + 2f, i + 3f), new Vec3i(i, -i, 100 + i));
            }
            BatchGpu.write(new Batch(items, 3), seg, 0);
            for (int i = 0; i < 3; i++) {
                long base = i * BatchGpu.STRIDE_ITEMS;
                // scalar mat4x3: 4 columns of 12 bytes, so the translation starts at +36
                assertEquals(i + 1f, f(seg, base + 36), "item " + i + " translation x");
                assertEquals(i + 3f, f(seg, base + 44), "item " + i + " translation z");
                assertEquals(1f, f(seg, base), "rotation part is the identity");
                assertEquals(100 + i, GpuWriter.getInt(seg, base + PlacementGpu.OFFSET_CELL + 8));
            }
            assertEquals(3, GpuWriter.getInt(seg, BatchGpu.OFFSET_COUNT));
        }
    }

    @Test
    void byteBufferOverloadWritesNativeOrder() {
        ByteBuffer bb = ByteBuffer.allocateDirect((int) ParticleGpu.SIZE + 8).order(ByteOrder.nativeOrder());
        ParticleGpu.write(new Particle(new Vec3f(1f, 2f, 3f), 4f, Vec3f.ZERO, 5), bb, 8);
        assertEquals(1f, bb.getFloat(8));
        assertEquals(4f, bb.getFloat(8 + (int) ParticleGpu.OFFSET_LIFE));
        assertEquals(5, bb.getInt(8 + (int) ParticleGpu.OFFSET_FLAGS));
        assertEquals(0, bb.position(), "writing must not move the position");
    }

    @Test
    void paddingIsLeftUntouched() {
        try (Arena arena = Arena.ofConfined()) {
            // each std140 mat3 column is 12 bytes of data in a 16-byte slot: the last 4 bytes are padding
            MemorySegment seg = arena.allocate(MatrixBlockGpu.SIZE, 16);
            seg.fill((byte) 0x7B);
            MatrixBlockGpu.write(new MatrixBlock(Mat3f.IDENTITY, Mat4x3f.IDENTITY, Vec2f.ZERO), seg, 0);
            for (int column = 0; column < 3; column++) {
                for (int b = 12; b < 16; b++) {
                    assertEquals((byte) 0x7B, seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE,
                            MatrixBlockGpu.OFFSET_NORMAL + 16L * column + b), "padding byte " + b + " of column " + column);
                }
            }
            assertEquals(1f, f(seg, MatrixBlockGpu.OFFSET_NORMAL), "data next to it is written");
        }
    }

    @Test
    void writerHelpersHandleUnalignedOffsets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(64, 16);
            GpuWriter.putFloat(seg, 3, 1.5f);
            GpuWriter.putVec3(seg, 21, new Vec3f(1f, 2f, 3f));
            GpuWriter.putInt(seg, 41, -7);
            assertEquals(1.5f, f(seg, 3));
            assertEquals(3f, f(seg, 29));
            assertEquals(-7, GpuWriter.getInt(seg, 41));
            // a heap ByteBuffer wraps too
            ByteBuffer heap = ByteBuffer.allocate(16);
            MemorySegment view = GpuWriter.of(heap);
            GpuWriter.putFloat(view, 4, 2.5f);
            assertEquals(2.5f, f(view, 4));
        }
    }
}
