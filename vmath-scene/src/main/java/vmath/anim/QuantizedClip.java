package vmath.anim;

/**
 * An {@link AnimationClip} stored in quantised form, sampled directly without expanding it: a
 * fraction of the memory for a small, bounded error.
 *
 * <p>For every key
 *
 * <ul>
 *   <li>the <b>time</b> is a 16-bit fraction of the clip duration (an error of at most
 *       {@code duration / 131070} seconds: 0.08 ms for a 10 second clip);</li>
 *   <li>a <b>translation</b> or <b>scale</b> is three 16-bit values, each a fraction of the range
 *       of that component over the track ({@code range / 131070} at most; a track that does not
 *       move at all costs nothing in precision);</li>
 *   <li>a <b>rotation</b> is a unit quaternion in the "smallest three" form, the largest of its
 *       four components dropped and rebuilt from the others: in {@link RotationFormat#PACKED_32}
 *       three 10-bit signed fractions and the 2-bit index of the dropped component in one
 *       {@code int} (the same format as {@code vmath.pack.QuatPacked}), in
 *       {@link RotationFormat#PACKED_64} three 20-bit fractions and the index in one
 *       {@code long}.</li>
 * </ul>
 *
 * <p>The rotation error of a component of the kept three is half a step of its range
 * {@code [-1/sqrt 2, 1/sqrt 2]}: 6.9e-4 for 10 bits and 6.7e-7 for 20 bits; the angle between the
 * stored and the true orientation is at most about 4 times that (the dropped component adds its own
 * share), the measured worst cases are in {@code docs/ANIMATION.md}.
 *
 * <p>{@link #sample} interpolates like {@link ClipSampler} (linear, slerp, held ends) and allocates
 * nothing; it keeps a cursor per track, so use one instance per playing clip and thread. When
 * several keys of a track fall into the same time code, the earlier ones are held at that time.
 * {@link #decode()} expands the clip back to an {@link AnimationClip}, for tools and tests.
 *
 * <p><b>Thread safety.</b> The data is immutable, but sampling updates the cursors: not
 * thread-safe; give each thread its own copy by calling {@link #copy()}, which shares the data.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AnimationClip clip = AnimationClip.builder(1).translation(0, new float[] {0f, 1f}, new float[] {0f, 0f, 0f, 1f, 0f, 0f}).build();
 * QuantizedClip quantized = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32);
 * long bytes = quantized.sizeInBytes();
 * Pose pose = new Pose(1);
 * quantized.sample(0.5f, true, pose);                                     // decodes while sampling; use one copy per thread
 * }</pre>
 */
public final class QuantizedClip {

    /**
     * How a rotation key is stored.
     */
    public enum RotationFormat {
        /**
         * Smallest-three in 32 bits: 10 bits per kept component.
         */
        PACKED_32,
        /**
         * Smallest-three in 64 bits: 20 bits per kept component.
         */
        PACKED_64
    }

    private static final float INV_SQRT2 = (float) Math.sqrt(0.5);

    private final int jointCount;
    private final float duration;
    private final RotationFormat format;
    private final int[] trackJoint, trackChannel, trackStart, trackKeys, trackRangeStart;
    private final char[] timeCodes;
    private final char[] valueCodes; // 3 per key for translation and scale tracks, nothing for rotation tracks
    private final int[] trackValueStart; // first value code of the track, or -1 for rotations
    private final float[] ranges; // per translation and scale track: min xyz, extent xyz
    private final int[] rotation32;
    private final long[] rotation64;
    private final int[] trackRotationStart;
    private final int[] cursor;

    private QuantizedClip(QuantizedClip o) {
        jointCount = o.jointCount;
        duration = o.duration;
        format = o.format;
        trackJoint = o.trackJoint;
        trackChannel = o.trackChannel;
        trackStart = o.trackStart;
        trackKeys = o.trackKeys;
        trackRangeStart = o.trackRangeStart;
        timeCodes = o.timeCodes;
        valueCodes = o.valueCodes;
        trackValueStart = o.trackValueStart;
        ranges = o.ranges;
        rotation32 = o.rotation32;
        rotation64 = o.rotation64;
        trackRotationStart = o.trackRotationStart;
        cursor = new int[o.cursor.length];
    }

    private QuantizedClip(AnimationClip clip, RotationFormat format) {
        this.jointCount = clip.jointCount();
        this.duration = clip.duration();
        this.format = format;
        int tracks = clip.trackCount();
        trackJoint = clip.trackJoints().clone();
        trackChannel = clip.trackChannels().clone();
        trackStart = clip.trackStarts().clone();
        trackKeys = clip.trackKeyCounts().clone();
        trackRangeStart = new int[tracks];
        trackValueStart = new int[tracks];
        trackRotationStart = new int[tracks];
        cursor = new int[tracks];
        int keys = clip.keyCount(), vectorKeys = 0, rotationKeys = 0, rangeTracks = 0;
        for (int t = 0; t < tracks; t++) {
            if (trackChannel[t] == 1) {
                rotationKeys += trackKeys[t];
            } else {
                vectorKeys += trackKeys[t];
                rangeTracks++;
            }
        }
        timeCodes = new char[keys];
        valueCodes = new char[3 * vectorKeys];
        ranges = new float[6 * rangeTracks];
        rotation32 = format == RotationFormat.PACKED_32 ? new int[rotationKeys] : null;
        rotation64 = format == RotationFormat.PACKED_64 ? new long[rotationKeys] : null;
        float[] times = clip.keyTimes(), values = clip.keyValues();
        int[] valueStart = clip.trackValueStarts();
        int vo = 0, ro = 0, rg = 0;
        for (int t = 0; t < tracks; t++) {
            int s = trackStart[t], n = trackKeys[t];
            char previous = 0;
            for (int i = 0; i < n; i++) {
                char code = duration > 0f ? (char) Math.round(Math.max(0f, Math.min(1f, times[s + i] / duration)) * 65535f) : 0;
                if (code < previous) {
                    code = previous; // rounding must not reorder keys
                }
                timeCodes[s + i] = code;
                previous = code;
            }
            if (trackChannel[t] == 1) {
                trackValueStart[t] = -1;
                trackRotationStart[t] = ro;
                for (int i = 0; i < n; i++) {
                    int o = valueStart[t] + 4 * i;
                    if (format == RotationFormat.PACKED_32) {
                        rotation32[ro++] = pack32(values, o);
                    } else {
                        rotation64[ro++] = pack64(values, o);
                    }
                }
            } else {
                trackRangeStart[t] = rg;
                trackValueStart[t] = vo;
                float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
                float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < 3; k++) {
                        float v = values[valueStart[t] + 3 * i + k];
                        lo[k] = Math.min(lo[k], v);
                        hi[k] = Math.max(hi[k], v);
                    }
                }
                for (int k = 0; k < 3; k++) {
                    ranges[rg + k] = lo[k];
                    ranges[rg + 3 + k] = hi[k] - lo[k];
                }
                rg += 6;
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < 3; k++) {
                        float extent = ranges[trackRangeStart[t] + 3 + k];
                        float v = values[valueStart[t] + 3 * i + k];
                        valueCodes[vo++] = extent > 0f ? (char) Math.round((v - ranges[trackRangeStart[t] + k]) / extent * 65535f) : 0;
                    }
                }
            }
        }
    }

    /**
     * Quantises {@code clip}, storing the rotations in the given format.
     *
     * @param clip the clip; must not be {@code null}
     * @param format the format; must not be {@code null}
     * @return the quantised clip, never {@code null}
     */
    public static QuantizedClip of(AnimationClip clip, RotationFormat format) {
        return new QuantizedClip(clip, format);
    }

    /**
     * Duplicates the sampler state while sharing the immutable data, so that several instances or
     * threads can play the same clip with their own cursors.
     *
     * @return a sampler over the same data with its own cursors, for another thread or another
     *     playing instance
     */
    public QuantizedClip copy() {
        return new QuantizedClip(this);
    }

    /**
     * Counts the joints that the clip animates.
     *
     * @return the number of joints the clip animates
     */
    public int jointCount() {
        return jointCount;
    }

    /**
     * Exposes the duration of the clip.
     *
     * @return the duration in seconds
     */
    public float duration() {
        return duration;
    }

    /**
     * Reports how the rotation keys are stored, which determines the size and the precision of the
     * clip.
     *
     * @return the format of the rotation keys
     */
    public RotationFormat rotationFormat() {
        return format;
    }

    /**
     * Counts the tracks of the clip.
     *
     * @return the number of tracks
     */
    public int trackCount() {
        return trackJoint.length;
    }

    /**
     * Counts the keys over all tracks of the clip.
     *
     * @return the number of keys over all tracks
     */
    public int keyCount() {
        return timeCodes.length;
    }

    /**
     * Measures the memory of the quantised clip, counting every table and array, to compare with
     * the uncompressed clip.
     *
     * @return the size of the quantised data in bytes: key times, values and rotations, the ranges
     *     of the vector tracks, and the track tables
     */
    public long sizeInBytes() {
        long bytes = 2L * timeCodes.length + 2L * valueCodes.length + 4L * ranges.length + 5L * 4 * trackJoint.length + 4L * trackValueStart.length + 4L * trackRotationStart.length;
        bytes += rotation32 != null ? 4L * rotation32.length : 8L * rotation64.length;
        return bytes;
    }

    /**
     * Measures the memory that an uncompressed clip needs under the same accounting, to compare
     * with {@link #sizeInBytes()}.
     *
     * @param clip the clip; must not be {@code null}
     * @return the size in bytes that {@code clip} takes as {@link AnimationClip} stores it: 4 bytes
     *     per key time and per value float, and its track tables
     */
    public static long sizeInBytes(AnimationClip clip) {
        long values = 0;
        for (int t = 0; t < clip.trackCount(); t++) {
            values += (long) clip.trackKeyCounts()[t] * (clip.trackChannels()[t] == 1 ? 4 : 3);
        }
        return 4L * clip.keyCount() + 4L * values + 5L * 4 * clip.trackCount();
    }

    // ------------------------------------------------------------ sampling

    /**
     * The time of the key code, in seconds.
     */
    private float timeOf(char code) {
        return code * duration / 65535f;
    }

    /**
     * Maps a playback time to a time inside the clip as {@link ClipSampler#wrap} does.
     *
     * @param time the time
     * @param loop whether loop
     * @return the time inside the clip, in {@code [0, duration]}
     */
    public float wrap(float time, boolean loop) {
        float d = duration;
        if (!(d > 0f) || time != time) {
            return 0f;
        }
        if (loop) {
            float t = time - (float) Math.floor(time / d) * d;
            return t >= d ? 0f : t;
        }
        return Math.max(0f, Math.min(d, time));
    }

    /**
     * Writes the value of every animated channel at {@code time} into {@code pose}, like
     * {@link ClipSampler#sample}.
     *
     * <p>Allocates nothing.
     *
     * @param time the time
     * @param loop whether loop
     * @param pose the pose; must not be {@code null}
     * @throws IllegalArgumentException if {@code pose} does not have as many joints as the clip
     */
    public void sample(float time, boolean loop, Pose pose) {
        if (pose.jointCount() != jointCount) {
            throw new IllegalArgumentException("pose has " + pose.jointCount() + " joints, the clip " + jointCount);
        }
        float t = wrap(time, loop);
        float[] out = pose.data();
        for (int tr = 0; tr < trackJoint.length; tr++) {
            int s = trackStart[tr], n = trackKeys[tr];
            int ch = trackChannel[tr];
            int dst = trackJoint[tr] * TransformMath.TRS + (ch == 0 ? 0 : ch == 1 ? 3 : 7);
            float t0 = timeOf(timeCodes[s]), tLast = timeOf(timeCodes[s + n - 1]);
            if (n == 1 || t <= t0) {
                decode(tr, 0, dst, out);
                continue;
            }
            if (t >= tLast) {
                decode(tr, n - 1, dst, out);
                continue;
            }
            int i = interval(tr, s, n, t);
            float a = timeOf(timeCodes[s + i]), b = timeOf(timeCodes[s + i + 1]);
            float f = b > a ? (t - a) / (b - a) : 0f;
            if (ch == 1) {
                interpolateRotation(tr, i, f, dst, out);
            } else {
                int v0 = trackValueStart[tr] + 3 * i, v1 = v0 + 3, r = trackRangeStart[tr];
                for (int k = 0; k < 3; k++) {
                    float x0 = ranges[r + k] + ranges[r + 3 + k] * valueCodes[v0 + k] / 65535f;
                    float x1 = ranges[r + k] + ranges[r + 3 + k] * valueCodes[v1 + k] / 65535f;
                    out[dst + k] = x0 + (x1 - x0) * f;
                }
            }
        }
    }

    /**
     * Index {@code i} relative to the track with {@code time(i) <= t < time(i + 1)}; t is strictly
     * inside the track.
     */
    private int interval(int track, int s, int n, float t) {
        int c = cursor[track];
        if (c + 1 < n && timeOf(timeCodes[s + c]) <= t && t < timeOf(timeCodes[s + c + 1])) {
            return c;
        }
        if (c + 2 < n && timeOf(timeCodes[s + c + 1]) <= t && t < timeOf(timeCodes[s + c + 2])) {
            cursor[track] = c + 1;
            return c + 1;
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (timeOf(timeCodes[s + mid]) <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        cursor[track] = lo;
        return lo;
    }

    /**
     * Writes the value of key {@code i} of the track at {@code out[dst ..]}.
     */
    private void decode(int tr, int i, int dst, float[] out) {
        if (trackChannel[tr] == 1) {
            if (format == RotationFormat.PACKED_32) {
                unpack32(rotation32[trackRotationStart[tr] + i], out, dst);
            } else {
                unpack64(rotation64[trackRotationStart[tr] + i], out, dst);
            }
        } else {
            int v = trackValueStart[tr] + 3 * i, r = trackRangeStart[tr];
            for (int k = 0; k < 3; k++) {
                out[dst + k] = ranges[r + k] + ranges[r + 3 + k] * valueCodes[v + k] / 65535f;
            }
        }
    }

    private final float[] qa = new float[4], qb = new float[4];

    private void interpolateRotation(int tr, int i, float f, int dst, float[] out) {
        decode(tr, i, 0, qa);
        decode(tr, i + 1, 0, qb);
        TransformMath.slerp(qa, 0, qb, 0, f, out, dst);
    }

    // ------------------------------------------------------------ expanding

    /**
     * Expands the clip to an {@link AnimationClip} with the quantised values (the key times are
     * those of the codes), for inspection and tools.
     *
     * <p>Allocates.
     *
     * @return the expanded clip, never {@code null}
     */
    public AnimationClip decode() {
        AnimationClip.Builder b = AnimationClip.builder(jointCount);
        b.duration(duration);
        float[] tmp = new float[4];
        for (int tr = 0; tr < trackJoint.length; tr++) {
            int n = trackKeys[tr], comps = trackChannel[tr] == 1 ? 4 : 3;
            float[] t = new float[n], v = new float[n * comps];
            float previous = -1f;
            for (int i = 0; i < n; i++) {
                float time = timeOf(timeCodes[trackStart[tr] + i]);
                // the builder wants strictly increasing times: keys that share a code are moved apart by the smallest step
                if (time <= previous) {
                    time = Math.nextUp(previous);
                }
                t[i] = time;
                previous = time;
                decode(tr, i, 0, tmp);
                System.arraycopy(tmp, 0, v, i * comps, comps);
            }
            b.track(trackJoint[tr], AnimationClip.Channel.values()[trackChannel[tr]], t, v);
        }
        return b.build();
    }

    // ------------------------------------------------------------ smallest three

    private static int largest(float[] v, int o) {
        int l = 0;
        for (int i = 1; i < 4; i++) {
            if (Math.abs(v[o + i]) > Math.abs(v[o + l])) {
                l = i;
            }
        }
        return l;
    }

    private static int pack32(float[] v, int o) {
        int l = largest(v, o);
        float sign = v[o + l] < 0f ? -1f : 1f;
        int packed = l << 30, shift = 0;
        for (int i = 0; i < 4; i++) {
            if (i != l) {
                float x = Math.max(-1f, Math.min(1f, v[o + i] * sign / INV_SQRT2));
                packed |= (Math.round(x * 511f) & 0x3FF) << shift;
                shift += 10;
            }
        }
        return packed;
    }

    private static long pack64(float[] v, int o) {
        int l = largest(v, o);
        float sign = v[o + l] < 0f ? -1f : 1f;
        long packed = (long) l << 62;
        int shift = 0;
        for (int i = 0; i < 4; i++) {
            if (i != l) {
                double x = Math.max(-1.0, Math.min(1.0, v[o + i] * (double) sign / INV_SQRT2));
                packed |= (Math.round(x * 524287.0) & 0xFFFFFL) << shift;
                shift += 20;
            }
        }
        return packed;
    }

    private static void unpack32(int packed, float[] out, int o) {
        int l = packed >>> 30;
        float sumSquares = 0f;
        int shift = 0;
        for (int i = 0; i < 4; i++) {
            if (i != l) {
                int code = (packed >> shift) & 0x3FF;
                code = code >= 512 ? code - 1024 : code;
                float x = code / 511f * INV_SQRT2;
                out[o + i] = x;
                sumSquares += x * x;
                shift += 10;
            }
        }
        out[o + l] = (float) Math.sqrt(Math.max(0f, 1f - sumSquares));
    }

    private static void unpack64(long packed, float[] out, int o) {
        int l = (int) (packed >>> 62);
        double sumSquares = 0;
        int shift = 0;
        for (int i = 0; i < 4; i++) {
            if (i != l) {
                long code = (packed >> shift) & 0xFFFFFL;
                code = code >= 524288L ? code - 1048576L : code;
                double x = code / 524287.0 * INV_SQRT2;
                out[o + i] = (float) x;
                sumSquares += x * x;
                shift += 20;
            }
        }
        out[o + l] = (float) Math.sqrt(Math.max(0.0, 1.0 - sumSquares));
    }

    /**
     * Writes the quantised rotation of key {@code i} of rotation track {@code tr} (for tests and
     * tools) as a unit quaternion to {@code out[0 .. 4)}.
     *
     * @param tr the track index
     * @param i the index
     * @param out receives the result
     * @throws IllegalArgumentException if track {@code tr} is not a rotation track
     */
    public void rotationKey(int tr, int i, float[] out) {
        if (trackChannel[tr] != 1) {
            throw new IllegalArgumentException("track " + tr + " is not a rotation track");
        }
        decode(tr, i, 0, out);
    }

    @Override
    public String toString() {
        return "QuantizedClip[" + trackJoint.length + " tracks, " + timeCodes.length + " keys, " + sizeInBytes() + " bytes, " + format + ", " + duration + " s]";
    }
}
