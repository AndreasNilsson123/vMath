package vmath.core;

import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.joml.Vector4f;

/** Conversions to fresh, mutable JOML objects, used as the test oracle. */
final class J {

    private J() {
    }

    static Vector2f j(Vec2f v) {
        return new Vector2f(v.x(), v.y());
    }

    static Vector3f j(Vec3f v) {
        return new Vector3f(v.x(), v.y(), v.z());
    }

    static Vector4f j(Vec4f v) {
        return new Vector4f(v.x(), v.y(), v.z(), v.w());
    }

    static Quaternionf j(Quatf q) {
        return new Quaternionf(q.x(), q.y(), q.z(), q.w());
    }

    static Matrix3f j(Mat3f m) {
        return new Matrix3f(
                m.m00(), m.m01(), m.m02(),
                m.m10(), m.m11(), m.m12(),
                m.m20(), m.m21(), m.m22());
    }

    static Matrix4f j(Mat4f m) {
        return new Matrix4f(
                m.m00(), m.m01(), m.m02(), m.m03(),
                m.m10(), m.m11(), m.m12(), m.m13(),
                m.m20(), m.m21(), m.m22(), m.m23(),
                m.m30(), m.m31(), m.m32(), m.m33());
    }

    static Vector2d j(Vec2d v) {
        return new Vector2d(v.x(), v.y());
    }

    static Vector3d j(Vec3d v) {
        return new Vector3d(v.x(), v.y(), v.z());
    }

    static Vector4d j(Vec4d v) {
        return new Vector4d(v.x(), v.y(), v.z(), v.w());
    }

    static Quaterniond j(Quatd q) {
        return new Quaterniond(q.x(), q.y(), q.z(), q.w());
    }

    static Matrix3d j(Mat3d m) {
        return new Matrix3d(
                m.m00(), m.m01(), m.m02(),
                m.m10(), m.m11(), m.m12(),
                m.m20(), m.m21(), m.m22());
    }

    static Matrix4d j(Mat4d m) {
        return new Matrix4d(
                m.m00(), m.m01(), m.m02(), m.m03(),
                m.m10(), m.m11(), m.m12(), m.m13(),
                m.m20(), m.m21(), m.m22(), m.m23(),
                m.m30(), m.m31(), m.m32(), m.m33());
    }
}
