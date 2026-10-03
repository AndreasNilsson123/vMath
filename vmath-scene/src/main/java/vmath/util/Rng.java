package vmath.util;

import vmath.annotations.Experimental;

/**
 * A fast, seedable pseudo-random number generator (xoshiro256++, Blackman and Vigna) with the
 * sampling that graphics code needs: uniform numbers, a normal distribution, and points on and in a
 * circle, disk, sphere, ball, hemisphere and cosine-weighted hemisphere.
 *
 * <p>Given the same seed it produces the same sequence on every platform.
 *
 * <p>The generator has 256 bits of state and a period of {@code 2^256 - 1}. It is <b>not</b>
 * cryptographically secure. {@link #Rng(long)} expands the seed with SplitMix64, so any seed
 * (including 0 and consecutive numbers) gives a well-mixed state. {@link #split()} gives a new
 * generator for another thread or subsystem by seeding it from this one's output; the two streams
 * are independent in practice (this is the usual derivation, not a proof of non-overlap).
 *
 * <p>Methods that return a point write it into an array at an offset, so sampling allocates
 * nothing.
 *
 * <p><b>Thread safety.</b> Mutable and not thread-safe: use one instance per thread (see
 * {@link #split()}).
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Rng rng = new Rng(42L);                                                        // the same seed gives the same sequence
 * int die = rng.nextInt(1, 7);                                                   // 1 to 6
 * float[] direction = new float[3];
 * rng.onUnitSphere(direction, 0);                                                // a uniform point on the sphere
 * Rng other = rng.split();                                                       // an independent stream, for another thread
 * }</pre>
 */
@Experimental("jump-ahead and more distributions may be added")
public final class Rng {

    private long s0, s1, s2, s3;
    private double spareGaussian;
    private boolean hasSpare;

    /**
     * Creates a generator whose state is derived from {@code seed} with SplitMix64.
     *
     * @param seed the seed
     */
    public Rng(long seed) {
        seed(seed);
    }

    /**
     * Creates a generator with the given 256-bit state, which must not be all zero (an all-zero
     * state never changes).
     *
     * @param s0 the first 64-bit word of the state
     * @param s1 the second 64-bit word of the state
     * @param s2 the third 64-bit word of the state
     * @param s3 the fourth 64-bit word of the state
     * @throws IllegalArgumentException if the state is all zero
     */
    public Rng(long s0, long s1, long s2, long s3) {
        if ((s0 | s1 | s2 | s3) == 0) {
            throw new IllegalArgumentException("the state must not be all zero");
        }
        this.s0 = s0;
        this.s1 = s1;
        this.s2 = s2;
        this.s3 = s3;
    }

    /**
     * Re-seeds the generator exactly as {@link #Rng(long)} does and forgets any cached normal
     * sample.
     *
     * @param seed the seed
     */
    public void seed(long seed) {
        long x = seed;
        x += 0x9E3779B97F4A7C15L;
        s0 = mix(x);
        x += 0x9E3779B97F4A7C15L;
        s1 = mix(x);
        x += 0x9E3779B97F4A7C15L;
        s2 = mix(x);
        x += 0x9E3779B97F4A7C15L;
        s3 = mix(x);
        hasSpare = false;
    }

    /**
     * The SplitMix64 output function: a bijective, well-mixing scramble of a 64-bit value.
     */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Runs the SplitMix64 mixing function for a seed, which is what seeds the generator and is
     * exposed so that tests and tools can derive streams without a generator.
     *
     * @param seed the seed
     * @return the first value that SplitMix64 produces for {@code seed}: exposed so that tests and
     *     tools can derive streams without constructing a generator
     */
    public static long splitMix64(long seed) {
        return mix(seed + 0x9E3779B97F4A7C15L);
    }

    /**
     * Derives an independent generator from this one, which is how to give each thread or subsystem
     * its own stream; this generator advances.
     *
     * @return a new generator seeded from this one's output; this generator advances
     */
    public Rng split() {
        return new Rng(nextLong());
    }

    /**
     * Advances the generator and returns the next 64 random bits.
     *
     * @return the next 64 random bits
     */
    public long nextLong() {
        long result = Long.rotateLeft(s0 + s3, 23) + s0;
        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);
        return result;
    }

    /**
     * Advances the generator and returns 32 random bits, taken from the high half of the 64-bit
     * output because it has the better statistical quality.
     *
     * @return the next 32 random bits (the high half of {@link #nextLong}, which has the better
     *     statistical quality)
     */
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /**
     * Draws a uniform integer below a bound without modulo bias, using Lemire's multiply-and-reject
     * method.
     *
     * <p>{@code bound} must be positive.
     *
     * @param bound the bound
     * @return a uniform integer in {@code [0, bound)} without modulo bias (the multiply-and-reject
     *     method of Lemire)
     * @throws IllegalArgumentException if {@code bound} is not positive
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("the bound must be positive: " + bound);
        }
        long m = (nextInt() & 0xFFFFFFFFL) * bound;
        long low = m & 0xFFFFFFFFL;
        if (low < bound) {
            long threshold = (0x100000000L - bound) % bound;
            while (low < threshold) {
                m = (nextInt() & 0xFFFFFFFFL) * bound;
                low = m & 0xFFFFFFFFL;
            }
        }
        return (int) (m >>> 32);
    }

    /**
     * Draws a uniform integer from a range.
     *
     * @param low the low
     * @param high the high
     * @return a uniform integer in {@code [low, high)}
     * @throws IllegalArgumentException if the range is empty
     */
    public int nextInt(int low, int high) {
        if (high <= low) {
            throw new IllegalArgumentException("the range is empty: [" + low + ", " + high + ")");
        }
        return low + nextInt(high - low);
    }

    /**
     * Draws a uniform double in the unit interval using 53 random bits, which is every
     * representable step.
     *
     * @return a uniform double in {@code [0, 1)} with 53 random bits
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * Draws a uniform float in the unit interval using 24 random bits, which is every representable
     * step.
     *
     * @return a uniform float in {@code [0, 1)} with 24 random bits
     */
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    /**
     * Draws a uniform double from a range.
     *
     * @param low the low
     * @param high the high
     * @return a uniform double in {@code [low, high)}
     */
    public double nextDouble(double low, double high) {
        return low + (high - low) * nextDouble();
    }

    /**
     * Returns a random boolean.
     *
     * @return a uniformly distributed random boolean
     */
    public boolean nextBoolean() {
        return nextLong() < 0;
    }

    /**
     * Draws a normally distributed value with Marsaglia's polar method, which produces two values
     * per round; the second one is cached for the next call.
     *
     * @return a normally distributed double with mean 0 and standard deviation 1 (the polar method
     *     of Marsaglia; every second call returns the sample cached by the one before)
     */
    public double nextGaussian() {
        if (hasSpare) {
            hasSpare = false;
            return spareGaussian;
        }
        double u, v, s;
        do {
            u = 2 * nextDouble() - 1;
            v = 2 * nextDouble() - 1;
            s = u * u + v * v;
        } while (s >= 1 || s == 0);
        double f = Math.sqrt(-2 * Math.log(s) / s);
        spareGaussian = v * f;
        hasSpare = true;
        return u * f;
    }

    /**
     * Shuffles the first {@code n} elements of {@code a} uniformly (Fisher-Yates).
     *
     * @param a the array to shuffle
     * @param n the number of leading elements to shuffle
     */
    public void shuffle(int[] a, int n) {
        for (int i = n - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    // ------------------------------------------------------------ points

    /**
     * Writes a uniform point on the unit circle to {@code out[offset]} and {@code out[offset + 1]}.
     *
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void onUnitCircle(float[] out, int offset) {
        double a = 2 * Math.PI * nextDouble();
        out[offset] = (float) Math.cos(a);
        out[offset + 1] = (float) Math.sin(a);
    }

    /**
     * Writes a point uniformly distributed over the unit disk (by area) to {@code out[offset]} and
     * {@code out[offset + 1]}.
     *
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void inUnitDisk(float[] out, int offset) {
        double r = Math.sqrt(nextDouble()), a = 2 * Math.PI * nextDouble();
        out[offset] = (float) (r * Math.cos(a));
        out[offset + 1] = (float) (r * Math.sin(a));
    }

    /**
     * Writes a uniform point on the unit sphere to {@code out[offset .. offset + 3)}.
     *
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void onUnitSphere(float[] out, int offset) {
        double z = 2 * nextDouble() - 1, a = 2 * Math.PI * nextDouble();
        double r = Math.sqrt(Math.max(0, 1 - z * z));
        out[offset] = (float) (r * Math.cos(a));
        out[offset + 1] = (float) (r * Math.sin(a));
        out[offset + 2] = (float) z;
    }

    /**
     * Writes a point uniformly distributed in the unit ball (by volume) to
     * {@code out[offset .. offset + 3)}.
     *
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void inUnitBall(float[] out, int offset) {
        double z = 2 * nextDouble() - 1, a = 2 * Math.PI * nextDouble();
        double r = Math.sqrt(Math.max(0, 1 - z * z)), scale = Math.cbrt(nextDouble());
        out[offset] = (float) (scale * r * Math.cos(a));
        out[offset + 1] = (float) (scale * r * Math.sin(a));
        out[offset + 2] = (float) (scale * z);
    }

    /**
     * Writes a uniform direction on the hemisphere around the unit vector {@code (nx, ny, nz)} (the
     * points of the unit sphere with a non-negative dot product with it).
     *
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void onHemisphere(float nx, float ny, float nz, float[] out, int offset) {
        onUnitSphere(out, offset);
        if (out[offset] * nx + out[offset + 1] * ny + out[offset + 2] * nz < 0) {
            out[offset] = -out[offset];
            out[offset + 1] = -out[offset + 1];
            out[offset + 2] = -out[offset + 2];
        }
    }

    /**
     * Writes a direction on the hemisphere around the unit vector {@code (nx, ny, nz)} with density
     * proportional to the cosine of the angle to it (the method of Malley: a point of the unit disk
     * lifted onto the hemisphere), the right distribution for diffuse light sampling.
     *
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void cosineHemisphere(float nx, float ny, float nz, float[] out, int offset) {
        double r = Math.sqrt(nextDouble()), a = 2 * Math.PI * nextDouble();
        double x = r * Math.cos(a), y = r * Math.sin(a), z = Math.sqrt(Math.max(0, 1 - r * r));
        // an orthonormal basis (t, b, n) after Duff, Burgess, Christensen, Hughes, Mara (2017)
        double sign = Math.copySign(1.0, nz);
        double c = -1.0 / (sign + nz), d = nx * ny * c;
        double tx = 1 + sign * nx * nx * c, ty = sign * d, tz = -sign * nx;
        double bx = d, by = sign + ny * ny * c, bz = -ny;
        out[offset] = (float) (x * tx + y * bx + z * nx);
        out[offset + 1] = (float) (x * ty + y * by + z * ny);
        out[offset + 2] = (float) (x * tz + y * bz + z * nz);
    }
}
