package vmath.util;

import vmath.annotations.Experimental;

/**
 * Low-discrepancy point sequences and Poisson-disk sampling: ways to place samples that cover a
 * domain more evenly than independent random numbers do, for anti-aliasing, soft shadows, ambient
 * occlusion, light sampling and object scattering.
 *
 * <ul>
 *   <li>{@link #halton}: the Halton sequence in bases 2, 3, 5, 7, ..., any prefix of it is well
 *       spread; the next point only needs the index.</li>
 *   <li>{@link #sobol2}: the first two dimensions of the Sobol sequence; every block of {@code 2^k}
 *       points aligned at a multiple of {@code 2^k} puts exactly one point into each of the
 *       {@code 2^k} equal cells of any grid of {@code 2^a x 2^b} cells with {@code a + b = k}.</li>
 *   <li>{@link #r2}: the "R2" additive-recurrence sequence (Roberts): a point is the previous one
 *       plus a constant, wrapped; simple, and good for any number of points.</li>
 *   <li>{@link #hammersley}: the Hammersley set, a point set of a known size {@code n} (the index
 *       divided by {@code n}, and the base 2 radical inverse), slightly more even than a prefix of
 *       a sequence.</li>
 *   <li>{@link #poissonDisk}: random points at least a given distance apart, by Bridson's
 *       algorithm.</li>
 * </ul>
 *
 * <p>All sequences are deterministic: the same index always gives the same point. Points are in the
 * unit square {@code [0, 1)^2} unless stated otherwise.
 *
 * <p><b>Thread safety.</b> Stateless apart from the {@link Rng} you pass to {@link #poissonDisk};
 * every method may be called from any number of threads at the same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] point = new float[2];
 * Sequences.sobol2(5L, point, 0);                                                // the 6th point of the 2D Sobol sequence
 * double h = Sequences.halton(5L, 0);
 * float[] disk = new float[2 * 200];
 * int count = Sequences.poissonDisk(new Rng(1L), 10f, 10f, 1f, 30, disk);       // points at least 1 apart
 * }</pre>
 */
@Experimental("blue-noise tables and more Sobol dimensions may be added")
public final class Sequences {

    private static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53};
    private static final int[] SOBOL_V2 = new int[32];

    static {
        // the second Sobol dimension: direction numbers v_k = v_{k-1} xor (v_{k-1} >> 1), starting at 1/2 (the primitive polynomial x + 1)
        SOBOL_V2[0] = 0x80000000;
        for (int k = 1; k < 32; k++) {
            SOBOL_V2[k] = SOBOL_V2[k - 1] ^ (SOBOL_V2[k - 1] >>> 1);
        }
    }

    private Sequences() {
    }

    /**
     * Computes the radical inverse, which mirrors the digits of an index in a base about the radix
     * point, giving a low-discrepancy number.
     *
     * <p>The van der Corput sequence for the given base.
     *
     * @param base the base
     * @param index the index
     * @return the radical inverse of {@code index} in {@code base}: its digits mirrored about the
     *     point, a number in {@code [0, 1)}
     * @throws IllegalArgumentException if {@code base} is below 2
     */
    public static double radicalInverse(int base, long index) {
        if (base < 2) {
            throw new IllegalArgumentException("the base must be at least 2: " + base);
        }
        double inv = 1.0 / base, f = inv, result = 0;
        long i = index;
        while (i > 0) {
            result += (i % base) * f;
            i /= base;
            f *= inv;
        }
        return result;
    }

    /**
     * Computes one dimension of the Halton low-discrepancy sequence, using a different prime base
     * for each dimension, which gives well-spread sample points.
     *
     * @param index the index
     * @param dimension the dimension
     * @return dimension {@code dimension} (0 to 15) of the Halton sequence at {@code index}: the
     *     radical inverse in the prime number of that dimension (2, 3, 5, 7, ...)
     * @throws IllegalArgumentException if {@code dimension} is not a supported dimension
     */
    public static double halton(long index, int dimension) {
        if (dimension < 0 || dimension >= PRIMES.length) {
            throw new IllegalArgumentException("the dimension must be in [0, " + PRIMES.length + "): " + dimension);
        }
        return radicalInverse(PRIMES[dimension], index);
    }

    /**
     * Writes the point {@code index} of the 2D Sobol sequence to {@code out[offset]} and
     * {@code out[offset + 1]}.
     *
     * <p>Index 0 is the origin. {@code index} must be below {@code 2^32}.
     *
     * @param index the index
     * @param out receives the result
     * @param offset the index of the first element to read or write
     * @throws IllegalArgumentException if {@code index} is not in {@code [0, 2^32)}
     */
    public static void sobol2(long index, float[] out, int offset) {
        if (index < 0 || index >= (1L << 32)) {
            throw new IllegalArgumentException("the index must be in [0, 2^32): " + index);
        }
        int x = 0, y = 0;
        for (int k = 0; k < 32; k++) {
            if (((index >>> k) & 1) != 0) {
                x ^= Integer.reverse(1 << k);
                y ^= SOBOL_V2[k];
            }
        }
        out[offset] = (float) ((x & 0xFFFFFFFFL) * 0x1.0p-32);
        out[offset + 1] = (float) ((y & 0xFFFFFFFFL) * 0x1.0p-32);
    }

    /**
     * Writes the point {@code index} of the R2 sequence (the generalised golden ratio for two
     * dimensions) to {@code out[offset]} and {@code out[offset + 1]}.
     *
     * @param index the index
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public static void r2(long index, float[] out, int offset) {
        final double g = 1.32471795724474602596; // the real root of x^3 = x + 1
        final double a1 = 1.0 / g, a2 = 1.0 / (g * g);
        double x = (0.5 + a1 * (index + 1)) % 1.0, y = (0.5 + a2 * (index + 1)) % 1.0;
        out[offset] = (float) x;
        out[offset + 1] = (float) y;
    }

    /**
     * Writes the point {@code index} of the Hammersley set of {@code count} points to
     * {@code out[offset]} and {@code out[offset + 1]}.
     *
     * @param index the index
     * @param count the number of elements
     * @param out receives the result
     * @param offset the index of the first element to read or write
     * @throws IllegalArgumentException if {@code index} is not in {@code [0, count)}
     */
    public static void hammersley(int index, int count, float[] out, int offset) {
        if (count <= 0 || index < 0 || index >= count) {
            throw new IllegalArgumentException("the index " + index + " is not in [0, " + count + ")");
        }
        out[offset] = (float) ((index + 0.5) / count);
        out[offset + 1] = (float) ((Integer.reverse(index) & 0xFFFFFFFFL) * 0x1.0p-32);
    }

    /**
     * Generates blue-noise points by Bridson's algorithm, which grows the set from existing points
     * and rejects candidates that are closer than the radius; the output is bounded by a
     * caller-supplied array.
     *
     * <p>The points go to {@code out} as {@code x, y} pairs, and the number of points written is
     * returned; the sampling stops at {@code out.length / 2} points. This allocates a grid of cells
     * the size of {@code radius / sqrt(2)}, so it is for set-up, not for every frame.
     *
     * @param rng the rng; must not be {@code null}
     * @param width the width
     * @param height the height
     * @param radius the radius
     * @param candidates the candidates
     * @param out receives the result in {@code [0, 2)}
     * @return random points in the rectangle {@code [0, width) x [0, height)} that are at least
     *     {@code radius} apart, and that fill the rectangle (Bridson's algorithm: it tries
     *     {@code candidates} positions around a point, 30 is the usual value, before it retires the
     *     point; a larger number leaves fewer gaps, and a gap larger than twice the radius is
     *     possible but unlikely)
     * @throws IllegalArgumentException if the size or the radius is not positive, there is no
     *     candidate, or the grid would have too many cells
     */
    public static int poissonDisk(Rng rng, float width, float height, float radius, int candidates, float[] out) {
        if (!(width > 0) || !(height > 0) || !(radius > 0) || candidates < 1) {
            throw new IllegalArgumentException("the size and the radius must be positive and there must be a candidate: " + width + ", " + height + ", " + radius + ", " + candidates);
        }
        double cell = radius / Math.sqrt(2);
        int gw = (int) Math.ceil(width / cell), gh = (int) Math.ceil(height / cell);
        if ((long) gw * gh > (1 << 26)) {
            throw new IllegalArgumentException("the grid would have " + (long) gw * gh + " cells: increase the radius");
        }
        int[] grid = new int[gw * gh];
        java.util.Arrays.fill(grid, -1);
        int capacity = out.length / 2;
        int[] active = new int[Math.max(capacity, 1)];
        int count = 0, activeCount = 0;
        if (capacity == 0) {
            return 0;
        }
        double r2 = (double) radius * radius;
        out[0] = (float) (rng.nextDouble() * width);
        out[1] = (float) (rng.nextDouble() * height);
        grid[cellIndex(out[0], out[1], cell, gw, gh)] = 0;
        active[activeCount++] = 0;
        count = 1;
        while (activeCount > 0 && count < capacity) {
            int pick = rng.nextInt(activeCount);
            int base = active[pick];
            double bx = out[2 * base], by = out[2 * base + 1];
            boolean found = false;
            for (int k = 0; k < candidates && !found; k++) {
                double dist = radius * (1 + rng.nextDouble()), angle = 2 * Math.PI * rng.nextDouble();
                double px = bx + dist * Math.cos(angle), py = by + dist * Math.sin(angle);
                if (px < 0 || py < 0 || px >= width || py >= height) {
                    continue;
                }
                float fx = (float) px, fy = (float) py;
                if (fx < width && fy < height && farFromAll(out, grid, fx, fy, cell, gw, gh, r2)) {
                    out[2 * count] = fx;
                    out[2 * count + 1] = fy;
                    grid[cellIndex(fx, fy, cell, gw, gh)] = count;
                    active[activeCount++] = count;
                    count++;
                    found = true;
                }
            }
            if (!found) {
                active[pick] = active[--activeCount];
            }
        }
        return count;
    }

    private static int cellIndex(float x, float y, double cell, int gw, int gh) {
        int cx = Math.min(gw - 1, (int) (x / cell)), cy = Math.min(gh - 1, (int) (y / cell));
        return cy * gw + cx;
    }

    private static boolean farFromAll(float[] pts, int[] grid, float x, float y, double cell, int gw, int gh, double r2) {
        int cx = Math.min(gw - 1, (int) (x / cell)), cy = Math.min(gh - 1, (int) (y / cell));
        for (int j = Math.max(0, cy - 2); j <= Math.min(gh - 1, cy + 2); j++) {
            for (int i = Math.max(0, cx - 2); i <= Math.min(gw - 1, cx + 2); i++) {
                int q = grid[j * gw + i];
                if (q >= 0) {
                    double dx = pts[2 * q] - x, dy = pts[2 * q + 1] - y;
                    if (dx * dx + dy * dy < r2) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
