package vmath.anim;

/**
 * The array-level maths shared by {@link TransformHierarchy} and the skeleton code, so both use exactly the same formulas.
 *
 * <p>A transform is 10 floats: translation {@code (x, y, z)}, unit quaternion {@code (x, y, z, w)}, scale {@code (x, y, z)}. A matrix is
 * 16 floats in column-major order (index {@code column * 4 + row}), affine with bottom row {@code (0, 0, 0, 1)}.
 */
final class TransformMath {

    /** Floats per transform. */
    static final int TRS = 10;

    private TransformMath() {
    }

    /**
     * Writes {@code world[i] = world[parent] * T * R * S} into {@code w} at {@code i * 16}, for the transform at {@code trs[o..o+9]}; a
     * negative {@code parent} means the node is a root (no parent matrix).
     */
    static void compose(float[] w, int i, int parent, float[] trs, int o) {
        float tx = trs[o], ty = trs[o + 1], tz = trs[o + 2];
        float x = trs[o + 3], y = trs[o + 4], z = trs[o + 5], q = trs[o + 6];
        float sx = trs[o + 7], sy = trs[o + 8], sz = trs[o + 9];
        float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = q * x, wy = q * y, wz = q * z;
        float l00 = (1f - 2f * (yy + zz)) * sx, l10 = 2f * (xy + wz) * sx, l20 = 2f * (xz - wy) * sx;
        float l01 = 2f * (xy - wz) * sy, l11 = (1f - 2f * (xx + zz)) * sy, l21 = 2f * (yz + wx) * sy;
        float l02 = 2f * (xz + wy) * sz, l12 = 2f * (yz - wx) * sz, l22 = (1f - 2f * (xx + yy)) * sz;
        int d = i * 16;
        if (parent < 0) {
            w[d] = l00;
            w[d + 1] = l10;
            w[d + 2] = l20;
            w[d + 3] = 0f;
            w[d + 4] = l01;
            w[d + 5] = l11;
            w[d + 6] = l21;
            w[d + 7] = 0f;
            w[d + 8] = l02;
            w[d + 9] = l12;
            w[d + 10] = l22;
            w[d + 11] = 0f;
            w[d + 12] = tx;
            w[d + 13] = ty;
            w[d + 14] = tz;
            w[d + 15] = 1f;
            return;
        }
        int s = parent * 16;
        float a00 = w[s], a10 = w[s + 1], a20 = w[s + 2];
        float a01 = w[s + 4], a11 = w[s + 5], a21 = w[s + 6];
        float a02 = w[s + 8], a12 = w[s + 9], a22 = w[s + 10];
        float a03 = w[s + 12], a13 = w[s + 13], a23 = w[s + 14];
        w[d] = a00 * l00 + a01 * l10 + a02 * l20;
        w[d + 1] = a10 * l00 + a11 * l10 + a12 * l20;
        w[d + 2] = a20 * l00 + a21 * l10 + a22 * l20;
        w[d + 3] = 0f;
        w[d + 4] = a00 * l01 + a01 * l11 + a02 * l21;
        w[d + 5] = a10 * l01 + a11 * l11 + a12 * l21;
        w[d + 6] = a20 * l01 + a21 * l11 + a22 * l21;
        w[d + 7] = 0f;
        w[d + 8] = a00 * l02 + a01 * l12 + a02 * l22;
        w[d + 9] = a10 * l02 + a11 * l12 + a12 * l22;
        w[d + 10] = a20 * l02 + a21 * l12 + a22 * l22;
        w[d + 11] = 0f;
        w[d + 12] = a00 * tx + a01 * ty + a02 * tz + a03;
        w[d + 13] = a10 * tx + a11 * ty + a12 * tz + a13;
        w[d + 14] = a20 * tx + a21 * ty + a22 * tz + a23;
        w[d + 15] = 1f;
    }

    /** {@code out[oo..oo+15] = a * b} for affine matrices (out may alias neither input). */
    static void multiplyAffine(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        for (int c = 0; c < 3; c++) {
            float b0 = b[bo + c * 4], b1 = b[bo + c * 4 + 1], b2 = b[bo + c * 4 + 2];
            out[oo + c * 4] = a[ao] * b0 + a[ao + 4] * b1 + a[ao + 8] * b2;
            out[oo + c * 4 + 1] = a[ao + 1] * b0 + a[ao + 5] * b1 + a[ao + 9] * b2;
            out[oo + c * 4 + 2] = a[ao + 2] * b0 + a[ao + 6] * b1 + a[ao + 10] * b2;
            out[oo + c * 4 + 3] = 0f;
        }
        float tx = b[bo + 12], ty = b[bo + 13], tz = b[bo + 14];
        out[oo + 12] = a[ao] * tx + a[ao + 4] * ty + a[ao + 8] * tz + a[ao + 12];
        out[oo + 13] = a[ao + 1] * tx + a[ao + 5] * ty + a[ao + 9] * tz + a[ao + 13];
        out[oo + 14] = a[ao + 2] * tx + a[ao + 6] * ty + a[ao + 10] * tz + a[ao + 14];
        out[oo + 15] = 1f;
    }

    /** Spherical interpolation along the shortest arc, see {@link vmath.bulk.QuatArray#slerp(float[], int, float[], int, float, float[], int)}: one implementation for bulk and animation code. */
    static void slerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int oo) {
        vmath.bulk.QuatArray.slerp(a, ao, b, bo, t, out, oo);
    }

    /**
     * Inverse of the affine matrix at {@code m[mo..mo+15]} into {@code out[oo..oo+15]} (computed in double). Returns false, leaving
     * {@code out} untouched, if the matrix is singular (a zero scale, for example).
     */
    static boolean invertAffine(float[] m, int mo, float[] out, int oo) {
        double a = m[mo], b = m[mo + 4], c = m[mo + 8];
        double d = m[mo + 1], e = m[mo + 5], f = m[mo + 9];
        double g = m[mo + 2], h = m[mo + 6], i = m[mo + 10];
        double c00 = e * i - f * h, c01 = -(d * i - f * g), c02 = d * h - e * g;
        double det = a * c00 + b * c01 + c * c02;
        double scale = Math.abs(a) + Math.abs(b) + Math.abs(c) + Math.abs(d) + Math.abs(e) + Math.abs(f) + Math.abs(g) + Math.abs(h) + Math.abs(i);
        if (!(Math.abs(det) > 1e-18 * scale * scale * scale) || !Double.isFinite(det)) {
            return false;
        }
        double inv = 1.0 / det;
        double i00 = c00 * inv, i10 = c01 * inv, i20 = c02 * inv;
        double i01 = -(b * i - c * h) * inv, i11 = (a * i - c * g) * inv, i21 = -(a * h - b * g) * inv;
        double i02 = (b * f - c * e) * inv, i12 = -(a * f - c * d) * inv, i22 = (a * e - b * d) * inv;
        double tx = m[mo + 12], ty = m[mo + 13], tz = m[mo + 14];
        out[oo] = (float) i00;
        out[oo + 1] = (float) i10;
        out[oo + 2] = (float) i20;
        out[oo + 3] = 0f;
        out[oo + 4] = (float) i01;
        out[oo + 5] = (float) i11;
        out[oo + 6] = (float) i21;
        out[oo + 7] = 0f;
        out[oo + 8] = (float) i02;
        out[oo + 9] = (float) i12;
        out[oo + 10] = (float) i22;
        out[oo + 11] = 0f;
        out[oo + 12] = (float) -(i00 * tx + i01 * ty + i02 * tz);
        out[oo + 13] = (float) -(i10 * tx + i11 * ty + i12 * tz);
        out[oo + 14] = (float) -(i20 * tx + i21 * ty + i22 * tz);
        out[oo + 15] = 1f;
        return true;
    }

    /** The identity quaternion; read-only, shared. */
    static final float[] IDENTITY_Q = {0f, 0f, 0f, 1f};

    /** {@code out = a * conjugate(b)}: for unit quaternions, {@code a} relative to {@code b}. */
    static void multiplyQuatByConjugate(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = -b[bo], by = -b[bo + 1], bz = -b[bo + 2], bw = b[bo + 3];
        float x = aw * bx + ax * bw + ay * bz - az * by;
        float y = aw * by - ax * bz + ay * bw + az * bx;
        float z = aw * bz + ax * by - ay * bx + az * bw;
        float w = aw * bw - ax * bx - ay * by - az * bz;
        out[oo] = x;
        out[oo + 1] = y;
        out[oo + 2] = z;
        out[oo + 3] = w;
    }

    /** {@code out = a * b} for quaternions (Hamilton product; the same composition order as {@code Quatf.mul}). */
    static void multiplyQuat(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float x = aw * bx + ax * bw + ay * bz - az * by;
        float y = aw * by - ax * bz + ay * bw + az * bx;
        float z = aw * bz + ax * by - ay * bx + az * bw;
        float w = aw * bw - ax * bx - ay * by - az * bz;
        out[oo] = x;
        out[oo + 1] = y;
        out[oo + 2] = z;
        out[oo + 3] = w;
    }
}
