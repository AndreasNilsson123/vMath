package vmath.util;

import vmath.core.Quatf;

/**
 * Real spherical harmonics up to the second band (nine coefficients per colour channel): a compact, smooth representation of a function on the sphere, used for diffuse ambient
 * light (an irradiance environment map in 27 numbers), light probes and low-frequency visibility.
 *
 * <p><b>Basis and layout.</b> The nine basis functions {@code Y_lm} are the real, orthonormal ones of Ramamoorthi and Hanrahan ("An Efficient Representation for Irradiance Environment
 * Maps", 2001), with the index {@code l * l + l + m}: 0 is the constant, 1 to 3 are proportional to {@code y, z, x}, and 4 to 8 are proportional to {@code xy, yz, 3z^2 - 1, xz,
 * x^2 - y^2}. A set of coefficients for the three channels red, green and blue is a {@code float[27]} with the three channels of coefficient {@code i} at {@code 3 i} to
 * {@code 3 i + 2}, which is the layout a shader uniform array of {@code vec3} wants. The directions are unit vectors in a frame with the y axis up (the frame itself is
 * irrelevant to the mathematics; the same one must be used for projecting and evaluating).
 *
 * <p><b>Operations.</b> {@link #project} turns a function on the sphere into coefficients by numerical integration with a Gauss-Legendre quadrature that integrates the basis products exactly;
 * {@link #evaluate} reconstructs the function; {@link #irradiance} gives the diffuse irradiance at a surface normal (the cosine-weighted integral of the radiance over the hemisphere) exactly
 * for the band-limited function, with the factors {@code pi, 2 pi / 3, pi / 4} of the clamped-cosine kernel; {@link #rotate} turns the whole environment by a rotation without going
 * back to the function; {@link #addDirectionalLight} and {@link #addConstant} build an environment from lights.
 *
 * <p>Two bands cannot represent sharp features: the reconstruction of a bright small light rings and goes negative, and only the <em>irradiance</em> (a heavily smoothed quantity, accurate
 * to a few per cent for typical environments) is well represented. Do not use these coefficients for specular reflection.
 *
 * <p><b>Thread safety.</b> Stateless apart from constants computed once: every method may be called from any number of threads at the same time.
 */
public final class SphericalHarmonics {

    /** The number of basis functions, and of coefficients per colour channel. */
    public static final int COEFFICIENTS = 9;
    /** The length of a coefficient array for the three colour channels, {@code 3 * COEFFICIENTS}. */
    public static final int LENGTH = 3 * COEFFICIENTS;

    /** A function on the sphere with a colour value in each direction: an environment map, as the numbers {@link #project} integrates. */
    @FunctionalInterface
    public interface Radiance {
        /** Writes the red, green and blue values for the unit direction {@code (x, y, z)} to {@code out[0 .. 3)}. */
        void radiance(double x, double y, double z, double[] out);
    }

    private static final double C0 = 0.28209479177387814; // 1 / (2 sqrt(pi))
    private static final double C1 = 0.4886025119029199; // sqrt(3) / (2 sqrt(pi))
    private static final double C2A = 1.0925484305920792; // sqrt(15) / (2 sqrt(pi))
    private static final double C2B = 0.31539156525252005; // sqrt(5) / (4 sqrt(pi))
    private static final double C2C = 0.5462742152960396; // sqrt(15) / (4 sqrt(pi))

    /** The factors of the clamped-cosine kernel by band: {@code pi}, {@code 2 pi / 3}, {@code pi / 4}, in the order of the coefficients. */
    private static final double[] COSINE = {Math.PI, 2 * Math.PI / 3, 2 * Math.PI / 3, 2 * Math.PI / 3, Math.PI / 4, Math.PI / 4, Math.PI / 4, Math.PI / 4, Math.PI / 4};

    /** Nine well-spread directions and the inverse of the matrix of the basis at them: used to rotate the coefficients. */
    private static final double[][] ROTATION_DIRECTIONS = new double[COEFFICIENTS][3];
    private static final double[][] INVERSE = new double[COEFFICIENTS][COEFFICIENTS];

    static {
        // a Fibonacci spiral over the sphere
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        double[] b = new double[COEFFICIENTS];
        double[][] m = new double[COEFFICIENTS][COEFFICIENTS];
        for (int k = 0; k < COEFFICIENTS; k++) {
            double z = 1.0 - 2.0 * (k + 0.5) / COEFFICIENTS, r = Math.sqrt(1.0 - z * z), phi = golden * k;
            ROTATION_DIRECTIONS[k][0] = r * Math.cos(phi);
            ROTATION_DIRECTIONS[k][1] = z;
            ROTATION_DIRECTIONS[k][2] = r * Math.sin(phi);
            basisUnit(ROTATION_DIRECTIONS[k][0], ROTATION_DIRECTIONS[k][1], ROTATION_DIRECTIONS[k][2], b);
            System.arraycopy(b, 0, m[k], 0, COEFFICIENTS);
        }
        invert(m, INVERSE);
    }

    private SphericalHarmonics() {
    }

    /** Inverts the square matrix {@code a} into {@code out} by Gauss-Jordan elimination with partial pivoting; the matrix of the rotation directions is well conditioned. */
    private static void invert(double[][] a, double[][] out) {
        int n = a.length;
        double[][] w = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, w[i], 0, n);
            w[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(w[r][col]) > Math.abs(w[piv][col])) {
                    piv = r;
                }
            }
            double[] t = w[col];
            w[col] = w[piv];
            w[piv] = t;
            double d = w[col][col];
            for (int j = 0; j < 2 * n; j++) {
                w[col][j] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r != col) {
                    double f = w[r][col];
                    for (int j = 0; j < 2 * n; j++) {
                        w[r][j] -= f * w[col][j];
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            System.arraycopy(w[i], n, out[i], 0, n);
        }
    }

    // ------------------------------------------------------------ basis

    private static void basisUnit(double x, double y, double z, double[] out) {
        out[0] = C0;
        out[1] = C1 * y;
        out[2] = C1 * z;
        out[3] = C1 * x;
        out[4] = C2A * x * y;
        out[5] = C2A * y * z;
        out[6] = C2B * (3.0 * z * z - 1.0);
        out[7] = C2A * x * z;
        out[8] = C2C * (x * x - y * y);
    }

    /**
     * Writes the nine basis functions for the direction {@code (x, y, z)} to {@code out[0 .. 9)}. The direction is normalised first, and must not be zero.
     */
    public static void basis(double x, double y, double z, double[] out) {
        double l = Math.sqrt(x * x + y * y + z * z);
        basisUnit(x / l, y / l, z / l, out);
    }

    // ------------------------------------------------------------ projection and evaluation

    /**
     * Projects {@code env} onto the basis: {@code coefficient_i = integral of env * Y_i over the sphere}, written to {@code out[0 .. 27)} (see the class comment for the layout).
     * The integral is evaluated by a product quadrature with {@code n} Gauss-Legendre nodes in the cosine of the polar angle and {@code 2 n} equally spaced azimuths, which is exact for
     * any polynomial of degree below {@code 2 n} in the direction; for a smooth environment a small {@code n} (8 to 16) already gives the coefficients to float precision. The
     * environment is evaluated {@code 2 n^2} times. A function with a jump, such as a sun disc, converges only slowly.
     */
    public static void project(Radiance env, int n, float[] out) {
        if (n < 1 || n > 4096) {
            throw new IllegalArgumentException("n must be in [1, 4096]: " + n);
        }
        if (out.length < LENGTH) {
            throw new IllegalArgumentException("the output needs " + LENGTH + " floats, has " + out.length);
        }
        double[] nodes = new double[n], weights = new double[n];
        gaussLegendre(n, nodes, weights);
        double[] acc = new double[LENGTH];
        double[] y = new double[COEFFICIENTS], rgb = new double[3];
        int m = 2 * n;
        for (int i = 0; i < n; i++) {
            double z = nodes[i], s = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            for (int j = 0; j < m; j++) {
                double phi = 2.0 * Math.PI * (j + 0.5) / m;
                double x = s * Math.cos(phi), yy = s * Math.sin(phi);
                // the library's convention has y up: the polar axis of the quadrature is y
                env.radiance(x, z, yy, rgb);
                basisUnit(x, z, yy, y);
                double w = weights[i] * (2.0 * Math.PI / m);
                for (int k = 0; k < COEFFICIENTS; k++) {
                    acc[3 * k] += w * y[k] * rgb[0];
                    acc[3 * k + 1] += w * y[k] * rgb[1];
                    acc[3 * k + 2] += w * y[k] * rgb[2];
                }
            }
        }
        for (int i = 0; i < LENGTH; i++) {
            out[i] = (float) acc[i];
        }
    }

    /** The nodes and weights of the {@code n}-point Gauss-Legendre rule on {@code [-1, 1]}, found by Newton's method on the Legendre polynomial. */
    private static void gaussLegendre(int n, double[] nodes, double[] weights) {
        for (int i = 0; i < n; i++) {
            double z = Math.cos(Math.PI * (i + 0.75) / (n + 0.5)), pp = 1;
            for (int iter = 0; iter < 100; iter++) {
                double p1 = 1.0, p2 = 0.0;
                for (int j = 0; j < n; j++) {
                    double p3 = p2;
                    p2 = p1;
                    p1 = ((2.0 * j + 1.0) * z * p2 - j * p3) / (j + 1.0);
                }
                pp = n * (z * p1 - p2) / (z * z - 1.0);
                double z1 = z;
                z = z1 - p1 / pp;
                if (Math.abs(z - z1) < 1e-15) {
                    break;
                }
            }
            nodes[i] = z;
            weights[i] = 2.0 / ((1.0 - z * z) * pp * pp);
        }
    }

    /** Reconstructs the function at the unit direction {@code (x, y, z)} from the coefficients: {@code sum of coefficient_i * Y_i}, written to {@code out[0 .. 3)}. */
    public static void evaluate(float[] coefficients, double x, double y, double z, double[] out) {
        weighted(coefficients, x, y, z, false, out);
    }

    /**
     * The irradiance at a surface with the normal {@code (nx, ny, nz)} (need not be unit) from the environment of the coefficients: the integral of {@code radiance * max(0, n . w)} over the
     * sphere of directions {@code w}, computed with the factors {@code pi}, {@code 2 pi / 3} and {@code pi / 4} of the clamped cosine per band. Exact when the environment really is
     * band-limited. For a Lambertian surface the outgoing radiance is {@code albedo / pi} times this. Written to {@code out[0 .. 3)}.
     */
    public static void irradiance(float[] coefficients, double nx, double ny, double nz, double[] out) {
        weighted(coefficients, nx, ny, nz, true, out);
    }

    /** The sum of the coefficients times the basis at the direction, each optionally times the cosine-kernel factor of its band; no allocation. */
    private static void weighted(float[] c, double dx, double dy, double dz, boolean cosine, double[] out) {
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double x = dx / l, y = dy / l, z = dz / l;
        double b0 = C0, b1 = C1 * y, b2 = C1 * z, b3 = C1 * x, b4 = C2A * x * y, b5 = C2A * y * z, b6 = C2B * (3.0 * z * z - 1.0), b7 = C2A * x * z, b8 = C2C * (x * x - y * y);
        if (cosine) {
            b0 *= COSINE[0];
            b1 *= COSINE[1];
            b2 *= COSINE[2];
            b3 *= COSINE[3];
            b4 *= COSINE[4];
            b5 *= COSINE[5];
            b6 *= COSINE[6];
            b7 *= COSINE[7];
            b8 *= COSINE[8];
        }
        out[0] = c[0] * b0 + c[3] * b1 + c[6] * b2 + c[9] * b3 + c[12] * b4 + c[15] * b5 + c[18] * b6 + c[21] * b7 + c[24] * b8;
        out[1] = c[1] * b0 + c[4] * b1 + c[7] * b2 + c[10] * b3 + c[13] * b4 + c[16] * b5 + c[19] * b6 + c[22] * b7 + c[25] * b8;
        out[2] = c[2] * b0 + c[5] * b1 + c[8] * b2 + c[11] * b3 + c[14] * b4 + c[17] * b5 + c[20] * b6 + c[23] * b7 + c[26] * b8;
    }

    /**
     * Multiplies each coefficient by the factor of the clamped-cosine kernel for its band, producing coefficients whose {@link #evaluate} is the {@link #irradiance}: what a renderer
     * uploads once per probe so that the shader only evaluates a polynomial. {@code out} may be the same array as {@code coefficients}.
     */
    public static void convolveWithCosine(float[] coefficients, float[] out) {
        for (int k = 0; k < COEFFICIENTS; k++) {
            for (int c = 0; c < 3; c++) {
                out[3 * k + c] = (float) (coefficients[3 * k + c] * COSINE[k]);
            }
        }
    }

    // ------------------------------------------------------------ building environments

    /** Adds a constant environment of the colour {@code (r, g, b)} to the coefficients: {@code coefficient_0 += colour / Y_0 = colour * 2 sqrt(pi)}. */
    public static void addConstant(float[] coefficients, double r, double g, double b) {
        coefficients[0] += (float) (r / C0);
        coefficients[1] += (float) (g / C0);
        coefficients[2] += (float) (b / C0);
    }

    /**
     * Adds a directional light from the direction {@code (dx, dy, dz)} (pointing from the surface towards the light, need not be unit) to the coefficients: a delta function of weight
     * {@code (r, g, b)}, so that the {@link #irradiance} of a surface facing the light squarely is about that colour (it is the band-limited approximation of {@code max(0, n . d)}, which
     * overshoots by up to 6% there). {@code r, g, b} are the irradiance on a surface perpendicular to the light.
     */
    public static void addDirectionalLight(float[] coefficients, double dx, double dy, double dz, double r, double g, double b) {
        double[] y = new double[COEFFICIENTS];
        basis(dx, dy, dz, y);
        for (int k = 0; k < COEFFICIENTS; k++) {
            coefficients[3 * k] += (float) (y[k] * r);
            coefficients[3 * k + 1] += (float) (y[k] * g);
            coefficients[3 * k + 2] += (float) (y[k] * b);
        }
    }

    // ------------------------------------------------------------ rotation

    /**
     * Rotates the environment by {@code rotation} (a unit quaternion): the environment seen from the rotated frame, that is {@code f'(w) = f(rotation^-1 w)}, so a light that was in
     * direction {@code d} is in direction {@code rotation d} afterwards. The rotation is exact for the band-limited function (the nine coefficients transform among themselves) and
     * is computed by sampling the basis at nine fixed directions, not from the function. {@code out} must not be the same array as {@code coefficients}.
     */
    public static void rotate(float[] coefficients, Quatf rotation, float[] out) {
        if (out == coefficients) {
            throw new IllegalArgumentException("the output must be a different array from the input");
        }
        double qx = rotation.x(), qy = rotation.y(), qz = rotation.z(), qw = rotation.w();
        double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        qx /= n;
        qy /= n;
        qz /= n;
        qw /= n;
        // the inverse rotation is the conjugate
        double[][] sampled = new double[COEFFICIENTS][COEFFICIENTS];
        double[] rotated = new double[3];
        for (int k = 0; k < COEFFICIENTS; k++) {
            double[] d = ROTATION_DIRECTIONS[k];
            rotateVector(-qx, -qy, -qz, qw, d[0], d[1], d[2], rotated);
            basisUnit(rotated[0], rotated[1], rotated[2], sampled[k]);
        }
        double[] t = new double[COEFFICIENTS];
        for (int c = 0; c < 3; c++) {
            for (int k = 0; k < COEFFICIENTS; k++) {
                double s = 0;
                for (int i = 0; i < COEFFICIENTS; i++) {
                    s += sampled[k][i] * coefficients[3 * i + c];
                }
                t[k] = s;
            }
            for (int i = 0; i < COEFFICIENTS; i++) {
                double s = 0;
                for (int k = 0; k < COEFFICIENTS; k++) {
                    s += INVERSE[i][k] * t[k];
                }
                out[3 * i + c] = (float) s;
            }
        }
    }

    private static void rotateVector(double qx, double qy, double qz, double qw, double vx, double vy, double vz, double[] out) {
        double tx = 2 * (qy * vz - qz * vy), ty = 2 * (qz * vx - qx * vz), tz = 2 * (qx * vy - qy * vx);
        out[0] = vx + qw * tx + (qy * tz - qz * ty);
        out[1] = vy + qw * ty + (qz * tx - qx * tz);
        out[2] = vz + qw * tz + (qx * ty - qy * tx);
    }
}
