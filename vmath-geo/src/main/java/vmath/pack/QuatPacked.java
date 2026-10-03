package vmath.pack;

import vmath.core.Quatf;

/**
 * A unit quaternion in 32 bits by the "smallest three" method: the largest component is dropped (it
 * can be rebuilt from the other three because the length is 1), and the remaining three are stored
 * as 10-bit signed values.
 *
 * <p>Two bits say which component was dropped. Because {@code q} and {@code -q} are the same
 * rotation, the dropped component is always made positive, and each remaining component then lies
 * in {@code [-1/sqrt(2), 1/sqrt(2)]}, which is the range the 10 bits cover.
 *
 * <p>Layout: bits 0 to 9, 10 to 19 and 20 to 29 hold the three kept components in increasing
 * component order (x, y, z, w minus the dropped one), bits 30 and 31 hold the dropped component's
 * index (0 x, 1 y, 2 z, 3 w). This is the usual format for orientations in animation streams and
 * for vertex tangent frames. The worst-case rotation error is about 0.004 radians (0.24 degrees),
 * measured over millions of random rotations (see {@code docs/FORMATS.md}).
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * int packed = QuatPacked.pack(Quatf.rotationY(0.7f));                   // 32 bits, smallest three components
 * Quatf back = QuatPacked.unpack(packed);                                // a unit quaternion for the same rotation
 * }</pre>
 */
public final class QuatPacked {

    private QuatPacked() {
    }

    private static final float RANGE = (float) Math.sqrt(0.5);

    /**
     * Packs a unit quaternion.
     *
     * <p>The input is normalized first, so slightly drifted quaternions are fine.
     *
     * @param q the quaternion; must not be {@code null}
     * @return the packed quaternion
     */
    public static int pack(Quatf q) {
        Quatf n = q.normalize();
        float[] c = {n.x(), n.y(), n.z(), n.w()};
        int largest = 0;
        for (int i = 1; i < 4; i++) {
            if (Math.abs(c[i]) > Math.abs(c[largest])) {
                largest = i;
            }
        }
        float sign = c[largest] < 0f ? -1f : 1f;
        int packed = largest << 30;
        int shift = 0;
        for (int i = 0; i < 4; i++) {
            if (i == largest) {
                continue;
            }
            float v = c[i] * sign / RANGE; // in [-1, 1]
            packed |= Norm.packSnorm10(v) << shift;
            shift += 10;
        }
        return packed;
    }

    /**
     * Unpacks to a unit quaternion (of the rotation that was packed; the overall sign may differ,
     * which is the same rotation).
     *
     * @param packed the packed value
     * @return the unit quaternion, never {@code null}
     */
    public static Quatf unpack(int packed) {
        int largest = packed >>> 30;
        float[] c = new float[4];
        float sumSquares = 0f;
        int shift = 0;
        for (int i = 0; i < 4; i++) {
            if (i == largest) {
                continue;
            }
            float v = Norm.unpackSnorm10(packed >>> shift) * RANGE;
            c[i] = v;
            sumSquares += v * v;
            shift += 10;
        }
        c[largest] = (float) Math.sqrt(Math.max(0f, 1f - sumSquares));
        return new Quatf(c[0], c[1], c[2], c[3]).normalize();
    }
}
