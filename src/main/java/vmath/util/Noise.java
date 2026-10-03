package vmath.util;

import vmath.annotations.Experimental;

/**
 * Coherent noise: smooth pseudo-random functions of a position, for terrain, clouds, textures, wind and animation jitter. All functions are deterministic (the same position and
 * {@code seed} always give the same value, on every platform), allocate nothing, are continuous, and have no stored table: the lattice values come from an integer hash.
 *
 * <ul>
 *   <li><b>Value noise</b> ({@link #value2}, {@link #value3}): random values on the integer lattice, smoothly interpolated. Cheap; blocky in character.</li>
 *   <li><b>Gradient (Perlin) noise</b> ({@link #perlin2}, {@link #perlin3}, {@link #perlin4}): random gradients on the lattice; zero at every lattice point. The gradients are the
 *       {@code +-1} diagonal vectors, and the result is scaled by {@code 2 / dimension}, which makes {@code [-1, 1]} a <em>guaranteed</em> bound (the largest possible value of this
 *       construction is {@code dimension / 2}). The typical amplitude is therefore well below 1; see the measured values in {@code docs/NOISE.md}.</li>
 *   <li><b>Simplex noise</b> ({@link #simplex2}, {@link #simplex3}): gradient noise on a simplex grid: fewer lattice points per sample than Perlin in 3D, and less axis-aligned structure.
 *       Scaled so that the largest value found by dense sampling is just under 1.</li>
 *   <li><b>Worley (cellular) noise</b> ({@link #worley2}, {@link #worley3}): the distances to the nearest and second nearest of a jittered grid of feature points.</li>
 *   <li><b>Curl noise</b> ({@link #curl2}, {@link #curl3}): the curl of a noise potential, a divergence-free vector field, for fluid-looking particle motion.</li>
 *   <li><b>Fractal sums</b> ({@link #fbm2}, {@link #fbm3}), <b>domain warping</b> ({@link #warpedFbm2}) and <b>batch filling</b> of a {@code float[]} grid ({@link #fill2}).</li>
 * </ul>
 *
 * <p>Coordinates are in lattice units: the features are about one unit across, so scale the position before the call to change the size of the features.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time.
 */
@Experimental("simplex noise in 4D and analytic derivatives may be added")
public final class Noise {

    /** Which noise {@link #fbm2}, {@link #fbm3} and {@link #fill2} sum. */
    public enum Kind {
        /** {@link Noise#value2} and {@link Noise#value3}. */
        VALUE,
        /** {@link Noise#perlin2} and {@link Noise#perlin3}. */
        PERLIN,
        /** {@link Noise#simplex2} and {@link Noise#simplex3}. */
        SIMPLEX
    }

    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0), G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final double F3 = 1.0 / 3.0, G3 = 1.0 / 6.0;
    private static final double SIMPLEX2_SCALE = 99.0, SIMPLEX3_SCALE = 75.0;
    private static final double[] GRAD2 = new double[16];

    static {
        for (int k = 0; k < 8; k++) {
            GRAD2[2 * k] = Math.cos(k * Math.PI / 4);
            GRAD2[2 * k + 1] = Math.sin(k * Math.PI / 4);
        }
    }

    private static final int[] GRAD3 = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1, 0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1};

    private Noise() {
    }

    // ------------------------------------------------------------ hashing

    /** A 32-bit hash of an integer lattice point and a seed, well mixed in all bits. */
    static int hash(int x, int y, int z, int w, int seed) {
        int h = seed * 0x2545F491 + x * 0x27D4EB2D + y * 0x165667B1 + z * 0x9E3779B1 + w * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    static double toUnit(int h) {
        return (h >>> 8) * (1.0 / (1 << 24));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ------------------------------------------------------------ value noise

    /** 2D value noise in {@code [-1, 1]}. */
    public static double value2(double x, double y, int seed) {
        int xi = floor(x), yi = floor(y);
        double fx = x - xi, fy = y - yi;
        double u = fade(fx), v = fade(fy);
        double a = lattice(xi, yi, 0, seed), b = lattice(xi + 1, yi, 0, seed);
        double c = lattice(xi, yi + 1, 0, seed), d = lattice(xi + 1, yi + 1, 0, seed);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    /** 3D value noise in {@code [-1, 1]}. */
    public static double value3(double x, double y, double z, int seed) {
        int xi = floor(x), yi = floor(y), zi = floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        double u = fade(fx), v = fade(fy), w = fade(fz);
        double a = lerp(lattice(xi, yi, zi, seed), lattice(xi + 1, yi, zi, seed), u);
        double b = lerp(lattice(xi, yi + 1, zi, seed), lattice(xi + 1, yi + 1, zi, seed), u);
        double c = lerp(lattice(xi, yi, zi + 1, seed), lattice(xi + 1, yi, zi + 1, seed), u);
        double d = lerp(lattice(xi, yi + 1, zi + 1, seed), lattice(xi + 1, yi + 1, zi + 1, seed), u);
        return lerp(lerp(a, b, v), lerp(c, d, v), w);
    }

    private static double lattice(int x, int y, int z, int seed) {
        return 2 * toUnit(hash(x, y, z, 0, seed)) - 1;
    }

    // ------------------------------------------------------------ gradient (Perlin) noise

    /** 2D gradient noise in {@code [-1, 1]}; 0 at the integer lattice points. */
    public static double perlin2(double x, double y, int seed) {
        int xi = floor(x), yi = floor(y);
        double fx = x - xi, fy = y - yi;
        double u = fade(fx), v = fade(fy);
        double n00 = grad2(hash(xi, yi, 0, 0, seed), fx, fy), n10 = grad2(hash(xi + 1, yi, 0, 0, seed), fx - 1, fy);
        double n01 = grad2(hash(xi, yi + 1, 0, 0, seed), fx, fy - 1), n11 = grad2(hash(xi + 1, yi + 1, 0, 0, seed), fx - 1, fy - 1);
        return lerp(lerp(n00, n10, u), lerp(n01, n11, u), v); // 2 / dimension = 1
    }

    private static double grad2(int h, double dx, double dy) {
        return dx * (1 - 2 * (h & 1)) + dy * (1 - ((h & 2)));
    }

    /** 3D gradient noise in {@code [-1, 1]} (scaled by 2/3); 0 at the integer lattice points. */
    public static double perlin3(double x, double y, double z, int seed) {
        int xi = floor(x), yi = floor(y), zi = floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        double u = fade(fx), v = fade(fy), w = fade(fz);
        double a = lerp(grad3(hash(xi, yi, zi, 0, seed), fx, fy, fz), grad3(hash(xi + 1, yi, zi, 0, seed), fx - 1, fy, fz), u);
        double b = lerp(grad3(hash(xi, yi + 1, zi, 0, seed), fx, fy - 1, fz), grad3(hash(xi + 1, yi + 1, zi, 0, seed), fx - 1, fy - 1, fz), u);
        double c = lerp(grad3(hash(xi, yi, zi + 1, 0, seed), fx, fy, fz - 1), grad3(hash(xi + 1, yi, zi + 1, 0, seed), fx - 1, fy, fz - 1), u);
        double d = lerp(grad3(hash(xi, yi + 1, zi + 1, 0, seed), fx, fy - 1, fz - 1), grad3(hash(xi + 1, yi + 1, zi + 1, 0, seed), fx - 1, fy - 1, fz - 1), u);
        return lerp(lerp(a, b, v), lerp(c, d, v), w) * (2.0 / 3.0);
    }

    private static double grad3(int h, double dx, double dy, double dz) {
        return dx * (1 - 2 * (h & 1)) + dy * (1 - (h & 2)) + dz * (1 - ((h >> 1) & 2));
    }

    /** 4D gradient noise in {@code [-1, 1]} (scaled by 1/2); 0 at the integer lattice points. Use the fourth coordinate for time. */
    public static double perlin4(double x, double y, double z, double t, int seed) {
        int xi = floor(x), yi = floor(y), zi = floor(z), ti = floor(t);
        double fx = x - xi, fy = y - yi, fz = z - zi, ft = t - ti;
        double u = fade(fx), v = fade(fy), w = fade(fz), s = fade(ft);
        double r0 = perlin4Slice(xi, yi, zi, ti, fx, fy, fz, ft, u, v, w, seed);
        double r1 = perlin4Slice(xi, yi, zi, ti + 1, fx, fy, fz, ft - 1, u, v, w, seed);
        return lerp(r0, r1, s) * 0.5;
    }

    private static double perlin4Slice(int xi, int yi, int zi, int ti, double fx, double fy, double fz, double ft, double u, double v, double w, int seed) {
        double a = lerp(grad4(hash(xi, yi, zi, ti, seed), fx, fy, fz, ft), grad4(hash(xi + 1, yi, zi, ti, seed), fx - 1, fy, fz, ft), u);
        double b = lerp(grad4(hash(xi, yi + 1, zi, ti, seed), fx, fy - 1, fz, ft), grad4(hash(xi + 1, yi + 1, zi, ti, seed), fx - 1, fy - 1, fz, ft), u);
        double c = lerp(grad4(hash(xi, yi, zi + 1, ti, seed), fx, fy, fz - 1, ft), grad4(hash(xi + 1, yi, zi + 1, ti, seed), fx - 1, fy, fz - 1, ft), u);
        double d = lerp(grad4(hash(xi, yi + 1, zi + 1, ti, seed), fx, fy - 1, fz - 1, ft), grad4(hash(xi + 1, yi + 1, zi + 1, ti, seed), fx - 1, fy - 1, fz - 1, ft), u);
        return lerp(lerp(a, b, v), lerp(c, d, v), w);
    }

    private static double grad4(int h, double dx, double dy, double dz, double dt) {
        return dx * (1 - 2 * (h & 1)) + dy * (1 - (h & 2)) + dz * (1 - ((h >> 1) & 2)) + dt * (1 - ((h >> 2) & 2));
    }

    // ------------------------------------------------------------ simplex noise

    /** 2D simplex noise, scaled so that the values found by dense sampling stay just inside {@code [-1, 1]}. */
    public static double simplex2(double x, double y, int seed) {
        double s = (x + y) * F2;
        int i = floor(x + s), j = floor(y + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t), y0 = y - (j - t);
        int i1 = x0 > y0 ? 1 : 0, j1 = 1 - i1;
        double x1 = x0 - i1 + G2, y1 = y0 - j1 + G2;
        double x2 = x0 - 1 + 2 * G2, y2 = y0 - 1 + 2 * G2;
        double n = corner2(hash(i, j, 0, 0, seed), x0, y0) + corner2(hash(i + i1, j + j1, 0, 0, seed), x1, y1) + corner2(hash(i + 1, j + 1, 0, 0, seed), x2, y2);
        return SIMPLEX2_SCALE * n;
    }

    private static double corner2(int h, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t <= 0) {
            return 0;
        }
        int g = (h & 7) * 2;
        t *= t;
        return t * t * (GRAD2[g] * x + GRAD2[g + 1] * y);
    }

    /** 3D simplex noise, scaled so that the values found by dense sampling stay just inside {@code [-1, 1]}. */
    public static double simplex3(double x, double y, double z, int seed) {
        double s = (x + y + z) * F3;
        int i = floor(x + s), j = floor(y + s), k = floor(z + s);
        double t = (i + j + k) * G3;
        double x0 = x - (i - t), y0 = y - (j - t), z0 = z - (k - t);
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1;
            } else if (x0 < z0) {
                i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1;
            } else {
                i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            }
        }
        double x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2 * G3, y2 = y0 - j2 + 2 * G3, z2 = z0 - k2 + 2 * G3;
        double x3 = x0 - 1 + 3 * G3, y3 = y0 - 1 + 3 * G3, z3 = z0 - 1 + 3 * G3;
        double n = corner3(hash(i, j, k, 0, seed), x0, y0, z0) + corner3(hash(i + i1, j + j1, k + k1, 0, seed), x1, y1, z1)
                + corner3(hash(i + i2, j + j2, k + k2, 0, seed), x2, y2, z2) + corner3(hash(i + 1, j + 1, k + 1, 0, seed), x3, y3, z3);
        return SIMPLEX3_SCALE * n;
    }

    private static double corner3(int h, double x, double y, double z) {
        double t = 0.5 - x * x - y * y - z * z;
        if (t <= 0) {
            return 0;
        }
        int g = ((h & 0xFFFF) % 12) * 3;
        t *= t;
        return t * t * (GRAD3[g] * x + GRAD3[g + 1] * y + GRAD3[g + 2] * z);
    }

    // ------------------------------------------------------------ Worley noise

    /**
     * 2D Worley noise: the distance to the nearest ({@code out[0]}) and to the second nearest ({@code out[1]}) feature point, one point per lattice cell at a random position
     * within the cell, moved towards the cell centre by {@code 1 - jitter} (0 gives a regular grid, 1 fully random positions). The nine cells around the sample are searched; the
     * nearest distance is exact, and the second nearest is exact except in rare configurations where it lies beyond those cells (rate in {@code docs/NOISE.md}).
     */
    public static void worley2(double x, double y, int seed, double jitter, double[] out) {
        int xi = floor(x), yi = floor(y);
        double best1 = Double.POSITIVE_INFINITY, best2 = Double.POSITIVE_INFINITY;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                int cx = xi + di, cy = yi + dj;
                int h = hash(cx, cy, 0, 0, seed);
                double px = cx + 0.5 + (toUnit(h) - 0.5) * jitter;
                double py = cy + 0.5 + (toUnit(hash(cx, cy, 1, 0, seed)) - 0.5) * jitter;
                double d = (px - x) * (px - x) + (py - y) * (py - y);
                if (d < best1) {
                    best2 = best1;
                    best1 = d;
                } else if (d < best2) {
                    best2 = d;
                }
            }
        }
        out[0] = Math.sqrt(best1);
        out[1] = Math.sqrt(best2);
    }

    /** 3D Worley noise; see {@link #worley2}: the 27 cells around the sample are searched. */
    public static void worley3(double x, double y, double z, int seed, double jitter, double[] out) {
        int xi = floor(x), yi = floor(y), zi = floor(z);
        double best1 = Double.POSITIVE_INFINITY, best2 = Double.POSITIVE_INFINITY;
        for (int dk = -1; dk <= 1; dk++) {
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int cx = xi + di, cy = yi + dj, cz = zi + dk;
                    double px = cx + 0.5 + (toUnit(hash(cx, cy, cz, 0, seed)) - 0.5) * jitter;
                    double py = cy + 0.5 + (toUnit(hash(cx, cy, cz, 1, seed)) - 0.5) * jitter;
                    double pz = cz + 0.5 + (toUnit(hash(cx, cy, cz, 2, seed)) - 0.5) * jitter;
                    double d = (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
                    if (d < best1) {
                        best2 = best1;
                        best1 = d;
                    } else if (d < best2) {
                        best2 = d;
                    }
                }
            }
        }
        out[0] = Math.sqrt(best1);
        out[1] = Math.sqrt(best2);
    }

    // ------------------------------------------------------------ curl noise

    private static final double CURL_EPS = 1e-4;

    /**
     * The 2D curl of a gradient-noise potential: the vector {@code (d psi / dy, -d psi / dx)}, written to {@code out[0]} and {@code out[1]}. The field is divergence-free: it has
     * no sources or sinks, so particles carried by it swirl without bunching up. The derivatives are central differences with a step of 1e-4.
     */
    public static void curl2(double x, double y, int seed, double[] out) {
        out[0] = (perlin2(x, y + CURL_EPS, seed) - perlin2(x, y - CURL_EPS, seed)) / (2 * CURL_EPS);
        out[1] = -(perlin2(x + CURL_EPS, y, seed) - perlin2(x - CURL_EPS, y, seed)) / (2 * CURL_EPS);
    }

    /** The 3D curl of a vector potential made of three independent gradient noises (seeds {@code seed}, {@code seed + 1}, {@code seed + 2}), written to {@code out[0 .. 3)}. */
    public static void curl3(double x, double y, double z, int seed, double[] out) {
        double e = CURL_EPS, inv = 1 / (2 * e);
        double dz1 = (perlin3(x, y, z + e, seed) - perlin3(x, y, z - e, seed)) * inv;
        double dy1 = (perlin3(x, y + e, z, seed) - perlin3(x, y - e, z, seed)) * inv;
        double dx2 = (perlin3(x + e, y, z, seed + 1) - perlin3(x - e, y, z, seed + 1)) * inv;
        double dz2 = (perlin3(x, y, z + e, seed + 1) - perlin3(x, y, z - e, seed + 1)) * inv;
        double dx3 = (perlin3(x + e, y, z, seed + 2) - perlin3(x - e, y, z, seed + 2)) * inv;
        double dy3 = (perlin3(x, y + e, z, seed + 2) - perlin3(x, y - e, z, seed + 2)) * inv;
        out[0] = dy3 - dz2;
        out[1] = dz1 - dx3;
        out[2] = dx2 - dy1;
    }

    // ------------------------------------------------------------ fractal sums, warping, batches

    /** One octave of the chosen noise in 2D. */
    public static double sample2(Kind kind, double x, double y, int seed) {
        switch (kind) {
            case VALUE:
                return value2(x, y, seed);
            case PERLIN:
                return perlin2(x, y, seed);
            default:
                return simplex2(x, y, seed);
        }
    }

    /** One octave of the chosen noise in 3D. */
    public static double sample3(Kind kind, double x, double y, double z, int seed) {
        switch (kind) {
            case VALUE:
                return value3(x, y, z, seed);
            case PERLIN:
                return perlin3(x, y, z, seed);
            default:
                return simplex3(x, y, z, seed);
        }
    }

    /**
     * Fractional Brownian motion: the sum of {@code octaves} noises, each {@code lacunarity} times finer (2 is usual) and {@code gain} times as strong (0.5 is usual) as the one
     * before, divided by the sum of the strengths so that the result stays in the range of the single noise. Each octave uses its own seed.
     */
    public static double fbm2(Kind kind, double x, double y, int seed, int octaves, double lacunarity, double gain) {
        double sum = 0, amplitude = 1, norm = 0, frequency = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * sample2(kind, x * frequency, y * frequency, seed + o * 1013);
            norm += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return norm > 0 ? sum / norm : 0;
    }

    /** 3D {@link #fbm2}. */
    public static double fbm3(Kind kind, double x, double y, double z, int seed, int octaves, double lacunarity, double gain) {
        double sum = 0, amplitude = 1, norm = 0, frequency = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * sample3(kind, x * frequency, y * frequency, z * frequency, seed + o * 1013);
            norm += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return norm > 0 ? sum / norm : 0;
    }

    /**
     * Domain-warped fractal noise: {@link #fbm2} evaluated at a position that has itself been moved by two other fractal noises, {@code strength} lattice units at most. Gives the
     * swirling, marble-like look that plain noise lacks.
     */
    public static double warpedFbm2(Kind kind, double x, double y, int seed, int octaves, double lacunarity, double gain, double strength) {
        double wx = fbm2(kind, x + 5.2, y + 1.3, seed + 7919, octaves, lacunarity, gain);
        double wy = fbm2(kind, x + 9.7, y + 8.3, seed + 15485, octaves, lacunarity, gain);
        return fbm2(kind, x + strength * wx, y + strength * wy, seed, octaves, lacunarity, gain);
    }

    /**
     * Fills {@code out[0 .. width * height)} (row by row, the {@code x} index running fastest) with {@link #fbm2} sampled on a regular grid: sample {@code (i, j)} is taken at
     * {@code (x0 + i dx, y0 + j dy)}. With {@code octaves = 1} this is the plain noise.
     */
    public static void fill2(Kind kind, float[] out, int width, int height, double x0, double y0, double dx, double dy, int seed, int octaves, double lacunarity, double gain) {
        if (width < 0 || height < 0 || (long) width * height > out.length) {
            throw new IllegalArgumentException("a " + width + " x " + height + " grid does not fit in an array of " + out.length);
        }
        int k = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                out[k++] = (float) fbm2(kind, x0 + i * dx, y0 + j * dy, seed, octaves, lacunarity, gain);
            }
        }
    }
}
