package vmath.gl;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import vmath.core.Mat3f;
import vmath.core.Mat4f;
import vmath.core.Mat4x3f;
import vmath.core.Quatf;
import vmath.core.Vec2f;
import vmath.core.Vec2i;
import vmath.core.Vec3f;
import vmath.core.Vec3i;
import vmath.core.Vec4f;

/**
 * Typed writes into GPU-bound memory at explicit byte offsets.
 *
 * <p>Offsets come from a {@link StructLayout} (or the constants of a generated {@code <Name>Gpu}
 * class); nothing here inserts padding, so bytes that no member covers are left as they were.
 * Allocate zero-filled memory (for example {@code Arena.allocate}) if the padding must be
 * deterministic.
 *
 * <p>All writes use native byte order and tolerate any alignment, which is what GPUs and
 * {@code glBufferData} expect. Matrices are written column-major, the layout GLSL uses. Matrices
 * and arrays need the distance between columns or elements, which depends on the layout: ask
 * {@link GlslType.Mat#columnStride} or {@link GlslType.Array#stride}.
 *
 * <p>Every method is a static one-liner over {@link MemorySegment}, so it inlines and allocates
 * nothing.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MemorySegment buffer = GpuWriter.of(ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder()));
 * GpuWriter.putMat4(buffer, 0, Mat4f.IDENTITY);                        // 16 floats, column-major
 * GpuWriter.putVec3(buffer, 64, new Vec3f(1f, 2f, 3f));
 * float x = GpuWriter.getFloat(buffer, 64);                            // 1
 * }</pre>
 */
public final class GpuWriter {

    private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED;

    private GpuWriter() {
    }

    /**
     * Wraps a byte buffer (heap or direct) as a segment.
     *
     * <p>The buffer's own byte order is ignored: writes are native-order.
     *
     * @param buffer the buffer; must not be {@code null}
     * @return a segment over the content of the buffer: writes to it show in the buffer
     */
    public static MemorySegment of(ByteBuffer buffer) {
        return MemorySegment.ofBuffer(buffer);
    }

    /**
     * Writes a float at byte {@code offset}.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write
     */
    public static void putFloat(MemorySegment dst, long offset, float v) {
        dst.set(F, offset, v);
    }

    /**
     * Writes an int at byte {@code offset}.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write
     */
    public static void putInt(MemorySegment dst, long offset, int v) {
        dst.set(I, offset, v);
    }

    /**
     * Writes 8 bytes: x, then y.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write; must not be {@code null}
     */
    public static void putVec2(MemorySegment dst, long offset, Vec2f v) {
        dst.set(F, offset, v.x());
        dst.set(F, offset + 4, v.y());
    }

    /**
     * Writes 12 bytes; the fourth component slot of a {@code vec3} is padding and is not touched.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write; must not be {@code null}
     */
    public static void putVec3(MemorySegment dst, long offset, Vec3f v) {
        dst.set(F, offset, v.x());
        dst.set(F, offset + 4, v.y());
        dst.set(F, offset + 8, v.z());
    }

    /**
     * Writes 16 bytes: x, y, z, w.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write; must not be {@code null}
     */
    public static void putVec4(MemorySegment dst, long offset, Vec4f v) {
        dst.set(F, offset, v.x());
        dst.set(F, offset + 4, v.y());
        dst.set(F, offset + 8, v.z());
        dst.set(F, offset + 12, v.w());
    }

    /**
     * Writes a quaternion as a {@code vec4} in {@code x, y, z, w} order.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param q the quaternion; must not be {@code null}
     */
    public static void putQuat(MemorySegment dst, long offset, Quatf q) {
        dst.set(F, offset, q.x());
        dst.set(F, offset + 4, q.y());
        dst.set(F, offset + 8, q.z());
        dst.set(F, offset + 12, q.w());
    }

    /**
     * Writes 8 bytes: x, then y.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write; must not be {@code null}
     */
    public static void putIVec2(MemorySegment dst, long offset, Vec2i v) {
        dst.set(I, offset, v.x());
        dst.set(I, offset + 4, v.y());
    }

    /**
     * Writes 12 bytes; the fourth slot of an {@code ivec3} is padding and is not touched.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param v the value to write; must not be {@code null}
     */
    public static void putIVec3(MemorySegment dst, long offset, Vec3i v) {
        dst.set(I, offset, v.x());
        dst.set(I, offset + 4, v.y());
        dst.set(I, offset + 8, v.z());
    }

    /**
     * Writes a {@code mat4}: 16 contiguous floats, column-major.
     *
     * <p>The same in every layout.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param m the matrix; must not be {@code null}
     */
    public static void putMat4(MemorySegment dst, long offset, Mat4f m) {
        dst.set(F, offset, m.m00());
        dst.set(F, offset + 4, m.m01());
        dst.set(F, offset + 8, m.m02());
        dst.set(F, offset + 12, m.m03());
        dst.set(F, offset + 16, m.m10());
        dst.set(F, offset + 20, m.m11());
        dst.set(F, offset + 24, m.m12());
        dst.set(F, offset + 28, m.m13());
        dst.set(F, offset + 32, m.m20());
        dst.set(F, offset + 36, m.m21());
        dst.set(F, offset + 40, m.m22());
        dst.set(F, offset + 44, m.m23());
        dst.set(F, offset + 48, m.m30());
        dst.set(F, offset + 52, m.m31());
        dst.set(F, offset + 56, m.m32());
        dst.set(F, offset + 60, m.m33());
    }

    /**
     * Writes a {@code mat3} as three column vectors {@code columnStride} bytes apart (16 in
     * std140/std430, 12 in scalar layout; use {@code GlslType.MAT3.columnStride(layout)}).
     *
     * <p>The padding after each column is not touched.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param m the matrix; must not be {@code null}
     * @param columnStride the column stride
     */
    public static void putMat3(MemorySegment dst, long offset, Mat3f m, long columnStride) {
        putVec3(dst, offset, new Vec3f(m.m00(), m.m01(), m.m02()));
        putVec3(dst, offset + columnStride, new Vec3f(m.m10(), m.m11(), m.m12()));
        putVec3(dst, offset + 2 * columnStride, new Vec3f(m.m20(), m.m21(), m.m22()));
    }

    /**
     * Writes a {@code mat4x3} (four columns of three rows, i.e. an affine transform) as four column
     * vectors {@code columnStride} bytes apart, translation last.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param m the matrix; must not be {@code null}
     * @param columnStride the column stride
     */
    public static void putMat4x3(MemorySegment dst, long offset, Mat4x3f m, long columnStride) {
        putVec3(dst, offset, new Vec3f(m.m00(), m.m01(), m.m02()));
        putVec3(dst, offset + columnStride, new Vec3f(m.m10(), m.m11(), m.m12()));
        putVec3(dst, offset + 2 * columnStride, new Vec3f(m.m20(), m.m21(), m.m22()));
        putVec3(dst, offset + 3 * columnStride, new Vec3f(m.m30(), m.m31(), m.m32()));
    }

    // ---------------------------------------------------------------- readers, mainly for tests and debugging

    /**
     * Reads a float at byte {@code offset}.
     *
     * @param src the source to read from; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @return the float at the offset
     */
    public static float getFloat(MemorySegment src, long offset) {
        return src.get(F, offset);
    }

    /**
     * Reads an int at byte {@code offset}.
     *
     * @param src the source to read from; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @return the int at the offset
     */
    public static int getInt(MemorySegment src, long offset) {
        return src.get(I, offset);
    }
}
