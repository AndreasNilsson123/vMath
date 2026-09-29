package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * A transform as translation, rotation and scale (TRS): 40 bytes, no matrix. It is the natural representation for
 * scene-graph nodes and animation, because pieces can be blended independently ({@link #blend}) and inspected
 * without decomposing.
 *
 * <p><b>Composition caveat.</b> A TRS cannot represent every product of two TRS transforms: a rotation followed by a
 * <em>non-uniform</em> scale produces shear. {@link #mul} is exact when the left operand's scale is uniform (the common
 * case), and otherwise applies the scales component-wise in the child's frame, which is what Unity and most engines do. Use
 * {@link Mat4x3f} when exact shear matters. {@link #inverse()} has the same limit.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 */
@GenerateDouble
@ValueType
public record Transformf(Vec3f translation, Quatf rotation, Vec3f scale) {

    public static final Transformf IDENTITY = new Transformf(Vec3f.ZERO, Quatf.IDENTITY, Vec3f.ONE);

    public static Transformf ofTranslation(Vec3f t) {
        return new Transformf(t, Quatf.IDENTITY, Vec3f.ONE);
    }

    public static Transformf ofRotation(Quatf q) {
        return new Transformf(Vec3f.ZERO, q, Vec3f.ONE);
    }

    public static Transformf ofScale(Vec3f s) {
        return new Transformf(Vec3f.ZERO, Quatf.IDENTITY, s);
    }

    /** The TRS of an affine matrix (see {@link Mat4f#decompose()}: no shear, mirroring folds into a negative x scale). */
    public static Transformf fromMat4(Mat4f m) {
        Mat4f.Trs d = m.decompose();
        return new Transformf(d.translation(), d.rotation(), d.scale());
    }

    /** True when all three scale components are equal within {@code eps}. */
    public boolean hasUniformScale(float eps) {
        return Math.abs(scale.x() - scale.y()) <= eps && Math.abs(scale.y() - scale.z()) <= eps;
    }

    public Vec3f transformPosition(Vec3f p) {
        return rotation.transform(p.mul(scale)).add(translation);
    }

    /** Rotation and scale, no translation. */
    public Vec3f transformDirection(Vec3f d) {
        return rotation.transform(d.mul(scale));
    }

    /**
     * Composition {@code this * child}: transforming by the result applies {@code child} first, then {@code this}.
     * Exact when {@code this} has a uniform scale; see the class comment.
     */
    public Transformf mul(Transformf child) {
        return new Transformf(
                transformPosition(child.translation),
                rotation.mul(child.rotation),
                scale.mul(child.scale));
    }

    /**
     * The inverse transform. Exact when the scale is uniform (or the rotation is the identity); a zero scale component
     * yields infinite components.
     */
    public Transformf inverse() {
        Quatf inv = rotation.conjugate();
        Vec3f invScale = new Vec3f(1f / scale.x(), 1f / scale.y(), 1f / scale.z());
        Vec3f invTranslation = inv.transform(translation.negate()).mul(invScale);
        return new Transformf(invTranslation, inv, invScale);
    }

    /**
     * Interpolates between two transforms: linear in translation and scale, spherical (shortest arc) in rotation.
     * {@code t = 0} gives this, {@code t = 1} gives {@code other}.
     */
    public Transformf blend(Transformf other, float t) {
        return new Transformf(
                translation.lerp(other.translation, t),
                rotation.slerp(other.rotation, t),
                scale.lerp(other.scale, t));
    }

    public Mat4f toMat4() {
        return Mat4f.translationRotateScale(translation, rotation, scale);
    }

    public Mat4x3f toMat4x3() {
        return Mat4x3f.translationRotateScale(translation, rotation, scale);
    }

    public boolean approxEquals(Transformf o, float eps) {
        return translation.approxEquals(o.translation, eps) && rotation.sameRotation(o.rotation, eps)
                && scale.approxEquals(o.scale, eps);
    }

    public boolean isFinite() {
        return translation.isFinite() && rotation.isFinite() && scale.isFinite();
    }

    @FloatOnly
    public Transformd toDouble() {
        return new Transformd(translation.toDouble(), rotation.toDouble(), scale.toDouble());
    }

    @DoubleOnly
    public Transformf toFloat() {
        return new Transformf(translation.toFloat(), rotation.toFloat(), scale.toFloat());
    }
}
