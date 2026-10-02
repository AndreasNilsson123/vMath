package vmath.gl;

import java.nio.FloatBuffer;
import vmath.core.Mat3f;
import vmath.core.Mat4f;
import vmath.core.Vec3f;

/**
 * Writers for GLSL {@code std140} uniform-block layout, where it differs from tight packing.
 *
 * <p>Indices are in floats, not bytes. {@code vec3} and each {@code mat3} column occupy a 16-byte
 * slot (4 floats). Callers are responsible for the base alignment of the member itself.
 *
 * <p>For whole blocks (std140, std430 and scalar layouts, arrays, nested structs, generated writers) use {@link GlslType},
 * {@link StructLayout} and {@link GpuWriter} instead; this class covers only three member types.
 *
 * @deprecated superseded by {@link GlslType}, {@link StructLayout}, {@link GpuWriter} and the generated {@code @GpuStruct} writers, which cover every member type and layout;
 *             it will be removed in a later release
 */
@Deprecated(since = "0.2.0", forRemoval = true)
public final class Std140 {

    private Std140() {
    }

    /** Floats occupied by a {@code vec3} member (padded). */
    public static final int VEC3_FLOATS = 4;
    /** Floats occupied by a {@code mat3} member (3 padded columns). */
    public static final int MAT3_FLOATS = 12;
    /** Floats occupied by a {@code mat4} member. */
    public static final int MAT4_FLOATS = 16;

    /** Writes {@code v} into a 4-float slot, zeroing the pad. */
    public static void putVec3(FloatBuffer dst, int index, Vec3f v) {
        v.writeTo(dst, index);
        dst.put(index + 3, 0f);
    }

    /** Writes {@code m} as three padded {@code vec4} columns. */
    public static void putMat3(FloatBuffer dst, int index, Mat3f m) {
        for (int c = 0; c < 3; c++) {
            putVec3(dst, index + 4 * c, m.column(c));
        }
    }

    public static void putMat4(FloatBuffer dst, int index, Mat4f m) {
        m.writeTo(dst, index);
    }
}
