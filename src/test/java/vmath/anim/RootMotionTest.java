package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class RootMotionTest {

    private static final float DURATION = 4f, OMEGA = 0.5f;
    private static final float[] VELOCITY = {2f, 0.5f, 3f};

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    /** A two-joint clip: joint 0 walks with a constant velocity and turns about the y axis at a constant rate; joint 1 is a bystander. */
    private AnimationClip walk() {
        float[] t = {0f, 1f, 2f, 3f, 4f};
        float[] pos = new float[15], rot = new float[20];
        for (int i = 0; i < 5; i++) {
            for (int k = 0; k < 3; k++) {
                pos[3 * i + k] = VELOCITY[k] * t[i];
            }
            float a = OMEGA * t[i] / 2f;
            rot[4 * i + 1] = (float) Math.sin(a);
            rot[4 * i + 3] = (float) Math.cos(a);
        }
        return AnimationClip.builder(2).translation(0, t, pos).rotation(0, t, rot).build();
    }

    private static double[] yawRotate(double angle, double x, double y, double z) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[] {c * x + s * z, y, -s * x + c * z};
    }

    @Test
    void theMovementBetweenTwoTimesIsInTheFrameOfTheFirst() {
        RootMotion m = new RootMotion(walk(), 0);
        float[] out = new float[7];
        for (int i = 0; i < 200; i++) {
            float t0 = (float) (rng.nextDouble() * DURATION), t1 = (float) (rng.nextDouble() * DURATION);
            m.delta(t0, t1, false, out);
            double dt = t1 - t0;
            // the world movement is v dt; in the frame of the root at t0 (a yaw of omega t0) it is rotated back by that yaw
            double[] expected = yawRotate(-OMEGA * t0, VELOCITY[0] * dt, VELOCITY[1] * dt, VELOCITY[2] * dt);
            assertEquals(expected[0], out[0], 2e-4, "x for " + t0 + " to " + t1);
            assertEquals(expected[1], out[1], 2e-4);
            assertEquals(expected[2], out[2], 2e-4);
            // the rotation is the yaw omega dt
            assertEquals(Math.sin(OMEGA * dt / 2), out[4], 2e-4);
            assertEquals(Math.cos(OMEGA * dt / 2), out[6], 2e-4);
            assertEquals(0.0, out[3], 2e-4);
            assertEquals(0.0, out[5], 2e-4);
        }
        m.delta(1.5f, 1.5f, false, out);
        assertEquals(0f, Math.abs(out[0]) + Math.abs(out[1]) + Math.abs(out[2]), 1e-6f);
        assertEquals(1f, Math.abs(out[6]), 1e-6f);
        assertEquals(walk().duration(), m.clip().duration());
    }

    @Test
    void totalAndClampingWithoutALoop() {
        RootMotion m = new RootMotion(walk(), 0);
        float[] out = new float[7];
        m.total(out);
        assertEquals(VELOCITY[0] * DURATION, out[0], 1e-4);
        assertEquals(VELOCITY[1] * DURATION, out[1], 1e-4);
        assertEquals(Math.sin(OMEGA * DURATION / 2), out[4], 1e-4);
        // times outside the clip are clamped without a loop
        float[] clamped = new float[7];
        m.delta(-5f, 100f, false, clamped);
        for (int i = 0; i < 7; i++) {
            assertEquals(out[i], clamped[i], 1e-6f);
        }
        m.delta(100f, 120f, false, clamped);
        assertEquals(0f, Math.abs(clamped[0]) + Math.abs(clamped[1]) + Math.abs(clamped[2]), 1e-6f);
    }

    @Test
    void aLoopCarriesTheMovementAcrossTheWrap() {
        RootMotion m = new RootMotion(walk(), 0);
        float[] out = new float[7];
        float t0 = 3.5f, t1 = 0.5f;
        m.delta(t0, t1, true, out);
        // from 3.5 to the end (0.5 s) in the frame at 3.5, then from the start to 0.5 (0.5 s) in the frame of the start, carried by the turn of the first part
        double[] first = yawRotate(-OMEGA * t0, VELOCITY[0] * 0.5, VELOCITY[1] * 0.5, VELOCITY[2] * 0.5);
        double turn = OMEGA * (DURATION - t0);
        double[] secondInStart = {VELOCITY[0] * 0.5, VELOCITY[1] * 0.5, VELOCITY[2] * 0.5};
        // frame at the start has no yaw: its coordinates are the world ones; the first part ends turned by `turn` relative to t0, so the second is rotated by that turn into the t0 frame
        double c = Math.cos(turn), s = Math.sin(turn);
        double[] carried = {c * secondInStart[0] + s * secondInStart[2], secondInStart[1], -s * secondInStart[0] + c * secondInStart[2]};
        assertEquals(first[0] + carried[0], out[0], 3e-4);
        assertEquals(first[1] + carried[1], out[1], 3e-4);
        assertEquals(first[2] + carried[2], out[2], 3e-4);
        // the turn is that of the two parts together: 0.5 s and 0.5 s
        assertEquals(Math.sin(OMEGA * 1.0 / 2), out[4], 3e-4);
        // a loop that does not wrap is an ordinary segment
        float[] plain = new float[7], looped = new float[7];
        m.delta(0.5f, 3.0f, false, plain);
        m.delta(0.5f, 3.0f, true, looped);
        for (int i = 0; i < 7; i++) {
            assertEquals(plain[i], looped[i], 0f);
        }
        // times past the end wrap into the clip
        m.delta(4.5f, 4.75f, true, plain);
        m.delta(0.5f, 0.75f, true, looped);
        for (int i = 0; i < 7; i++) {
            assertEquals(plain[i], looped[i], 2e-5f);
        }
    }

    @Test
    void aSeamlessWalkCycleMovesTheSameAcrossTheLoopPoint() {
        // a clip that returns to its start orientation: the movement over the loop point equals the movement in the middle
        float[] t = {0f, 1f, 2f};
        float[] pos = {0, 0, 0, 0, 0, 1, 0, 0, 2};
        float[] rot = {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1};
        AnimationClip c = AnimationClip.builder(1).translation(0, t, pos).rotation(0, t, rot).build();
        RootMotion m = new RootMotion(c, 0);
        float[] across = new float[7], inside = new float[7];
        m.delta(1.75f, 0.25f, true, across);
        m.delta(0.25f, 0.75f, false, inside);
        assertEquals(inside[2], across[2], 1e-5f);
        assertEquals(0.5f, across[2], 1e-5f);
    }

    @Test
    void stripRemovesTheHorizontalMotionAndTheYaw() {
        AnimationClip clip = walk();
        RootMotion m = new RootMotion(clip, 0);
        Pose reference = new Pose(2);
        reference.setTranslation(0, 7f, 8f, 9f);
        ClipSampler sampler = new ClipSampler(clip);
        for (int i = 0; i < 50; i++) {
            float t = (float) (rng.nextDouble() * DURATION);
            // a pose with a lean about the local x axis on top of the animated yaw
            Pose p = new Pose(2);
            sampler.sample(t, false, p);
            float lean = (float) (rng.nextDouble() * 0.6 - 0.3);
            Quatf yawQ = new Quatf(p.data()[3], p.data()[4], p.data()[5], p.data()[6]);
            Quatf q = yawQ.mul(Quatf.fromAxisAngle(lean, new Vec3f(1, 0, 0)));
            p.setRotation(0, q.x(), q.y(), q.z(), q.w());
            float y = p.data()[1];
            Pose a = new Pose(2);
            a.copyFrom(p);
            m.strip(a, reference, RootMotion.Mode.TRANSLATION_XZ_AND_YAW);
            assertEquals(7f, a.data()[0], 0f);
            assertEquals(y, a.data()[1], 0f, "the height stays");
            assertEquals(9f, a.data()[2], 0f);
            // what remains is the lean: no yaw
            assertEquals(0f, RootMotion.yaw(a.data(), 3), 1e-4f);
            Quatf expected = Quatf.fromAxisAngle(lean, new Vec3f(1, 0, 0));
            float dot = Math.abs(a.data()[3] * expected.x() + a.data()[4] * expected.y() + a.data()[5] * expected.z() + a.data()[6] * expected.w());
            assertEquals(1f, dot, 1e-4f, "t = " + t);
            // the yaw alone
            Pose b = new Pose(2);
            b.copyFrom(p);
            m.strip(b, reference, RootMotion.Mode.YAW);
            assertEquals(p.data()[0], b.data()[0], 0f, "translation untouched");
            assertEquals(0f, RootMotion.yaw(b.data(), 3), 1e-4f);
            // the translation alone
            Pose c = new Pose(2);
            c.copyFrom(p);
            m.strip(c, reference, RootMotion.Mode.TRANSLATION_XYZ);
            assertEquals(7f, c.data()[0], 0f);
            assertEquals(8f, c.data()[1], 0f);
            assertEquals(9f, c.data()[2], 0f);
            assertEquals(p.data()[6], c.data()[6], 0f, "rotation untouched");
            Pose d = new Pose(2);
            d.copyFrom(p);
            m.strip(d, reference, RootMotion.Mode.TRANSLATION_XZ);
            assertEquals(7f, d.data()[0], 0f);
            assertEquals(y, d.data()[1], 0f);
            Pose e = new Pose(2);
            e.copyFrom(p);
            m.strip(e, reference, RootMotion.Mode.NONE);
            assertEquals(p.data()[0], e.data()[0], 0f);
            assertEquals(p.data()[3], e.data()[3], 0f);
        }
        // the bystander is never touched
        Pose p = new Pose(2);
        p.setTranslation(1, 5f, 5f, 5f);
        m.strip(p, reference, RootMotion.Mode.TRANSLATION_XZ_AND_YAW);
        assertEquals(5f, p.data()[10], 0f);
    }

    @Test
    void yawOfKnownRotations() {
        float[] q = new float[4];
        for (int i = 0; i < 100; i++) {
            float a = (float) (rng.nextDouble() * 2 * Math.PI - Math.PI);
            q[0] = 0f;
            q[1] = (float) Math.sin(a / 2);
            q[2] = 0f;
            q[3] = (float) Math.cos(a / 2);
            float y = RootMotion.yaw(q, 0);
            assertEquals(Math.sin(a / 2), Math.sin(y / 2), 1e-5);
            assertEquals(Math.cos(a / 2), Math.cos(y / 2), 1e-5);
        }
        // a rotation about a horizontal axis has no yaw, and removing it changes nothing
        float[] lean = {(float) Math.sin(0.3), 0f, 0f, (float) Math.cos(0.3)};
        assertEquals(0f, RootMotion.yaw(lean, 0), 1e-6f);
        float[] copy = lean.clone();
        RootMotion.removeYaw(copy, 0);
        for (int i = 0; i < 4; i++) {
            assertEquals(lean[i], copy[i], 1e-6f);
        }
        // a half turn about a horizontal axis: the twist is undefined and the rotation stays as it is
        float[] half = {1f, 0f, 0f, 0f};
        RootMotion.removeYaw(half, 0);
        assertEquals(1f, half[0], 0f);
    }

    @Test
    void argumentsAreChecked() {
        AnimationClip c = walk();
        assertThrows(IllegalArgumentException.class, () -> new RootMotion(c, 2));
        assertThrows(IllegalArgumentException.class, () -> new RootMotion(c, -1));
        assertTrue(new RootMotion(c, 1).clip() == c);
    }

    @Test
    void samplingOneJointMatchesSamplingAllAndLeavesTheOthers() {
        AnimationClip clip = walk();
        ClipSampler all = new ClipSampler(clip), one = new ClipSampler(clip);
        Pose full = new Pose(2), partial = new Pose(2);
        float[] marker = partial.data();
        for (int i = 0; i < marker.length; i++) {
            marker[i] = 42f + i;
        }
        float[] before = marker.clone();
        for (float t : new float[] {0f, 0.3f, 1.7f, 3.99f, 4f, 9f, -1f}) {
            all.sample(t, true, full);
            one.sampleJoint(t, true, 0, partial);
            for (int k = 0; k < 7; k++) { // translation and rotation are animated, the scale is not
                assertEquals(full.data()[k], partial.data()[k], 0f, "joint 0, t = " + t);
            }
            for (int k = 7; k < 20; k++) {
                assertEquals(before[k], partial.data()[k], 0f, "the scale of joint 0 and the whole of joint 1 are untouched");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> one.sampleJoint(0f, true, 0, new Pose(3)));
    }
}
