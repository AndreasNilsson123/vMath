package vmath.core;

/**
 * Axis order of an Euler-angle triple, <b>extrinsic</b> and in application order: {@code XYZ} means rotate about the
 * fixed world X axis first, then about world Y, then about world Z. The rotation is {@code Rz * Ry * Rx}. JOML names
 * its methods by intrinsic order (it right-multiplies), so our {@code XYZ(a, b, c)} is JOML's
 * {@code rotationZYX(c, b, a)}.
 *
 * <p>An intrinsic sequence (each rotation about the already rotated axes) is the same rotation with the order
 * reversed: intrinsic {@code X-Y'-Z''} equals extrinsic {@code ZYX} with the angles reversed.
 *
 * <p>The six orders with three distinct axes are Tait-Bryan angles (yaw/pitch/roll style); the six that repeat the
 * first axis are proper Euler angles.
 */
public enum EulerOrder {
    XYZ(0, 1, 2), XZY(0, 2, 1), YXZ(1, 0, 2), YZX(1, 2, 0), ZXY(2, 0, 1), ZYX(2, 1, 0),
    XYX(0, 1, 0), XZX(0, 2, 0), YXY(1, 0, 1), YZY(1, 2, 1), ZXZ(2, 0, 2), ZYZ(2, 1, 2);

    private final int first;
    private final int second;
    private final int third;

    EulerOrder(int first, int second, int third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    /** Axis index (0 = X, 1 = Y, 2 = Z) of the first rotation applied. */
    public int first() {
        return first;
    }

    public int second() {
        return second;
    }

    public int third() {
        return third;
    }

    /** True for orders that repeat the first axis (proper Euler angles). */
    public boolean isProper() {
        return first == third;
    }
}
