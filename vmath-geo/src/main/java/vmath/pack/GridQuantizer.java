package vmath.pack;

import vmath.annotations.Experimental;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/**
 * Position quantization onto a grid of {@code 2^bits} levels per axis inside a bounding box, for 1
 * to 16 bits: the general form of {@link Quantizer} (which is the 16-bit, per-axis case).
 *
 * <p>With 14 bits a mesh 2 units across is off by at most 6.1e-5 units (61 micrometres if the unit
 * is a metre); with 10 bits, 9.8e-4 (about a millimetre).
 *
 * <p>Two grids are offered. {@link #of} scales each axis to its own extent, so every axis uses all
 * its levels but the grid cells are not cubes. {@link #uniform} uses the largest extent for all
 * three axes, so the cells are cubes and the error is the same in every direction (what
 * meshoptimizer does, and what you want when the data is later simplified or compared); the shorter
 * axes then use fewer levels.
 *
 * <p>The stored codes are unsigned and fit in a {@code short} for up to 16 bits (read them back
 * with {@code & 0xFFFF}). {@link #dequantizationMatrix()} maps the normalized value of a code
 * ({@code code / (2^bits - 1)}, which is what a GPU produces when it reads the code as a normalized
 * integer, for 8 and 16 bits) back to the model space, so it can be folded into the model matrix.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Aabbf bounds = Aabbf.of(new Vec3f(-10f, 0f, -10f), new Vec3f(10f, 5f, 10f));
 * GridQuantizer grid = GridQuantizer.of(bounds, 16);
 * short[] packed = new short[3];
 * grid.pack(new Vec3f(1f, 2f, 3f), packed, 0);
 * Vec3f back = grid.unpack(packed, 0);                                    // within grid.maxError() of the original
 * }</pre>
 */
@Experimental("the set of helpers may grow")
public final class GridQuantizer {

    private final Aabbf bounds;
    private final int bits;
    private final int levels;
    private final float sizeX;
    private final float sizeY;
    private final float sizeZ;

    private GridQuantizer(Aabbf bounds, int bits, boolean uniform) {
        if (bounds.isEmpty()) {
            throw new IllegalArgumentException("cannot quantize relative to an empty box");
        }
        if (bits < 1 || bits > 16) {
            throw new IllegalArgumentException("bits must be in [1, 16]: " + bits);
        }
        this.bounds = bounds;
        this.bits = bits;
        this.levels = (1 << bits) - 1;
        float x = bounds.maxX() - bounds.minX(), y = bounds.maxY() - bounds.minY(), z = bounds.maxZ() - bounds.minZ();
        if (uniform) {
            float s = Math.max(x, Math.max(y, z));
            x = s;
            y = s;
            z = s;
        }
        this.sizeX = x;
        this.sizeY = y;
        this.sizeZ = z;
    }

    /**
     * Creates a quantisation grid over a box in which each axis uses the full code range, which
     * gives the best precision for flat boxes at the price of unequal cell sizes.
     *
     * @param bounds the bounds; must not be {@code null}
     * @param bits the number of bits
     * @return a grid that scales each axis to its own extent
     */
    public static GridQuantizer of(Aabbf bounds, int bits) {
        return new GridQuantizer(bounds, bits, false);
    }

    /**
     * Creates a quantisation grid with cubic cells, which keeps the precision equal on all axes but
     * wastes codes on the shorter ones.
     *
     * @param bounds the bounds; must not be {@code null}
     * @param bits the number of bits
     * @return a grid with cubic cells: all axes use the largest extent of the box
     */
    public static GridQuantizer uniform(Aabbf bounds, int bits) {
        return new GridQuantizer(bounds, bits, true);
    }

    /**
     * Exposes the box the grid covers.
     *
     * @return the box the grid covers
     */
    public Aabbf bounds() {
        return bounds;
    }

    /**
     * Exposes the number of bits that each axis uses.
     *
     * @return the number of bits per axis
     */
    public int bits() {
        return bits;
    }

    /**
     * Computes the largest code of an axis from the bit count.
     *
     * @return the largest code, {@code 2^bits - 1}
     */
    public int levels() {
        return levels;
    }

    private int axis(float v, float min, float size) {
        return size > 0f ? Quantize.unorm((v - min) / size, bits) : 0;
    }

    /**
     * Quantizes a coordinate to a code by scaling it to the box and rounding; values outside the
     * box are clamped.
     *
     * @param v the coordinate along the axis
     * @param axis the axis
     * @return the code of {@code v} along axis 0 (x), 1 (y) or 2 (z), clamped to the box
     * @throws IllegalArgumentException if {@code axis} is not 0, 1 or 2
     */
    public int quantize(float v, int axis) {
        return switch (axis) {
            case 0 -> axis(v, bounds.minX(), sizeX);
            case 1 -> axis(v, bounds.minY(), sizeY);
            case 2 -> axis(v, bounds.minZ(), sizeZ);
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    /**
     * Writes the three codes of {@code (x, y, z)} to {@code dst[offset .. offset + 2]} as unsigned
     * 16-bit values.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param dst receives the result
     * @param offset the index of the first element to read or write
     */
    public void pack(float x, float y, float z, short[] dst, int offset) {
        dst[offset] = (short) axis(x, bounds.minX(), sizeX);
        dst[offset + 1] = (short) axis(y, bounds.minY(), sizeY);
        dst[offset + 2] = (short) axis(z, bounds.minZ(), sizeZ);
    }

    /**
     * Writes the three codes of {@code p} to {@code dst[offset .. offset + 2]} as unsigned 16-bit
     * values.
     *
     * @param p the vector; must not be {@code null}
     * @param dst receives the result
     * @param offset the index of the first element to read or write
     */
    public void pack(Vec3f p, short[] dst, int offset) {
        pack(p.x(), p.y(), p.z(), dst, offset);
    }

    /**
     * Dequantizes three codes to the position at the centre of their cell.
     *
     * @param qx the code along x
     * @param qy the code along y
     * @param qz the code along z
     * @return the position the three codes stand for
     */
    public Vec3f unpack(int qx, int qy, int qz) {
        return new Vec3f(
                bounds.minX() + (float) ((double) (qx & levels) / levels * sizeX),
                bounds.minY() + (float) ((double) (qy & levels) / levels * sizeY),
                bounds.minZ() + (float) ((double) (qz & levels) / levels * sizeZ));
    }

    /**
     * Dequantizes three unsigned 16-bit codes from an array to a position.
     *
     * @param src the source to read from
     * @param offset the index of the first element to read or write
     * @return the position the three unsigned 16-bit codes at {@code src[offset .. offset + 2]}
     *     stand for
     */
    public Vec3f unpack(short[] src, int offset) {
        return unpack(src[offset] & 0xFFFF, src[offset + 1] & 0xFFFF, src[offset + 2] & 0xFFFF);
    }

    /**
     * Returns from normalized code values ({@code code / levels}, in [0, 1]) back to model space:
     * scale by the grid extent, then move to the box's minimum corner.
     *
     * @return the matrix from normalized code values to model space, never {@code null}
     */
    public Mat4f dequantizationMatrix() {
        return Mat4f.translation(bounds.minX(), bounds.minY(), bounds.minZ()).mul(Mat4f.scaling(sizeX, sizeY, sizeZ));
    }

    /**
     * Computes the worst-case quantisation error, which is half a cell for points inside the box.
     *
     * @return the largest error per axis for points inside the box: half a step,
     *     {@code extent / (2 * levels)}
     */
    public Vec3f maxError() {
        float d = 2f * levels;
        return new Vec3f(sizeX / d, sizeY / d, sizeZ / d);
    }
}
