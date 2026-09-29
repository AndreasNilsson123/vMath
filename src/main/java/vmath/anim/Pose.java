package vmath.anim;

import java.util.Arrays;

/**
 * The local transform of every joint at one moment: what an animation clip samples into and what blending combines. Stored as one
 * array, 10 floats per joint: translation {@code x, y, z}, unit quaternion {@code x, y, z, w}, scale {@code x, y, z}.
 *
 * <p>All blend operations write into a caller-supplied {@code out} pose and allocate nothing. {@code out} may be the same object as one
 * of the inputs.
 */
public final class Pose {

    private final float[] trs;
    private final int joints;

    /** A pose of {@code jointCount} joints, all at the identity. */
    public Pose(int jointCount) {
        joints = jointCount;
        trs = new float[jointCount * TransformMath.TRS];
        setIdentity();
    }

    /** A pose at the skeleton's bind pose. */
    public Pose(Skeleton skeleton) {
        this(skeleton.jointCount());
        setToBind(skeleton);
    }

    public int jointCount() {
        return joints;
    }

    /** The live array, 10 floats per joint. */
    public float[] data() {
        return trs;
    }

    public void setIdentity() {
        for (int j = 0; j < joints; j++) {
            int o = j * TransformMath.TRS;
            Arrays.fill(trs, o, o + 3, 0f);
            trs[o + 3] = 0f;
            trs[o + 4] = 0f;
            trs[o + 5] = 0f;
            trs[o + 6] = 1f;
            Arrays.fill(trs, o + 7, o + 10, 1f);
        }
    }

    public void setToBind(Skeleton skeleton) {
        requireJoints(skeleton.jointCount());
        System.arraycopy(skeleton.bindArray(), 0, trs, 0, trs.length);
    }

    public void copyFrom(Pose other) {
        requireJoints(other.joints);
        System.arraycopy(other.trs, 0, trs, 0, trs.length);
    }

    public void setTranslation(int joint, float x, float y, float z) {
        int o = joint * TransformMath.TRS;
        trs[o] = x;
        trs[o + 1] = y;
        trs[o + 2] = z;
    }

    /** Sets the rotation; the quaternion is normalised. */
    public void setRotation(int joint, float x, float y, float z, float w) {
        int o = joint * TransformMath.TRS + 3;
        trs[o] = x;
        trs[o + 1] = y;
        trs[o + 2] = z;
        trs[o + 3] = w;
        Skeleton.normalize(trs, o);
    }

    public void setScale(int joint, float x, float y, float z) {
        int o = joint * TransformMath.TRS + 7;
        trs[o] = x;
        trs[o + 1] = y;
        trs[o + 2] = z;
    }

    private void requireJoints(int n) {
        if (n != joints) {
            throw new IllegalArgumentException("joint count mismatch: " + n + " vs " + joints);
        }
    }

    // ---------------------------------------------------------------- blending

    /**
     * {@code out = a} blended toward {@code b} by {@code t}: translation and scale linearly, rotation by slerp along the shortest arc.
     * {@code t = 0} gives {@code a}, {@code t = 1} gives {@code b}.
     */
    public static void lerp(Pose a, Pose b, float t, Pose out) {
        a.requireJoints(b.joints);
        a.requireJoints(out.joints);
        for (int j = 0; j < a.joints; j++) {
            blendJoint(a.trs, b.trs, j * TransformMath.TRS, t, out.trs);
        }
    }

    /**
     * Layered blend: joint {@code j} of {@code out} is {@code base} blended toward {@code overlay} by {@code mask[j] * weight}. A mask
     * of 1 for the upper body and 0 for the legs plays an attack animation over a walk, for example.
     */
    public static void blendMasked(Pose base, Pose overlay, float[] mask, float weight, Pose out) {
        base.requireJoints(overlay.joints);
        base.requireJoints(out.joints);
        if (mask.length < base.joints) {
            throw new IllegalArgumentException("mask needs " + base.joints + " entries");
        }
        for (int j = 0; j < base.joints; j++) {
            float t = Math.max(0f, Math.min(1f, mask[j] * weight));
            blendJoint(base.trs, overlay.trs, j * TransformMath.TRS, t, out.trs);
        }
    }

    private static void blendJoint(float[] a, float[] b, int o, float t, float[] out) {
        for (int k = 0; k < 3; k++) {
            out[o + k] = a[o + k] + (b[o + k] - a[o + k]) * t;
            out[o + 7 + k] = a[o + 7 + k] + (b[o + 7 + k] - a[o + 7 + k]) * t;
        }
        TransformMath.slerp(a, o + 3, b, o + 3, t, out, o + 3);
    }

    /**
     * Turns {@code source} into an additive pose relative to {@code reference}: what has to be added to the reference to get the source
     * (translation difference, rotation {@code source * inverse(reference)}, scale ratio). Typically the reference is the first frame of a
     * clip, so the result holds only the motion, which {@link #applyAdditive} can then lay over any base pose.
     */
    public static void makeAdditive(Pose reference, Pose source, Pose out) {
        reference.requireJoints(source.joints);
        reference.requireJoints(out.joints);
        for (int j = 0; j < reference.joints; j++) {
            int o = j * TransformMath.TRS;
            float[] r = reference.trs, s = source.trs;
            for (int k = 0; k < 3; k++) {
                float rs = r[o + 7 + k];
                out.trs[o + k] = s[o + k] - r[o + k];
                out.trs[o + 7 + k] = Math.abs(rs) > 1e-12f ? s[o + 7 + k] / rs : 1f;
            }
            TransformMath.multiplyQuatByConjugate(s, o + 3, r, o + 3, out.trs, o + 3);
            Skeleton.normalize(out.trs, o + 3);
        }
    }

    /**
     * Lays an additive pose over {@code base} with strength {@code weight}: translation is added, rotation is multiplied on the left by
     * the additive rotation scaled toward the identity, scale is multiplied by the additive ratio scaled toward 1. With weight 1 and the
     * additive made by {@link #makeAdditive} against the same reference, the source pose comes back.
     */
    public static void applyAdditive(Pose base, Pose additive, float weight, Pose out) {
        base.requireJoints(additive.joints);
        base.requireJoints(out.joints);
        for (int j = 0; j < base.joints; j++) {
            int o = j * TransformMath.TRS;
            // read the base rotation first: out may be the base pose itself
            float bx = base.trs[o + 3], by = base.trs[o + 4], bz = base.trs[o + 5], bw = base.trs[o + 6];
            for (int k = 0; k < 3; k++) {
                out.trs[o + k] = base.trs[o + k] + weight * additive.trs[o + k];
                out.trs[o + 7 + k] = base.trs[o + 7 + k] * (1f + weight * (additive.trs[o + 7 + k] - 1f));
            }
            // out's rotation slot temporarily holds the scaled additive rotation, then delta * base
            TransformMath.slerp(TransformMath.IDENTITY_Q, 0, additive.trs, o + 3, weight, out.trs, o + 3);
            float dx = out.trs[o + 3], dy = out.trs[o + 4], dz = out.trs[o + 5], dw = out.trs[o + 6];
            out.trs[o + 3] = dw * bx + dx * bw + dy * bz - dz * by;
            out.trs[o + 4] = dw * by - dx * bz + dy * bw + dz * bx;
            out.trs[o + 5] = dw * bz + dx * by - dy * bx + dz * bw;
            out.trs[o + 6] = dw * bw - dx * bx - dy * by - dz * bz;
            Skeleton.normalize(out.trs, o + 3);
        }
    }
}
