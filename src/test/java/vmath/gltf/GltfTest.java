package vmath.gltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vmath.anim.AnimationClip;
import vmath.anim.ClipSampler;
import vmath.anim.Pose;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.mesh.Mesh;

class GltfTest {

    // ---------------------------------------------------------------- fixtures

    /** A growing little-endian byte buffer. */
    private static final class Bin {
        private ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);

        private void room(int n) {
            if (b.remaining() < n) {
                ByteBuffer nb = ByteBuffer.allocate(Math.max(b.capacity() * 2, b.position() + n)).order(ByteOrder.LITTLE_ENDIAN);
                b.flip();
                nb.put(b);
                b = nb;
            }
        }

        Bin f(float... v) {
            for (float x : v) {
                room(4);
                b.putFloat(x);
            }
            return this;
        }

        Bin u16(int... v) {
            for (int x : v) {
                room(2);
                b.putShort((short) x);
            }
            return this;
        }

        Bin u8(int... v) {
            for (int x : v) {
                room(1);
                b.put((byte) x);
            }
            return this;
        }

        Bin u32(long... v) {
            for (long x : v) {
                room(4);
                b.putInt((int) x);
            }
            return this;
        }

        Bin align4() {
            while (b.position() % 4 != 0) {
                u8(0);
            }
            return this;
        }

        int size() {
            return b.position();
        }

        byte[] bytes() {
            byte[] out = new byte[b.position()];
            b.flip();
            b.get(out);
            b.position(out.length);
            b.limit(b.capacity());
            return out;
        }
    }

    private static String dataUri(byte[] bytes) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** A glTF JSON document with one embedded buffer; {@code rest} is spliced in after the buffers entry, e.g. views, accessors and meshes. */
    private static String gltf(byte[] buffer, String rest) {
        return "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + buffer.length + ",\"uri\":\"" + dataUri(buffer) + "\"}]," + rest + "}";
    }

    private static Gltf parse(String json) {
        return Gltf.parse(json.getBytes(StandardCharsets.UTF_8), null);
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jp = (j.length + 3) & ~3, bp = bin == null ? 0 : (bin.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + jp + (bin == null ? 0 : 8 + bp)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(b.capacity());
        b.putInt(jp).putInt(0x4E4F534A).put(j);
        for (int i = j.length; i < jp; i++) {
            b.put((byte) 0x20);
        }
        if (bin != null) {
            b.putInt(bp).putInt(0x004E4942).put(bin);
        }
        return b.array();
    }

    /** One triangle: 3 positions, 3 normals, 3 uvs, 3 ushort indices. */
    private static byte[] triangleBuffer() {
        Bin b = new Bin();
        b.f(0, 0, 0, 1, 0, 0, 0, 1, 0);          // positions, offset 0, 36 bytes
        b.f(0, 0, 1, 0, 0, 1, 0, 0, 1);          // normals, offset 36
        b.f(0, 0, 1, 0, 0, 1);                   // uvs, offset 72, 24 bytes
        b.u16(0, 1, 2).align4();                 // indices, offset 96 (8 bytes with padding)
        return b.bytes();
    }

    private static final String TRIANGLE_REST = "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},"
            + "{\"buffer\":0,\"byteOffset\":72,\"byteLength\":24},{\"buffer\":0,\"byteOffset\":96,\"byteLength\":6}],"
            + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]},"
            + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
            + "{\"bufferView\":3,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
            + "\"meshes\":[{\"name\":\"tri\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0}]}],"
            + "\"materials\":[{\"name\":\"m\"}]";

    private static Gltf triangle() {
        return parse(gltf(triangleBuffer(), TRIANGLE_REST));
    }

    // ---------------------------------------------------------------- geometry

    @Test
    void aTriangleBecomesAMesh() {
        Gltf g = triangle();
        assertEquals(1, g.meshCount());
        assertEquals("tri", g.mesh(0).name());
        Mesh m = g.toMesh(0, 0);
        assertEquals(3, m.vertexCount());
        assertEquals(1, m.triangleCount());
        assertEquals(1f, m.positions()[3]);
        assertEquals(1f, m.positions()[7]);
        assertTrue(m.hasNormals() && m.hasUvs(0) && !m.hasTangents() && !m.hasUvs(1));
        assertEquals(1f, m.normals()[2]);
        assertEquals(1f, m.uvs(0)[2]);
        assertArrayEquals(new int[] {0, 1, 2}, java.util.Arrays.copyOf(m.indices(), 3));
        Gltf.AccessorInfo info = g.accessorInfo(0);
        assertEquals(3, info.count());
        assertEquals(3, info.components());
        assertArrayEquals(new float[] {1, 1, 0}, info.max());
        assertEquals(0, g.accessorInfo(1).min() == null ? 0 : 1);
    }

    @Test
    void theSameFileAsAGlbContainer() {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":104}]," + TRIANGLE_REST + "}";
        Gltf g = Gltf.parse(glb(json, triangleBuffer()), null);
        assertEquals(1f, g.toMesh(0, 0).positions()[3]);
        // binary chunk shorter than the declared buffer
        String tooLong = json.replace("\"byteLength\":104", "\"byteLength\":4000");
        assertThrows(GltfException.class, () -> Gltf.parse(glb(tooLong, triangleBuffer()), null));
        // a GLB with no binary chunk but a buffer without uri
        assertThrows(GltfException.class, () -> Gltf.parse(glb(json, null), null));
    }

    @Test
    void glbContainerIsValidated() {
        byte[] good = glb("{\"asset\":{\"version\":\"2.0\"}}", null);
        Gltf.parse(good, null);
        byte[] badVersion = good.clone();
        badVersion[4] = 1;
        assertThrows(GltfException.class, () -> Gltf.parse(badVersion, null));
        byte[] badLength = good.clone();
        badLength[8] = (byte) 0xFF;
        badLength[9] = 0x7F;
        assertThrows(GltfException.class, () -> Gltf.parse(badLength, null));
        byte[] badChunk = good.clone();
        badChunk[12] = (byte) 0xFF;
        badChunk[13] = 0x7F;
        assertThrows(GltfException.class, () -> Gltf.parse(badChunk, null));
        assertThrows(GltfException.class, () -> Gltf.parse(java.util.Arrays.copyOf(good, 20), null));
        byte[] noJson = good.clone();
        noJson[16] = 'X';
        assertThrows(GltfException.class, () -> Gltf.parse(noJson, null), "no JSON chunk");
    }

    @Test
    void interleavedAccessorsWithAByteStride() {
        Bin b = new Bin();
        // two vertices: position (12) + normal (12) + uv (8) = 32 bytes each
        b.f(1, 2, 3, 0, 0, 1, 0.25f, 0.5f);
        b.f(4, 5, 6, 0, 1, 0, 0.75f, 1f);
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":64,\"byteStride\":32}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"},"
                + "{\"bufferView\":0,\"byteOffset\":12,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"},"
                + "{\"bufferView\":0,\"byteOffset\":24,\"componentType\":5126,\"count\":2,\"type\":\"VEC2\"}]";
        Gltf g = parse(gltf(b.bytes(), rest));
        assertArrayEquals(new float[] {1, 2, 3, 4, 5, 6}, g.readFloats(0));
        assertArrayEquals(new float[] {0, 0, 1, 0, 1, 0}, g.readFloats(1));
        assertArrayEquals(new float[] {0.25f, 0.5f, 0.75f, 1f}, g.readFloats(2));
        // the last element of the last accessor ends exactly at the end of the view; one byte more is refused
        String pastEnd = rest.replace("\"byteOffset\":24", "\"byteOffset\":25");
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), pastEnd)).readFloats(2));
    }

    @Test
    void normalizedAndPlainIntegers() {
        Bin b = new Bin();
        b.u16(0, 32768, 65535, 0).u8(0, 128, 255, 0).u8(0x80, 0x7F, 0x81, 0).u16(0xFFFF, 0x8000, 0x7FFF, 0);
        // offsets: ushort x4 = 0..8, ubyte x4 = 8..12, byte x4 = 12..16 (0x80 = -128, 0x7F = 127, 0x81 = -127), short x4 = 16..24
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":24}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5123,\"normalized\":true,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":0,\"byteOffset\":8,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":0,\"byteOffset\":12,\"componentType\":5120,\"normalized\":true,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":0,\"byteOffset\":16,\"componentType\":5122,\"normalized\":true,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":0,\"byteOffset\":8,\"componentType\":5121,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":0,\"byteOffset\":12,\"componentType\":5120,\"count\":3,\"type\":\"SCALAR\"}]";
        Gltf g = parse(gltf(b.bytes(), rest));
        assertArrayEquals(new float[] {0f, 32768f / 65535f, 1f}, g.readFloats(0), 1e-6f);
        assertArrayEquals(new float[] {0f, 128f / 255f, 1f}, g.readFloats(1), 1e-6f);
        assertArrayEquals(new float[] {-1f, 1f, -127f / 127f}, g.readFloats(2), 1e-6f, "-128 clamps to -1");
        assertArrayEquals(new float[] {-1f / 32767f, -1f, 1f}, g.readFloats(3), 1e-6f, "-32768 clamps to -1");
        assertArrayEquals(new int[] {0, 128, 255}, g.readInts(4));
        assertArrayEquals(new int[] {-128, 127, -127}, g.readInts(5));
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), rest.replace("\"componentType\":5123,\"normalized\":true", "\"componentType\":5125,\"normalized\":true"))).readFloats(0),
                "normalized unsigned int is not allowed");
    }

    @Test
    void unsignedIntIndicesAbove31Bits() {
        Bin b = new Bin();
        b.u32(0, 1, 2).u32(0x7FFFFFFFL, 0xFFFFFFFFL);
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":20}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5125,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":0,\"byteOffset\":12,\"componentType\":5125,\"count\":2,\"type\":\"SCALAR\"}]";
        Gltf g = parse(gltf(b.bytes(), rest));
        assertArrayEquals(new int[] {0, 1, 2}, g.readInts(0));
        assertArrayEquals(new float[] {2147483647f, 4294967295f}, g.readFloats(1), 1e-3f);
    }

    @Test
    void sparseAccessors() {
        Bin b = new Bin();
        b.f(1, 1, 1, 2, 2, 2, 3, 3, 3);   // base: 3 vec3, 36 bytes, offset 0
        b.u8(0, 2, 0, 0);                 // sparse indices (ubyte 0 and 2), offset 36, padded to 40
        b.f(9, 9, 9, 8, 8, 8);            // sparse values, offset 40
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":2},{\"buffer\":0,\"byteOffset\":40,\"byteLength\":24}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"sparse\":{\"count\":2,\"indices\":{\"bufferView\":1,\"componentType\":5121},"
                + "\"values\":{\"bufferView\":2}}},"
                + "{\"componentType\":5126,\"count\":4,\"type\":\"VEC3\",\"sparse\":{\"count\":1,\"indices\":{\"bufferView\":1,\"byteOffset\":1,\"componentType\":5121},"
                + "\"values\":{\"bufferView\":2,\"byteOffset\":12}}}]";
        Gltf g = parse(gltf(b.bytes(), rest));
        assertArrayEquals(new float[] {9, 9, 9, 2, 2, 2, 8, 8, 8}, g.readFloats(0), "two elements replaced");
        assertArrayEquals(new float[] {0, 0, 0, 0, 0, 0, 8, 8, 8, 0, 0, 0}, g.readFloats(1), "no buffer view: zeros with one sparse element");
        // a sparse index must stay below the accessor count (here the second accessor shrinks to 2 elements but its index is 2)
        String bad = rest.replace("\"componentType\":5126,\"count\":4,\"type\":\"VEC3\",\"sparse\"", "\"componentType\":5126,\"count\":2,\"type\":\"VEC3\",\"sparse\"");
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), bad)).readFloats(1));
    }

    @Test
    void matrixColumnsArePaddedToFourBytes() {
        // a MAT2 of unsigned bytes: columns of 2 bytes padded to 4, so an element is 8 bytes
        Bin b = new Bin();
        b.u8(1, 2, 0, 0, 3, 4, 0, 0, 5, 6, 0, 0, 7, 8, 0, 0);
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":16}],\"accessors\":[{\"bufferView\":0,\"componentType\":5121,\"count\":2,\"type\":\"MAT2\"}]";
        Gltf g = parse(gltf(b.bytes(), rest));
        assertArrayEquals(new float[] {1, 2, 3, 4, 5, 6, 7, 8}, g.readFloats(0));
        // a MAT3 of shorts: columns of 6 bytes padded to 8, an element is 24 bytes
        Bin s = new Bin();
        for (int i = 1; i <= 9; i++) {
            s.u16(i);
            if (i % 3 == 0) {
                s.u16(0);
            }
        }
        String rest3 = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":24}],\"accessors\":[{\"bufferView\":0,\"componentType\":5123,\"count\":1,\"type\":\"MAT3\"}]";
        assertArrayEquals(new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, parse(gltf(s.bytes(), rest3)).readFloats(0));
    }

    @Test
    void readIntoWritesOneAttributeOfAnInterleavedBuffer() {
        Gltf g = triangle();
        MemorySegment seg = MemorySegment.ofArray(new byte[3 * 40]);
        for (long o = 0; o < seg.byteSize(); o += 4) {
            seg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o, -7f);
        }
        // position at 0 (12 bytes), normal at 16 (12 bytes), uv at 32 (8 bytes); bytes 12..16 and 28..32 belong to other attributes
        assertEquals(3, g.readInto(0, seg, 0, 40, ByteOrder.nativeOrder()));
        assertEquals(3, g.readInto(1, seg, 16, 40, ByteOrder.nativeOrder()));
        assertEquals(3, g.readInto(2, seg, 32, 40, ByteOrder.nativeOrder()));
        assertEquals(1f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 40), "vertex 1 position x");
        assertEquals(1f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 40 + 16 + 8), "vertex 1 normal z");
        assertEquals(1f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 40 + 32), "vertex 1 uv u");
        for (int v = 0; v < 3; v++) {
            assertEquals(-7f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, v * 40L + 12), "the gap before the normal is untouched");
            assertEquals(-7f, seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, v * 40L + 28), "the gap before the uv is untouched");
        }
        // the byte order is honoured
        MemorySegment big = MemorySegment.ofArray(new byte[3 * 12]);
        g.readInto(0, big, 0, 12, ByteOrder.BIG_ENDIAN);
        assertEquals(1f, big.get(ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 12));
    }

    @Test
    void stripsFansAndNonIndexedPrimitives() {
        Bin b = new Bin();
        b.f(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0, 2, 1, 0);      // 5 positions
        b.u16(0, 1, 2, 3, 4).align4();                         // 5 indices
        String prim = "{\"attributes\":{\"POSITION\":0},\"indices\":1,\"mode\":%d}";
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":60},{\"buffer\":0,\"byteOffset\":60,\"byteLength\":10}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":5,\"type\":\"VEC3\"},{\"bufferView\":1,\"componentType\":5123,\"count\":5,\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[" + String.format(prim, 5) + "," + String.format(prim, 6) + ",{\"attributes\":{\"POSITION\":0},\"mode\":4},"
                + "{\"attributes\":{\"POSITION\":0},\"mode\":0}]}]";
        Gltf g = parse(gltf(b.bytes(), rest));
        Mesh strip = g.toMesh(0, 0);
        assertEquals(3, strip.triangleCount());
        int[] si = strip.indices();
        assertArrayEquals(new int[] {0, 1, 2, 2, 1, 3, 2, 3, 4}, java.util.Arrays.copyOf(si, 9), "strip: every second triangle is flipped to keep the winding");
        Mesh fan = g.toMesh(0, 1);
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 0, 3, 4}, java.util.Arrays.copyOf(fan.indices(), 9));
        assertThrows(GltfException.class, () -> g.toMesh(0, 2), "5 vertices are not a whole number of triangles");
        assertThrows(GltfException.class, () -> g.toMesh(0, 3), "points are not triangles");
    }

    @Test
    void malformedGeometryIsRejected() {
        // index out of range
        Bin b = new Bin();
        b.f(0, 0, 0, 1, 0, 0, 0, 1, 0).u16(0, 1, 7).align4();
        String rest = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}]";
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), rest)).toMesh(0, 0));
        // an accessor that reads past its view
        String past = rest.replace("\"count\":3,\"type\":\"VEC3\"", "\"count\":4,\"type\":\"VEC3\"");
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), past)).readFloats(0));
        // a buffer view past its buffer
        String view = rest.replace("\"byteLength\":36}", "\"byteLength\":3600}");
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), view)));
        // stride smaller than the element
        String stride = rest.replace("\"byteLength\":36}", "\"byteLength\":36,\"byteStride\":4}");
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), stride)).readFloats(0), "a stride of 4 is valid for a view, but not for a VEC3 accessor reading it");
        // zero count, unknown component type, unknown type
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), rest.replace("\"count\":3,\"type\":\"VEC3\"", "\"count\":0,\"type\":\"VEC3\""))).readFloats(0));
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), rest.replace("5126", "5127"))).readFloats(0));
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), rest.replace("VEC3", "VEC9"))).readFloats(0));
        // accessor index that does not exist
        assertThrows(GltfException.class, () -> parse(gltf(b.bytes(), rest.replace("\"POSITION\":0", "\"POSITION\":5"))));
        // normals with the wrong count
        Gltf tri = triangle();
        assertEquals(3, tri.toMesh(0, 0).vertexCount());
        String badNormal = TRIANGLE_REST.replace("{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}", "{\"bufferView\":1,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"}");
        assertThrows(GltfException.class, () -> parse(gltf(triangleBuffer(), badNormal)).toMesh(0, 0));
    }

    @Test
    void fileLevelChecks() {
        assertThrows(GltfException.class, () -> Gltf.parse("[]".getBytes(StandardCharsets.UTF_8), null));
        assertThrows(GltfException.class, () -> Gltf.parse("{}".getBytes(StandardCharsets.UTF_8), null), "no asset");
        assertThrows(GltfException.class, () -> Gltf.parse("{\"asset\":{\"version\":\"1.0\"}}".getBytes(StandardCharsets.UTF_8), null));
        assertThrows(GltfException.class, () -> Gltf.parse("{\"asset\":{\"version\":\"2.0\"},\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]}".getBytes(StandardCharsets.UTF_8), null));
        Gltf.parse("{\"asset\":{\"version\":\"2.0\"},\"extensionsRequired\":[\"KHR_mesh_quantization\"]}".getBytes(StandardCharsets.UTF_8), null);
        Gltf.parse("﻿{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.UTF_8), null);
        byte[] small = new byte[4];
        String uriBad = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":4,\"uri\":\"data:application/octet-stream;base64,@@@@\"}]}";
        assertThrows(GltfException.class, () -> Gltf.parse(uriBad.getBytes(StandardCharsets.UTF_8), null));
        String shortBuffer = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":400,\"uri\":\"" + dataUri(small) + "\"}]}";
        assertThrows(GltfException.class, () -> Gltf.parse(shortBuffer.getBytes(StandardCharsets.UTF_8), null));
        String external = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":4,\"uri\":\"x.bin\"}]}";
        assertThrows(GltfException.class, () -> Gltf.parse(external.getBytes(StandardCharsets.UTF_8), null), "no resolver");
        Gltf viaResolver = Gltf.parse(external.getBytes(StandardCharsets.UTF_8), uri -> small);
        assertEquals(0, viaResolver.accessorCount());
        assertThrows(GltfException.class, () -> Gltf.parse(external.getBytes(StandardCharsets.UTF_8), uri -> {
            throw new IOException("nope");
        }));
    }

    // ---------------------------------------------------------------- materials, nodes, images

    @Test
    void materialsHaveTheSpecificationDefaults() {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"materials\":[{},{\"name\":\"metal\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.5,0.25,0.125,1],"
                + "\"baseColorTexture\":{\"index\":2,\"texCoord\":1},\"metallicFactor\":0.0,\"roughnessFactor\":0.25,\"metallicRoughnessTexture\":{\"index\":3}},"
                + "\"normalTexture\":{\"index\":4,\"scale\":2.0},\"occlusionTexture\":{\"index\":5,\"strength\":0.5},\"emissiveFactor\":[1,0.5,0],\"emissiveTexture\":{\"index\":6},"
                + "\"alphaMode\":\"MASK\",\"alphaCutoff\":0.25,\"doubleSided\":true}],"
                + "\"textures\":[{\"source\":1,\"sampler\":0},{}],\"samplers\":[{\"magFilter\":9729,\"wrapS\":33071}],\"images\":[{\"uri\":\"a.png\"},{\"bufferView\":0,\"mimeType\":\"image/png\"}]}";
        Gltf g = parse(json);
        Gltf.Material d = g.material(0);
        assertArrayEquals(new float[] {1, 1, 1, 1}, d.baseColorFactor());
        assertEquals(1f, d.metallicFactor());
        assertEquals(1f, d.roughnessFactor());
        assertEquals(-1, d.baseColorTexture());
        assertEquals("OPAQUE", d.alphaMode());
        assertEquals(0.5f, d.alphaCutoff());
        assertFalse(d.doubleSided());
        assertArrayEquals(new float[] {0, 0, 0}, d.emissiveFactor());
        Gltf.Material m = g.material(1);
        assertEquals("metal", m.name());
        assertArrayEquals(new float[] {0.5f, 0.25f, 0.125f, 1f}, m.baseColorFactor());
        assertEquals(2, m.baseColorTexture());
        assertEquals(1, m.baseColorTexCoord());
        assertEquals(0f, m.metallicFactor());
        assertEquals(0.25f, m.roughnessFactor());
        assertEquals(3, m.metallicRoughnessTexture());
        assertEquals(4, m.normalTexture());
        assertEquals(2f, m.normalScale());
        assertEquals(5, m.occlusionTexture());
        assertEquals(0.5f, m.occlusionStrength());
        assertArrayEquals(new float[] {1, 0.5f, 0}, m.emissiveFactor());
        assertEquals(6, m.emissiveTexture());
        assertEquals("MASK", m.alphaMode());
        assertTrue(m.doubleSided());
        assertEquals(new Gltf.Texture(1, 0), g.texture(0));
        assertEquals(new Gltf.Texture(-1, -1), g.texture(1));
        assertEquals(9729, g.sampler(0).magFilter());
        assertEquals(33071, g.sampler(0).wrapS());
        assertEquals(10497, g.sampler(0).wrapT());
        assertEquals("a.png", g.image(0).uri());
        assertEquals("image/png", g.image(1).mimeType());
    }

    @Test
    void imageBytesFromADataUriAndABufferView() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":8,\"uri\":\"" + dataUri(png) + "\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":4,\"byteLength\":4}],\"images\":[{\"uri\":\"" + dataUri(png) + "\"},{\"bufferView\":0,\"mimeType\":\"image/png\"},{}]}";
        Gltf g = parse(json);
        assertArrayEquals(png, g.imageBytes(0));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, g.imageBytes(1));
        assertThrows(GltfException.class, () -> g.imageBytes(2));
    }

    @Test
    void nodesComposeTheirTransformsDownTheTree() {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":["
                + "{\"name\":\"root\",\"translation\":[1,2,3],\"children\":[1,2]},"
                + "{\"scale\":[2,2,2],\"rotation\":[0,0,0.7071068,0.7071068],\"translation\":[1,0,0]},"
                + "{\"matrix\":[1,0,0,0, 0,1,0,0, 0,0,1,0, 5,6,7,1]}]}";
        Gltf g = parse(json);
        assertEquals(0, g.defaultScene());
        assertArrayEquals(new int[] {0}, g.sceneNodes(0));
        Mat4f[] w = g.worldMatrices();
        // node 1: translate(1,2,3) * translate(1,0,0) * rotateZ(90) * scale(2): the point (1,0,0) goes to (2,2,3) + (0,2,0)
        Vec3f p = w[1].transformPosition(new Vec3f(1f, 0f, 0f));
        assertEquals(2f, p.x(), 1e-5f);
        assertEquals(4f, p.y(), 1e-5f);
        assertEquals(3f, p.z(), 1e-5f);
        // node 2 has a plain matrix under the root: translation (1,2,3) + (5,6,7)
        Vec3f q = w[2].transformPosition(new Vec3f(0f, 0f, 0f));
        assertEquals(6f, q.x(), 1e-5f);
        assertEquals(8f, q.y(), 1e-5f);
        assertEquals(10f, q.z(), 1e-5f);
        assertArrayEquals(new float[] {1, 2, 3}, g.node(0).translation());
        assertArrayEquals(new float[] {1, 1, 1}, g.node(0).scale());
        assertNotNull(g.node(2).matrix());
    }

    @Test
    void badNodeGraphsAreRejected() {
        String twoParents = "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"children\":[2]},{\"children\":[2]},{}]}";
        assertThrows(GltfException.class, () -> parse(twoParents));
        String cycle = "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"children\":[1]},{\"children\":[0]}]}";
        assertThrows(GltfException.class, () -> parse(cycle));
        String self = "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"children\":[0]}]}";
        assertThrows(GltfException.class, () -> parse(self));
        String missing = "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"children\":[9]}]}";
        assertThrows(GltfException.class, () -> parse(missing));
        String badScene = "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{}],\"scenes\":[{\"nodes\":[3]}]}";
        assertThrows(GltfException.class, () -> parse(badScene));
    }

    @Test
    void aVeryDeepChainDoesNotOverflowTheStack() {
        int n = 20_000;
        StringBuilder sb = new StringBuilder("{\"asset\":{\"version\":\"2.0\"},\"nodes\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"translation\":[0,1,0]").append(i + 1 < n ? ",\"children\":[" + (i + 1) + "]" : "").append("}");
        }
        sb.append("]}");
        // the JSON itself is flat (an array of nodes), so this exercises the node walk, not the parser depth
        Gltf g = parse(sb.toString());
        Mat4f[] w = g.worldMatrices();
        assertEquals(n, w[n - 1].transformPosition(new Vec3f(0f, 0f, 0f)).y(), 0.5f);
    }

    // ---------------------------------------------------------------- skins and animation

    /** Three joints in a chain (node 0 root, 1 child of 0, 2 child of 1) listed in the skin as [2, 0, 1]; each is 1 unit up from its parent. */
    private static String skinDocument(Bin bin, String extraAccessors, String extraRest) {
        return gltf(bin.bytes(), "\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + bin.size() + "}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"MAT4\"}" + extraAccessors + "],"
                + "\"nodes\":[{\"name\":\"hips\",\"translation\":[0,1,0],\"children\":[1]},{\"name\":\"spine\",\"translation\":[0,1,0],\"children\":[2]},{\"name\":\"head\",\"translation\":[0,1,0]}],"
                + "\"skins\":[{\"name\":\"skin\",\"joints\":[2,0,1],\"inverseBindMatrices\":0}]" + extraRest);
    }

    private static Bin inverseBinds() {
        // inverse bind matrices in the order of the skin's joints [head (y=3), hips (y=1), spine (y=2)]: translation by -world y
        Bin b = new Bin();
        for (float y : new float[] {3, 1, 2}) {
            b.f(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -y, 0, 1);
        }
        return b;
    }

    @Test
    void skinJointsAreReorderedParentsFirst() {
        Gltf g = parse(skinDocument(inverseBinds(), "", ""));
        assertEquals(1, g.skinCount());
        Gltf.SkinData s = g.skin(0);
        assertEquals("skin", s.name());
        assertEquals(3, s.skeleton().jointCount());
        assertArrayEquals(new int[] {0, 1, 2}, s.jointNodes(), "hips, spine, head");
        assertArrayEquals(new int[] {2, 0, 1}, s.skinToSkeleton(), "skin entry 0 is the head, now joint 2");
        assertEquals(-1, s.skeleton().parent(0));
        assertEquals(0, s.skeleton().parent(1));
        assertEquals(1, s.skeleton().parent(2));
        assertEquals("spine", s.skeleton().name(1));
        // the file's inverse bind matrices, put in skeleton order, agree with the ones the skeleton derives from the bind pose
        float[] own = s.skeleton().inverseBindMatrices();
        assertArrayEquals(own, s.inverseBindMatrices(), 1e-5f);
        assertTrue(g.skin(0) == s, "cached");
    }

    @Test
    void jointsAndWeightsOfAPrimitive() {
        Bin b = inverseBinds();
        int at = b.size();
        b.f(0, 0, 0, 1, 0, 0, 0, 1, 0);                    // 3 positions
        int jointsAt = b.size();
        b.u8(0, 1, 0, 0, 1, 2, 0, 0, 2, 2, 2, 2);          // JOINTS_0 ubyte x4 x3
        int weightsAt = b.size();
        b.u8(255, 0, 0, 0, 128, 127, 0, 0, 64, 64, 64, 63); // WEIGHTS_0 normalized ubyte
        String acc = ",{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},{\"bufferView\":2,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"},"
                + "{\"bufferView\":3,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}";
        String json = skinDocument(b, acc, ",\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":1,\"JOINTS_0\":2,\"WEIGHTS_0\":3}}]}]")
                .replace("\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + b.size() + "}]",
                        "\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + at + "},{\"buffer\":0,\"byteOffset\":" + at + ",\"byteLength\":36},"
                                + "{\"buffer\":0,\"byteOffset\":" + jointsAt + ",\"byteLength\":12},{\"buffer\":0,\"byteOffset\":" + weightsAt + ",\"byteLength\":12}]");
        Gltf g = parse(json);
        Gltf.VertexSkinning vs = g.readSkinning(0, 0);
        assertArrayEquals(new int[] {0, 1, 0, 0, 1, 2, 0, 0, 2, 2, 2, 2}, vs.joints());
        assertEquals(1f, vs.weights()[0], 1e-6f);
        assertEquals(128f / 255f + 127f / 255f, vs.weights()[4] + vs.weights()[5], 1e-5f);
        // remapping a file joint index to the skeleton
        int[] remap = g.skin(0).skinToSkeleton();
        assertEquals(2, remap[vs.joints()[0]], "vertex 0 is bound to the head");
        assertEquals(0, remap[vs.joints()[1]]);
        assertThrows(GltfException.class, () -> triangle().readSkinning(0, 0), "a primitive without joints");
    }

    @Test
    void skinErrors() {
        String badJoint = skinDocument(inverseBinds(), "", "").replace("\"joints\":[2,0,1]", "\"joints\":[2,0,7]");
        assertThrows(GltfException.class, () -> parse(badJoint).skin(0));
        String repeated = skinDocument(inverseBinds(), "", "").replace("\"joints\":[2,0,1]", "\"joints\":[2,0,2]");
        assertThrows(GltfException.class, () -> parse(repeated).skin(0));
        // a node that is not a joint between two joints: the joints 0 and 2 only
        String gap = skinDocument(inverseBinds(), "", "").replace("\"joints\":[2,0,1]", "\"joints\":[2,0]");
        assertThrows(GltfException.class, () -> parse(gap).skin(0));
        String noJoints = skinDocument(inverseBinds(), "", "").replace("\"joints\":[2,0,1]", "\"joints\":[]");
        assertThrows(GltfException.class, () -> parse(noJoints).skin(0));
        String zeroScale = skinDocument(inverseBinds(), "", "").replace("\"name\":\"head\",", "\"name\":\"head\",\"scale\":[0,1,1],");
        assertThrows(GltfException.class, () -> parse(zeroScale).skin(0), "a joint that cannot be inverted");
    }

    @Test
    void animationChannelsBecomeTracks() {
        Bin b = inverseBinds();
        int t0 = b.size();
        b.f(0, 1, 2);                                   // times
        int v0 = b.size();
        b.f(0, 0, 0, 10, 0, 0, 10, 10, 0);              // translation of the hips, LINEAR
        int v1 = b.size();
        b.f(0, 0, 0, 1, 0, 0, 0.7071068f, 0.7071068f);  // rotation (x y z w) x2 of the spine, STEP: identity then 90 degrees about z
        int t1 = b.size();
        b.f(0, 1);
        int v2 = b.size();
        b.f(5, 5, 5);                                   // a channel for a node that is not a joint
        String acc = ",{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":3,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":2,\"type\":\"VEC4\"},"
                + "{\"bufferView\":5,\"componentType\":5126,\"count\":1,\"type\":\"VEC3\"}";
        String anim = "{\"name\":\"walk\",\"samplers\":[{\"input\":1,\"output\":2},{\"input\":3,\"output\":4,\"interpolation\":\"STEP\"},{\"input\":3,\"output\":5}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}},{\"sampler\":1,\"target\":{\"node\":1,\"path\":\"rotation\"}},"
                + "{\"sampler\":2,\"target\":{\"node\":3,\"path\":\"scale\"}},{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"weights\"}}]}";
        // one buffer view per accessor range
        String json = gltf(b.bytes(), "\"bufferViews\":[{\"buffer\":0,\"byteLength\":192},{\"buffer\":0,\"byteOffset\":" + t0 + ",\"byteLength\":12},"
                + "{\"buffer\":0,\"byteOffset\":" + v0 + ",\"byteLength\":36},{\"buffer\":0,\"byteOffset\":" + t1 + ",\"byteLength\":8},"
                + "{\"buffer\":0,\"byteOffset\":" + v1 + ",\"byteLength\":32},{\"buffer\":0,\"byteOffset\":" + v2 + ",\"byteLength\":12}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"MAT4\"}" + acc + "],"
                + "\"nodes\":[{\"name\":\"hips\",\"translation\":[0,1,0],\"children\":[1]},{\"name\":\"spine\",\"translation\":[0,1,0],\"children\":[2]},{\"name\":\"head\",\"translation\":[0,1,0]},{\"name\":\"other\"}],"
                + "\"skins\":[{\"joints\":[0,1,2]}],\"animations\":[" + anim + "]");
        Gltf g = parse(json);
        assertEquals(1, g.animationCount());
        assertEquals("walk", g.animationName(0));
        Gltf.SkinData skin = g.skin(0);
        AnimationClip clip = g.clip(0, skin);
        assertEquals(2, clip.trackCount(), "translation of the hips and rotation of the spine; the scale of a non-joint and the weights channel are ignored");
        assertEquals(2f, clip.duration(), 1e-6f);
        ClipSampler sampler = new ClipSampler(clip);
        Pose pose = new Pose(skin.skeleton());
        sampler.sample(0.5f, false, pose);
        assertEquals(5f, pose.data()[0], 1e-5f, "linear halfway between 0 and 10");
        sampler.sample(1.5f, false, pose);
        assertEquals(10f, pose.data()[0], 1e-5f);
        assertEquals(5f, pose.data()[1], 1e-5f);
        // the spine (joint 1): STEP holds the first rotation until just before the second key
        ClipSampler s2 = new ClipSampler(clip);
        s2.sample(0.5f, false, pose);
        assertEquals(1f, pose.data()[10 + 6], 1e-5f, "identity w");
        assertEquals(0f, pose.data()[10 + 5], 1e-5f);
        s2.sample(1.0f, false, pose);
        assertEquals(0.7071068f, pose.data()[10 + 5], 1e-4f, "at the second key the rotation has jumped");
    }

    @Test
    void cubicSplineCurvesAreResampled() {
        Bin b = inverseBinds();
        int t0 = b.size();
        b.f(0, 1);
        int v0 = b.size();
        // CUBICSPLINE translation of the hips: per key (in-tangent, value, out-tangent); zero tangents give a smoothstep from (0,0,0) to (4,0,0)
        b.f(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0);
        String anim = "{\"samplers\":[{\"input\":1,\"output\":2,\"interpolation\":\"CUBICSPLINE\"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}";
        String json = gltf(b.bytes(), "\"bufferViews\":[{\"buffer\":0,\"byteLength\":192},{\"buffer\":0,\"byteOffset\":" + t0 + ",\"byteLength\":8},"
                + "{\"buffer\":0,\"byteOffset\":" + v0 + ",\"byteLength\":72}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"MAT4\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},{\"bufferView\":2,\"componentType\":5126,\"count\":6,\"type\":\"VEC3\"}],"
                + "\"nodes\":[{\"children\":[1]},{\"children\":[2]},{}],\"skins\":[{\"joints\":[0,1,2]}],\"animations\":[" + anim + "]");
        Gltf g = parse(json);
        Gltf.SkinData skin = g.skin(0);
        AnimationClip coarse = g.clip(0, skin, 4f);
        AnimationClip fine = g.clip(0, skin, 60f);
        assertTrue(fine.keyCount() > coarse.keyCount() && coarse.keyCount() >= 5, "keys: " + coarse.keyCount() + " vs " + fine.keyCount());
        Pose pose = new Pose(skin.skeleton());
        new ClipSampler(fine).sample(0.5f, false, pose);
        assertEquals(2f, pose.data()[0], 1e-3f, "zero tangents: the midpoint is the average");
        new ClipSampler(fine).sample(0.25f, false, pose);
        float s = 0.25f;
        assertEquals(4f * (-2 * s * s * s + 3 * s * s), pose.data()[0], 0.02f, "smoothstep (the fine clip is piecewise linear)");
        new ClipSampler(fine).sample(1f, false, pose);
        assertEquals(4f, pose.data()[0], 1e-5f);
        assertThrows(IllegalArgumentException.class, () -> g.clip(0, skin, 0f));
    }

    @Test
    void animationErrors() {
        Bin b = inverseBinds();
        int t0 = b.size();
        b.f(0, 1);
        int v0 = b.size();
        b.f(0, 0, 0, 1, 1, 1, 2, 2, 2);
        String views = "\"bufferViews\":[{\"buffer\":0,\"byteLength\":192},{\"buffer\":0,\"byteOffset\":" + t0 + ",\"byteLength\":8},{\"buffer\":0,\"byteOffset\":" + v0
                + ",\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"MAT4\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"nodes\":[{\"children\":[1]},{\"children\":[2]},{}],\"skins\":[{\"joints\":[0,1,2]}],";
        String wrongCount = gltf(b.bytes(), views + "\"animations\":[{\"samplers\":[{\"input\":1,\"output\":2}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}]");
        Gltf g = parse(wrongCount);
        assertThrows(GltfException.class, () -> g.clip(0, g.skin(0)), "2 keys but 3 values");
        String duplicate = gltf(b.bytes(), views + "\"animations\":[{\"samplers\":[{\"input\":1,\"output\":2}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}},"
                + "{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}]");
        Gltf d = parse(duplicate);
        assertThrows(GltfException.class, () -> d.clip(0, d.skin(0)));
        String badSampler = gltf(b.bytes(), views + "\"animations\":[{\"samplers\":[],\"channels\":[{\"sampler\":4,\"target\":{\"node\":0,\"path\":\"translation\"}}]}]");
        Gltf s = parse(badSampler);
        assertThrows(GltfException.class, () -> s.clip(0, s.skin(0)));
        String badInterp = gltf(b.bytes(), views + "\"animations\":[{\"samplers\":[{\"input\":1,\"output\":2,\"interpolation\":\"WOBBLE\"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}]");
        Gltf w = parse(badInterp);
        assertThrows(GltfException.class, () -> w.clip(0, w.skin(0)));
    }

    // ---------------------------------------------------------------- hostile input

    /** Everything a consumer might do with a loaded file; only GltfException may come out. */
    private static void useEverything(Gltf g) {
        for (int a = 0; a < g.accessorCount(); a++) {
            try {
                g.readFloats(a);
            } catch (GltfException expected) {
                // fine
            }
        }
        for (int m = 0; m < g.meshCount(); m++) {
            for (int p = 0; p < g.mesh(m).primitives().size(); p++) {
                try {
                    g.toMesh(m, p);
                } catch (GltfException expected) {
                    // fine
                }
                try {
                    g.readSkinning(m, p);
                } catch (GltfException expected) {
                    // fine
                }
            }
        }
        g.worldMatrices();
        for (int s = 0; s < g.skinCount(); s++) {
            try {
                Gltf.SkinData skin = g.skin(s);
                for (int an = 0; an < g.animationCount(); an++) {
                    g.clip(an, skin);
                }
            } catch (GltfException expected) {
                // fine
            }
        }
        for (int i = 0; i < g.imageCount(); i++) {
            try {
                g.imageBytes(i);
            } catch (GltfException expected) {
                // fine
            }
        }
    }

    @Test
    void corruptedFilesOnlyEverRaiseGltfException() {
        // a file with a mesh, a skin and an animation, as JSON and as GLB
        Bin b = inverseBinds();
        int at = b.size();
        b.f(0, 0, 0, 1, 0, 0, 0, 1, 0).u16(0, 1, 2).align4();
        int jointsAt = b.size();
        b.u8(0, 1, 0, 0, 1, 2, 0, 0, 2, 2, 2, 2).f(1, 0, 0, 0, 0.5f, 0.5f, 0, 0, 0.25f, 0.25f, 0.25f, 0.25f);
        int timesAt = b.size();
        b.f(0, 1, 2, 0, 0, 0, 1, 1, 1, 2, 2, 2);
        String json = gltf(b.bytes(), "\"bufferViews\":[{\"buffer\":0,\"byteLength\":192},{\"buffer\":0,\"byteOffset\":" + at + ",\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":" + (at + 36) + ",\"byteLength\":6},{\"buffer\":0,\"byteOffset\":" + jointsAt + ",\"byteLength\":12},"
                + "{\"buffer\":0,\"byteOffset\":" + (jointsAt + 12) + ",\"byteLength\":48},{\"buffer\":0,\"byteOffset\":" + timesAt + ",\"byteLength\":12},"
                + "{\"buffer\":0,\"byteOffset\":" + (timesAt + 12) + ",\"byteLength\":36}],\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"MAT4\"},{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"},{\"bufferView\":3,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"},{\"bufferView\":5,\"componentType\":5126,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":6,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":1,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":2,\"material\":0}]}],\"materials\":[{}],"
                + "\"nodes\":[{\"children\":[1],\"translation\":[0,1,0]},{\"children\":[2],\"translation\":[0,1,0]},{\"translation\":[0,1,0]}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"skins\":[{\"joints\":[2,0,1],\"inverseBindMatrices\":0}],\"animations\":[{\"samplers\":[{\"input\":5,\"output\":6}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}],\"images\":[{\"bufferView\":1}]");
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        useEverything(Gltf.parse(jsonBytes, null)); // the unmodified file works
        byte[] glbBytes = glb(json.replace(dataUri(b.bytes()), "x").replaceFirst("\"uri\":\"x\",?", ""), b.bytes());
        java.util.Random rnd = new java.util.Random(12345);
        int accepted = 0, rejected = 0;
        for (byte[] original : new byte[][] {jsonBytes, glbBytes}) {
            for (int trial = 0; trial < 20000; trial++) {
                byte[] d = original.clone();
                int edits = 1 + rnd.nextInt(4);
                for (int e = 0; e < edits; e++) {
                    int pos = rnd.nextInt(d.length);
                    switch (rnd.nextInt(3)) {
                        case 0 -> d[pos] = (byte) rnd.nextInt(256);
                        case 1 -> d[pos] = (byte) "0123456789-[]{}\",:".charAt(rnd.nextInt(18));
                        default -> d[pos] ^= (byte) (1 << rnd.nextInt(8));
                    }
                }
                byte[] mutated = rnd.nextInt(10) == 0 ? java.util.Arrays.copyOf(d, rnd.nextInt(d.length)) : d;
                try {
                    Gltf g = Gltf.parse(mutated, null);
                    useEverything(g);
                    accepted++;
                } catch (GltfException expected) {
                    rejected++;
                }
            }
        }
        assertTrue(accepted > 100 && rejected > 100, "both outcomes must occur: " + accepted + " accepted, " + rejected + " rejected");
    }

    // ---------------------------------------------------------------- files on disk

    @Test
    void loadsAFileWithAnExternalBuffer(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("tri data.bin"), triangleBuffer());
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":104,\"uri\":\"tri%20data.bin\"}]," + TRIANGLE_REST + "}";
        Files.writeString(dir.resolve("tri.gltf"), json);
        Gltf g = Gltf.load(dir.resolve("tri.gltf"));
        assertEquals(1f, g.toMesh(0, 0).positions()[3]);
        // a .glb on disk
        String embedded = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":104}]," + TRIANGLE_REST + "}";
        Files.write(dir.resolve("tri.glb"), glb(embedded, triangleBuffer()));
        assertEquals(1f, Gltf.load(dir.resolve("tri.glb")).toMesh(0, 0).positions()[3]);
    }

    @Test
    void aBufferUriCannotLeaveTheDirectory(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectory(dir.resolve("models"));
        Files.write(dir.resolve("secret.bin"), triangleBuffer());
        for (String uri : new String[] {"../secret.bin", "..%2Fsecret.bin", "sub/../../secret.bin"}) {
            String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":104,\"uri\":\"" + uri + "\"}]}";
            Files.writeString(sub.resolve("evil.gltf"), json);
            assertThrows(GltfException.class, () -> Gltf.load(sub.resolve("evil.gltf")), uri);
        }
        assertFalse(Files.exists(sub.resolve("secret.bin")));
    }
}
