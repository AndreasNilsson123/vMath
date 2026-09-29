package vmath.anim;

import vmath.bulk.Mat4fArray;

/**
 * Skinning: turning a {@link Pose} into the joint matrices a skinned mesh needs, plus a CPU reference of what the GPU does with them.
 *
 * <p><b>Joint matrices.</b> For each joint the skinning matrix is {@code world * inverseBind}: it takes a vertex from bind space to its
 * place in the posed skeleton. At the bind pose every one of them is the identity. They are written to a {@link Mat4fArray} (16 floats
 * per joint, column-major) that uploads as a {@code mat4[]} storage or uniform buffer: in std430 and std140 alike a {@code mat4} array
 * has a stride of {@link #JOINT_MATRIX_BYTES} bytes, so it needs no padding.
 *
 * <p><b>Vertex data.</b> Each vertex has up to four joint indices and four weights. {@link #packWeights} stores weights as four unsigned
 * bytes that sum to exactly 255 (a {@code unorm8x4} attribute), and the joint indices fit in one {@code uvec4} or in four bytes.
 *
 * <p><b>The reference.</b> {@link #skinPositions} and {@link #skinNormals} compute linear blend skinning on the CPU, the definition the
 * GPU shader has to match; use them as the oracle when checking one. Normals use the joint matrices' rotation part, which is exact for
 * rotation and uniform scale and approximate for non-uniform scale (the exact method needs the inverse transpose).
 */
public final class Skinning {

    /** Bytes of one joint matrix in a {@code mat4[]} buffer (std140 and std430 agree). */
    public static final int JOINT_MATRIX_BYTES = 64;

    private Skinning() {
    }

    /**
     * Computes the world matrix of every joint of {@code pose} into {@code worldOut} (16 floats per joint, at least
     * {@code 16 * jointCount}), in one forward pass.
     */
    public static void worldMatrices(Skeleton skeleton, Pose pose, float[] worldOut) {
        int n = skeleton.jointCount();
        check(skeleton, pose, worldOut);
        int[] parent = skeleton.parents();
        float[] trs = pose.data();
        for (int j = 0; j < n; j++) {
            TransformMath.compose(worldOut, j, parent[j], trs, j * TransformMath.TRS);
        }
    }

    /**
     * Computes {@code world * inverseBind} for every joint into {@code out} ({@code out} is resized to the joint count).
     *
     * @param worldScratch scratch of at least {@code 16 * jointCount} floats, reused between calls so nothing is allocated
     */
    public static void jointMatrices(Skeleton skeleton, Pose pose, float[] worldScratch, Mat4fArray out) {
        int n = skeleton.jointCount();
        worldMatrices(skeleton, pose, worldScratch);
        out.ensureCapacity(n);
        out.setSize(n);
        float[] ib = skeleton.inverseBindArray();
        float[] dst = out.data();
        for (int j = 0; j < n; j++) {
            TransformMath.multiplyAffine(worldScratch, j * 16, ib, j * 16, dst, j * 16);
        }
    }

    private static void check(Skeleton skeleton, Pose pose, float[] world) {
        if (pose.jointCount() != skeleton.jointCount()) {
            throw new IllegalArgumentException("pose has " + pose.jointCount() + " joints, the skeleton " + skeleton.jointCount());
        }
        if (world.length < skeleton.jointCount() * 16) {
            throw new IllegalArgumentException("scratch needs " + skeleton.jointCount() * 16 + " floats");
        }
    }

    // ---------------------------------------------------------------- CPU reference

    /**
     * Linear blend skinning of {@code vertexCount} positions ({@code x, y, z} each): the sum over the vertex's four joints of
     * {@code weight * (jointMatrix * position)}. Weights are used as given (they should sum to 1).
     *
     * @param jointMatrices the array of {@link #jointMatrices}, 16 floats per joint
     * @param joints        four joint indices per vertex
     * @param weights       four weights per vertex
     */
    public static void skinPositions(float[] jointMatrices, float[] positions, int[] joints, float[] weights, int vertexCount, float[] out) {
        for (int v = 0; v < vertexCount; v++) {
            float x = positions[v * 3], y = positions[v * 3 + 1], z = positions[v * 3 + 2];
            float ox = 0f, oy = 0f, oz = 0f;
            for (int k = 0; k < 4; k++) {
                float w = weights[v * 4 + k];
                if (w == 0f) {
                    continue;
                }
                int m = joints[v * 4 + k] * 16;
                ox += w * (jointMatrices[m] * x + jointMatrices[m + 4] * y + jointMatrices[m + 8] * z + jointMatrices[m + 12]);
                oy += w * (jointMatrices[m + 1] * x + jointMatrices[m + 5] * y + jointMatrices[m + 9] * z + jointMatrices[m + 13]);
                oz += w * (jointMatrices[m + 2] * x + jointMatrices[m + 6] * y + jointMatrices[m + 10] * z + jointMatrices[m + 14]);
            }
            out[v * 3] = ox;
            out[v * 3 + 1] = oy;
            out[v * 3 + 2] = oz;
        }
    }

    /**
     * Skins unit normals with the rotation part of the joint matrices and renormalises the result (a zero result stays zero). See the
     * class comment for the non-uniform scale caveat.
     */
    public static void skinNormals(float[] jointMatrices, float[] normals, int[] joints, float[] weights, int vertexCount, float[] out) {
        for (int v = 0; v < vertexCount; v++) {
            float x = normals[v * 3], y = normals[v * 3 + 1], z = normals[v * 3 + 2];
            float ox = 0f, oy = 0f, oz = 0f;
            for (int k = 0; k < 4; k++) {
                float w = weights[v * 4 + k];
                if (w == 0f) {
                    continue;
                }
                int m = joints[v * 4 + k] * 16;
                ox += w * (jointMatrices[m] * x + jointMatrices[m + 4] * y + jointMatrices[m + 8] * z);
                oy += w * (jointMatrices[m + 1] * x + jointMatrices[m + 5] * y + jointMatrices[m + 9] * z);
                oz += w * (jointMatrices[m + 2] * x + jointMatrices[m + 6] * y + jointMatrices[m + 10] * z);
            }
            float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (len > 1e-20f) {
                ox /= len;
                oy /= len;
                oz /= len;
            }
            out[v * 3] = ox;
            out[v * 3 + 1] = oy;
            out[v * 3 + 2] = oz;
        }
    }

    // ---------------------------------------------------------------- weight packing

    /**
     * Packs four weights (renormalised to sum to 1; negative or NaN weights count as 0) into one int of four unsigned bytes, weight 0 in the
     * lowest byte. The four bytes sum to exactly 255, so the unpacked weights sum to 1 again. All-zero weights pack as full weight on the
     * first joint.
     */
    public static int packWeights(float[] weights, int offset) {
        float sum = 0f;
        float[] w = new float[4];
        for (int k = 0; k < 4; k++) {
            float x = weights[offset + k];
            w[k] = x > 0f ? x : 0f; // also drops NaN
            sum += w[k];
        }
        int[] q = new int[4];
        if (!(sum > 0f)) {
            q[0] = 255;
        } else {
            int total = 0;
            int largest = 0;
            for (int k = 0; k < 4; k++) {
                q[k] = Math.round(w[k] / sum * 255f);
                total += q[k];
                if (w[k] > w[largest]) {
                    largest = k;
                }
            }
            q[largest] += 255 - total; // put the rounding error on the biggest weight
        }
        return q[0] | (q[1] << 8) | (q[2] << 16) | (q[3] << 24);
    }

    /** Unpacks four weights packed by {@link #packWeights} into {@code out[offset..offset+3]}. */
    public static void unpackWeights(int packed, float[] out, int offset) {
        for (int k = 0; k < 4; k++) {
            out[offset + k] = ((packed >>> (8 * k)) & 0xFF) / 255f;
        }
    }
}
