package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * A rigid transform, a rotation followed by a translation, with no scale: 28 bytes.
 *
 * <p>It is the transform of a bone, a camera or a rigid body, where a scale would only get in the
 * way: the inverse is the conjugate rotation (no division, exact), a product needs no special
 * case for shear, and the type cannot express a transform that a physics engine or a skeleton
 * does not allow. For scale use {@link Transformf}, for shear {@link Mat4x3f}; for blending whole
 * bone hierarchies without the volume loss of linear blending use {@link DualQuatf}, which
 * {@link #toDualQuat()} makes.
 *
 * <p>The rotation is expected to be a unit quaternion. Products and blends keep it close to
 * unit; after very many products call {@link #normalize()}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * RigidTransformf parent = new RigidTransformf(new Vec3f(0f, 1f, 0f), Quatf.rotationY(0.5f));
 * RigidTransformf child = RigidTransformf.ofTranslation(new Vec3f(2f, 0f, 0f));
 * RigidTransformf world = parent.mul(child);                  // the child is applied first
 * Vec3f p = world.transformPosition(Vec3f.ZERO);
 * Vec3f back = world.inverse().transformPosition(p);          // Vec3f.ZERO again
 * RigidTransformf halfway = parent.blend(world, 0.5f);
 * Mat4f matrix = world.toMat4();
 * }</pre>
 *
 * @param translation the translation; must not be {@code null}
 * @param rotation the rotation, a unit quaternion; must not be {@code null}
 */
@GenerateDouble
@ValueType
public record RigidTransformf(Vec3f translation, Quatf rotation) {

    /**
     * The identity.
     */
    public static final RigidTransformf IDENTITY = new RigidTransformf(Vec3f.ZERO, Quatf.IDENTITY);

    /**
     * Builds a rigid transform that only translates.
     *
     * @param t the translation; must not be {@code null}
     * @return a transform with identity rotation
     */
    public static RigidTransformf ofTranslation(Vec3f t) {
        return new RigidTransformf(t, Quatf.IDENTITY);
    }

    /**
     * Builds a rigid transform that only rotates.
     *
     * @param q the rotation, a unit quaternion; must not be {@code null}
     * @return a transform without translation
     */
    public static RigidTransformf ofRotation(Quatf q) {
        return new RigidTransformf(Vec3f.ZERO, q);
    }

    /**
     * Takes the rigid part of an affine matrix: the translation and the rotation of its upper-left
     * 3x3 block.
     *
     * <p>The block must be a rotation (orthonormal, determinant 1); a scale or a shear in the
     * matrix gives a rotation that is not meaningful, so use {@link Transformf#fromMat4} and drop
     * the scale when the matrix may have one.
     *
     * @param m the matrix; must not be {@code null}
     * @return the transform with the translation and the rotation of {@code m}
     */
    public static RigidTransformf fromMat4(Mat4f m) {
        return new RigidTransformf(m.getTranslation(), Quatf.fromMat3(m.upperLeft3x3()));
    }

    /**
     * Takes the translation and the rotation of a TRS transform and drops its scale.
     *
     * @param t the transform; must not be {@code null}
     * @return the rigid transform with the translation and the rotation of {@code t}
     */
    public static RigidTransformf fromTransform(Transformf t) {
        return new RigidTransformf(t.translation(), t.rotation());
    }

    /**
     * Transforms a point: rotate, then translate.
     *
     * @param p the point; must not be {@code null}
     * @return the transformed point
     */
    public Vec3f transformPosition(Vec3f p) {
        return rotation.transform(p).add(translation);
    }

    /**
     * Transforms a direction: only the rotation applies, the translation does not.
     *
     * @param d the direction; must not be {@code null}
     * @return the rotated direction
     */
    public Vec3f transformDirection(Vec3f d) {
        return rotation.transform(d);
    }

    /**
     * Transforms a point by the inverse of this transform without building the inverse, which
     * saves a rotation.
     *
     * @param p the point; must not be {@code null}
     * @return the point in the frame that this transform maps to the parent frame
     */
    public Vec3f inverseTransformPosition(Vec3f p) {
        return rotation.conjugate().transform(p.sub(translation));
    }

    /**
     * Transforms a direction by the inverse rotation.
     *
     * @param d the direction; must not be {@code null}
     * @return the direction in the frame that this transform maps to the parent frame
     */
    public Vec3f inverseTransformDirection(Vec3f d) {
        return rotation.conjugate().transform(d);
    }

    /**
     * Composes two rigid transforms; the right operand acts on the point first. The product of
     * rigid transforms is always rigid, so it is exact.
     *
     * @param child the child; must not be {@code null}
     * @return the composition {@code this * child}: transforming by the result applies
     *     {@code child} first, then {@code this}
     */
    public RigidTransformf mul(RigidTransformf child) {
        return new RigidTransformf(transformPosition(child.translation), rotation.mul(child.rotation));
    }

    /**
     * Inverts the transform with the conjugate rotation, which is exact for a unit rotation and
     * needs no division.
     *
     * @return the inverse transform
     */
    public RigidTransformf inverse() {
        Quatf inv = rotation.conjugate();
        return new RigidTransformf(inv.transform(translation.negate()), inv);
    }

    /**
     * Interpolates between two rigid transforms: linear in the translation, spherical along the
     * shortest arc in the rotation. The path of a point is not a screw motion; see
     * {@link DualQuatf#sclerp} for that.
     *
     * @param other the other transform; must not be {@code null}
     * @param t the blend parameter, 0 for this transform and 1 for {@code other}
     * @return the blended transform
     */
    public RigidTransformf blend(RigidTransformf other, float t) {
        return new RigidTransformf(translation.lerp(other.translation, t), rotation.slerp(other.rotation, t));
    }

    /**
     * Scales the rotation back to unit length, which products slowly lose; the translation is
     * unchanged.
     *
     * @return the transform with a normalised rotation
     */
    public RigidTransformf normalize() {
        return new RigidTransformf(translation, rotation.normalize());
    }

    /**
     * Converts the transform to a 4x4 matrix.
     *
     * @return the matrix, with the rotation in the upper-left block and the translation in the last
     *     column
     */
    public Mat4f toMat4() {
        return Mat4f.translationRotateScale(translation, rotation, Vec3f.ONE);
    }

    /**
     * Converts the transform to a compact affine matrix.
     *
     * @return the same transform as a {@link Mat4x3f}
     */
    public Mat4x3f toMat4x3() {
        return Mat4x3f.translationRotateScale(translation, rotation, Vec3f.ONE);
    }

    /**
     * Converts the transform to a TRS transform with unit scale.
     *
     * @return the same transform as a {@link Transformf} with scale one
     */
    public Transformf toTransform() {
        return new Transformf(translation, rotation, Vec3f.ONE);
    }

    /**
     * Converts the transform to a dual quaternion, which blends several transforms without the
     * volume loss of blending matrices.
     *
     * @return the unit dual quaternion of this transform
     */
    public DualQuatf toDualQuat() {
        return DualQuatf.of(rotation, translation);
    }

    /**
     * Compares two transforms with an absolute tolerance, treating a quaternion and its negative
     * as the same rotation.
     *
     * @param o the other transform; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when the translations agree within {@code eps} and the rotations are
     *     the same within {@code eps}
     */
    public boolean approxEquals(RigidTransformf o, float eps) {
        return translation.approxEquals(o.translation, eps) && rotation.sameRotation(o.rotation, eps);
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when every component is finite (neither infinite nor NaN)
     */
    public boolean isFinite() {
        return translation.isFinite() && rotation.isFinite();
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public RigidTransformd toDouble() {
        return new RigidTransformd(translation.toDouble(), rotation.toDouble());
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
    public RigidTransformf relativeTo(Vec3d origin) {
        return new RigidTransformf(translation.relativeTo(origin), rotation.toFloat());
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public RigidTransformf toFloat() {
        return new RigidTransformf(translation.toFloat(), rotation.toFloat());
    }
}
