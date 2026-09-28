package vmath.core;

import java.util.SplittableRandom;

/**
 * Seeded random inputs for property tests.
 *
 * <p>The seed defaults to a fixed value so runs are reproducible. Override it with
 * {@code -Dvmath.seed=<n>} (for example a nightly job using the date) to explore new inputs;
 * every failure message includes the seed.
 */
final class Rnd {

    static final long SEED = Long.getLong("vmath.seed", 0x5EEDL);
    /** Trials per property. */
    static final int N = Integer.getInteger("vmath.trials", 2_000);

    private final SplittableRandom r;

    private Rnd(long seed) {
        this.r = new SplittableRandom(seed);
    }

    static Rnd create() {
        return new Rnd(SEED);
    }

    double range(double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    boolean nextBoolean() {
        return r.nextBoolean();
    }

    // ------------------------------------------------------------ float

    Vec2f nextVec2f() {
        return new Vec2f((float) range(-10, 10), (float) range(-10, 10));
    }

    Vec3f nextVec3f() {
        return new Vec3f((float) range(-10, 10), (float) range(-10, 10), (float) range(-10, 10));
    }

    Vec4f nextVec4f() {
        return new Vec4f((float) range(-10, 10), (float) range(-10, 10), (float) range(-10, 10), (float) range(-10, 10));
    }

    Vec3f nextScaleVec3f() {
        return new Vec3f((float) range(0.25, 4), (float) range(0.25, 4), (float) range(0.25, 4));
    }

    Quatf nextUnitQuatf() {
        return nextUnitQuatd().toFloat();
    }

    /** Translation * rotation * scale: the typical, well-conditioned model matrix. */
    Mat4f nextTrsMat4f() {
        return Mat4f.translationRotateScale(nextVec3f().mul(10f), nextUnitQuatf(), nextScaleVec3f());
    }

    /** Dense matrix with entries in [-1, 1] plus a dominant diagonal, so it is safely invertible. */
    Mat4f nextDenseMat4f() {
        return nextDenseMat4d().toFloat();
    }

    Mat3f nextDenseMat3f() {
        return nextDenseMat3d().toFloat();
    }

    // ------------------------------------------------------------ double

    Vec2d nextVec2d() {
        return new Vec2d(range(-10, 10), range(-10, 10));
    }

    Vec3d nextVec3d() {
        return new Vec3d(range(-10, 10), range(-10, 10), range(-10, 10));
    }

    Vec4d nextVec4d() {
        return new Vec4d(range(-10, 10), range(-10, 10), range(-10, 10), range(-10, 10));
    }

    Vec3d nextScaleVec3d() {
        return new Vec3d(range(0.25, 4), range(0.25, 4), range(0.25, 4));
    }

    Quatd nextUnitQuatd() {
        // Uniform on the 3-sphere via normalized Gaussian 4-vector.
        double x, y, z, w, len2;
        do {
            x = gaussian();
            y = gaussian();
            z = gaussian();
            w = gaussian();
            len2 = x * x + y * y + z * z + w * w;
        } while (len2 < 1e-6);
        double inv = 1.0 / Math.sqrt(len2);
        return new Quatd(x * inv, y * inv, z * inv, w * inv);
    }

    Mat4d nextTrsMat4d() {
        return Mat4d.translationRotateScale(nextVec3d().mul(10.0), nextUnitQuatd(), nextScaleVec3d());
    }

    Mat4d nextDenseMat4d() {
        double[] m = new double[16];
        for (int i = 0; i < 16; i++) {
            m[i] = range(-1, 1);
        }
        for (int d = 0; d < 4; d++) {
            m[d * 5] += (r.nextBoolean() ? 4.0 : -4.0);
        }
        return new Mat4d(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]);
    }

    Mat3d nextDenseMat3d() {
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) {
            m[i] = range(-1, 1);
        }
        for (int d = 0; d < 3; d++) {
            m[d * 4] += (r.nextBoolean() ? 4.0 : -4.0);
        }
        return new Mat3d(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]);
    }

    private double gaussian() {
        // Box-Muller; SplittableRandom has no nextGaussian on JDK 17-.
        double u1 = Math.max(r.nextDouble(), 1e-300);
        double u2 = r.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
