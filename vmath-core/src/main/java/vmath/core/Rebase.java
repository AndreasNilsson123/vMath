package vmath.core;

/**
 * Camera-relative rendering for whole arrays: converts world data held in {@code double} to the
 * {@code float} data that the GPU gets, after subtracting an origin in double precision.
 *
 * <p>A {@code float} has 24 bits of precision, so at 6.4 million units from the world origin its
 * spacing is 0.5. Keeping the world in {@code double}, subtracting the camera (or any origin near
 * it) in double, and only then narrowing keeps the precision where it is needed. The single-value
 * forms are {@code relativeTo} on the {@code double} types; this class does the same for the
 * arrays of a scene: positions, model matrices and bounds. When the origin moves, data that is
 * already relative needs a {@link #shiftPositions shift} instead of a conversion from the world
 * data, which is what {@link FloatingOrigin} reports.
 *
 * <p>Layouts: positions are {@code x, y, z} triples, matrices are 16 values in column-major order
 * (the layout of {@code Mat4d} and of the bulk containers; the translation is the last column,
 * elements 12 to 14) and bounds are {@code minX, minY, minZ, maxX, maxY, maxZ}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays you pass in are not synchronised, so two threads must not write the same
 * one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] world = {6_400_000.25, 10.5, -3.125, 6_400_001.75, 11.0, -3.0};   // two positions, in metres
 * float[] local = new float[6];
 * Rebase.positions(world, 0, 6_400_000.0, 10.0, -3.0, local, 0, 2);          // relative to a point near them
 * float firstX = local[0];                                                   // 0.25, exact; narrowing first would give 6400000.0
 * }</pre>
 */
public final class Rebase {

    private Rebase() {
    }

    /**
     * Subtracts an origin from {@code count} positions in double precision and narrows the result
     * to {@code float}.
     *
     * @param src the world positions, three doubles each; must not be {@code null}
     * @param srcOffset the index of the first double of the first position
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param dst receives the relative positions, three floats each; must not be {@code null}
     * @param dstOffset the index of the first float of the first result
     * @param count the number of positions; zero or less does nothing
     * @throws ArrayIndexOutOfBoundsException if an array is too short for {@code count} positions
     * @throws NullPointerException if an array is {@code null} and {@code count} is positive
     */
    public static void positions(double[] src, int srcOffset, double ox, double oy, double oz, float[] dst, int dstOffset, int count) {
        for (int i = 0, s = srcOffset, d = dstOffset; i < count; i++, s += 3, d += 3) {
            dst[d] = (float) (src[s] - ox);
            dst[d + 1] = (float) (src[s + 1] - oy);
            dst[d + 2] = (float) (src[s + 2] - oz);
        }
    }

    /**
     * Subtracts an origin from {@code count} positions in double precision and narrows the result
     * to {@code float}.
     *
     * @param src the world positions, three doubles each; must not be {@code null}
     * @param srcOffset the index of the first double of the first position
     * @param origin the origin; must not be {@code null}
     * @param dst receives the relative positions, three floats each; must not be {@code null}
     * @param dstOffset the index of the first float of the first result
     * @param count the number of positions; zero or less does nothing
     * @throws ArrayIndexOutOfBoundsException if an array is too short for {@code count} positions
     * @throws NullPointerException if an array is {@code null} and {@code count} is positive
     */
    public static void positions(double[] src, int srcOffset, Vec3d origin, float[] dst, int dstOffset, int count) {
        positions(src, srcOffset, origin.x(), origin.y(), origin.z(), dst, dstOffset, count);
    }

    /**
     * Converts {@code count} model matrices to {@code float}, moving their translation by an
     * origin in double precision first: the matrix of each object in world coordinates becomes its
     * matrix in a frame whose origin is the given point. Only the other twelve elements are
     * rounded.
     *
     * @param src the world matrices, 16 doubles each in column-major order; must not be
     *     {@code null}
     * @param srcOffset the index of the first double of the first matrix
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param dst receives the relative matrices, 16 floats each; must not be {@code null}
     * @param dstOffset the index of the first float of the first result
     * @param count the number of matrices; zero or less does nothing
     * @throws ArrayIndexOutOfBoundsException if an array is too short for {@code count} matrices
     * @throws NullPointerException if an array is {@code null} and {@code count} is positive
     */
    public static void matrices(double[] src, int srcOffset, double ox, double oy, double oz, float[] dst, int dstOffset, int count) {
        for (int i = 0, s = srcOffset, d = dstOffset; i < count; i++, s += 16, d += 16) {
            for (int k = 0; k < 12; k++) {
                dst[d + k] = (float) src[s + k];
            }
            dst[d + 12] = (float) (src[s + 12] - ox);
            dst[d + 13] = (float) (src[s + 13] - oy);
            dst[d + 14] = (float) (src[s + 14] - oz);
            dst[d + 15] = (float) src[s + 15];
        }
    }

    /**
     * Converts {@code count} model matrices to {@code float}, moving their translation by an
     * origin in double precision first.
     *
     * @param src the world matrices, 16 doubles each in column-major order; must not be
     *     {@code null}
     * @param srcOffset the index of the first double of the first matrix
     * @param origin the origin; must not be {@code null}
     * @param dst receives the relative matrices, 16 floats each; must not be {@code null}
     * @param dstOffset the index of the first float of the first result
     * @param count the number of matrices; zero or less does nothing
     * @throws ArrayIndexOutOfBoundsException if an array is too short for {@code count} matrices
     * @throws NullPointerException if an array is {@code null} and {@code count} is positive
     */
    public static void matrices(double[] src, int srcOffset, Vec3d origin, float[] dst, int dstOffset, int count) {
        matrices(src, srcOffset, origin.x(), origin.y(), origin.z(), dst, dstOffset, count);
    }

    /**
     * Subtracts an origin from {@code count} axis-aligned boxes in double precision and narrows
     * them to {@code float}. The corners are rounded to the nearest float, not outwards, so a
     * culling test that must never miss should add a margin of the float spacing at the distances
     * involved.
     *
     * @param src the world boxes, {@code minX, minY, minZ, maxX, maxY, maxZ} each; must not be
     *     {@code null}
     * @param srcOffset the index of the first double of the first box
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param dst receives the relative boxes, six floats each; must not be {@code null}
     * @param dstOffset the index of the first float of the first result
     * @param count the number of boxes; zero or less does nothing
     * @throws ArrayIndexOutOfBoundsException if an array is too short for {@code count} boxes
     * @throws NullPointerException if an array is {@code null} and {@code count} is positive
     */
    public static void bounds(double[] src, int srcOffset, double ox, double oy, double oz, float[] dst, int dstOffset, int count) {
        for (int i = 0, s = srcOffset, d = dstOffset; i < count; i++, s += 6, d += 6) {
            dst[d] = (float) (src[s] - ox);
            dst[d + 1] = (float) (src[s + 1] - oy);
            dst[d + 2] = (float) (src[s + 2] - oz);
            dst[d + 3] = (float) (src[s + 3] - ox);
            dst[d + 4] = (float) (src[s + 4] - oy);
            dst[d + 5] = (float) (src[s + 5] - oz);
        }
    }

    /**
     * Moves {@code count} positions that are already relative to an origin when that origin
     * moves: subtracts the shift of the origin from every position, in place.
     *
     * <p>The shift is narrowed to {@code float} first, which is exact for a whole multiple of a
     * cell size that is a power of two, as {@link FloatingOrigin} makes it. Each subtraction is
     * then exact for a position within a factor of two of the shift (the common case: objects near
     * the camera that caused the shift), and otherwise rounds to the precision of its result,
     * which is as fine as a {@code float} of that size can be.
     *
     * @param xyz the positions, three floats each; must not be {@code null}
     * @param offset the index of the first float of the first position
     * @param count the number of positions; zero or less does nothing
     * @param shift the movement of the origin, as {@link FloatingOrigin#lastShift()} reports it;
     *     must not be {@code null}
     * @throws ArrayIndexOutOfBoundsException if the array is too short for {@code count} positions
     * @throws NullPointerException if the array is {@code null} and {@code count} is positive
     */
    public static void shiftPositions(float[] xyz, int offset, int count, Vec3d shift) {
        float dx = (float) shift.x(), dy = (float) shift.y(), dz = (float) shift.z();
        for (int i = 0, o = offset; i < count; i++, o += 3) {
            xyz[o] -= dx;
            xyz[o + 1] -= dy;
            xyz[o + 2] -= dz;
        }
    }

    /**
     * Moves {@code count} model matrices that are already relative to an origin when that origin
     * moves: subtracts the shift of the origin from the translation of every matrix, in place.
     *
     * @param m the matrices, 16 floats each in column-major order; must not be {@code null}
     * @param offset the index of the first float of the first matrix
     * @param count the number of matrices; zero or less does nothing
     * @param shift the movement of the origin, as {@link FloatingOrigin#lastShift()} reports it;
     *     must not be {@code null}
     * @throws ArrayIndexOutOfBoundsException if the array is too short for {@code count} matrices
     * @throws NullPointerException if the array is {@code null} and {@code count} is positive
     */
    public static void shiftMatrices(float[] m, int offset, int count, Vec3d shift) {
        float dx = (float) shift.x(), dy = (float) shift.y(), dz = (float) shift.z();
        for (int i = 0, o = offset; i < count; i++, o += 16) {
            m[o + 12] -= dx;
            m[o + 13] -= dy;
            m[o + 14] -= dz;
        }
    }
}
