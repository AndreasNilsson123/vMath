package vmath.gl;

import java.lang.foreign.MemorySegment;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4x3f;

/**
 * Writes per-instance data for instanced and indirect drawing into a {@link MemorySegment}: an
 * affine transform and a word of user data (a material index, a flag mask, an entity id).
 *
 * <p><b>Layout</b> (64 bytes per instance, so an array of them has a stride that works in std140,
 * std430 and scalar layouts and stays 16-byte aligned):
 * <pre>
 *   offset  0: vec4 row0   (m00, m10, m20, translation x)
 *   offset 16: vec4 row1   (m01, m11, m21, translation y)
 *   offset 32: vec4 row2   (m02, m12, m22, translation z)
 *   offset 48: uint userData
 *   offset 52: 12 bytes of padding
 * </pre>
 * Three rows of a {@code vec4} rather than a {@code mat4x3} (whose columns would be padded to 16
 * bytes): in the shader the world position is
 * {@code vec3(dot(row0, p), dot(row1, p), dot(row2, p))} with {@code p = vec4(position, 1)}. That
 * is 48 bytes for the transform instead of 64.
 *
 * <p>Nothing here allocates. The padding is never written.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 */
public final class InstanceWriter {

    /**
     * Bytes between consecutive instances.
     */
    public static final long STRIDE = 64;
    /**
     * Bytes of the transform rows.
     */
    public static final long TRANSFORM_BYTES = 48;
    /**
     * Offset of the user-data word.
     */
    public static final long OFFSET_USER_DATA = 48;

    private InstanceWriter() {
    }

    /**
     * Writes the three transform rows of {@code m} (12 floats) at byte offset {@code offset}.
     *
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param m the matrix; must not be {@code null}
     */
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

    /**
     * Writes instance number {@code index} (transform and user data) at {@code index * STRIDE}.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     * @param m the matrix; must not be {@code null}
     * @param userData the user data of the object
     */
    public static void write(MemorySegment dst, long index, Mat4x3f m, int userData) {
        long base = index * STRIDE;
        writeTransform(dst, base, m);
        GpuWriter.putInt(dst, base + OFFSET_USER_DATA, userData);
    }

    /**
     * Writes instance number {@code index} with no rotation or scale, only the position (the common
     * case for static props and particles).
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param userData the user data of the object
     */
    public static void writeTranslation(MemorySegment dst, long index, float x, float y, float z, int userData) {
        long base = index * STRIDE;
        GpuWriter.putFloat(dst, base, 1f);
        GpuWriter.putFloat(dst, base + 4, 0f);
        GpuWriter.putFloat(dst, base + 8, 0f);
        GpuWriter.putFloat(dst, base + 12, x);
        GpuWriter.putFloat(dst, base + 16, 0f);
        GpuWriter.putFloat(dst, base + 20, 1f);
        GpuWriter.putFloat(dst, base + 24, 0f);
        GpuWriter.putFloat(dst, base + 28, y);
        GpuWriter.putFloat(dst, base + 32, 0f);
        GpuWriter.putFloat(dst, base + 36, 0f);
        GpuWriter.putFloat(dst, base + 40, 1f);
        GpuWriter.putFloat(dst, base + 44, z);
        GpuWriter.putInt(dst, base + OFFSET_USER_DATA, userData);
    }

    /**
     * Writes instance number {@code index} as the box {@code center +- size / 2}: a scale along the
     * axes and a translation, which maps a unit cube centred on the origin (the cube of
     * {@code Primitives.box(0.5f, 0.5f, 0.5f)}) onto the box.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     * @param cx the x coordinate of the centre of the box
     * @param cy the y coordinate of the centre of the box
     * @param cz the z coordinate of the centre of the box
     * @param sx the size of the box along x
     * @param sy the size of the box along y
     * @param sz the size of the box along z
     * @param userData the user data of the object
     */
    public static void writeBox(MemorySegment dst, long index, float cx, float cy, float cz, float sx, float sy, float sz, int userData) {
        long base = index * STRIDE;
        GpuWriter.putFloat(dst, base, sx);
        GpuWriter.putFloat(dst, base + 4, 0f);
        GpuWriter.putFloat(dst, base + 8, 0f);
        GpuWriter.putFloat(dst, base + 12, cx);
        GpuWriter.putFloat(dst, base + 16, 0f);
        GpuWriter.putFloat(dst, base + 20, sy);
        GpuWriter.putFloat(dst, base + 24, 0f);
        GpuWriter.putFloat(dst, base + 28, cy);
        GpuWriter.putFloat(dst, base + 32, 0f);
        GpuWriter.putFloat(dst, base + 36, 0f);
        GpuWriter.putFloat(dst, base + 40, sz);
        GpuWriter.putFloat(dst, base + 44, cz);
        GpuWriter.putInt(dst, base + OFFSET_USER_DATA, userData);
    }

    /**
     * Writes one box instance per visible object, in ascending object order, starting at instance
     * {@code firstInstance}: the transform maps a unit cube centred on the origin onto the object's
     * bounding box (see {@link #writeBox}) and the user data is the object's index. Drawing the
     * instances of one unit cube mesh then draws every bounding box exactly, which is what a
     * culling demonstration or a debug view wants.
     *
     * <p>Returns the number written, which is the instance count for the draw command. It walks the
     * set a word at a time like {@link #writeVisibleTranslations}.
     *
     * @param dst receives the result; must not be {@code null}
     * @param firstInstance the first instance
     * @param visible the visibility set; must not be {@code null}
     * @param bounds the bounds; must not be {@code null}
     * @return the number written, which is the instance count for the draw command
     */
    public static int writeVisibleBoxes(MemorySegment dst, long firstInstance, VisibilitySet visible, BoundsArray bounds) {
        long[] words = visible.words();
        long k = firstInstance;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            while (w != 0L) {
                int i = (wi << 6) + Long.numberOfTrailingZeros(w);
                w &= w - 1L;
                float x0 = bounds.minX(i), y0 = bounds.minY(i), z0 = bounds.minZ(i);
                float x1 = bounds.maxX(i), y1 = bounds.maxY(i), z1 = bounds.maxZ(i);
                writeBox(dst, k++, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f, x1 - x0, y1 - y0, z1 - z0, i);
            }
        }
        return (int) (k - firstInstance);
    }

    /**
     * Writes one translation-only instance per visible object, in ascending object order, starting
     * at instance {@code firstInstance}: the translation is the centre of the object's box and the
     * user data is the object's index (so a shader can look up anything else about it).
     *
     * <p>Returns the number written, which is the instance count for the draw command.
     *
     * <p>This walks the set a word at a time rather than calling {@link VisibilitySet#nextSetBit}
     * per object; in {@code InstanceWriteBench} that halves the scan cost and takes the whole step
     * from about 1.9 ms to about 1.4 ms for 95 000 of 1 000 000 objects.
     *
     * @param dst receives the result; must not be {@code null}
     * @param firstInstance the first instance
     * @param visible the visibility set; must not be {@code null}
     * @param bounds the bounds; must not be {@code null}
     * @return the number written, which is the instance count for the draw command
     */
    public static int writeVisibleTranslations(MemorySegment dst, long firstInstance, VisibilitySet visible, BoundsArray bounds) {
        long[] words = visible.words();
        long k = firstInstance;
        for (int wi = 0; wi < words.length; wi++) {
            long w = words[wi];
            while (w != 0L) {
                int i = (wi << 6) + Long.numberOfTrailingZeros(w);
                w &= w - 1L;
                writeTranslation(dst, k++, (bounds.minX(i) + bounds.maxX(i)) * 0.5f, (bounds.minY(i) + bounds.maxY(i)) * 0.5f,
                        (bounds.minZ(i) + bounds.maxZ(i)) * 0.5f, i);
            }
        }
        return (int) (k - firstInstance);
    }
}
