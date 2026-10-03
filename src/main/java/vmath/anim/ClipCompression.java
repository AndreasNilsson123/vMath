package vmath.anim;

import java.util.Arrays;

/**
 * Curve fitting for animation clips: removes the keys of every track that the track can do without, within a tolerance. Motion captured or baked at 30 or 60 keys per second is mostly
 * smooth, and most keys lie (almost) on the straight line between their neighbours; keeping only the keys that bend the curve makes the clip several times smaller with no visible change.
 *
 * <p>The algorithm is the recursive subdivision of Ramer, Douglas and Peucker in the space of time and value: the first and last key are kept; the key farthest from the curve that
 * interpolates between the kept neighbours (linear for translation and scale, slerp for rotation, exactly as {@link ClipSampler} interpolates) is kept if it is farther than the tolerance, and
 * both sides are examined again. The distance is the Euclidean one for translations and scales, and the angle in radians between the two orientations for rotations.
 *
 * <p><b>The guarantee.</b> For translation and scale tracks the reduced curve differs from the original by at most the tolerance <b>everywhere</b>, not only at the removed keys: both are
 * piecewise linear, their difference is piecewise linear with its corners at the original keys, and the largest value of such a function is at a corner. For rotation tracks the guarantee
 * holds at the original keys and the deviation between them is of the same size in practice ({@link #measure} reports it by sampling both clips). A track whose every key is within the
 * tolerance of its first key becomes a single constant key.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time.
 */
public final class ClipCompression {

    private ClipCompression() {
    }

    /**
     * A copy of {@code clip} with keys removed from each track within the given tolerances: {@code translationTolerance} in the units of the positions, {@code rotationTolerance} in
     * radians, {@code scaleTolerance} in the units of the scale values. A tolerance of zero removes only keys that are exactly on the interpolation of their neighbours. The duration of
     * the clip is kept.
     */
    public static AnimationClip reduce(AnimationClip clip, float translationTolerance, float rotationTolerance, float scaleTolerance) {
        if (!(translationTolerance >= 0f) || !(rotationTolerance >= 0f) || !(scaleTolerance >= 0f)) {
            throw new IllegalArgumentException("the tolerances must not be negative: " + translationTolerance + ", " + rotationTolerance + ", " + scaleTolerance);
        }
        AnimationClip.Builder b = AnimationClip.builder(clip.jointCount());
        b.duration(clip.duration());
        int[] joint = clip.trackJoints(), channel = clip.trackChannels(), start = clip.trackStarts(), valueStart = clip.trackValueStarts(), keys = clip.trackKeyCounts();
        float[] times = clip.keyTimes(), values = clip.keyValues();
        for (int tr = 0; tr < joint.length; tr++) {
            int n = keys[tr];
            int comps = channel[tr] == 1 ? 4 : 3;
            float tol = channel[tr] == 0 ? translationTolerance : channel[tr] == 1 ? rotationTolerance : scaleTolerance;
            boolean[] keep = select(times, start[tr], values, valueStart[tr], n, comps, channel[tr] == 1, tol);
            int kept = 0;
            for (boolean k : keep) {
                if (k) {
                    kept++;
                }
            }
            float[] t = new float[kept], v = new float[kept * comps];
            int j = 0;
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    t[j] = times[start[tr] + i];
                    System.arraycopy(values, valueStart[tr] + i * comps, v, j * comps, comps);
                    j++;
                }
            }
            b.track(joint[tr], AnimationClip.Channel.values()[channel[tr]], t, v);
        }
        return b.build();
    }

    /** The keys of one track to keep: the flags, by the subdivision described in the class comment. */
    private static boolean[] select(float[] times, int ts, float[] values, int vs, int n, int comps, boolean rotation, float tol) {
        boolean[] keep = new boolean[n];
        keep[0] = true;
        if (n == 1) {
            return keep;
        }
        // a track that never leaves the tolerance around its first value is a constant
        boolean constant = true;
        for (int i = 1; i < n && constant; i++) {
            constant = distance(values, vs, values, vs + i * comps, comps, rotation) <= tol;
        }
        if (constant) {
            return keep;
        }
        keep[n - 1] = true;
        float[] tmp = new float[4];
        int[] stack = new int[2 * Math.max(8, 2 * (int) (Math.log(n) / Math.log(2)) + 8)];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        while (sp > 0) {
            int hi = stack[--sp], lo = stack[--sp];
            if (hi - lo < 2) {
                continue;
            }
            float t0 = times[ts + lo], t1 = times[ts + hi];
            int worst = -1;
            float worstError = tol;
            for (int i = lo + 1; i < hi; i++) {
                float f = (times[ts + i] - t0) / (t1 - t0);
                interpolate(values, vs + lo * comps, values, vs + hi * comps, f, comps, rotation, tmp);
                float e = distance(values, vs + i * comps, tmp, 0, comps, rotation);
                if (e > worstError) {
                    worstError = e;
                    worst = i;
                }
            }
            if (worst >= 0) {
                keep[worst] = true;
                if (sp + 4 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = lo;
                stack[sp++] = worst;
                stack[sp++] = worst;
                stack[sp++] = hi;
            }
        }
        return keep;
    }

    private static void interpolate(float[] a, int ao, float[] b, int bo, float f, int comps, boolean rotation, float[] out) {
        if (rotation) {
            TransformMath.slerp(a, ao, b, bo, f, out, 0);
        } else {
            for (int k = 0; k < comps; k++) {
                out[k] = a[ao + k] + (b[bo + k] - a[ao + k]) * f;
            }
        }
    }

    /** The Euclidean distance of two values, or the angle in radians between two unit quaternions (the shortest arc: {@code q} and {@code -q} are the same orientation). */
    private static float distance(float[] a, int ao, float[] b, int bo, int comps, boolean rotation) {
        if (rotation) {
            // the angle by atan2 of the lengths of the difference and the sum (after choosing the sign that makes the dot product positive): accurate for tiny angles, where acos of
            // a dot product that rounds to 1 would report a rotation of 7e-4 radians for two identical quaternions
            double dot = (double) a[ao] * b[bo] + (double) a[ao + 1] * b[bo + 1] + (double) a[ao + 2] * b[bo + 2] + (double) a[ao + 3] * b[bo + 3];
            double sign = dot < 0 ? -1.0 : 1.0, dd = 0, ss = 0;
            for (int k = 0; k < 4; k++) {
                double x = a[ao + k] - sign * b[bo + k], y = a[ao + k] + sign * b[bo + k];
                dd += x * x;
                ss += y * y;
            }
            return (float) (2.0 * Math.atan2(Math.sqrt(dd), Math.sqrt(ss)));
        }
        double s = 0;
        for (int k = 0; k < comps; k++) {
            double d = a[ao + k] - b[bo + k];
            s += d * d;
        }
        return (float) Math.sqrt(s);
    }

    /**
     * Measures how far {@code reduced} is from {@code original} by sampling both at {@code samples} evenly spaced times over the original's duration (and at the end): the largest
     * translation distance, the largest rotation angle in radians and the largest scale distance over all joints are written to {@code out[0]}, {@code out[1]} and {@code out[2]}. The
     * clips must have the same number of joints. A channel that only one of the clips animates is compared with the identity (zero translation, no rotation, unit scale).
     */
    public static void measure(AnimationClip original, AnimationClip reduced, int samples, double[] out) {
        if (original.jointCount() != reduced.jointCount()) {
            throw new IllegalArgumentException("the clips have different numbers of joints: " + original.jointCount() + ", " + reduced.jointCount());
        }
        if (samples < 2) {
            throw new IllegalArgumentException("need at least 2 samples: " + samples);
        }
        ClipSampler sa = new ClipSampler(original), sb = new ClipSampler(reduced);
        Pose pa = new Pose(original.jointCount()), pb = new Pose(original.jointCount());
        double maxT = 0, maxR = 0, maxS = 0;
        float d = original.duration();
        for (int i = 0; i < samples; i++) {
            float t = d * i / (samples - 1);
            sa.sample(t, false, pa);
            sb.sample(t, false, pb);
            float[] x = pa.data(), y = pb.data();
            for (int j = 0; j < original.jointCount(); j++) {
                int o = j * TransformMath.TRS;
                maxT = Math.max(maxT, distance(x, o, y, o, 3, false));
                maxR = Math.max(maxR, distance(x, o + 3, y, o + 3, 4, true));
                maxS = Math.max(maxS, distance(x, o + 7, y, o + 7, 3, false));
            }
        }
        out[0] = maxT;
        out[1] = maxR;
        out[2] = maxS;
    }
}
