package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.gl.GpuWriter;
import vmath.pack.Half;
import vmath.pack.Octahedral;

class MeshTest {

    /** A unit cube with 8 shared vertices, wound counter-clockwise from outside. */
    static Mesh sharedCube() {
        Mesh m = new Mesh(8, 12);
        for (int i = 0; i < 8; i++) {
            m.addVertex((i & 1) == 0 ? 0f : 1f, (i & 2) == 0 ? 0f : 1f, (i & 4) == 0 ? 0f : 1f);
        }
        int[][] quads = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};
        for (int[] q : quads) {
            m.addQuad(q[0], q[1], q[2], q[3]);
        }
        return m;
    }

    @Test
    void geometryQueriesOnACube() {
        Mesh m = sharedCube();
        assertEquals(8, m.vertexCount());
        assertEquals(12, m.triangleCount());
        assertEquals(36, m.indexCount());
        assertEquals(new Aabbf(0f, 0f, 0f, 1f, 1f, 1f), m.bounds());
        assertEquals(6.0, m.surfaceArea(), 1e-9);
        assertEquals(1.0, Math.abs(m.signedVolume()), 1e-9);
        assertTrue(new Mesh().bounds().isEmpty());
    }

    @Test
    void streamsAreOptionalAndStayInStepWithTheVertices() {
        Mesh m = new Mesh(2, 1);
        assertFalse(m.hasNormals());
        assertNull(m.normals());
        assertThrows(IndexOutOfBoundsException.class, () -> m.setNormal(0, 0f, 1f, 0f), "no such vertex yet");
        m.addVertex(0f, 0f, 0f);
        assertThrows(IllegalStateException.class, () -> m.setNormal(0, 0f, 1f, 0f), "stream is off");
        m.enableNormals();
        m.enableTangents();
        m.enableUvs(0);
        m.enableUvs(2);
        assertTrue(m.hasNormals() && m.hasTangents() && m.hasUvs(0) && m.hasUvs(2) && !m.hasUvs(1));
        for (int i = 0; i < 500; i++) { // forces several reallocations
            int v = m.addVertex(i, 2f * i, 3f * i);
            m.setNormal(v, 0f, 0f, 1f);
            m.setTangent(v, 1f, 0f, 0f, -1f);
            m.setUv(2, v, 0.25f, 0.75f);
        }
        assertEquals(501, m.vertexCount());
        assertTrue(m.normals().length >= 501 * 3 && m.tangents().length >= 501 * 4 && m.uvs(2).length >= 501 * 2);
        assertEquals(1f, m.normals()[500 * 3 + 2]);
        assertEquals(-1f, m.tangents()[500 * 4 + 3]);
        assertEquals(0.75f, m.uvs(2)[500 * 2 + 1]);
        m.disableNormals();
        assertNull(m.normals());
        assertThrows(IllegalArgumentException.class, () -> m.enableUvs(Mesh.MAX_UV_SETS));
        assertThrows(IndexOutOfBoundsException.class, () -> m.setPosition(501, 0f, 0f, 0f));
    }

    @Test
    void clearedMeshesGiveZeroedEntriesAndCopyVertexCopiesEverything() {
        Mesh m = new Mesh();
        m.enableNormals();
        m.enableUvs(0);
        m.addVertex(1f, 2f, 3f, 0f, 1f, 0f, 0.5f, 0.5f);
        m.clear();
        assertEquals(0, m.vertexCount());
        int v = m.addVertex(9f, 9f, 9f);
        assertEquals(0f, m.normals()[v * 3 + 1], "stale data from before clear() must not leak");
        assertEquals(0f, m.uvs(0)[v * 2]);
        int a = m.addVertex(4f, 5f, 6f, 0f, 0f, 1f, 0.1f, 0.2f);
        int b = m.copyVertex(a);
        assertEquals(m.positions()[a * 3 + 1], m.positions()[b * 3 + 1]);
        assertEquals(1f, m.normals()[b * 3 + 2]);
        assertEquals(0.2f, m.uvs(0)[b * 2 + 1]);
    }

    @Test
    void trianglesMustReferToExistingVertices() {
        Mesh m = new Mesh();
        m.addVertex(0f, 0f, 0f);
        assertThrows(IllegalArgumentException.class, () -> m.addTriangle(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> m.addTriangle(-1, 0, 0));
    }

    // ------------------------------------------------------------ export

    @Test
    void interleavedVerticesRoundTripThroughTheirFormats() {
        Mesh m = Primitives.uvSphere(2f, 12, 6);
        VertexLayout layout = VertexLayout.builder().position().normalOct16().tangent().uvHalf(0).uv(0).build();
        assertEquals(12 + 4 + 16 + 4 + 8, layout.stride());
        MemorySegment seg = MemorySegment.ofArray(new byte[(int) MeshExport.vertexBytes(m, layout) + 8]);
        long end = MeshExport.writeVertices(m, layout, seg, 8);
        assertEquals(8 + (long) m.vertexCount() * layout.stride(), end);
        var attrs = layout.attributes();
        for (int v = 0; v < m.vertexCount(); v++) {
            long base = 8 + (long) v * layout.stride();
            assertEquals(m.positions()[v * 3 + 1], GpuWriter.getFloat(seg, base + attrs.get(0).offset() + 4));
            Vec3f n = Octahedral.unpack16(GpuWriter.getInt(seg, base + attrs.get(1).offset()));
            float dot = n.x() * m.normals()[v * 3] + n.y() * m.normals()[v * 3 + 1] + n.z() * m.normals()[v * 3 + 2];
            assertTrue(dot > 0.9999f, "octahedral normal off by too much at vertex " + v);
            assertEquals(m.tangents()[v * 4 + 3], GpuWriter.getFloat(seg, base + attrs.get(2).offset() + 12));
            int halves = GpuWriter.getInt(seg, base + attrs.get(3).offset());
            assertEquals(Half.round(m.uvs(0)[v * 2]), Half.unpack2(halves, 0));
            assertEquals(m.uvs(0)[v * 2 + 1], GpuWriter.getFloat(seg, base + attrs.get(4).offset() + 4));
        }
    }

    @Test
    void indicesAreWrittenAs32And16Bit() {
        Mesh m = sharedCube();
        MemorySegment s32 = MemorySegment.ofArray(new byte[(int) MeshExport.indexBytes32(m)]);
        assertEquals(MeshExport.indexBytes32(m), MeshExport.writeIndices32(m, s32, 0));
        MemorySegment s16 = MemorySegment.ofArray(new byte[(int) MeshExport.indexBytes16(m)]);
        assertEquals(MeshExport.indexBytes16(m), MeshExport.writeIndices16(m, s16, 0));
        int[] idx = m.indices();
        for (int i = 0; i < m.indexCount(); i++) {
            assertEquals(idx[i], GpuWriter.getInt(s32, 4L * i));
            assertEquals(idx[i], Short.toUnsignedInt(s16.get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, 2L * i)));
        }
        assertEquals(4L * 36, MeshExport.indexBytes32(m));
        assertEquals(2L * 36, MeshExport.indexBytes16(m));
    }

    @Test
    void exportRejectsMissingStreamsSmallBuffersAndOversizeIndexRanges() {
        Mesh m = sharedCube();
        VertexLayout needsNormals = VertexLayout.builder().position().normal().build();
        MemorySegment big = MemorySegment.ofArray(new byte[1024]);
        assertThrows(IllegalStateException.class, () -> MeshExport.writeVertices(m, needsNormals, big, 0));
        VertexLayout positionOnly = VertexLayout.builder().position().build();
        assertThrows(IllegalArgumentException.class, () -> MeshExport.writeVertices(m, positionOnly, MemorySegment.ofArray(new byte[10]), 0));
        assertThrows(IllegalArgumentException.class, () -> MeshExport.writeIndices32(m, MemorySegment.ofArray(new byte[10]), 0));
        assertThrows(IllegalStateException.class, () -> VertexLayout.builder().build());
        Mesh huge = new Mesh(70000, 1);
        for (int i = 0; i < 65537; i++) {
            huge.addVertex(i, 0f, 0f);
        }
        assertThrows(IllegalArgumentException.class, () -> MeshExport.writeIndices16(huge, big, 0));
        assertNotNull(positionOnly.attributes());
        assertArrayEquals(new int[] {0}, new int[] {positionOnly.attributes().get(0).offset()});
    }
}
