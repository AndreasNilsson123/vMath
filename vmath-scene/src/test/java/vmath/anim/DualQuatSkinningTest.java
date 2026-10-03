package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.bulk.Mat4fArray;
import vmath.core.DualQuatf;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.RigidTransformf;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class DualQuatSkinningTest {

    final Rnd rnd = Rnd.create();

    private static final int JOINTS = 5;

    /** A chain of joints, each one unit above its parent in the bind pose. */
    private Skeleton chain() {
        int[] parents = new int[JOINTS];
        float[] bind = new float[JOINTS * 10];
        for (int j = 0; j < JOINTS; j++) {
            parents[j] = j - 1;
            bind[j * 10 + 1] = j == 0 ? 0f : 1f;
            bind[j * 10 + 6] = 1f; // identity rotation
            bind[j * 10 + 7] = bind[j * 10 + 8] = bind[j * 10 + 9] = 1f;
        }
        return new Skeleton(parents, bind);
    }

    private Pose randomPose(Skeleton skeleton) {
        Pose pose = new Pose(skeleton);
        float[] d = pose.data();
        for (int j = 0; j < JOINTS; j++) {
            Vec3f t = rnd.nextVec3f().mul(0.1f);
            Quatf q = rnd.nextUnitQuatf();
            d[j * 10] = t.x();
            d[j * 10 + 1] = t.y() + (j == 0 ? 0f : 1f);
            d[j * 10 + 2] = t.z();
            d[j * 10 + 3] = q.x();
            d[j * 10 + 4] = q.y();
            d[j * 10 + 5] = q.z();
            d[j * 10 + 6] = q.w();
            d[j * 10 + 7] = d[j * 10 + 8] = d[j * 10 + 9] = 1f;
        }
        return pose;
    }

    private static float[] jointMatrices(Skeleton skeleton, Pose pose) {
        Mat4fArray out = new Mat4fArray(JOINTS);
        Skinning.jointMatrices(skeleton, pose, new float[JOINTS * 16], out);
        return java.util.Arrays.copyOf(out.data(), JOINTS * 16);
    }

    private static DualQuatf dualQuat(float[] dq, int joint) {
        int o = joint * 8;
        return new DualQuatf(new Quatf(dq[o], dq[o + 1], dq[o + 2], dq[o + 3]), new Quatf(dq[o + 4], dq[o + 5], dq[o + 6], dq[o + 7]));
    }

    @Test
    void jointDualQuaternionsAreTheRigidTransformsOfTheJointMatrices() {
        Skeleton skeleton = chain();
        for (int trial = 0; trial < 50; trial++) {
            float[] matrices = jointMatrices(skeleton, randomPose(skeleton));
            float[] dq = new float[JOINTS * 8];
            Skinning.jointDualQuaternions(matrices, JOINTS, dq);
            for (int j = 0; j < JOINTS; j++) {
                float[] m = java.util.Arrays.copyOfRange(matrices, j * 16, j * 16 + 16);
                DualQuatf expected = RigidTransformf.fromMat4(Mat4f.fromArray(m, 0)).toDualQuat();
                DualQuatf got = dualQuat(dq, j);
                assertTrue(got.sameTransform(expected, 1e-4f), "joint " + j);
                assertEquals(1f, got.real().length(), 1e-5f, "unit real part");
                assertEquals(0f, got.real().dot(got.dual()), 1e-4f, "orthogonal parts");
            }
        }
    }

    @Test
    void aVertexWithOneJointIsSkinnedLikeLinearBlending() {
        Skeleton skeleton = chain();
        for (int trial = 0; trial < 50; trial++) {
            float[] matrices = jointMatrices(skeleton, randomPose(skeleton));
            float[] dq = new float[JOINTS * 8];
            Skinning.jointDualQuaternions(matrices, JOINTS, dq);
            int count = 8;
            float[] positions = new float[count * 3], normals = new float[count * 3];
            int[] joints = new int[count * 4];
            float[] weights = new float[count * 4];
            for (int v = 0; v < count; v++) {
                Vec3f p = rnd.nextVec3f(), n = rnd.nextVec3f().normalize();
                positions[v * 3] = p.x();
                positions[v * 3 + 1] = p.y();
                positions[v * 3 + 2] = p.z();
                normals[v * 3] = n.x();
                normals[v * 3 + 1] = n.y();
                normals[v * 3 + 2] = n.z();
                joints[v * 4] = v % JOINTS;
                weights[v * 4] = 1f;
            }
            float[] lbs = new float[count * 3], dual = new float[count * 3];
            Skinning.skinPositions(matrices, positions, joints, weights, count, lbs);
            Skinning.skinPositionsDualQuat(dq, positions, joints, weights, count, dual);
            assertArrayEquals(lbs, dual, 2e-4f);
            Skinning.skinNormals(matrices, normals, joints, weights, count, lbs);
            Skinning.skinNormalsDualQuat(dq, normals, joints, weights, count, dual);
            assertArrayEquals(lbs, dual, 2e-4f);
        }
    }

    /** Two joints at the same place, one twisted by half a turn, with weights one half each. */
    private static float[] twistedPair() {
        float[] matrices = new float[32];
        Mat4f.IDENTITY.writeTo(matrices, 0);
        Mat4f.rotationZ((float) Math.PI).writeTo(matrices, 16);
        return matrices;
    }

    @Test
    void aTwistKeepsItsVolumeWhereLinearBlendingCollapsesIt() {
        float[] matrices = twistedPair();
        float[] dq = new float[16];
        Skinning.jointDualQuaternions(matrices, 2, dq);
        float[] positions = {1f, 0f, 0f, 0f, 2f, 0f, 0.5f, 0.5f, 3f};
        int[] joints = {0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0};
        float[] weights = {0.5f, 0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0f};
        float[] lbs = new float[9], dual = new float[9];
        Skinning.skinPositions(matrices, positions, joints, weights, 3, lbs);
        Skinning.skinPositionsDualQuat(dq, positions, joints, weights, 3, dual);
        for (int v = 0; v < 3; v++) {
            float inPlane = (float) Math.hypot(positions[v * 3], positions[v * 3 + 1]);
            float lbsPlane = (float) Math.hypot(lbs[v * 3], lbs[v * 3 + 1]);
            float dualPlane = (float) Math.hypot(dual[v * 3], dual[v * 3 + 1]);
            assertTrue(lbsPlane < 1e-4f, "linear blending collapses the point onto the axis, vertex " + v);
            assertEquals(inPlane, dualPlane, 1e-4f, "dual quaternion blending keeps the distance from the axis, vertex " + v);
            assertEquals(positions[v * 3 + 2], dual[v * 3 + 2], 1e-4f, "and the height");
        }
    }

    @Test
    void theSignOfAJointDualQuaternionDoesNotChangeTheBlend() {
        float[] matrices = twistedPair();
        float[] dq = new float[16];
        Skinning.jointDualQuaternions(matrices, 2, dq);
        float[] positions = {1f, 0.3f, 0.2f, -0.7f, 0.4f, 0.1f};
        int[] joints = {0, 1, 0, 0, 1, 0, 0, 0};
        float[] weights = {0.7f, 0.3f, 0f, 0f, 0.2f, 0.8f, 0f, 0f};
        float[] before = new float[6], after = new float[6];
        Skinning.skinPositionsDualQuat(dq, positions, joints, weights, 2, before);
        for (int i = 8; i < 16; i++) {
            dq[i] = -dq[i]; // the same transform with the opposite sign
        }
        Skinning.skinPositionsDualQuat(dq, positions, joints, weights, 2, after);
        assertArrayEquals(before, after, 1e-5f);
    }

    @Test
    void weightsOfZeroAreSkippedAndAVertexWithoutWeightsIsCopied() {
        float[] matrices = twistedPair();
        float[] dq = new float[16];
        Skinning.jointDualQuaternions(matrices, 2, dq);
        float[] positions = {1f, 2f, 3f, 4f, 5f, 6f};
        int[] joints = {1, 0, 0, 0, 0, 0, 0, 0};
        float[] weights = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        float[] out = new float[6];
        Skinning.skinPositionsDualQuat(dq, positions, joints, weights, 2, out);
        assertArrayEquals(new float[] {-1f, -2f, 3f}, java.util.Arrays.copyOf(out, 3), 1e-5f, "a half turn about z");
        assertArrayEquals(new float[] {4f, 5f, 6f}, java.util.Arrays.copyOfRange(out, 3, 6), 0f, "no weights, no change");
        float[] normals = {0f, 1f, 0f, 0f, 0f, 1f};
        float[] normalsOut = new float[6];
        Skinning.skinNormalsDualQuat(dq, normals, joints, weights, 2, normalsOut);
        assertArrayEquals(new float[] {0f, -1f, 0f}, java.util.Arrays.copyOf(normalsOut, 3), 1e-5f);
        assertArrayEquals(new float[] {0f, 0f, 1f}, java.util.Arrays.copyOfRange(normalsOut, 3, 6), 0f);
    }

    @Test
    void theOutputMayBeTheInput() {
        Skeleton skeleton = chain();
        float[] matrices = jointMatrices(skeleton, randomPose(skeleton));
        float[] dq = new float[JOINTS * 8];
        Skinning.jointDualQuaternions(matrices, JOINTS, dq);
        float[] positions = {1f, 2f, 3f, -4f, 5f, 6f};
        int[] joints = {1, 2, 0, 0, 3, 4, 0, 0};
        float[] weights = {0.6f, 0.4f, 0f, 0f, 0.5f, 0.5f, 0f, 0f};
        float[] separate = new float[6], inPlace = positions.clone();
        Skinning.skinPositionsDualQuat(dq, positions, joints, weights, 2, separate);
        Skinning.skinPositionsDualQuat(dq, inPlace, joints, weights, 2, inPlace);
        assertArrayEquals(separate, inPlace, 0f);
    }

    @Test
    void everyBranchOfTheMatrixToQuaternionConversionIsUsed() {
        // rotations by half a turn about each axis have a trace of -1, and so use the three branches that pick the largest diagonal
        float[] m = new float[16 * 4];
        Mat4f.IDENTITY.writeTo(m, 0);
        Mat4f.rotationX((float) Math.PI).writeTo(m, 16);
        Mat4f.rotationY((float) Math.PI).writeTo(m, 32);
        Mat4f.rotationZ((float) Math.PI).writeTo(m, 48);
        float[] dq = new float[32];
        Skinning.jointDualQuaternions(m, 4, dq);
        for (int j = 0; j < 4; j++) {
            Quatf q = dualQuat(dq, j).real();
            assertEquals(1f, q.length(), 1e-5f);
            Vec3f p = new Vec3f(1f, 2f, 3f);
            Vec3f expected = Mat4f.fromArray(java.util.Arrays.copyOfRange(m, j * 16, j * 16 + 16), 0).transformDirection(p);
            assertTrue(q.transform(p).approxEquals(expected, 1e-4f), "joint " + j);
        }
    }
}
