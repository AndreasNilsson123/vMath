package vmath.anim;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyframed animation data for a skeleton: for each animated joint up to three tracks (translation, rotation, scale), each a sorted list
 * of key times with a value per key. Everything is stored in flat arrays (one for all key times, one for all key values), so a clip
 * with thousands of keys is a few arrays, and it is immutable once built.
 *
 * <p>Between keys, translation and scale are interpolated linearly and rotation by slerp along the shortest arc. Before the first key a
 * track holds its first value and after the last its last value. A joint or channel without a track keeps whatever the pose already
 * held (normally the bind pose). Step or cubic interpolation is not supported.
 *
 * <p>Build one with {@link #builder(int)}.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads freely. The arrays it hands out are its own storage: do
 * not modify them.
 */
public final class AnimationClip {

    /** The three animatable channels of a joint. */
    public enum Channel {
        TRANSLATION(3), ROTATION(4), SCALE(3);

        private final int components;

        Channel(int components) {
            this.components = components;
        }

        /** Floats per key value: 3 for translation and scale, 4 for a rotation quaternion ({@code x, y, z, w}). */
        public int components() {
            return components;
        }
    }

    private final int jointCount;
    private final float duration;
    private final int[] trackJoint;
    private final int[] trackChannel;
    private final int[] trackStart;   // first key of the track in times[]
    private final int[] trackValueStart; // first value of the track in values[] (tracks differ in floats per key)
    private final int[] trackKeys;
    private final float[] times;
    private final float[] values;

    private AnimationClip(int jointCount, float duration, int[] trackJoint, int[] trackChannel, int[] trackStart, int[] trackValueStart,
                          int[] trackKeys, float[] times, float[] values) {
        this.jointCount = jointCount;
        this.duration = duration;
        this.trackJoint = trackJoint;
        this.trackChannel = trackChannel;
        this.trackStart = trackStart;
        this.trackValueStart = trackValueStart;
        this.trackKeys = trackKeys;
        this.times = times;
        this.values = values;
    }

    public static Builder builder(int jointCount) {
        return new Builder(jointCount);
    }

    public int jointCount() {
        return jointCount;
    }

    /** Length of the clip in seconds: the time of the last key of any track, or the length given to the builder if that is larger. */
    public float duration() {
        return duration;
    }

    public int trackCount() {
        return trackJoint.length;
    }

    /** Total number of keys over all tracks. */
    public int keyCount() {
        return times.length;
    }

    int[] trackJoints() {
        return trackJoint;
    }

    int[] trackChannels() {
        return trackChannel;
    }

    int[] trackStarts() {
        return trackStart;
    }

    int[] trackValueStarts() {
        return trackValueStart;
    }

    int[] trackKeyCounts() {
        return trackKeys;
    }

    float[] keyTimes() {
        return times;
    }

    float[] keyValues() {
        return values;
    }

    /** Collects tracks and validates them. */
    public static final class Builder {
        private final int jointCount;
        private float minDuration;
        private final List<Integer> joints = new ArrayList<>();
        private final List<Integer> channels = new ArrayList<>();
        private final List<float[]> trackTimes = new ArrayList<>();
        private final List<float[]> trackValues = new ArrayList<>();

        private Builder(int jointCount) {
            if (jointCount < 1) {
                throw new IllegalArgumentException("a clip needs at least one joint");
            }
            this.jointCount = jointCount;
        }

        /**
         * Adds a track. {@code times} must be strictly increasing and finite (at least one key); {@code values} holds
         * {@link Channel#components()} floats per key. Rotation keys are normalised. The arrays are copied.
         */
        public Builder track(int joint, Channel channel, float[] times, float[] values) {
            if (joint < 0 || joint >= jointCount) {
                throw new IllegalArgumentException("joint " + joint + " is outside [0, " + jointCount + ")");
            }
            for (int i = 0; i < joints.size(); i++) {
                if (joints.get(i) == joint && channels.get(i) == channel.ordinal()) {
                    throw new IllegalArgumentException("joint " + joint + " already has a " + channel + " track");
                }
            }
            if (times.length < 1) {
                throw new IllegalArgumentException("a track needs at least one key");
            }
            if (values.length != times.length * channel.components()) {
                throw new IllegalArgumentException(channel + " needs " + channel.components() + " floats per key: " + times.length + " keys, "
                        + values.length + " values");
            }
            for (int i = 0; i < times.length; i++) {
                if (!Float.isFinite(times[i]) || times[i] < 0f || (i > 0 && !(times[i] > times[i - 1]))) {
                    throw new IllegalArgumentException("key times must be finite, non-negative and strictly increasing (key " + i + " = " + times[i] + ")");
                }
            }
            float[] v = values.clone();
            for (int i = 0; i < v.length; i++) {
                if (!Float.isFinite(v[i])) {
                    throw new IllegalArgumentException("non-finite key value at " + i);
                }
            }
            if (channel == Channel.ROTATION) {
                for (int k = 0; k < times.length; k++) {
                    Skeleton.normalize(v, k * 4);
                }
            }
            joints.add(joint);
            channels.add(channel.ordinal());
            trackTimes.add(times.clone());
            trackValues.add(v);
            return this;
        }

        public Builder translation(int joint, float[] times, float[] xyz) {
            return track(joint, Channel.TRANSLATION, times, xyz);
        }

        public Builder rotation(int joint, float[] times, float[] xyzw) {
            return track(joint, Channel.ROTATION, times, xyzw);
        }

        public Builder scale(int joint, float[] times, float[] xyz) {
            return track(joint, Channel.SCALE, times, xyz);
        }

        /** Makes the clip at least this long even if its last key is earlier (a clip that holds its last pose). */
        public Builder duration(float seconds) {
            if (!(seconds >= 0f) || Float.isInfinite(seconds)) {
                throw new IllegalArgumentException("duration must be finite and >= 0: " + seconds);
            }
            this.minDuration = seconds;
            return this;
        }

        public AnimationClip build() {
            int tracks = joints.size();
            int keys = 0;
            for (float[] t : trackTimes) {
                keys += t.length;
            }
            int[] tj = new int[tracks], tc = new int[tracks], ts = new int[tracks], tvs = new int[tracks], tk = new int[tracks];
            float[] allTimes = new float[keys];
            int valueCount = 0;
            for (int i = 0; i < tracks; i++) {
                valueCount += trackValues.get(i).length;
            }
            float[] allValues = new float[valueCount];
            int k = 0, vo = 0;
            float duration = minDuration;
            for (int i = 0; i < tracks; i++) {
                float[] t = trackTimes.get(i), v = trackValues.get(i);
                tj[i] = joints.get(i);
                tc[i] = channels.get(i);
                ts[i] = k;
                tvs[i] = vo;
                tk[i] = t.length;
                System.arraycopy(t, 0, allTimes, k, t.length);
                System.arraycopy(v, 0, allValues, vo, v.length);
                k += t.length;
                vo += v.length;
                duration = Math.max(duration, t[t.length - 1]);
            }
            return new AnimationClip(jointCount, duration, tj, tc, ts, tvs, tk, allTimes, allValues);
        }
    }
}
