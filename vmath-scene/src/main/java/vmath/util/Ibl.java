package vmath.util;

/**
 * The math of image-based lighting with the GGX microfacet model: the distribution and visibility
 * terms, importance sampling of the GGX lobe, the split-sum approximation of Karis (2013) that
 * turns the specular reflection of an environment into two precomputed pieces, and the generators
 * of those pieces on the CPU.
 *
 * <ul>
 *   <li>{@link #ggxDistribution}, {@link #smithGgxVisibility}, {@link #smithGgxIbl}: the normal
 *       distribution function and the two forms of the Smith geometry term used for direct lights
 *       and for image-based lighting.</li>
 *   <li>{@link #importanceSampleGgx}: a half vector distributed like the GGX lobe, from two random
 *       numbers.</li>
 *   <li>{@link #dfg} and {@link #brdfLut}: the second half of the split sum, the scale {@code A}
 *       and bias {@code B} of the specular colour {@code F0 * A + B} as a function of {@code n . v}
 *       and roughness; the lookup table has two floats per texel.</li>
 *   <li>{@link #prefilterGgx}: the first half of the split sum, the environment averaged over the
 *       GGX lobe around a direction, which a renderer stores per roughness in the levels of a cube
 *       map.</li>
 * </ul>
 *
 * <p>The sums use the Hammersley points of {@link Sequences}, so they are deterministic and
 * converge faster than random sampling. {@code roughness} is the perceptual (artist) roughness; the
 * GGX parameter is {@code alpha = roughness^2}. Environments are
 * {@link SphericalHarmonics.Radiance} functions; for diffuse light use {@link SphericalHarmonics}
 * instead.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] dfg = new double[2];
 * Ibl.dfg(0.5, 0.3, 256, dfg);                                                  // scale and bias of the split-sum specular term
 * float[] lut = new float[64 * 64 * 2];
 * Ibl.brdfLut(64, 128, lut);                                                    // the whole table, two floats per texel
 * }</pre>
 */
public final class Ibl {

    private Ibl() {
    }

    // ------------------------------------------------------------ the terms of the microfacet model

    /**
     * Evaluates the GGX normal distribution, which describes how microfacet normals are spread for
     * a given roughness; the roughness is the perceptual one, squared internally.
     *
     * @param noh the noh
     * @param roughness the roughness
     * @return the GGX normal distribution
     *     {@code D(h) = alpha^2 / (pi ((n.h)^2 (alpha^2 - 1) + 1)^2)} for {@code n . h} and the
     *     perceptual roughness ({@code alpha = roughness^2})
     */
    public static double ggxDistribution(double noh, double roughness) {
        double a = roughness * roughness, a2 = a * a;
        double d = noh * noh * (a2 - 1.0) + 1.0;
        return a2 / (Math.PI * d * d);
    }

    /**
     * Evaluates the height-correlated Smith visibility term of GGX for direct lighting, which folds
     * the geometry term and the denominator of the BRDF into one factor.
     *
     * <p>Both cosines must be positive.
     *
     * @param nov the nov
     * @param nol the nol
     * @param roughness the roughness
     * @return the height-correlated Smith visibility term {@code V = G / (4 (n.v) (n.l))} of GGX
     *     for direct lights (Heitz 2014), so that the specular BRDF is {@code D * V * F}
     */
    public static double smithGgxVisibility(double nov, double nol, double roughness) {
        double a2 = roughness * roughness * roughness * roughness;
        double gv = nol * Math.sqrt(nov * nov * (1.0 - a2) + a2);
        double gl = nov * Math.sqrt(nol * nol * (1.0 - a2) + a2);
        return 0.5 / (gv + gl);
    }

    /**
     * Evaluates the geometry term of Schlick-GGX in the form used for image-based lighting, which
     * uses a different remapping of roughness than direct lighting.
     *
     * @param nov the nov
     * @param nol the nol
     * @param roughness the roughness
     * @return the uncorrelated Smith geometry term {@code G = G1(n.v) G1(n.l)} of Schlick-GGX with
     *     {@code k = alpha / 2}, the form Karis uses for image-based lighting (it is a different
     *     approximation from {@link #smithGgxVisibility}, which is for direct lights)
     */
    public static double smithGgxIbl(double nov, double nol, double roughness) {
        double k = roughness * roughness / 2.0;
        return nov / (nov * (1.0 - k) + k) * (nol / (nol * (1.0 - k) + k));
    }

    // ------------------------------------------------------------ importance sampling

    /**
     * Samples a half vector distributed like the GGX lobe around the unit normal
     * {@code (nx, ny, nz)}: the density of {@code h} is {@code D(n.h) (n.h)}.
     *
     * <p>{@code u1} in {@code [0, 1)} gives the azimuth and {@code u2} in {@code [0, 1)} the polar
     * angle (0 is exactly the normal). Written to {@code out[0 .. 3)} as a unit vector.
     *
     * @param u1 the first uniform random number, in {@code [0, 1)}
     * @param u2 the second uniform random number, in {@code [0, 1)}
     * @param roughness the roughness
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param out receives the result in {@code [0, 3)}
     */
    public static void importanceSampleGgx(double u1, double u2, double roughness, double nx, double ny, double nz, double[] out) {
        double a = roughness * roughness;
        double phi = 2.0 * Math.PI * u1;
        double cosTheta = Math.sqrt((1.0 - u2) / (1.0 + (a * a - 1.0) * u2));
        double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
        double hx = sinTheta * Math.cos(phi), hy = sinTheta * Math.sin(phi), hz = cosTheta;
        // an orthonormal basis (t, b, n) after Duff, Burgess, Christensen, Hughes, Mara (2017)
        double sign = Math.copySign(1.0, nz);
        double c = -1.0 / (sign + nz), d = nx * ny * c;
        double tx = 1.0 + sign * nx * nx * c, ty = sign * d, tz = -sign * nx;
        double bx = d, by = sign + ny * ny * c, bz = -ny;
        out[0] = hx * tx + hy * bx + hz * nx;
        out[1] = hx * ty + hy * by + hz * ny;
        out[2] = hx * tz + hy * bz + hz * nz;
    }

    // ------------------------------------------------------------ the DFG term of the split sum

    /**
     * Computes the scale and bias of the split-sum specular term for the cosine {@code nov} between
     * the normal and the view vector and the perceptual {@code roughness}: the specular colour of a
     * surface of normal-incidence reflectance {@code F0} is {@code F0 * A + B}.
     *
     * <p>Computed by importance sampling the GGX lobe with {@code samples} Hammersley points;
     * {@code A} goes to {@code out[0]} and {@code B} to {@code out[1]}. Both are in {@code [0, 1]}
     * and {@code A + B <= 1}, the energy of a single reflection.
     *
     * @param nov the nov
     * @param roughness the roughness
     * @param samples the samples
     * @param out receives the result in {@code [0, 2)}
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    public static void dfg(double nov, double roughness, int samples, double[] out) {
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be positive: " + samples);
        }
        double nv = Math.max(nov, 1e-4);
        double vx = Math.sqrt(1.0 - nv * nv), vz = nv; // the view vector in the frame of the normal (0, 0, 1)
        double a = 0, b = 0;
        double[] h = new double[3];
        float[] u = new float[2];
        for (int i = 0; i < samples; i++) {
            Sequences.hammersley(i, samples, u, 0);
            importanceSampleGgx(u[0], u[1], roughness, 0.0, 0.0, 1.0, h);
            double voh = vx * h[0] + vz * h[2];
            double lz = 2.0 * voh * h[2] - vz; // the z of the reflected light direction
            double noh = h[2];
            if (lz > 0 && voh > 0) {
                double g = smithGgxIbl(nv, lz, roughness);
                double gVis = g * voh / (noh * nv);
                double fc = Math.pow(1.0 - voh, 5.0);
                a += (1.0 - fc) * gVis;
                b += fc * gVis;
            }
        }
        out[0] = a / samples;
        out[1] = b / samples;
    }

    /**
     * Fills {@code out} with the table of {@link #dfg} for {@code size x size} texels: texel
     * {@code (i, j)} holds {@code A} and {@code B} for {@code n . v = (i + 0.5) / size} and
     * {@code roughness = (j + 0.5) / size}, at {@code out[2 (j size + i)]} and
     * {@code out[2 (j size + i) + 1]}.
     *
     * <p>Each texel takes {@code samples} samples; 128 to 1024 gives a smooth table (the table is
     * smooth, so a texture of 32 x 32 or 64 x 64 is enough).
     *
     * @param size the size
     * @param samples the samples
     * @param out receives the result
     * @throws IllegalArgumentException if {@code out} cannot hold the table
     */
    public static void brdfLut(int size, int samples, float[] out) {
        if (size < 1 || (long) size * size * 2 > out.length) {
            throw new IllegalArgumentException("a " + size + " x " + size + " table of two floats does not fit in an array of " + out.length);
        }
        double[] ab = new double[2];
        for (int j = 0; j < size; j++) {
            double roughness = (j + 0.5) / size;
            for (int i = 0; i < size; i++) {
                dfg((i + 0.5) / size, roughness, samples, ab);
                out[2 * (j * size + i)] = (float) ab[0];
                out[2 * (j * size + i) + 1] = (float) ab[1];
            }
        }
    }

    // ------------------------------------------------------------ the prefiltered environment

    /**
     * Prefilters the environment over the GGX lobe around the direction {@code (nx, ny, nz)} (taken
     * as both the normal and the view direction, the usual simplification of the split sum): the
     * sum over {@code samples} importance-sampled light directions {@code l} of
     * {@code env(l) (n . l)}, divided by the sum of {@code n . l}.
     *
     * <p>Roughness 0 returns the environment in the direction itself; for a constant environment
     * the result is that constant. Written to {@code out[0 .. 3)}.
     *
     * @param env the env; must not be {@code null}
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param roughness the roughness
     * @param samples the samples
     * @param out receives the result in {@code [0, 3)}
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    public static void prefilterGgx(SphericalHarmonics.Radiance env, double nx, double ny, double nz, double roughness, int samples, double[] out) {
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be positive: " + samples);
        }
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        if (roughness <= 0.0) {
            env.radiance(nx, ny, nz, out);
            return;
        }
        double[] h = new double[3], rgb = new double[3];
        float[] u = new float[2];
        double r = 0, g = 0, b = 0, weight = 0;
        for (int i = 0; i < samples; i++) {
            Sequences.hammersley(i, samples, u, 0);
            importanceSampleGgx(u[0], u[1], roughness, nx, ny, nz, h);
            double voh = nx * h[0] + ny * h[1] + nz * h[2]; // the view direction is the normal
            double lx = 2.0 * voh * h[0] - nx, ly = 2.0 * voh * h[1] - ny, lz = 2.0 * voh * h[2] - nz;
            double nol = nx * lx + ny * ly + nz * lz;
            if (nol > 0) {
                env.radiance(lx, ly, lz, rgb);
                r += rgb[0] * nol;
                g += rgb[1] * nol;
                b += rgb[2] * nol;
                weight += nol;
            }
        }
        if (weight > 0) {
            out[0] = r / weight;
            out[1] = g / weight;
            out[2] = b / weight;
        } else {
            env.radiance(nx, ny, nz, out);
        }
    }
}
