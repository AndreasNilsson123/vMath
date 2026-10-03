package vmath.pack;

import vmath.annotations.Experimental;

/**
 * Texture-coordinate quantization onto a grid of {@code 2^bits} levels per axis inside a rectangle,
 * for 1 to 16 bits.
 *
 * <p>A set of UVs that stays inside {@code [0, 1]} fits the unit rectangle (a plain unorm16 or
 * unorm12 is then enough); UVs that tile or wrap need a rectangle that covers their range.
 * {@link #fit} finds it from the data. The error is half a step,
 * {@code extent / (2 * (2^bits - 1))}, per axis: 7.6e-6 of the extent at 16 bits, and 1.2e-4 at 12.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] uv = {0.1f, 0.2f, 0.9f, 0.8f};
 * UvQuantizer quantizer = UvQuantizer.fit(uv, 2, 16);                    // the smallest rectangle around the coordinates
 * short[] packed = new short[2];
 * quantizer.pack(0.5f, 0.5f, packed, 0);
 * float u = quantizer.unpackU(packed[0] & 0xFFFF);
 * }</pre>
 */
@Experimental("the set of helpers may grow")
public final class UvQuantizer {

    private final float minU;
    private final float minV;
    private final float sizeU;
    private final float sizeV;
    private final int bits;
    private final int levels;

    /**
     * Creates a quantizer for the rectangle {@code [minU, maxU] x [minV, maxV]}, which must not be
     * inverted or contain non-finite values.
     *
     * @param minU the min u
     * @param minV the min v
     * @param maxU the max u
     * @param maxV the max v
     * @param bits the number of bits
     * @throws IllegalArgumentException if the rectangle is not finite or is inverted, or
     *     {@code bits} is not in {@code [1, 16]}
     */
    public UvQuantizer(float minU, float minV, float maxU, float maxV, int bits) {
        if (!(maxU >= minU && maxV >= minV) || !Float.isFinite(minU + maxU + minV + maxV)) {
            throw new IllegalArgumentException("the rectangle must be finite and not inverted");
        }
        if (bits < 1 || bits > 16) {
            throw new IllegalArgumentException("bits must be in [1, 16]: " + bits);
        }
        this.minU = minU;
        this.minV = minV;
        this.sizeU = maxU - minU;
        this.sizeV = maxV - minV;
        this.bits = bits;
        this.levels = (1 << bits) - 1;
    }

    /**
     * Fits a quantizer to the bounds of a set of texture coordinates, with a unit square as the
     * fallback for an empty set.
     *
     * @param uv the coordinates as {@code u, v} pairs
     * @param count the number of elements
     * @param bits the number of bits
     * @return the quantizer for the smallest rectangle around the first {@code count} pairs of
     *     {@code uv} ({@code u, v, u, v, ...}); {@code [0, 1]} if there are none
     * @throws IllegalArgumentException if a coordinate is not finite
     */
    public static UvQuantizer fit(float[] uv, int count, int bits) {
        if (count <= 0) {
            return new UvQuantizer(0f, 0f, 1f, 1f, bits);
        }
        float u0 = Float.POSITIVE_INFINITY, v0 = u0, u1 = Float.NEGATIVE_INFINITY, v1 = u1;
        for (int i = 0; i < count; i++) {
            float u = uv[2 * i], v = uv[2 * i + 1];
            if (!(Float.isFinite(u) && Float.isFinite(v))) {
                throw new IllegalArgumentException("texture coordinate " + i + " is not finite");
            }
            u0 = Math.min(u0, u);
            u1 = Math.max(u1, u);
            v0 = Math.min(v0, v);
            v1 = Math.max(v1, v);
        }
        return new UvQuantizer(u0, v0, u1, v1, bits);
    }

    /**
     * Exposes the bit count per coordinate.
     *
     * @return the number of bits per coordinate
     */
    public int bits() {
        return bits;
    }

    /**
     * Exposes the lower u bound of the covered rectangle.
     *
     * @return the smallest u the rectangle covers
     */
    public float minU() {
        return minU;
    }

    /**
     * Exposes the lower v bound of the covered rectangle.
     *
     * @return the smallest v the rectangle covers
     */
    public float minV() {
        return minV;
    }

    /**
     * Exposes the u extent of the covered rectangle.
     *
     * @return the extent of the rectangle in u
     */
    public float sizeU() {
        return sizeU;
    }

    /**
     * Exposes the v extent of the covered rectangle.
     *
     * @return the extent of the rectangle in v
     */
    public float sizeV() {
        return sizeV;
    }

    /**
     * Computes the largest code from the bit count.
     *
     * @return the largest code, {@code 2^bits - 1}
     */
    public int levels() {
        return levels;
    }

    /**
     * Quantizes a u coordinate to a code by scaling it to the rectangle and rounding; values
     * outside the rectangle are clamped.
     *
     * @param u the u coordinate
     * @return the code of {@code u} (clamped to the rectangle)
     */
    public int quantizeU(float u) {
        return sizeU > 0f ? Quantize.unorm((u - minU) / sizeU, bits) : 0;
    }

    /**
     * Quantizes a v coordinate to a code by scaling it to the rectangle and rounding; values
     * outside the rectangle are clamped.
     *
     * @param v the v coordinate
     * @return the code of {@code v} (clamped to the rectangle)
     */
    public int quantizeV(float v) {
        return sizeV > 0f ? Quantize.unorm((v - minV) / sizeV, bits) : 0;
    }

    /**
     * Writes the two codes of {@code (u, v)} to {@code dst[offset]} and {@code dst[offset + 1]} as
     * unsigned 16-bit values.
     *
     * @param u the u coordinate
     * @param v the v coordinate
     * @param dst receives the result
     * @param offset the index of the first element to read or write
     */
    public void pack(float u, float v, short[] dst, int offset) {
        dst[offset] = (short) quantizeU(u);
        dst[offset + 1] = (short) quantizeV(v);
    }

    /**
     * Dequantizes a code to a u coordinate.
     *
     * @param code the code
     * @return the u that {@code code} stands for
     */
    public float unpackU(int code) {
        return minU + (float) ((double) (code & levels) / levels * sizeU);
    }

    /**
     * Dequantizes a code to a v coordinate.
     *
     * @param code the code
     * @return the v that {@code code} stands for
     */
    public float unpackV(int code) {
        return minV + (float) ((double) (code & levels) / levels * sizeV);
    }

    /**
     * Computes the worst-case quantisation error in u, which is half a step.
     *
     * @return the largest error in u for points inside the rectangle
     */
    public float maxErrorU() {
        return sizeU / (2f * levels);
    }

    /**
     * Computes the worst-case quantisation error in v, which is half a step.
     *
     * @return the largest error in v for points inside the rectangle
     */
    public float maxErrorV() {
        return sizeV / (2f * levels);
    }
}
