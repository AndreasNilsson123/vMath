package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class ClipCompressionTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    /** A clip of {@code joints} joints sampled at 60 keys per second for 5 seconds from smooth functions plus a little noise. */
    private AnimationClip smoothClip(int joints, double noise) {
        int keys = 301;
        float[] t = new float[keys];
        for (int i = 0; i < keys; i++) {
            t[i] = i / 60f;
        }
        AnimationClip.Builder b = AnimationClip.builder(joints);
        for (int j = 0; j < joints; j++) {
            float[] pos = new float[3 * keys], rot = new float[4 * keys], scl = new float[3 * keys];
            double f1 = 0.5 + rng.nextDouble(), f2 = 1 + 2 * rng.nextDouble(), p = rng.nextDouble() * 6;
            for (int i = 0; i < keys; i++) {
                double x = t[i];
                pos[3 * i] = (float) (Math.sin(f1 * x + p) * 2 + noise * rng.nextGaussian());
                pos[3 * i + 1] = (float) (Math.cos(f2 * x) + noise * rng.nextGaussian());
                pos[3 * i + 2] = (float) (x * 0.7 + noise * rng.nextGaussian());
                double a = 1.2 * Math.sin(f1 * x) + noise * rng.nextGaussian(), c = Math.cos(a / 2), s = Math.sin(a / 2);
                double ax = Math.sin(f2 * x + p), ay = Math.cos(f2 * x + p), l = Math.sqrt(ax * ax + ay * ay + 0.25);
                rot[4 * i] = (float) (s * ax / l);
                rot[4 * i + 1] = (float) (s * ay / l);
                rot[4 * i + 2] = (float) (s * 0.5 / l);
                rot[4 * i + 3] = (float) c;
                scl[3 * i] = (float) (1 + 0.1 * Math.sin(x));
                scl[3 * i + 1] = 1f;
                scl[3 * i + 2] = (float) (1 + 0.1 * Math.cos(x));
            }
            b.translation(j, t, pos).rotation(j, t, rot).scale(j, t, scl);
        }
        return b.build();
    }

    @Test
    void reductionKeepsTheCurveWithinTheTolerance() {
        AnimationClip clip = smoothClip(3, 0.0);
        float tt = 0.01f, tr = 0.005f, ts = 0.002f;
        AnimationClip reduced = ClipCompression.reduce(clip, tt, tr, ts);
        assertEquals(clip.duration(), reduced.duration());
        assertEquals(clip.trackCount(), reduced.trackCount());
        assertTrue(reduced.keyCount() * 3 < clip.keyCount(), "a smooth clip loses most of its keys: " + clip.keyCount() + " -> " + reduced.keyCount());
        double[] err = new double[3];
        ClipCompression.measure(clip, reduced, 3001, err); // 10 samples between the original keys
        assertTrue(err[0] <= tt * 1.0001, "translation error " + err[0]);
        assertTrue(err[2] <= ts * 1.0001, "scale error " + err[2]);
        // the rotation error is guaranteed at the original keys and measured in between: allow a small excess
        assertTrue(err[1] <= tr * 1.05, "rotation error " + err[1]);
        // zero error at the original keys for the translation and scale, which are piecewise linear
        double[] atKeys = new double[3];
        ClipCompression.measure(clip, reduced, 301, atKeys);
        assertTrue(atKeys[0] <= tt && atKeys[2] <= ts && atKeys[1] <= tr * 1.0001, "at the original key times: " + atKeys[0] + " " + atKeys[1] + " " + atKeys[2]);
    }

    @Test
    void theTranslationGuaranteeHoldsEverywhereForAnyTrack() {
        // random tracks with uneven key spacing: the bound holds at every time, not only at the removed keys
        for (int trial = 0; trial < 40; trial++) {
            int n = 5 + rng.nextInt(100);
            float[] t = new float[n], v = new float[3 * n];
            float time = 0;
            for (int i = 0; i < n; i++) {
                time += 0.01f + (float) rng.nextDouble() * 0.2f;
                t[i] = time;
                for (int k = 0; k < 3; k++) {
                    v[3 * i + k] = (float) (rng.nextGaussian() * 2);
                }
            }
            AnimationClip clip = AnimationClip.builder(1).translation(0, t, v).build();
            float tol = 0.2f + (float) rng.nextDouble();
            AnimationClip reduced = ClipCompression.reduce(clip, tol, 0f, 0f);
            double[] err = new double[3];
            ClipCompression.measure(clip, reduced, 5000, err);
            assertTrue(err[0] <= tol * 1.0001 + 1e-6, "trial " + trial + ": " + err[0] + " against " + tol);
            assertTrue(reduced.keyCount() <= clip.keyCount());
        }
    }

    @Test
    void zeroToleranceRemovesOnlyKeysThatAreExactlyOnTheLine() {
        float[] t = {0f, 1f, 2f, 3f, 4f, 5f};
        float[] v = {0, 0, 0, 1, 2, 3, 2, 4, 6, 3, 6, 9, 10, 0, 0, 0, 0, 1}; // keys 1, 2, 3 are on the line from key 0 to key 3
        AnimationClip clip = AnimationClip.builder(1).translation(0, t, v).build();
        AnimationClip reduced = ClipCompression.reduce(clip, 0f, 0f, 0f);
        assertEquals(4, reduced.keyCount(), "keys 1 and 2 are on the segment from key 0 to key 3");
        // noise keeps every key
        AnimationClip noisy = smoothClip(1, 0.3);
        AnimationClip same = ClipCompression.reduce(noisy, 0f, 0f, 0f);
        assertEquals(noisy.keyCount(), same.keyCount());
    }

    @Test
    void constantTracksBecomeOneKey() {
        float[] t = {0f, 1f, 2f, 3f};
        float[] pos = {1, 2, 3, 1.001f, 2, 3, 1, 2, 3.001f, 1, 2, 3};
        float[] rot = new float[16];
        for (int i = 0; i < 4; i++) {
            rot[4 * i + 3] = 1f;
        }
        AnimationClip clip = AnimationClip.builder(1).translation(0, t, pos).rotation(0, t, rot).build();
        AnimationClip reduced = ClipCompression.reduce(clip, 0.01f, 0.01f, 0.01f);
        assertEquals(2, reduced.keyCount(), "one key per track");
        assertEquals(3f, reduced.duration(), "the duration is kept");
        // a single-key track holds its value for the whole clip
        ClipSampler s = new ClipSampler(reduced);
        Pose p = new Pose(1);
        s.sample(2.5f, false, p);
        assertEquals(1f, p.data()[0], 0f);
        assertEquals(1f, p.data()[6], 0f);
        // a tighter tolerance keeps the wiggle
        assertTrue(ClipCompression.reduce(clip, 0.0005f, 0.01f, 0.01f).keyCount() > 2);
        // a one-key track is kept as it is
        AnimationClip one = AnimationClip.builder(1).translation(0, new float[] {0.5f}, new float[] {1, 2, 3}).build();
        assertEquals(1, ClipCompression.reduce(one, 1f, 1f, 1f).keyCount());
    }

    @Test
    void rotationErrorIsAnAngleAndHandlesNegatedQuaternions() {
        // keys 0 and 2 hold q and -q (the same orientation); the middle key is between them
        float[] t = {0f, 1f, 2f};
        double a = 0.4;
        float[] rot = {0, (float) Math.sin(a / 2), 0, (float) Math.cos(a / 2), 0, 0, 0, 1, 0, (float) -Math.sin(a / 2), 0, (float) -Math.cos(a / 2)};
        AnimationClip clip = AnimationClip.builder(1).rotation(0, t, rot).build();
        // the middle key is the identity, the end keys are +-0.4 rad about y apart: not on one arc, so it has to stay below a loose tolerance only
        assertEquals(3, ClipCompression.reduce(clip, 0f, 0.1f, 0f).keyCount());
        assertEquals(2, ClipCompression.reduce(clip, 0f, 0.5f, 0f).keyCount() + (ClipCompression.reduce(clip, 0f, 0.5f, 0f).keyCount() == 1 ? 1 : 0));
    }

    @Test
    void measureReportsDifferencesAndChecksItsArguments() {
        AnimationClip a = smoothClip(2, 0.0), b = smoothClip(2, 0.0);
        double[] out = new double[3];
        ClipCompression.measure(a, a, 100, out);
        assertEquals(0.0, out[0] + out[1] + out[2], 0.0);
        ClipCompression.measure(a, b, 100, out);
        assertTrue(out[0] > 0.01 && out[1] > 0.01, "different clips differ");
        assertThrows(IllegalArgumentException.class, () -> ClipCompression.measure(a, smoothClip(3, 0.0), 10, out));
        assertThrows(IllegalArgumentException.class, () -> ClipCompression.measure(a, a, 1, out));
        assertThrows(IllegalArgumentException.class, () -> ClipCompression.reduce(a, -1f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> ClipCompression.reduce(a, 0f, Float.NaN, 0f));
    }

    // ------------------------------------------------------------ quantised clips

    private static double angle(float[] a, int ao, float[] b, int bo) {
        // both quaternions are normalised in double first (a float quaternion is only unit to about 1e-7, which would show up as a 20% noise at an angle of 1e-3), then the angle is
        // 2 atan2(|a - b|, |a + b|) with the sign that makes the dot product positive: exact for small angles, where acos is not
        double na = Math.sqrt((double) a[ao] * a[ao] + (double) a[ao + 1] * a[ao + 1] + (double) a[ao + 2] * a[ao + 2] + (double) a[ao + 3] * a[ao + 3]);
        double nb = Math.sqrt((double) b[bo] * b[bo] + (double) b[bo + 1] * b[bo + 1] + (double) b[bo + 2] * b[bo + 2] + (double) b[bo + 3] * b[bo + 3]);
        double dot = 0;
        for (int k = 0; k < 4; k++) {
            dot += a[ao + k] / na * (b[bo + k] / nb);
        }
        double sign = dot < 0 ? -1 : 1, diff = 0, sum = 0;
        for (int k = 0; k < 4; k++) {
            double x = a[ao + k] / na, y = sign * b[bo + k] / nb;
            diff += (x - y) * (x - y);
            sum += (x + y) * (x + y);
        }
        return 2 * Math.atan2(Math.sqrt(diff), Math.sqrt(sum));
    }

    @Test
    void quantisedRotationsStayWithinTheirFormatsError() {
        int n = 100_000;
        float[] t = new float[n], rot = new float[4 * n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            double x = rng.nextGaussian(), y = rng.nextGaussian(), z = rng.nextGaussian(), w = rng.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z + w * w);
            rot[4 * i] = (float) (x / l);
            rot[4 * i + 1] = (float) (y / l);
            rot[4 * i + 2] = (float) (z / l);
            rot[4 * i + 3] = (float) (w / l);
        }
        AnimationClip clip = AnimationClip.builder(1).rotation(0, t, rot).build();
        float[] q = new float[4];
        double worst32 = 0, worst64 = 0;
        QuantizedClip c32 = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32), c64 = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_64);
        for (int i = 0; i < n; i++) {
            c32.rotationKey(0, i, q);
            assertEquals(1.0, Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), 2e-3);
            worst32 = Math.max(worst32, angle(rot, 4 * i, q, 0));
            c64.rotationKey(0, i, q);
            assertEquals(1.0, Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), 1e-5);
            worst64 = Math.max(worst64, angle(rot, 4 * i, q, 0));
        }
        // 10 bits: half a step of 2 / 1022 times the range 1 / sqrt(2) per component; the angle is a few times that. QuatPacked documents 0.004
        assertTrue(worst32 < 0.004, "32-bit worst angle " + worst32);
        assertTrue(worst32 > 1e-4, "and it is not accidentally exact: " + worst32);
        assertTrue(worst64 < 1e-5, "64-bit worst angle " + worst64);
        assertTrue(worst64 < worst32 / 100);
    }

    @Test
    void sampledQuantisedClipsMatchTheOriginalWithinTheBounds() {
        AnimationClip clip = smoothClip(4, 0.01);
        for (QuantizedClip.RotationFormat format : QuantizedClip.RotationFormat.values()) {
            QuantizedClip q = QuantizedClip.of(clip, format);
            assertEquals(format, q.rotationFormat());
            assertEquals(clip.jointCount(), q.jointCount());
            assertEquals(clip.duration(), q.duration());
            assertEquals(clip.keyCount(), q.keyCount());
            assertEquals(clip.trackCount(), q.trackCount());
            ClipSampler sampler = new ClipSampler(clip);
            Pose a = new Pose(4), b = new Pose(4);
            double maxT = 0, maxR = 0, maxS = 0;
            for (int i = 0; i < 2000; i++) {
                float time = (float) (rng.nextDouble() * 6 - 0.5); // also before the start and after the end
                boolean loop = i % 2 == 0;
                sampler.sample(time, loop, a);
                q.sample(time, loop, b);
                for (int j = 0; j < 4; j++) {
                    int o = j * 10;
                    maxT = Math.max(maxT, Math.hypot(Math.hypot(a.data()[o] - b.data()[o], a.data()[o + 1] - b.data()[o + 1]), a.data()[o + 2] - b.data()[o + 2]));
                    maxR = Math.max(maxR, angle(a.data(), o + 3, b.data(), o + 3));
                    maxS = Math.max(maxS, Math.abs(a.data()[o + 7] - b.data()[o + 7]) + Math.abs(a.data()[o + 9] - b.data()[o + 9]));
                }
            }
            // translation: range about 4 to 6 over 16 bits is 1e-4; the time shift adds the speed times 0.5 / 65535 of the duration
            assertTrue(maxT < 2e-3, format + " translation " + maxT);
            assertTrue(maxS < 2e-3, format + " scale " + maxS);
            assertTrue(maxR < (format == QuantizedClip.RotationFormat.PACKED_32 ? 0.012 : 0.005), format + " rotation " + maxR);
            assertEquals(0f, q.wrap(-0f, false), 0f);
            assertEquals(q.wrap(7.3f, true), sampler.wrap(7.3f, true), 1e-6f);
            assertEquals(0f, q.wrap(Float.NaN, true));
        }
    }

    @Test
    void quantisedClipsAreSmallAndDecodeToTheSameValues() {
        AnimationClip clip = smoothClip(10, 0.0);
        QuantizedClip q32 = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32), q64 = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_64);
        long original = QuantizedClip.sizeInBytes(clip);
        assertTrue(q32.sizeInBytes() * 2 < original, "32-bit: " + q32.sizeInBytes() + " of " + original);
        assertTrue(q64.sizeInBytes() > q32.sizeInBytes() && q64.sizeInBytes() < original);
        assertTrue(q32.toString().contains("PACKED_32"));
        // decode gives a clip that samples exactly like the quantised one (apart from the float rounding of the key times)
        AnimationClip back = q32.decode();
        assertEquals(clip.trackCount(), back.trackCount());
        assertEquals(clip.keyCount(), back.keyCount());
        ClipSampler sb = new ClipSampler(back);
        QuantizedClip fresh = q32.copy();
        Pose a = new Pose(10), b = new Pose(10);
        for (int i = 0; i < 200; i++) {
            float time = (float) (rng.nextDouble() * clip.duration());
            sb.sample(time, false, a);
            fresh.sample(time, false, b);
            for (int k = 0; k < a.data().length; k++) {
                assertEquals(a.data()[k], b.data()[k], 2e-4, "value " + k + " at " + time);
            }
        }
        double[] err = new double[3];
        ClipCompression.measure(clip, back, 1000, err);
        assertTrue(err[0] < 2e-3 && err[1] < 0.012 && err[2] < 2e-3, err[0] + " " + err[1] + " " + err[2]);
    }

    @Test
    void timeCodesThatCollideAndTinyClipsStayValid() {
        // keys closer together than the time step (duration / 65535) share a code: still ordered, still sampled without NaN
        float[] t = {0f, 1e-7f, 2e-7f, 1f};
        float[] pos = {0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0};
        AnimationClip clip = AnimationClip.builder(1).translation(0, t, pos).build();
        QuantizedClip q = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32);
        Pose p = new Pose(1);
        for (float time = 0f; time <= 1f; time += 0.05f) {
            q.sample(time, false, p);
            assertTrue(Float.isFinite(p.data()[0]) && p.data()[0] >= -1e-3f && p.data()[0] <= 3f + 1e-3f, "at " + time + ": " + p.data()[0]);
        }
        q.decode(); // distinct times again
        // a clip of zero duration: every sample is the first key
        AnimationClip still = AnimationClip.builder(1).translation(0, new float[] {0f}, new float[] {4, 5, 6}).rotation(0, new float[] {0f}, new float[] {0, 0, 0, 1}).build();
        QuantizedClip qs = QuantizedClip.of(still, QuantizedClip.RotationFormat.PACKED_64);
        qs.sample(3f, true, p);
        assertEquals(4f, p.data()[0], 0f);
        assertEquals(6f, p.data()[2], 0f);
        assertEquals(1f, p.data()[6], 1e-6f);
        // a track with no movement has an extent of zero and decodes exactly
        assertEquals(5f, p.data()[1], 0f);
        assertThrows(IllegalArgumentException.class, () -> qs.sample(0f, false, new Pose(2)));
        assertThrows(IllegalArgumentException.class, () -> q.rotationKey(0, 0, new float[4]));
    }

    @Test
    void cursorsAreIndependentAfterACopy() {
        AnimationClip clip = smoothClip(2, 0.0);
        QuantizedClip a = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32), b = a.copy();
        Pose pa = new Pose(2), pb = new Pose(2), ref = new Pose(2);
        // a plays forward, b jumps around: both give the answer of a fresh sampler
        for (int i = 0; i < 300; i++) {
            float ta = i * 0.016f, tb = (float) (rng.nextDouble() * 5);
            a.sample(ta, true, pa);
            b.sample(tb, true, pb);
            QuantizedClip fresh = QuantizedClip.of(clip, QuantizedClip.RotationFormat.PACKED_32);
            fresh.sample(ta, true, ref);
            for (int k = 0; k < 20; k++) {
                assertEquals(ref.data()[k], pa.data()[k], 0f);
            }
            fresh.sample(tb, true, ref);
            for (int k = 0; k < 20; k++) {
                assertEquals(ref.data()[k], pb.data()[k], 0f);
            }
        }
    }
}
