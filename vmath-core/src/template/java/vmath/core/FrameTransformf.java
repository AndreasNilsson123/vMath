package vmath.core;

import java.util.Objects;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;

/**
 * A transform that knows which coordinate frames it connects: it maps coordinates in the
 * {@linkplain #source() source} frame to coordinates in the {@linkplain #target() target} frame.
 *
 * <p>Composing transforms is where frames get mixed up: a product of the wrong two matrices is
 * still a matrix. Here {@link #mul} only accepts an operand whose target is this transform's
 * source, so {@code worldFromShip.mul(shipFromGun)} works and the other order does not, and
 * {@link #inverse()} swaps the frames. The check costs one comparison of names; the arithmetic is
 * that of {@link Transformf}, with its limits for non-uniform scale.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Frame world = Frame.of("world");
 * Frame ship = Frame.of("ship");
 * Frame gun = Frame.of("gun");
 * FrameTransformf worldFromShip = FrameTransformf.of(ship, world, Transformf.ofTranslation(new Vec3f(100f, 0f, 0f)));
 * FrameTransformf shipFromGun = FrameTransformf.of(gun, ship, Transformf.ofTranslation(new Vec3f(0f, 2f, 0f)));
 * FrameTransformf worldFromGun = worldFromShip.mul(shipFromGun);              // gun to world
 * Vec3f muzzle = worldFromGun.transformPosition(gun, Vec3f.ZERO);             // the frame of the point is checked
 * FrameTransformf gunFromWorld = worldFromGun.inverse();
 * }</pre>
 *
 * @param source the frame whose coordinates the transform takes; must not be {@code null}
 * @param target the frame whose coordinates the transform gives; must not be {@code null}
 * @param transform the transform from {@code source} to {@code target}; must not be {@code null}
 */
@GenerateDouble
@ValueType
public record FrameTransformf(Frame source, Frame target, Transformf transform) {

    /**
     * Checks that no component is {@code null}.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public FrameTransformf {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(transform, "transform");
    }

    /**
     * Creates a transform between two frames.
     *
     * @param source the frame whose coordinates the transform takes; must not be {@code null}
     * @param target the frame whose coordinates the transform gives; must not be {@code null}
     * @param transform the transform from {@code source} to {@code target}; must not be
     *     {@code null}
     * @return the tagged transform
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FrameTransformf of(Frame source, Frame target, Transformf transform) {
        return new FrameTransformf(source, target, transform);
    }

    /**
     * Creates the identity of a frame, which maps it to itself.
     *
     * @param frame the frame; must not be {@code null}
     * @return the identity from {@code frame} to {@code frame}
     * @throws NullPointerException if {@code frame} is {@code null}
     */
    public static FrameTransformf identity(Frame frame) {
        return new FrameTransformf(frame, frame, Transformf.IDENTITY);
    }

    /**
     * Composes with a transform that is applied first: the target of {@code inner} must be the
     * source of this transform.
     *
     * @param inner the transform applied first; must not be {@code null}
     * @return the transform from the source of {@code inner} to the target of this one
     * @throws IllegalArgumentException if the target of {@code inner} is not the source of this
     *     transform
     * @throws NullPointerException if {@code inner} is {@code null}
     */
    public FrameTransformf mul(FrameTransformf inner) {
        if (!inner.target.equals(source)) {
            throw new IllegalArgumentException("cannot compose " + this + " with " + inner + ": the target of the inner transform ("
                    + inner.target + ") is not the source of the outer one (" + source + ")");
        }
        return new FrameTransformf(inner.source, target, transform.mul(inner.transform));
    }

    /**
     * Inverts the transform and swaps its frames; exact when the scale is uniform (see
     * {@link Transformf#inverse()}).
     *
     * @return the transform from the target to the source
     */
    public FrameTransformf inverse() {
        return new FrameTransformf(target, source, transform.inverse());
    }

    /**
     * Transforms a point given in the source frame to the target frame.
     *
     * @param p the point in the source frame; must not be {@code null}
     * @return the point in the target frame
     */
    public Vec3f transformPosition(Vec3f p) {
        return transform.transformPosition(p);
    }

    /**
     * Transforms a point and checks that it is given in the frame the caller thinks it is.
     *
     * @param from the frame the caller says {@code p} is in; must not be {@code null}
     * @param p the point; must not be {@code null}
     * @return the point in the target frame
     * @throws IllegalArgumentException if {@code from} is not the source frame of this transform
     * @throws NullPointerException if an argument is {@code null}
     */
    public Vec3f transformPosition(Frame from, Vec3f p) {
        check(from);
        return transform.transformPosition(p);
    }

    /**
     * Transforms a direction given in the source frame to the target frame: scale and rotation
     * apply, the translation does not.
     *
     * @param d the direction in the source frame; must not be {@code null}
     * @return the direction in the target frame
     */
    public Vec3f transformDirection(Vec3f d) {
        return transform.transformDirection(d);
    }

    /**
     * Transforms a direction and checks that it is given in the frame the caller thinks it is.
     *
     * @param from the frame the caller says {@code d} is in; must not be {@code null}
     * @param d the direction; must not be {@code null}
     * @return the direction in the target frame
     * @throws IllegalArgumentException if {@code from} is not the source frame of this transform
     * @throws NullPointerException if an argument is {@code null}
     */
    public Vec3f transformDirection(Frame from, Vec3f d) {
        check(from);
        return transform.transformDirection(d);
    }

    private void check(Frame from) {
        if (!from.equals(source)) {
            throw new IllegalArgumentException("a point in the frame " + from + " cannot be transformed by " + this);
        }
    }

    /**
     * Converts the transform to a 4x4 matrix from the source frame to the target frame.
     *
     * @return the matrix of the transform
     */
    public Mat4f toMat4() {
        return transform.toMat4();
    }

    /**
     * Compares two transforms with an absolute tolerance; the frames must be equal.
     *
     * @param o the other transform; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when the frames are the same and the transforms agree within
     *     {@code eps}
     */
    public boolean approxEquals(FrameTransformf o, float eps) {
        return source.equals(o.source) && target.equals(o.target) && transform.approxEquals(o.transform, eps);
    }

    /**
     * Checks the transform for NaN and infinity.
     *
     * @return {@code true} when every component of the transform is finite
     */
    public boolean isFinite() {
        return transform.isFinite();
    }

    @Override
    public String toString() {
        return "FrameTransform[" + source + " -> " + target + ", " + transform + "]";
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public FrameTransformd toDouble() {
        return new FrameTransformd(source, target, transform.toDouble());
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public FrameTransformf toFloat() {
        return new FrameTransformf(source, target, transform.toFloat());
    }

    /**
     * Moves the translation by a double-precision origin before narrowing the transform to
     * {@code float}; the frames are kept.
     *
     * @param origin the new origin in the coordinates of the target frame; must not be
     *     {@code null}
     * @return the transform with its translation relative to {@code origin}, narrowed to float
     */
    @DoubleOnly
    public FrameTransformf relativeTo(Vec3d origin) {
        return new FrameTransformf(source, target, transform.relativeTo(origin));
    }
}
