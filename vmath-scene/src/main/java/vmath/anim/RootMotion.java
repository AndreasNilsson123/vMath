package vmath.anim;

/**
 * Root motion: the movement of the root joint of a clip (a character walking, turning, jumping),
 * extracted from the animation so that the game can move the character by it instead of letting the
 * animated pose slide on the spot or drift away.
 *
 * <p>Two operations:
 *
 * <ul>
 *   <li>{@link #delta} measures how far the root moved between two times of the clip, as a
 *       translation and a rotation, in the frame the root had at the first time (so "forward" is
 *       where the character was facing). It handles looping: when the second time is before the
 *       first, the clip is taken to have wrapped around.</li>
 *   <li>{@link #strip} removes the part of the root's motion that the game applies itself from a
 *       sampled pose: the translation in the horizontal plane (or in all axes) and the turn about
 *       the vertical axis, leaving the rest of the animation (the bobbing, the lean) in place.</li>
 * </ul>
 *
 * <p>The vertical axis is the y axis of the root joint's parent space; the horizontal plane is x-z.
 * An object samples the clip itself, so it owns a {@link ClipSampler} and two poses and allocates
 * nothing per call. <b>Thread safety.</b> Not thread-safe: one instance per thread and clip.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AnimationClip clip = AnimationClip.builder(1).translation(0, new float[] {0f, 1f}, new float[] {0f, 0f, 0f, 2f, 0f, 0f}).build();
 * RootMotion root = new RootMotion(clip, 0);
 * float[] delta = new float[7];                                           // translation xyz and rotation xyzw
 * root.delta(0.25f, 0.5f, true, delta);                                   // the movement of the root over that time step
 * Pose pose = new Pose(1);
 * new ClipSampler(clip).sample(0.5f, true, pose);
 * root.strip(pose, new Pose(1), RootMotion.Mode.TRANSLATION_XZ);          // leaves the movement to the game
 * }</pre>
 */
public final class RootMotion {

    /**
     * What {@link #strip} removes from the root.
     */
    public enum Mode {
        /**
         * Nothing: the pose is left as it is.
         */
        NONE,
        /**
         * The translation in the horizontal plane, x and z; the height stays.
         */
        TRANSLATION_XZ,
        /**
         * The translation along all three axes.
         */
        TRANSLATION_XYZ,
        /**
         * The rotation about the vertical axis (the yaw); the lean and roll stay.
         */
        YAW,
        /**
         * The horizontal translation and the yaw: the usual locomotion root motion.
         */
        TRANSLATION_XZ_AND_YAW
    }

    private final ClipSampler sampler;
    private final int root;
    private final Pose a, b;
    private final float[] q = new float[4], p = new float[4];

    /**
     * Creates the root motion of {@code rootJoint} in {@code clip}.
     *
     * @param clip the clip; must not be {@code null}
     * @param rootJoint the root joint
     * @throws IllegalArgumentException if {@code rootJoint} is not a joint of the clip
     */
    public RootMotion(AnimationClip clip, int rootJoint) {
        if (rootJoint < 0 || rootJoint >= clip.jointCount()) {
            throw new IllegalArgumentException("the root joint " + rootJoint + " is outside [0, " + clip.jointCount() + ")");
        }
        this.sampler = new ClipSampler(clip);
        this.root = rootJoint;
        this.a = new Pose(clip.jointCount());
        this.b = new Pose(clip.jointCount());
    }

    /**
     * Exposes the clip that the root motion is extracted from.
     *
     * @return the clip
     */
    public AnimationClip clip() {
        return sampler.clip();
    }

    /**
     * Computes the movement of the root from time {@code t0} to time {@code t1} (seconds in the
     * clip) and writes it to {@code out[0 .. 7)}: the translation {@code x, y, z} and the rotation
     * quaternion {@code x, y, z, w}, both in the frame of the root at {@code t0}: the translation
     * is {@code R0^-1 (T1 - T0)} and the rotation {@code R0^-1 R1}.
     *
     * <p>Without {@code loop} the times are clamped to the clip. With {@code loop}, a {@code t1}
     * before {@code t0} (after wrapping both into the clip) means that the clip started again: the
     * motion is that from {@code t0} to the end followed by that from the start to {@code t1}, the
     * second part carried into the frame the first ended in. If the clip is seamless (the root ends
     * where it starts, apart from the motion itself) this is the true movement across the loop
     * point.
     *
     * @param t0 the start time in seconds
     * @param t1 the end time in seconds
     * @param loop whether loop
     * @param out receives the result in {@code [0, 7)}
     */
    public void delta(float t0, float t1, boolean loop, float[] out) {
        float w0 = sampler.wrap(t0, loop), w1 = sampler.wrap(t1, loop);
        if (loop && w1 < w0) {
            float end = sampler.clip().duration();
            segment(w0, end, out);
            float t0x = out[0], t0y = out[1], t0z = out[2];
            float r0x = out[3], r0y = out[4], r0z = out[5], r0w = out[6];
            segment(0f, w1, out);
            // chain: the second movement is expressed in the frame the first one ended in
            rotate(r0x, r0y, r0z, r0w, out[0], out[1], out[2], p);
            float px = t0x + p[0], py = t0y + p[1], pz = t0z + p[2];
            multiply(r0x, r0y, r0z, r0w, out[3], out[4], out[5], out[6], q);
            out[0] = px;
            out[1] = py;
            out[2] = pz;
            out[3] = q[0];
            out[4] = q[1];
            out[5] = q[2];
            out[6] = q[3];
        } else {
            segment(w0, w1, out);
        }
    }

    /**
     * Computes the movement of the root over the whole clip, from its start to its end:
     * {@code delta(0, duration, false, out)}.
     *
     * @param out receives the result
     */
    public void total(float[] out) {
        delta(0f, sampler.clip().duration(), false, out);
    }

    private void segment(float t0, float t1, float[] out) {
        sampler.sampleJoint(t0, false, root, a);
        sampler.sampleJoint(t1, false, root, b);
        float[] da = a.data(), db = b.data();
        int o = root * TransformMath.TRS;
        float dx = db[o] - da[o], dy = db[o + 1] - da[o + 1], dz = db[o + 2] - da[o + 2];
        // R0^-1 d: rotate by the conjugate
        rotate(-da[o + 3], -da[o + 4], -da[o + 5], da[o + 6], dx, dy, dz, p);
        out[0] = p[0];
        out[1] = p[1];
        out[2] = p[2];
        // the rotation of the root relative to its rotation at t0: R0^-1 * R1
        multiply(-da[o + 3], -da[o + 4], -da[o + 5], da[o + 6], db[o + 3], db[o + 4], db[o + 5], db[o + 6], q);
        out[3] = q[0];
        out[4] = q[1];
        out[5] = q[2];
        out[6] = q[3];
    }

    /**
     * Removes the motion that the game applies from the root of {@code pose}, in place: the
     * horizontal (or full) translation is set to that of {@code reference} (the bind pose, or the
     * first frame of the clip), and the yaw (the twist about the vertical axis) is taken out of the
     * rotation, so that what remains is the swing, the lean away from upright.
     *
     * <p>{@code reference} supplies the translation values to restore; its rotation is not used.
     * The pose and the reference must have the root joint.
     *
     * @param pose the pose; must not be {@code null}
     * @param reference the reference; must not be {@code null}
     * @param mode the mode; must not be {@code null}
     */
    public void strip(Pose pose, Pose reference, Mode mode) {
        if (mode == Mode.NONE) {
            return;
        }
        float[] d = pose.data(), r = reference.data();
        int o = root * TransformMath.TRS;
        if (mode == Mode.TRANSLATION_XZ || mode == Mode.TRANSLATION_XZ_AND_YAW) {
            d[o] = r[o];
            d[o + 2] = r[o + 2];
        } else if (mode == Mode.TRANSLATION_XYZ) {
            d[o] = r[o];
            d[o + 1] = r[o + 1];
            d[o + 2] = r[o + 2];
        }
        if (mode == Mode.YAW || mode == Mode.TRANSLATION_XZ_AND_YAW) {
            removeYaw(d, o + 3);
        }
    }

    /**
     * Extracts the yaw from a quaternion by isolating its twist about the vertical axis, which is
     * how root motion turns are measured.
     *
     * @param q the array holding the quaternion
     * @param o the index of the first float of the quaternion in the array
     * @return the yaw of the rotation {@code (x, y, z, w)} at {@code q[o]}: the angle in radians of
     *     its twist about the y axis, in {@code (-pi, pi]}
     */
    public static float yaw(float[] q, int o) {
        // twist about y: the rotation (0, y, 0, w) normalised
        return 2f * (float) Math.atan2(q[o + 1], q[o + 3]);
    }

    /**
     * Replaces the quaternion at {@code q[o]} by the rotation with its yaw taken out:
     * {@code q = twist * swing} with the twist about the y axis ({@code (0, y, 0, w)} normalised),
     * and the result is the swing, {@code conjugate(twist) * q}.
     *
     * <p>A character turned by a yaw about the world's vertical and then leaned in its own frame
     * keeps its lean and loses the heading.
     *
     * @param q the array holding the quaternion, changed in place
     * @param o the index of the first float of the quaternion in the array
     */
    public static void removeYaw(float[] q, int o) {
        float x = q[o], y = q[o + 1], z = q[o + 2], w = q[o + 3];
        float l = (float) Math.sqrt(y * y + w * w);
        if (l < 1e-12f) {
            return; // a half turn about an axis in the horizontal plane has no defined twist
        }
        float cy = -y / l, cw = w / l; // the conjugate of the twist: (0, -ty, 0, tw)
        float sx = cw * x + cy * z;
        float sy = cw * y + cy * w;
        float sz = cw * z - cy * x;
        float sw = cw * w - cy * y;
        float n = (float) Math.sqrt(sx * sx + sy * sy + sz * sz + sw * sw);
        q[o] = sx / n;
        q[o + 1] = sy / n;
        q[o + 2] = sz / n;
        q[o + 3] = sw / n;
    }

    private static void rotate(float qx, float qy, float qz, float qw, float vx, float vy, float vz, float[] out) {
        float tx = 2f * (qy * vz - qz * vy), ty = 2f * (qz * vx - qx * vz), tz = 2f * (qx * vy - qy * vx);
        out[0] = vx + qw * tx + (qy * tz - qz * ty);
        out[1] = vy + qw * ty + (qz * tx - qx * tz);
        out[2] = vz + qw * tz + (qx * ty - qy * tx);
    }

    private static void multiply(float ax, float ay, float az, float aw, float bx, float by, float bz, float bw, float[] out) {
        out[0] = aw * bx + ax * bw + ay * bz - az * by;
        out[1] = aw * by - ax * bz + ay * bw + az * bx;
        out[2] = aw * bz + ax * by - ay * bx + az * bw;
        out[3] = aw * bw - ax * bx - ay * by - az * bz;
    }
}
