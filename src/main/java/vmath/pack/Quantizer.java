package vmath.pack;

import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;

/**
 * Position quantization: three unorm16 coordinates relative to a bounding box, 6 bytes instead of 12. A position is stored as its
 * fractional position inside the box, so every point in the box is off by at most {@code size / 131070} per axis (half a step): for a
 * mesh 2 units across that is about 15 micrometres.
 *
 * <p>For a mesh, compute the {@link Aabbf} of its vertices once, quantize every vertex with {@link #pack}, and fold
 * {@link #dequantizationMatrix()} into the model matrix (or the vertex shader) so the GPU reads the 16-bit values as normalized
 * integers and restores the positions with one matrix multiply.
 */
public final class Quantizer {

    private final Aabbf bounds;
    private final float sizeX;
    private final float sizeY;
    private final float sizeZ;

    /** A quantizer for points inside {@code bounds}, which must not be empty. */
    public Quantizer(Aabbf bounds) {
        if (bounds.isEmpty()) {
            throw new IllegalArgumentException("cannot quantize relative to an empty box");
        }
        this.bounds = bounds;
        this.sizeX = bounds.maxX() - bounds.minX();
        this.sizeY = bounds.maxY() - bounds.minY();
        this.sizeZ = bounds.maxZ() - bounds.minZ();
    }

    public Aabbf bounds() {
        return bounds;
    }

    private static int axis(float v, float min, float size) {
        return size > 0f ? Norm.packUnorm16((v - min) / size) : 0;
    }

    /** The three unorm16 values of {@code p} (clamped to the box), each in {@code 0..65535}, written to {@code dst[offset..offset+2]}. */
    public void pack(Vec3f p, short[] dst, int offset) {
        dst[offset] = (short) axis(p.x(), bounds.minX(), sizeX);
        dst[offset + 1] = (short) axis(p.y(), bounds.minY(), sizeY);
        dst[offset + 2] = (short) axis(p.z(), bounds.minZ(), sizeZ);
    }

    /** The position stored in three unorm16 values (as returned in the {@code short}s of {@link #pack}). */
    public Vec3f unpack(short x, short y, short z) {
        return new Vec3f(
                bounds.minX() + Norm.unpackUnorm16(x) * sizeX,
                bounds.minY() + Norm.unpackUnorm16(y) * sizeY,
                bounds.minZ() + Norm.unpackUnorm16(z) * sizeZ);
    }

    public Vec3f unpack(short[] src, int offset) {
        return unpack(src[offset], src[offset + 1], src[offset + 2]);
    }

    /**
     * The transform from normalized coordinates ({@code unpackUnorm16} of the stored values, in [0, 1]) back to model space:
     * scale by the box size, then translate to its minimum corner.
     */
    public Mat4f dequantizationMatrix() {
        return Mat4f.translation(bounds.minX(), bounds.minY(), bounds.minZ()).mul(Mat4f.scaling(sizeX, sizeY, sizeZ));
    }

    /** The largest possible error per axis: half a quantization step. */
    public Vec3f maxError() {
        return new Vec3f(sizeX / 131070f, sizeY / 131070f, sizeZ / 131070f);
    }
}
