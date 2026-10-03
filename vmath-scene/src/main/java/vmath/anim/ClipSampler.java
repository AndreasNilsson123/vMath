package vmath.anim;

/**
 * Samples an {@link AnimationClip} into a {@link Pose}.
 *
 * <p>One sampler per clip and playing instance: it remembers, for every track, the key interval
 * used last time, so playing a clip forward frame after frame finds the next interval by looking at
 * the current one and its neighbour instead of searching; jumping around in time falls back to a
 * binary search and gives identical results.
 *
 * <p>{@link #sample} writes only the channels the clip animates, so start from the bind pose (or
 * any base pose) and the joints the clip does not mention keep their values. Nothing is allocated
 * per call. Not thread-safe (it holds the cursors).
 *
 * <p><b>Thread safety.</b> Not thread-safe: it holds one cursor per track, so use one sampler per
 * playing instance of a clip and thread. Nothing blocks and nothing is allocated per call.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AnimationClip clip = AnimationClip.builder(1).translation(0, new float[] {0f, 1f}, new float[] {0f, 0f, 0f, 1f, 0f, 0f}).build();
 * ClipSampler sampler = new ClipSampler(clip);                            // one per playing instance: it keeps cursors
 * Pose pose = new Pose(1);
 * for (float time = 0f; time < 3f; time += 1f / 60f) {
 *     sampler.sample(time, true, pose);                                   // loops; nothing is allocated per call
 * }
 * }</pre>
 */
public final class ClipSampler {

    private final AnimationClip clip;
    private final int[] cursor;

    /**
     * Creates a sampler for {@code clip}, with every cursor at the start.
     *
     * @param clip the clip; must not be {@code null}
     */
    public ClipSampler(AnimationClip clip) {
        this.clip = clip;
        this.cursor = new int[clip.trackCount()];
    }

    /**
     * Exposes the clip that the sampler reads.
     *
     * @return the clip this sampler reads
     */
    public AnimationClip clip() {
        return clip;
    }

    /**
     * Maps a playback time to a time inside the clip: with {@code loop} it wraps around the
     * duration (also for negative times), without it clamps to {@code [0, duration]}.
     *
     * <p>A clip of zero duration always gives 0. NaN gives 0.
     *
     * @param time the time
     * @param loop whether loop
     * @return the time inside the clip, in {@code [0, duration]}
     */
    public float wrap(float time, boolean loop) {
        float d = clip.duration();
        if (!(d > 0f) || time != time) {
            return 0f;
        }
        if (loop) {
            float t = time - (float) Math.floor(time / d) * d;
            return t >= d ? 0f : t; // rounding can land exactly on d
        }
        return Math.max(0f, Math.min(d, time));
    }

    /**
     * Writes the value of every animated channel at {@code time} (see {@link #wrap}) into
     * {@code pose}.
     *
     * @param time the time
     * @param loop whether loop
     * @param pose the pose; must not be {@code null}
     * @throws IllegalArgumentException if {@code pose} does not have as many joints as the clip
     */
    public void sample(float time, boolean loop, Pose pose) {
        if (pose.jointCount() != clip.jointCount()) {
            throw new IllegalArgumentException("pose has " + pose.jointCount() + " joints, the clip " + clip.jointCount());
        }
        float t = wrap(time, loop);
        int tracks = clip.trackCount();
        for (int tr = 0; tr < tracks; tr++) {
            sampleTrack(tr, t, pose.data());
        }
    }

    /**
     * Samples only the channels of one {@code joint}, as {@link #sample} does for all of them: the
     * other joints of {@code pose} are left as they are.
     *
     * <p>For when a single joint is wanted (the root of {@link RootMotion}) and sampling the whole
     * skeleton would be wasted.
     *
     * @param time the time
     * @param loop whether loop
     * @param joint the joint index
     * @param pose the pose; must not be {@code null}
     * @throws IllegalArgumentException if {@code pose} does not have as many joints as the clip
     */
    public void sampleJoint(float time, boolean loop, int joint, Pose pose) {
        if (pose.jointCount() != clip.jointCount()) {
            throw new IllegalArgumentException("pose has " + pose.jointCount() + " joints, the clip " + clip.jointCount());
        }
        float t = wrap(time, loop);
        int[] joints = clip.trackJoints();
        for (int tr = 0; tr < joints.length; tr++) {
            if (joints[tr] == joint) {
                sampleTrack(tr, t, pose.data());
            }
        }
    }

    private void sampleTrack(int tr, float t, float[] out) {
        int[] joint = clip.trackJoints(), channel = clip.trackChannels(), start = clip.trackStarts(), valueStart = clip.trackValueStarts(), keys = clip.trackKeyCounts();
        float[] times = clip.keyTimes(), values = clip.keyValues();
        int s = start[tr], n = keys[tr];
        int ch = channel[tr];
        int comps = ch == 1 ? 4 : 3;
        int dst = joint[tr] * TransformMath.TRS + (ch == 0 ? 0 : ch == 1 ? 3 : 7);
        int vBase = valueStart[tr];
        if (n == 1 || t <= times[s]) {
            System.arraycopy(values, vBase, out, dst, comps);
            return;
        }
        if (t >= times[s + n - 1]) {
            System.arraycopy(values, vBase + (n - 1) * comps, out, dst, comps);
            return;
        }
        int i = findInterval(tr, s, n, times, t);
        float t0 = times[s + i], t1 = times[s + i + 1];
        float f = (t - t0) / (t1 - t0);
        int v0 = vBase + i * comps, v1 = v0 + comps;
        if (ch == 1) {
            TransformMath.slerp(values, v0, values, v1, f, out, dst);
        } else {
            for (int k = 0; k < 3; k++) {
                out[dst + k] = values[v0 + k] + (values[v1 + k] - values[v0 + k]) * f;
            }
        }
    }

    /**
     * Index {@code i} (relative to the track start) with {@code times[i] <= t < times[i + 1]}; t is
     * strictly inside the track.
     */
    private int findInterval(int track, int s, int n, float[] times, float t) {
        int c = cursor[track];
        if (c >= 0 && c + 1 < n && times[s + c] <= t && t < times[s + c + 1]) {
            return c;
        }
        if (c >= 0 && c + 2 < n && times[s + c + 1] <= t && t < times[s + c + 2]) {
            cursor[track] = c + 1;
            return c + 1;
        }
        int lo = 0, hi = n - 1; // times[s + lo] <= t < times[s + hi]
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[s + mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        cursor[track] = lo;
        return lo;
    }
}
