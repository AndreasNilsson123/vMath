package vmath.core;

/**
 * Axis order of an Euler-angle triple, <b>extrinsic</b> and in application order: {@code XYZ} means
 * rotate about the fixed world X axis first, then about world Y, then about world Z.
 *
 * <p>The rotation is {@code Rz * Ry * Rx}. JOML names its methods by intrinsic order (it
 * right-multiplies), so our {@code XYZ(a, b, c)} is JOML's {@code rotationZYX(c, b, a)}.
 *
 * <p>An intrinsic sequence (each rotation about the already rotated axes) is the same rotation with
 * the order reversed: intrinsic {@code X-Y'-Z''} equals extrinsic {@code ZYX} with the angles
 * reversed.
 *
 * <p>The six orders with three distinct axes are Tait-Bryan angles (yaw/pitch/roll style); the six
 * that repeat the first axis are proper Euler angles.
 *
 * <p><b>Thread safety.</b> Immutable: the constants can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Quatf q = Quatf.fromEuler(EulerOrder.YXZ, 0.3f, 0.1f, 0f);   // yaw, pitch, roll
 * Vec3f angles = q.toEuler(EulerOrder.YXZ);
 * boolean proper = EulerOrder.ZXZ.isProper();                  // true: the first and last axis are the same
 * }</pre>
 */
public enum EulerOrder {
    /**
     * Rotate about world X, then Y, then Z (Tait-Bryan angles).
     */
    XYZ(0, 1, 2),
    /**
     * Rotate about world X, then Z, then Y (Tait-Bryan angles).
     */
    XZY(0, 2, 1),
    /**
     * Rotate about world Y, then X, then Z (Tait-Bryan angles).
     */
    YXZ(1, 0, 2),
    /**
     * Rotate about world Y, then Z, then X (Tait-Bryan angles).
     */
    YZX(1, 2, 0),
    /**
     * Rotate about world Z, then X, then Y (Tait-Bryan angles).
     */
    ZXY(2, 0, 1),
    /**
     * Rotate about world Z, then Y, then X (Tait-Bryan angles).
     */
    ZYX(2, 1, 0),
    /**
     * Rotate about world X, then Y, then X (proper Euler angles).
     */
    XYX(0, 1, 0),
    /**
     * Rotate about world X, then Z, then X (proper Euler angles).
     */
    XZX(0, 2, 0),
    /**
     * Rotate about world Y, then X, then Y (proper Euler angles).
     */
    YXY(1, 0, 1),
    /**
     * Rotate about world Y, then Z, then Y (proper Euler angles).
     */
    YZY(1, 2, 1),
    /**
     * Rotate about world Z, then X, then Z (proper Euler angles).
     */
    ZXZ(2, 0, 2),
    /**
     * Rotate about world Z, then Y, then Z (proper Euler angles).
     */
    ZYZ(2, 1, 2);

    private final int first;
    private final int second;
    private final int third;

    EulerOrder(int first, int second, int third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    /**
     * Identifies the axis of the first elemental rotation of this Euler sequence.
     *
     * @return axis index (0 = X, 1 = Y, 2 = Z) of the first rotation applied
     */
    public int first() {
        return first;
    }

    /**
     * Identifies the axis of the second elemental rotation of this Euler sequence.
     *
     * @return axis index (0 = X, 1 = Y, 2 = Z) of the second rotation applied
     */
    public int second() {
        return second;
    }

    /**
     * Identifies the axis of the third elemental rotation of this Euler sequence.
     *
     * @return axis index (0 = X, 1 = Y, 2 = Z) of the third rotation applied
     */
    public int third() {
        return third;
    }

    /**
     * Classifies the sequence as proper Euler angles, which repeat the first axis, as opposed to
     * Tait-Bryan angles that use three different axes.
     *
     * @return {@code true} for orders that repeat the first axis (proper Euler angles)
     */
    public boolean isProper() {
        return first == third;
    }
}
