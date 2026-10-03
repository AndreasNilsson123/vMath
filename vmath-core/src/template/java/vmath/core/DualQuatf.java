package vmath.core;

import vmath.annotations.DoubleOnly;
import vmath.annotations.Eps;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * A dual quaternion, {@code real + epsilon * dual}: a rigid transform (a rotation and a
 * translation) in eight numbers that can be blended.
 *
 * <p>For a rotation {@code r} and a translation {@code t} the real part is {@code r} and the dual
 * part is {@code 0.5 * (t, 0) * r}. Products compose transforms, like the product of quaternions
 * composes rotations, and a <em>unit</em> dual quaternion (unit real part, and the two parts
 * orthogonal as four-vectors) stays a rigid transform. The reason to use them is blending: a
 * weighted sum of several dual quaternions, normalised ({@link #nlerp}, or the sums of
 * {@link #mul(float)} and {@link #add}), is a good rigid transform again, whereas the weighted sum of
 * matrices shrinks and pinches at a twisting joint (the candy-wrapper artefact of linear blend
 * skinning). {@link #sclerp} interpolates along the shortest screw motion, with constant speed.
 *
 * <p>A dual quaternion and its negative are the same transform. Functions that choose between
 * the two take the one that gives the shorter rotation. Methods that need a unit dual quaternion
 * say so; {@link #normalize()} makes one.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DualQuatf a = DualQuatf.of(Quatf.rotationY(0f), new Vec3f(0f, 0f, 0f));
 * DualQuatf b = DualQuatf.of(Quatf.rotationY(3f), new Vec3f(4f, 0f, 0f));
 * DualQuatf halfway = a.sclerp(b, 0.5f);                      // along the screw motion from a to b
 * DualQuatf mixed = a.mul(0.25f).add(b.mul(0.75f)).normalize(); // a weighted blend, as in skinning
 * Vec3f p = halfway.transformPosition(new Vec3f(1f, 0f, 0f));
 * Mat4f matrix = halfway.toMat4();
 * }</pre>
 *
 * @param real the real part, the rotation as a quaternion; must not be {@code null}
 * @param dual the dual part, {@code 0.5 * (t, 0) * real}; must not be {@code null}
 */
@GenerateDouble
@ValueType
public record DualQuatf(Quatf real, Quatf dual) {

    /**
     * The identity: no rotation and no translation.
     */
    public static final DualQuatf IDENTITY = new DualQuatf(Quatf.IDENTITY, new Quatf(0f, 0f, 0f, 0f));

    /**
     * The sine of the half angle below which a rotation is taken as none by {@link #pow}, so that
     * the axis is not divided by a tiny number.
     */
    @Eps(d = 1e-12)
    private static final float SCREW_EPS = 1e-6f;

    /**
     * Builds the dual quaternion of a rotation followed by a translation.
     *
     * @param rotation the rotation, a unit quaternion; must not be {@code null}
     * @param translation the translation; must not be {@code null}
     * @return the unit dual quaternion that rotates and then translates
     */
    public static DualQuatf of(Quatf rotation, Vec3f translation) {
        Quatf t = new Quatf(translation.x(), translation.y(), translation.z(), 0f);
        return new DualQuatf(rotation, scale(t.mul(rotation), 0.5f));
    }

    /**
     * Builds the dual quaternion of a rotation.
     *
     * @param rotation the rotation, a unit quaternion; must not be {@code null}
     * @return the unit dual quaternion with that rotation and no translation
     */
    public static DualQuatf ofRotation(Quatf rotation) {
        return new DualQuatf(rotation, new Quatf(0f, 0f, 0f, 0f));
    }

    /**
     * Builds the dual quaternion of a translation.
     *
     * @param translation the translation; must not be {@code null}
     * @return the unit dual quaternion with that translation and no rotation
     */
    public static DualQuatf ofTranslation(Vec3f translation) {
        return of(Quatf.IDENTITY, translation);
    }

    private static Quatf scale(Quatf q, float s) {
        return new Quatf(q.x() * s, q.y() * s, q.z() * s, q.w() * s);
    }

    private static Quatf plus(Quatf a, Quatf b) {
        return new Quatf(a.x() + b.x(), a.y() + b.y(), a.z() + b.z(), a.w() + b.w());
    }

    private static Quatf minus(Quatf a, Quatf b) {
        return new Quatf(a.x() - b.x(), a.y() - b.y(), a.z() - b.z(), a.w() - b.w());
    }

    /**
     * Composes two transforms; the right operand acts on the point first, as for matrices.
     *
     * @param o the transform applied first; must not be {@code null}
     * @return the product {@code this * o}; a unit dual quaternion when both are
     */
    public DualQuatf mul(DualQuatf o) {
        return new DualQuatf(real.mul(o.real), plus(real.mul(o.dual), dual.mul(o.real)));
    }

    /**
     * Scales both parts, which is how weighted sums are built; the result is not a rigid transform
     * until it is normalised.
     *
     * @param s the factor
     * @return the dual quaternion with both parts multiplied by {@code s}
     */
    public DualQuatf mul(float s) {
        return new DualQuatf(scale(real, s), scale(dual, s));
    }

    /**
     * Adds the parts, which is how weighted sums are built; the result is not a rigid transform
     * until it is normalised, and the operands should have real parts in the same hemisphere (see
     * {@link #nlerp} and {@link #dot}).
     *
     * @param o the other dual quaternion; must not be {@code null}
     * @return the sum, part by part
     */
    public DualQuatf add(DualQuatf o) {
        return new DualQuatf(plus(real, o.real), plus(dual, o.dual));
    }

    /**
     * Negates both parts, which gives the same transform; used to bring two operands into the
     * same hemisphere before a blend.
     *
     * @return the negative, the same rigid transform
     */
    public DualQuatf negate() {
        return new DualQuatf(scale(real, -1f), scale(dual, -1f));
    }

    /**
     * Takes the conjugate of both quaternions, which is the inverse of a unit dual quaternion
     * (the two parts are orthogonal, so the dual part of the product vanishes).
     *
     * @return the conjugate; the inverse transform when this is a unit dual quaternion
     */
    public DualQuatf conjugate() {
        return new DualQuatf(real.conjugate(), dual.conjugate());
    }

    /**
     * Inverts the dual quaternion exactly, also when it is not a unit one:
     * {@code (real^-1, -real^-1 * dual * real^-1)}.
     *
     * <p>For a unit dual quaternion {@link #conjugate()} gives the same and is cheaper. A zero real
     * part gives non-finite components.
     *
     * @return the inverse
     */
    public DualQuatf inverse() {
        Quatf r = real.invert();
        return new DualQuatf(r, scale(r.mul(dual).mul(r), -1f));
    }

    /**
     * Computes the four-dimensional dot product of the real parts, whose sign tells whether two
     * rotations are in the same hemisphere: when it is negative, blend with the negative of one.
     *
     * @param o the other dual quaternion; must not be {@code null}
     * @return the dot product of the real parts
     */
    public float dot(DualQuatf o) {
        return real.dot(o.real);
    }

    /**
     * Makes a unit dual quaternion: the real part gets unit length and the dual part is made
     * orthogonal to it, which is what a blended sum needs to become a rigid transform again.
     *
     * <p>A zero real part gives non-finite components.
     *
     * @return the normalised dual quaternion
     */
    public DualQuatf normalize() {
        float inv = 1f / real.length();
        Quatf r = scale(real, inv);
        Quatf d = scale(dual, inv);
        return new DualQuatf(r, minus(d, scale(r, r.dot(d))));
    }

    /**
     * Blends linearly and normalises: the dual quaternion linear blending of Kavan et al. along the
     * shorter rotation, cheap and with a path that is not a screw motion (the speed varies a little).
     *
     * @param other the other dual quaternion; must not be {@code null}
     * @param t the blend parameter, 0 for this and 1 for {@code other}
     * @return the normalised blend
     */
    public DualQuatf nlerp(DualQuatf other, float t) {
        DualQuatf o = dot(other) < 0f ? other.negate() : other;
        return mul(1f - t).add(o.mul(t)).normalize();
    }

    /**
     * Raises a unit dual quaternion to a real power: the screw motion (a rotation about an axis
     * together with a translation along it) scaled by {@code t}. {@code pow(0)} is the identity,
     * {@code pow(1)} is this transform along the shorter rotation, and {@code pow(0.5)} is half the
     * motion.
     *
     * <p>A transform with (almost) no rotation is a pure translation, which is scaled linearly.
     * Needs a unit dual quaternion.
     *
     * @param t the exponent
     * @return the transform that, applied {@code 1 / t} times, gives this one (along the shorter
     *     rotation)
     */
    public DualQuatf pow(float t) {
        DualQuatf q = real.w() < 0f ? negate() : this;
        Quatf r = q.real;
        Quatf d = q.dual;
        float sinHalf = (float) Math.sqrt(r.x() * r.x() + r.y() * r.y() + r.z() * r.z());
        if (!(sinHalf > SCREW_EPS)) {
            return new DualQuatf(Quatf.IDENTITY.nlerp(r, t), scale(d, t));
        }
        float cosHalf = r.w();
        float halfAngle = (float) Math.atan2(sinHalf, cosHalf);
        float invSin = 1f / sinHalf;
        Vec3f axis = new Vec3f(r.x() * invSin, r.y() * invSin, r.z() * invSin);
        float pitch = -2f * d.w() * invSin; // the translation along the axis
        Vec3f moment = new Vec3f(d.x(), d.y(), d.z()).sub(axis.mul(0.5f * pitch * cosHalf)).mul(invSin);
        float h = halfAngle * t;
        float s = (float) Math.sin(h);
        float c = (float) Math.cos(h);
        float p = pitch * t;
        Vec3f realVector = axis.mul(s);
        Vec3f dualVector = moment.mul(s).add(axis.mul(0.5f * p * c));
        return new DualQuatf(new Quatf(realVector.x(), realVector.y(), realVector.z(), c),
                new Quatf(dualVector.x(), dualVector.y(), dualVector.z(), -0.5f * p * s));
    }

    /**
     * Interpolates along the screw motion from this transform to another, with constant speed and
     * along the shorter rotation: {@code this * (this^-1 * other)^t}.
     *
     * <p>Both must be unit dual quaternions. It is the exact counterpart of the spherical
     * interpolation of rotations for rigid transforms, and costs more than {@link #nlerp}.
     *
     * @param other the other unit dual quaternion; must not be {@code null}
     * @param t the parameter, 0 for this and 1 for {@code other}
     * @return the interpolated unit dual quaternion
     */
    public DualQuatf sclerp(DualQuatf other, float t) {
        DualQuatf delta = conjugate().mul(other);
        if (delta.real.w() < 0f) {
            delta = delta.negate();
        }
        return mul(delta.pow(t));
    }

    /**
     * Reads the rotation.
     *
     * @return the real part, which is the rotation when this is a unit dual quaternion
     */
    public Quatf rotation() {
        return real;
    }

    /**
     * Extracts the translation as {@code 2 * dual * conjugate(real)}, divided by the squared
     * length of the real part so that it is also right for a real part that is not exactly unit.
     *
     * @return the translation of the rigid transform
     */
    public Vec3f translation() {
        Quatf t = dual.mul(real.conjugate());
        float k = 2f / real.lengthSquared();
        return new Vec3f(t.x() * k, t.y() * k, t.z() * k);
    }

    /**
     * Transforms a point: rotate, then translate. When many points are transformed by the same
     * dual quaternion, convert it once with {@link #toRigid()}.
     *
     * @param p the point; must not be {@code null}
     * @return the transformed point
     */
    public Vec3f transformPosition(Vec3f p) {
        return real.transform(p).add(translation());
    }

    /**
     * Transforms a direction: only the rotation applies.
     *
     * @param d the direction; must not be {@code null}
     * @return the rotated direction
     */
    public Vec3f transformDirection(Vec3f d) {
        return real.transform(d);
    }

    /**
     * Converts to a rotation and a translation.
     *
     * @return the rigid transform of this unit dual quaternion
     */
    public RigidTransformf toRigid() {
        return new RigidTransformf(translation(), real);
    }

    /**
     * Converts to a 4x4 matrix.
     *
     * @return the matrix of the rigid transform
     */
    public Mat4f toMat4() {
        return toRigid().toMat4();
    }

    /**
     * Compares the eight components with an absolute tolerance. A dual quaternion and its negative
     * are the same transform but differ here; use {@link #sameTransform} to treat them as equal.
     *
     * @param o the other dual quaternion; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every component differs by at most {@code eps}
     */
    public boolean approxEquals(DualQuatf o, float eps) {
        return real.approxEquals(o.real, eps) && dual.approxEquals(o.dual, eps);
    }

    /**
     * Compares two unit dual quaternions as transforms, so that a dual quaternion and its negative
     * are the same, by their rotation and their translation.
     *
     * @param o the other unit dual quaternion; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when the rotations are the same within {@code eps} and the translations
     *     agree within {@code eps}
     */
    public boolean sameTransform(DualQuatf o, float eps) {
        return real.sameRotation(o.real, eps) && translation().approxEquals(o.translation(), eps);
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when every component is finite (neither infinite nor NaN)
     */
    public boolean isFinite() {
        return real.isFinite() && dual.isFinite();
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public DualQuatd toDouble() {
        return new DualQuatd(real.toDouble(), dual.toDouble());
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public DualQuatf toFloat() {
        return new DualQuatf(real.toFloat(), dual.toFloat());
    }
}
