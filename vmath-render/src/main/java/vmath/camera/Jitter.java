package vmath.camera;

import vmath.core.Vec2f;

/**
 * Sub-pixel jitter sequences for temporal anti-aliasing and other accumulation techniques. Feed the offset of the current
 * frame to {@code Cameraf.jitteredProjection}.
 *
 * <p>The sequence is Halton (2, 3): low-discrepancy, so any prefix and any full cycle cover the pixel evenly, unlike random
 * offsets that clump.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class Jitter {

    private Jitter() {
    }

    /**
     * The radical inverse of {@code index} in {@code base}: the {@code index}-th element of the Halton sequence, in [0, 1).
     * {@code index} counts from 1 ({@code halton(1, 2) = 0.5}, then 0.25, 0.75, 0.125, ...); index 0 gives 0.
     */
    public static float halton(int index, int base) {
        if (index < 0 || base < 2) {
            throw new IllegalArgumentException("index must be >= 0 and base >= 2: " + index + ", " + base);
        }
        double result = 0.0;
        double f = 1.0 / base;
        int i = index;
        while (i > 0) {
            result += f * (i % base);
            i /= base;
            f /= base;
        }
        return (float) result;
    }

    /**
     * Jitter offset in pixels for frame {@code frame}, each component in [-0.5, 0.5): Halton (2, 3) with the given cycle
     * length (8 or 16 are common; the pattern repeats every {@code length} frames).
     */
    public static Vec2f offset(int frame, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1: " + length);
        }
        int index = Math.floorMod(frame, length) + 1;
        return new Vec2f(halton(index, 2) - 0.5f, halton(index, 3) - 0.5f);
    }
}
