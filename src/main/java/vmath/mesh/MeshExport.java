package vmath.mesh;

import java.lang.foreign.MemorySegment;
import vmath.core.Vec3f;
import vmath.gl.GpuWriter;
import vmath.pack.Half;
import vmath.pack.Octahedral;
import vmath.pack.Quantizer;
import vmath.pack.UvQuantizer;

/**
 * Writes a {@link Mesh} into GPU-ready buffers: interleaved vertices as described by a {@link VertexLayout}, and 16- or 32-bit indices.
 * Everything goes straight from the mesh's arrays into a {@link MemorySegment} (use {@link GpuWriter#of(java.nio.ByteBuffer)} to wrap a
 * {@code ByteBuffer}), in native byte order, with no per-vertex objects. A stream the layout asks for but the mesh lacks is an error,
 * not silently zero.
 */
public final class MeshExport {

    private MeshExport() {
    }

    /** Bytes {@link #writeVertices} will write for this mesh. */
    public static long vertexBytes(Mesh mesh, VertexLayout layout) {
        return (long) mesh.vertexCount() * layout.stride();
    }

    /** Bytes {@link #writeIndices32} writes for this mesh. */
    public static long indexBytes32(Mesh mesh) {
        return 4L * mesh.indexCount();
    }

    /** Bytes {@link #writeIndices16} writes for this mesh. */
    public static long indexBytes16(Mesh mesh) {
        return 2L * mesh.indexCount();
    }

    /**
     * The quantizer {@link #writeVertices} uses for a {@link VertexLayout.Format#POSITION_UNORM16X4} attribute: unorm16 over the mesh's bounding box. Its
     * {@code dequantizationMatrix()} turns the normalized values the GPU reads back into model space.
     *
     * @throws IllegalStateException if the mesh has no vertices
     */
    public static Quantizer positionQuantizer(Mesh mesh) {
        if (mesh.vertexCount() == 0) {
            throw new IllegalStateException("an empty mesh has no bounding box to quantize into");
        }
        return new Quantizer(mesh.bounds());
    }

    /**
     * The quantizer {@link #writeVertices} uses for a {@link VertexLayout.Format#UV_UNORM16X2} attribute of the given set: unorm16 over the smallest rectangle around
     * its texture coordinates. {@code minU() + sizeU() * code / 65535} restores a coordinate (in the shader: the normalized value times the size plus the minimum).
     */
    public static UvQuantizer uvQuantizer(Mesh mesh, int set) {
        require(mesh.hasUvs(set), "uv set " + set);
        return UvQuantizer.fit(mesh.uvs(set), mesh.vertexCount(), 16);
    }

    /** Writes every vertex, interleaved, starting at {@code offset}. Returns the offset just past the last byte written. */
    public static long writeVertices(Mesh mesh, VertexLayout layout, MemorySegment dst, long offset) {
        int stride = layout.stride();
        for (VertexLayout.Attribute a : layout.attributes()) {
            switch (a.format()) {
                case NORMAL_F32X3, NORMAL_OCT16 -> require(mesh.hasNormals(), "normals");
                case TANGENT_F32X4 -> require(mesh.hasTangents(), "tangents");
                case UV_F32X2, UV_HALF2, UV_UNORM16X2 -> require(mesh.hasUvs(a.uvSet()), "uv set " + a.uvSet());
                default -> { }
            }
        }
        if (dst.byteSize() < offset + (long) mesh.vertexCount() * stride) {
            throw new IllegalArgumentException("destination too small: need " + ((long) mesh.vertexCount() * stride) + " bytes from " + offset);
        }
        float[] pos = mesh.positions();
        Quantizer positionQuantizer = null;
        UvQuantizer[] uvQuantizers = new UvQuantizer[Mesh.MAX_UV_SETS];
        for (VertexLayout.Attribute a : layout.attributes()) {
            if (a.format() == VertexLayout.Format.POSITION_UNORM16X4 && positionQuantizer == null && mesh.vertexCount() > 0) {
                positionQuantizer = positionQuantizer(mesh);
            } else if (a.format() == VertexLayout.Format.UV_UNORM16X2 && uvQuantizers[a.uvSet()] == null && mesh.vertexCount() > 0) {
                uvQuantizers[a.uvSet()] = uvQuantizer(mesh, a.uvSet());
            }
        }
        short[] scratch = new short[3];
        for (int v = 0; v < mesh.vertexCount(); v++) {
            long base = offset + (long) v * stride;
            for (VertexLayout.Attribute a : layout.attributes()) {
                long o = base + a.offset();
                switch (a.format()) {
                    case POSITION_F32X3 -> put3(dst, o, pos, v * 3);
                    case POSITION_UNORM16X4 -> {
                        positionQuantizer.pack(new Vec3f(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]), scratch, 0);
                        dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, o, scratch[0]);
                        dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, o + 2, scratch[1]);
                        dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, o + 4, scratch[2]);
                        dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, o + 6, (short) 0xFFFF);
                    }
                    case NORMAL_F32X3 -> put3(dst, o, mesh.normals(), v * 3);
                    case NORMAL_OCT16 -> {
                        float[] n = mesh.normals();
                        GpuWriter.putInt(dst, o, Octahedral.pack16(new Vec3f(n[v * 3], n[v * 3 + 1], n[v * 3 + 2])));
                    }
                    case TANGENT_F32X4 -> {
                        float[] t = mesh.tangents();
                        for (int k = 0; k < 4; k++) {
                            GpuWriter.putFloat(dst, o + 4L * k, t[v * 4 + k]);
                        }
                    }
                    case UV_F32X2 -> {
                        float[] uv = mesh.uvs(a.uvSet());
                        GpuWriter.putFloat(dst, o, uv[v * 2]);
                        GpuWriter.putFloat(dst, o + 4, uv[v * 2 + 1]);
                    }
                    case UV_UNORM16X2 -> {
                        float[] uv = mesh.uvs(a.uvSet());
                        UvQuantizer uq = uvQuantizers[a.uvSet()];
                        dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, o, (short) uq.quantizeU(uv[v * 2]));
                        dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, o + 2, (short) uq.quantizeV(uv[v * 2 + 1]));
                    }
                    case UV_HALF2 -> {
                        float[] uv = mesh.uvs(a.uvSet());
                        GpuWriter.putInt(dst, o, Half.pack2(uv[v * 2], uv[v * 2 + 1]));
                    }
                }
            }
        }
        return offset + (long) mesh.vertexCount() * stride;
    }

    /** Writes the triangle indices as 32-bit integers. Returns the offset just past the last byte written. */
    public static long writeIndices32(Mesh mesh, MemorySegment dst, long offset) {
        if (dst.byteSize() < offset + indexBytes32(mesh)) {
            throw new IllegalArgumentException("destination too small: need " + indexBytes32(mesh) + " bytes from " + offset);
        }
        int[] idx = mesh.indices();
        for (int i = 0; i < mesh.indexCount(); i++) {
            GpuWriter.putInt(dst, offset + 4L * i, idx[i]);
        }
        return offset + indexBytes32(mesh);
    }

    /**
     * Writes the triangle indices as unsigned 16-bit integers (two bytes each). Throws if the mesh has more than 65536 vertices,
     * because their indices would not fit.
     */
    public static long writeIndices16(Mesh mesh, MemorySegment dst, long offset) {
        if (mesh.vertexCount() > 65536) {
            throw new IllegalArgumentException("16-bit indices cannot address " + mesh.vertexCount() + " vertices");
        }
        if (dst.byteSize() < offset + indexBytes16(mesh)) {
            throw new IllegalArgumentException("destination too small: need " + indexBytes16(mesh) + " bytes from " + offset);
        }
        int[] idx = mesh.indices();
        for (int i = 0; i < mesh.indexCount(); i++) {
            dst.set(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, offset + 2L * i, (short) idx[i]);
        }
        return offset + indexBytes16(mesh);
    }

    private static void put3(MemorySegment dst, long o, float[] a, int i) {
        GpuWriter.putFloat(dst, o, a[i]);
        GpuWriter.putFloat(dst, o + 4, a[i + 1]);
        GpuWriter.putFloat(dst, o + 8, a[i + 2]);
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("the layout needs " + what + " but the mesh has none");
        }
    }
}
