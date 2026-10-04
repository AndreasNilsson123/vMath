package vmath.samples.demos.skinning;

import vmath.anim.Pose;
import vmath.anim.Skeleton;
import vmath.anim.Skinning;
import vmath.bulk.Mat4fArray;

/**
 * The two-joint skeleton of the skinning demo and what the library makes of a twist of its second
 * joint: the joint matrices for linear blend skinning and the dual quaternions for dual quaternion
 * skinning, both from the same {@link Pose}.
 *
 * <p>Joint 0 is the root and stays at the bind pose. Joint 1 is its child at the same place and
 * turns about the x axis, which is the axis of the {@link Tube}. {@link #twist} runs the library's
 * {@code Skinning.jointMatrices} and {@code Skinning.jointDualQuaternions}; the CPU reference
 * methods of {@code Skinning} then skin with the results ({@link #skin}).
 *
 * <p>The class does not use OpenGL and allocates nothing after construction.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it owns scratch arrays that {@link #twist} and
 * {@link #skin} overwrite.
 */
final class TwistRig {

    private static final float[] BIND = {0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f};

    private final Skeleton skeleton = new Skeleton(new int[] {-1, 0}, BIND);
    private final Pose pose = new Pose(skeleton);
    private final float[] world = new float[32];
    private final Mat4fArray joints = new Mat4fArray(2);
    private final float[] dual = new float[16];

    /**
     * Poses the skeleton with the second joint turned about the x axis and computes the joint
     * matrices and the dual quaternions.
     *
     * @param radians the turn of joint 1 in radians; any value, a full turn is the bind pose again
     */
    void twist(float radians) {
        pose.setToBind(skeleton);
        pose.setRotation(1, (float) Math.sin(radians * 0.5f), 0f, 0f, (float) Math.cos(radians * 0.5f));
        Skinning.jointMatrices(skeleton, pose, world, joints);
        Skinning.jointDualQuaternions(joints.data(), 2, dual);
    }

    /**
     * Reads the joint matrices of the last {@link #twist}.
     *
     * @return the matrices, 16 floats per joint in column-major order; the array may be longer than
     *     32 floats and is live
     */
    float[] matrices() {
        return joints.data();
    }

    /**
     * Reads the dual quaternions of the last {@link #twist}.
     *
     * @return eight floats per joint: the real part {@code x, y, z, w}, then the dual part; live
     */
    float[] dualQuaternions() {
        return dual;
    }

    /**
     * Skins vertices on the CPU with the library's reference code, with linear blend skinning or
     * with dual quaternion skinning, using the last {@link #twist}.
     *
     * @param dualQuaternion {@code true} for dual quaternion skinning, {@code false} for linear
     *     blend skinning
     * @param positions the bind-pose positions, three floats per vertex; must not be {@code null}
     * @param jointIndices four joint indices per vertex; must not be {@code null}
     * @param weights four weights per vertex; must not be {@code null}
     * @param count the number of vertices
     * @param out receives the skinned positions, three floats per vertex; must not be {@code null}
     */
    void skin(boolean dualQuaternion, float[] positions, int[] jointIndices, float[] weights, int count, float[] out) {
        if (dualQuaternion) {
            Skinning.skinPositionsDualQuat(dual, positions, jointIndices, weights, count, out);
        } else {
            Skinning.skinPositions(joints.data(), positions, jointIndices, weights, count, out);
        }
    }

    /**
     * Skins normals on the CPU with the library's reference code, like {@link #skin}.
     *
     * @param dualQuaternion {@code true} for dual quaternion skinning
     * @param normals the bind-pose normals, three floats per vertex; must not be {@code null}
     * @param jointIndices four joint indices per vertex; must not be {@code null}
     * @param weights four weights per vertex; must not be {@code null}
     * @param count the number of vertices
     * @param out receives the skinned normals, three floats per vertex; must not be {@code null}
     */
    void skinNormals(boolean dualQuaternion, float[] normals, int[] jointIndices, float[] weights, int count, float[] out) {
        if (dualQuaternion) {
            Skinning.skinNormalsDualQuat(dual, normals, jointIndices, weights, count, out);
        } else {
            Skinning.skinNormals(joints.data(), normals, jointIndices, weights, count, out);
        }
    }

    /**
     * Measures how wide a ring of the tube is after skinning: the mean distance of its vertices
     * from the x axis.
     *
     * @param skinned the skinned positions of the ring, three floats per vertex; must not be
     *     {@code null}
     * @param count the number of vertices of the ring
     * @return the mean radius, 0 if the ring has collapsed onto the axis
     */
    static float radius(float[] skinned, int count) {
        double sum = 0.0;
        for (int v = 0; v < count; v++) {
            double y = skinned[v * 3 + 1], z = skinned[v * 3 + 2];
            sum += Math.sqrt(y * y + z * z);
        }
        return (float) (sum / count);
    }
}
