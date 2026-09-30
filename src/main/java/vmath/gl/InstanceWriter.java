package vmath.gl;

import java.lang.foreign.MemorySegment;
import vmath.core.Mat4x3f;

/**
 * Writes per-instance data for instanced and indirect drawing into a {@link MemorySegment}: an affine transform and a word of user data
 * (a material index, a flag mask, an entity id).
 *
 * <p><b>Layout</b> (64 bytes per instance, so an array of them has a stride that works in std140, std430 and scalar layouts and stays 16-byte aligned):
 * <pre>
 *   offset  0: vec4 row0   (m00, m10, m20, translation x)
 *   offset 16: vec4 row1   (m01, m11, m21, translation y)
 *   offset 32: vec4 row2   (m02, m12, m22, translation z)
 *   offset 48: uint userData
 *   offset 52: 12 bytes of padding
 * </pre>
 * Three rows of a {@code vec4} rather than a {@code mat4x3} (whose columns would be padded to 16 bytes): in the shader the world position is
 * {@code vec3(dot(row0, p), dot(row1, p), dot(row2, p))} with {@code p = vec4(position, 1)}. That is 48 bytes for the transform instead of 64.
 *
 * <p>Nothing here allocates. The padding is never written.
 */
public final class InstanceWriter {

    /** Bytes between consecutive instances. */
    public static final long STRIDE = 64;
    /** Bytes of the transform rows. */
    public static final long TRANSFORM_BYTES = 48;
    /** Offset of the user-data word. */
    public static final long OFFSET_USER_DATA = 48;

    private InstanceWriter() {
    }

    /** Writes the three transform rows of {@code m} (12 floats) at byte offset {@code offset}. */
    public static void writeTransform(MemorySegment dst, long offset, Mat4x3f m) {
        GpuWriter.putFloat(dst, offset, m.m00());
        GpuWriter.putFloat(dst, offset + 4, m.m10());
        GpuWriter.putFloat(dst, offset + 8, m.m20());
        GpuWriter.putFloat(dst, offset + 12, m.m30());
        GpuWriter.putFloat(dst, offset + 16, m.m01());
        GpuWriter.putFloat(dst, offset + 20, m.m11());
        GpuWriter.putFloat(dst, offset + 24, m.m21());
        GpuWriter.putFloat(dst, offset + 28, m.m31());
        GpuWriter.putFloat(dst, offset + 32, m.m02());
        GpuWriter.putFloat(dst, offset + 36, m.m12());
        GpuWriter.putFloat(dst, offset + 40, m.m22());
        GpuWriter.putFloat(dst, offset + 44, m.m32());
    }

    /** Writes instance number {@code index} (transform and user data) at {@code index * STRIDE}. */
    public static void write(MemorySegment dst, long index, Mat4x3f m, int userData) {
        long base = index * STRIDE;
        writeTransform(dst, base, m);
        GpuWriter.putInt(dst, base + OFFSET_USER_DATA, userData);
    }
}
