package vmath.core;

import java.util.Arrays;
import org.joml.Matrix3dc;
import org.joml.Matrix3fc;
import org.joml.Matrix4dc;
import org.joml.Matrix4fc;
import org.joml.Matrix4x3dc;
import org.joml.Matrix4x3fc;
import org.joml.Quaterniondc;
import org.joml.Quaternionfc;
import org.joml.Vector2dc;
import org.joml.Vector2fc;
import org.joml.Vector3dc;
import org.joml.Vector3fc;
import org.joml.Vector4dc;
import org.joml.Vector4fc;

/**
 * Tolerance assertions. A component passes when {@code |actual - expected| <= eps * max(1, |expected|)},
 * i.e. {@code eps} is absolute near zero and relative for large magnitudes.
 */
public final class Check {

    private Check() {
    }

    public static void check(boolean ok, int trial, String what) {
        if (!ok) {
            throw new AssertionError(ctx(trial) + what);
        }
    }

    // ------------------------------------------------------------ core

    public static void closeArr(double[] actual, double[] expected, double eps, int trial) {
        for (int i = 0; i < expected.length; i++) {
            double tol = eps * Math.max(1.0, Math.abs(expected[i]));
            boolean bothNaN = Double.isNaN(actual[i]) && Double.isNaN(expected[i]);
            if (!bothNaN && !(Math.abs(actual[i] - expected[i]) <= tol)) {
                throw new AssertionError(ctx(trial) + "component " + i + " differs by "
                        + Math.abs(actual[i] - expected[i]) + " (tol " + tol + ")\n  actual:   "
                        + Arrays.toString(actual) + "\n  expected: " + Arrays.toString(expected));
            }
        }
    }

    private static String ctx(int trial) {
        return "[trial " + trial + ", -Dvmath.seed=" + Rnd.SEED + "] ";
    }

    // ------------------------------------------------------------ scalars

    public static void close(double actual, double expected, double eps, int trial) {
        closeArr(new double[] {actual}, new double[] {expected}, eps, trial);
    }

    // ------------------------------------------------------------ float vs JOML

    public static void close(Vec2f a, Vector2fc e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y()}, new double[] {e.x(), e.y()}, eps, trial);
    }

    public static void close(Vec3f a, Vector3fc e, double eps, int trial) {
        closeArr(arr(a), new double[] {e.x(), e.y(), e.z()}, eps, trial);
    }

    public static void close(Vec4f a, Vector4fc e, double eps, int trial) {
        closeArr(arr(a), new double[] {e.x(), e.y(), e.z(), e.w()}, eps, trial);
    }

    public static void close(Quatf a, Quaternionfc e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y(), a.z(), a.w()}, new double[] {e.x(), e.y(), e.z(), e.w()}, eps, trial);
    }

    public static void close(Mat3f a, Matrix3fc e, double eps, int trial) {
        double[] ea = new double[9];
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                ea[c * 3 + r] = e.get(c, r);
            }
        }
        closeArr(arr(a), ea, eps, trial);
    }

    public static void close(Mat4f a, Matrix4fc e, double eps, int trial) {
        double[] ea = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                ea[c * 4 + r] = e.get(c, r);
            }
        }
        closeArr(arr(a), ea, eps, trial);
    }

    public static void close(Mat4x3f a, Matrix4x3fc e, double eps, int trial) {
        closeArr(arr(a), new double[] {e.m00(), e.m01(), e.m02(), e.m10(), e.m11(), e.m12(), e.m20(), e.m21(), e.m22(),
                e.m30(), e.m31(), e.m32()}, eps, trial);
    }

    public static void close(Mat4x3f a, Mat4x3f e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    private static double[] arr(Mat4x3f m) {
        float[] f = new float[12];
        m.writeTo(f, 0);
        return widen(f);
    }

    // ------------------------------------------------------------ float vs float

    public static void close(Vec2f a, Vec2f e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y()}, new double[] {e.x(), e.y()}, eps, trial);
    }

    public static void close(Vec3f a, Vec3f e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Vec4f a, Vec4f e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Mat3f a, Mat3f e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Mat4f a, Mat4f e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Quatf a, Quatf e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y(), a.z(), a.w()}, new double[] {e.x(), e.y(), e.z(), e.w()}, eps, trial);
    }

    public static void sameRotation(Quatf a, Quatf e, double eps, int trial) {
        check(a.sameRotation(e, (float) eps), trial, "rotations differ: " + a + " vs " + e);
    }

    private static double[] arr(Vec3f v) {
        return new double[] {v.x(), v.y(), v.z()};
    }

    private static double[] arr(Vec4f v) {
        return new double[] {v.x(), v.y(), v.z(), v.w()};
    }

    private static double[] arr(Mat3f m) {
        float[] f = new float[9];
        m.writeTo(f, 0);
        return widen(f);
    }

    private static double[] arr(Mat4f m) {
        float[] f = new float[16];
        m.writeTo(f, 0);
        return widen(f);
    }

    private static double[] widen(float[] f) {
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            d[i] = f[i];
        }
        return d;
    }

    // ------------------------------------------------------------ double vs JOML

    public static void close(Vec2d a, Vector2dc e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y()}, new double[] {e.x(), e.y()}, eps, trial);
    }

    public static void close(Vec3d a, Vector3dc e, double eps, int trial) {
        closeArr(arr(a), new double[] {e.x(), e.y(), e.z()}, eps, trial);
    }

    public static void close(Vec4d a, Vector4dc e, double eps, int trial) {
        closeArr(arr(a), new double[] {e.x(), e.y(), e.z(), e.w()}, eps, trial);
    }

    public static void close(Quatd a, Quaterniondc e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y(), a.z(), a.w()}, new double[] {e.x(), e.y(), e.z(), e.w()}, eps, trial);
    }

    public static void close(Mat3d a, Matrix3dc e, double eps, int trial) {
        double[] ea = new double[9];
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                ea[c * 3 + r] = e.get(c, r);
            }
        }
        closeArr(arr(a), ea, eps, trial);
    }

    public static void close(Mat4d a, Matrix4dc e, double eps, int trial) {
        double[] ea = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                ea[c * 4 + r] = e.get(c, r);
            }
        }
        closeArr(arr(a), ea, eps, trial);
    }

    public static void close(Mat4x3d a, Matrix4x3dc e, double eps, int trial) {
        closeArr(arr(a), new double[] {e.m00(), e.m01(), e.m02(), e.m10(), e.m11(), e.m12(), e.m20(), e.m21(), e.m22(),
                e.m30(), e.m31(), e.m32()}, eps, trial);
    }

    public static void close(Mat4x3d a, Mat4x3d e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    private static double[] arr(Mat4x3d m) {
        double[] d = new double[12];
        m.writeTo(d, 0);
        return d;
    }

    // ------------------------------------------------------------ double vs double

    public static void close(Vec2d a, Vec2d e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y()}, new double[] {e.x(), e.y()}, eps, trial);
    }

    public static void close(Vec3d a, Vec3d e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Vec4d a, Vec4d e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Mat3d a, Mat3d e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Mat4d a, Mat4d e, double eps, int trial) {
        closeArr(arr(a), arr(e), eps, trial);
    }

    public static void close(Quatd a, Quatd e, double eps, int trial) {
        closeArr(new double[] {a.x(), a.y(), a.z(), a.w()}, new double[] {e.x(), e.y(), e.z(), e.w()}, eps, trial);
    }

    public static void sameRotation(Quatd a, Quatd e, double eps, int trial) {
        check(a.sameRotation(e, eps), trial, "rotations differ: " + a + " vs " + e);
    }

    private static double[] arr(Vec3d v) {
        return new double[] {v.x(), v.y(), v.z()};
    }

    private static double[] arr(Vec4d v) {
        return new double[] {v.x(), v.y(), v.z(), v.w()};
    }

    private static double[] arr(Mat3d m) {
        double[] d = new double[9];
        m.writeTo(d, 0);
        return d;
    }

    private static double[] arr(Mat4d m) {
        double[] d = new double[16];
        m.writeTo(d, 0);
        return d;
    }
}
