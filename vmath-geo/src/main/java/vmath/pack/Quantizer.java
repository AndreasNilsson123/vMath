package vmath.pack;

import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/**
 * Position quantization: three unorm16 coordinates relative to a bounding box, 6 bytes instead of
 * 12.
 *
 * <p>A position is stored as its fractional position inside the box, so every point in the box is
 * off by at most {@code size / 131070} per axis (half a step): for a mesh 2 units across that is
 * about 15 micrometres.
 *
 * <p>For a mesh, compute the {@link Aabbf} of its vertices once, quantize every vertex with
 * {@link #pack}, and fold {@link #dequantizationMatrix()} into the model matrix (or the vertex
 * shader) so the GPU reads the 16-bit values as normalized integers and restores the positions with
 * one matrix multiply.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Quantizer quantizer = new Quantizer(Aabbf.of(new Vec3f(-5f, -5f, -5f), new Vec3f(5f, 5f, 5f)));
 * short[] packed = new short[3];
 * quantizer.pack(new Vec3f(1f, 2f, 3f), packed, 0);                       // three unorm16
 * Vec3f back = quantizer.unpack(packed, 0);
 * Mat4f toModel = quantizer.dequantizationMatrix();                       // the same decode as a matrix, for a shader
 * }</pre>
 */
public final class Quantizer {

    private final Aabbf bounds;
    private final float sizeX;
    private final float sizeY;
    private final float sizeZ;

    /**
     * Creates a quantizer for points inside {@code bounds}, which must not be empty.
     *
     * @param bounds the bounds; must not be {@code null}
     * @throws IllegalArgumentException if {@code bounds} is empty
     */
    public Quantizer(Aabbf bounds) {
        if (bounds.isEmpty()) {
            throw new IllegalArgumentException("cannot quantize relative to an empty box");
        }
        this.bounds = bounds;
        this.sizeX = bounds.maxX() - bounds.minX();
        this.sizeY = bounds.maxY() - bounds.minY();
        this.sizeZ = bounds.maxZ() - bounds.minZ();
    }

    /**
     * Exposes the box that positions are quantized against.
     *
     * @return the box the positions are quantized against
     */
    public Aabbf bounds() {
        return bounds;
    }

    private static int axis(float v, float min, float size) {
        return size > 0f ? Norm.packUnorm16((v - min) / size) : 0;
    }

    /**
     * Writes the three unorm16 values of {@code p} (clamped to the box), each in {@code 0..65535},
     * to {@code dst[offset..offset+2]}.
     *
     * @param p the vector; must not be {@code null}
     * @param dst receives the result
     * @param offset the index of the first element to read or write
     */
    public void pack(Vec3f p, short[] dst, int offset) {
        pack(p.x(), p.y(), p.z(), dst, offset);
    }

    /**
     * Writes the three unorm16 values of a position given by its components, as
     * {@link #pack(Vec3f, short[], int)} does; allocates nothing.
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
     * Decodes a position from three 16-bit codes.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return the position stored in three unorm16 values (as returned in the {@code short}s of
     *     {@link #pack})
     */
    public Vec3f unpack(short x, short y, short z) {
        return new Vec3f(
                bounds.minX() + Norm.unpackUnorm16(x) * sizeX,
                bounds.minY() + Norm.unpackUnorm16(y) * sizeY,
                bounds.minZ() + Norm.unpackUnorm16(z) * sizeZ);
    }

    /**
     * Decodes a position from three 16-bit codes in an array.
     *
     * @param src the source to read from
     * @param offset the index of the first element to read or write
     * @return the position stored in the three unorm16 values at {@code src[offset .. offset + 2]}
     */
    public Vec3f unpack(short[] src, int offset) {
        return unpack(src[offset], src[offset + 1], src[offset + 2]);
    }

    /**
     * Describes the decoding as a matrix, which a vertex shader can apply to the normalised integer
     * attributes instead of decoding on the CPU.
     *
     * @return the transform from normalized coordinates ({@code unpackUnorm16} of the stored
     *     values, in [0, 1]) back to model space: scale by the box size, then translate to its
     *     minimum corner
     */
    public Mat4f dequantizationMatrix() {
        return Mat4f.translation(bounds.minX(), bounds.minY(), bounds.minZ()).mul(Mat4f.scaling(sizeX, sizeY, sizeZ));
    }

    /**
     * Computes the worst-case quantisation error, which is half a step.
     *
     * @return the largest possible error per axis: half a quantization step
     */
    public Vec3f maxError() {
        return new Vec3f(sizeX / 131070f, sizeY / 131070f, sizeZ / 131070f);
    }
}
