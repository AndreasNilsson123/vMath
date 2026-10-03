package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * A transform as translation, rotation and scale (TRS): 40 bytes, no matrix.
 *
 * <p>It is the natural representation for scene-graph nodes and animation, because pieces can be
 * blended independently ({@link #blend}) and inspected without decomposing.
 *
 * <p><b>Composition caveat.</b> A TRS cannot represent every product of two TRS transforms: a
 * rotation followed by a <em>non-uniform</em> scale produces shear. {@link #mul} is exact when the
 * left operand's scale is uniform (the common case), and otherwise applies the scales
 * component-wise in the child's frame, which is what Unity and most engines do. Use {@link Mat4x3f}
 * when exact shear matters. {@link #inverse()} has the same limit.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Transformf parent = new Transformf(new Vec3f(0f, 1f, 0f), Quatf.rotationY(0.5f), Vec3f.ONE);
 * Transformf child = Transformf.ofTranslation(new Vec3f(2f, 0f, 0f));
 * Transformf world = parent.mul(child);                   // the child is applied first
 * Vec3f p = world.transformPosition(Vec3f.ZERO);
 * Transformf halfway = parent.blend(world, 0.5f);
 * Mat4f matrix = world.toMat4();
 * }</pre>
 *
 * @param translation the translation; must not be {@code null}
 * @param rotation the rotation; must not be {@code null}
 * @param scale the scale; must not be {@code null}
 */
@GenerateDouble
@ValueType
public record Transformf(Vec3f translation, Quatf rotation, Vec3f scale) {

    /**
     * The identity.
     */
    public static final Transformf IDENTITY = new Transformf(Vec3f.ZERO, Quatf.IDENTITY, Vec3f.ONE);

    /**
     * Builds a transform that contains only a translation, with identity rotation and unit scale.
     *
     * @param t the vector; must not be {@code null}
     * @return a transform that only translates
     */
    public static Transformf ofTranslation(Vec3f t) {
        return new Transformf(t, Quatf.IDENTITY, Vec3f.ONE);
    }

    /**
     * Builds a transform that contains only a rotation.
     *
     * @param q the quaternion; must not be {@code null}
     * @return a transform that only rotates
     */
    public static Transformf ofRotation(Quatf q) {
        return new Transformf(Vec3f.ZERO, q, Vec3f.ONE);
    }

    /**
     * Builds a transform that contains only a scale.
     *
     * @param s the vector; must not be {@code null}
     * @return a transform that only scales
     */
    public static Transformf ofScale(Vec3f s) {
        return new Transformf(Vec3f.ZERO, Quatf.IDENTITY, s);
    }

    /**
     * Decomposes an affine matrix into translation, rotation and scale; shear cannot be
     * represented, and a mirror is folded into a negative x scale.
     *
     * @param m the matrix; must not be {@code null}
     * @return the TRS of an affine matrix (see {@link Mat4f#decompose()}: no shear, mirroring folds
     *     into a negative x scale)
     */
    public static Transformf fromMat4(Mat4f m) {
        Mat4f.Trs d = m.decompose();
        return new Transformf(d.translation(), d.rotation(), d.scale());
    }

    /**
     * Tests whether the three scale factors agree within a tolerance, which is when normals can be
     * transformed like directions.
     *
     * @param eps the tolerance
     * @return {@code true} when all three scale components are equal within {@code eps}
     */
    public boolean hasUniformScale(float eps) {
        return Math.abs(scale.x() - scale.y()) <= eps && Math.abs(scale.y() - scale.z()) <= eps;
    }

    /**
     * Transforms a point: scale first, then rotate, then translate.
     *
     * @param p the vector; must not be {@code null}
     * @return the point {@code p} transformed by this transform: scaled, rotated and translated
     */
    public Vec3f transformPosition(Vec3f p) {
        return rotation.transform(p.mul(scale)).add(translation);
    }

    /**
     * Transforms a direction: scale and rotation apply, the translation does not.
     *
     * @param d the vector; must not be {@code null}
     * @return rotation and scale, no translation
     */
    public Vec3f transformDirection(Vec3f d) {
        return rotation.transform(d.mul(scale));
    }

    /**
     * Composes two transforms; the right operand acts on the point first.
     *
     * <p>Exact when the parent has a uniform scale, since a transform with a non-uniform scale
     * cannot represent the shear that the product may need. Exact when {@code this} has a uniform
     * scale; see the class comment.
     *
     * @param child the child; must not be {@code null}
     * @return composition {@code this * child}: transforming by the result applies {@code child}
     *     first, then {@code this}
     */
    public Transformf mul(Transformf child) {
        return new Transformf(
                transformPosition(child.translation),
                rotation.mul(child.rotation),
                scale.mul(child.scale));
    }

    /**
     * Inverts the transform by inverting each component; exact when the scale is uniform or the
     * rotation is the identity.
     *
     * <p>Exact when the scale is uniform (or the rotation is the identity); a zero scale component
     * yields infinite components.
     *
     * @return the inverse transform
     */
    public Transformf inverse() {
        Quatf inv = rotation.conjugate();
        Vec3f invScale = new Vec3f(1f / scale.x(), 1f / scale.y(), 1f / scale.z());
        Vec3f invTranslation = inv.transform(translation.negate()).mul(invScale);
        return new Transformf(invTranslation, inv, invScale);
    }

    /**
     * Interpolates between two transforms: linear in translation and scale, spherical (shortest
     * arc) in rotation.
     *
     * <p>{@code t = 0} gives this, {@code t = 1} gives {@code other}.
     *
     * @param other the other transform; must not be {@code null}
     * @param t the blend parameter, 0 for this transform and 1 for {@code other}
     * @return the blended transform, never {@code null}
     */
    public Transformf blend(Transformf other, float t) {
        return new Transformf(
                translation.lerp(other.translation, t),
                rotation.slerp(other.rotation, t),
                scale.lerp(other.scale, t));
    }

    /**
     * Converts the transform to a 4x4 model matrix in the order scale, rotate, translate.
     *
     * @return the rotation as a 4x4 matrix
     */
    public Mat4f toMat4() {
        return Mat4f.translationRotateScale(translation, rotation, scale);
    }

    /**
     * Converts the transform to a compact affine matrix in the order scale, rotate, translate.
     *
     * @return the same transform as a {@link Mat4x3f}: translation, rotation and scale combined
     */
    public Mat4x3f toMat4x3() {
        return Mat4x3f.translationRotateScale(translation, rotation, scale);
    }

    /**
     * Compares two transforms component by component with an absolute tolerance, for tests and for
     * detecting changes.
     *
     * @param o the other transform; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every component differs from that of {@code o} by at most
     *     {@code eps}
     */
    public boolean approxEquals(Transformf o, float eps) {
        return translation.approxEquals(o.translation, eps) && rotation.sameRotation(o.rotation, eps)
                && scale.approxEquals(o.scale, eps);
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when every component is finite (neither infinite nor NaN)
     */
    public boolean isFinite() {
        return translation.isFinite() && rotation.isFinite() && scale.isFinite();
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Transformd toDouble() {
        return new Transformd(translation.toDouble(), rotation.toDouble(), scale.toDouble());
    }

    /**
     * Moves the translation by a double-precision origin before narrowing the transform to
     * {@code float}, so that an object far from the world origin keeps its precision on the GPU.
     *
     * @param origin the new origin in the coordinates of this transform; must not be {@code null}
     * @return the transform in a frame whose origin is {@code origin}, subtracted in double and
     *     narrowed to float
     */
    @DoubleOnly
    public Transformf relativeTo(Vec3d origin) {
        return new Transformf(translation.relativeTo(origin), rotation.toFloat(), scale.toFloat());
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Transformf toFloat() {
        return new Transformf(translation.toFloat(), rotation.toFloat(), scale.toFloat());
    }
}
