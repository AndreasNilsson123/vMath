package vmath.samples.demos.skinning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of the tube and of what the library's skinning does to it: the candy-wrapper pinch of
 * linear blending at half a turn and the volume that dual quaternions keep, measured on the ring in
 * the middle of the tube.
 *
 * <p><b>Thread safety.</b> Each test builds its own rig; the tests may run in parallel.
 */
class TwistRigTest {

    private static float waist(TwistRig rig, Tube tube, boolean dual) {
        int around = tube.aroundCount();
        float[] pos = new float[around * 3], out = new float[around * 3], weights = new float[around * 4];
        int[] joints = new int[around * 4];
        System.arraycopy(tube.positions(), tube.middleRing() * 3, pos, 0, around * 3);
        System.arraycopy(tube.joints(), tube.middleRing() * 4, joints, 0, around * 4);
        System.arraycopy(tube.weights(), tube.middleRing() * 4, weights, 0, around * 4);
        rig.skin(dual, pos, joints, weights, around, out);
        return TwistRig.radius(out, around);
    }

    @Test
    void theTubeHasUnitWeightsAndHalfAndHalfInTheMiddle() {
        Tube tube = new Tube(21, 16);
        assertEquals(21 * 16, tube.vertexCount());
        assertEquals(20 * 16 * 6, tube.indices().length);
        for (int v = 0; v < tube.vertexCount(); v++) {
            float sum = tube.weights()[v * 4] + tube.weights()[v * 4 + 1] + tube.weights()[v * 4 + 2] + tube.weights()[v * 4 + 3];
            assertEquals(1f, sum, 1e-6f);
        }
        for (int a = 0; a < 16; a++) {
            assertEquals(0.5f, tube.weights()[(tube.middleRing() + a) * 4 + 1], 1e-6f);
        }
        assertEquals(1f, tube.weights()[0], 0f, "the end of the tube follows joint 0");
        assertEquals(1f, tube.weights()[(tube.vertexCount() - 1) * 4 + 1], 0f, "the other end follows joint 1");
    }

    @Test
    void aTubeNeedsAnOddNumberOfRings() {
        assertThrows(IllegalArgumentException.class, () -> new Tube(10, 8));
        assertThrows(IllegalArgumentException.class, () -> new Tube(5, 2));
    }

    @Test
    void atTheBindPoseBothMethodsLeaveTheTubeAlone() {
        Tube tube = new Tube(21, 16);
        TwistRig rig = new TwistRig();
        rig.twist(0f);
        float[] out = new float[tube.vertexCount() * 3];
        for (boolean dual : new boolean[] {false, true}) {
            rig.skin(dual, tube.positions(), tube.joints(), tube.weights(), tube.vertexCount(), out);
            for (int i = 0; i < out.length; i++) {
                assertEquals(tube.positions()[i], out[i], 1e-5f);
            }
        }
    }

    @Test
    void aFullTurnIsTheBindPoseAgainForBothMethods() {
        Tube tube = new Tube(21, 16);
        TwistRig rig = new TwistRig();
        rig.twist((float) (2 * Math.PI));
        float[] out = new float[tube.vertexCount() * 3];
        for (boolean dual : new boolean[] {false, true}) {
            rig.skin(dual, tube.positions(), tube.joints(), tube.weights(), tube.vertexCount(), out);
            for (int i = 0; i < out.length; i++) {
                assertEquals(tube.positions()[i], out[i], 1e-4f, (dual ? "dual quaternion" : "linear blend") + " " + i);
            }
        }
    }

    @Test
    void linearBlendingPinchesTheWaistAtHalfATurnAndDualQuaternionsKeepIt() {
        Tube tube = new Tube(41, 32);
        TwistRig rig = new TwistRig();
        rig.twist((float) Math.PI);
        assertTrue(waist(rig, tube, false) < 0.02f, "the linear blend collapses to the axis: " + waist(rig, tube, false));
        assertEquals(Tube.RADIUS, waist(rig, tube, true), 0.005f);
    }

    @Test
    void dualQuaternionsKeepTheWaistAtEveryAngleAndLinearBlendingOnlyAtTheEnds() {
        Tube tube = new Tube(41, 32);
        TwistRig rig = new TwistRig();
        float smallestLinear = Float.MAX_VALUE;
        for (int degrees = 0; degrees <= 360; degrees += 10) {
            rig.twist((float) Math.toRadians(degrees));
            assertEquals(Tube.RADIUS, waist(rig, tube, true), 0.005f, degrees + " degrees");
            smallestLinear = Math.min(smallestLinear, waist(rig, tube, false));
        }
        assertTrue(smallestLinear < 0.02f);
    }
}
