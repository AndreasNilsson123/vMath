package vmath.anim;

import vmath.bulk.Mat4fArray;

/**
 * Skinning: turning a {@link Pose} into the joint matrices a skinned mesh needs, plus a CPU
 * reference of what the GPU does with them.
 *
 * <p><b>Joint matrices.</b> For each joint the skinning matrix is {@code world * inverseBind}: it
 * takes a vertex from bind space to its place in the posed skeleton. At the bind pose every one of
 * them is the identity. They are written to a {@link Mat4fArray} (16 floats per joint,
 * column-major) that uploads as a {@code mat4[]} storage or uniform buffer: in std430 and std140
 * alike a {@code mat4} array has a stride of {@link #JOINT_MATRIX_BYTES} bytes, so it needs no
 * padding.
 *
 * <p><b>Vertex data.</b> Each vertex has up to four joint indices and four weights.
 * {@link #packWeights} stores weights as four unsigned bytes that sum to exactly 255 (a
 * {@code unorm8x4} attribute), and the joint indices fit in one {@code uvec4} or in four bytes.
 *
 * <p><b>The reference.</b> {@link #skinPositions} and {@link #skinNormals} compute linear blend
 * skinning on the CPU, the definition the GPU shader has to match; use them as the oracle when
 * checking one. Normals use the joint matrices' rotation part, which is exact for rotation and
 * uniform scale and approximate for non-uniform scale (the exact method needs the inverse
 * transpose).
 *
 * <p><b>Dual quaternion skinning.</b> Linear blending of matrices shrinks and pinches a mesh where
 * a joint twists (the candy-wrapper artefact). {@link #jointDualQuaternions} turns the joint
 * matrices into dual quaternions (eight floats per joint) and {@link #skinPositionsDualQuat} and
 * {@link #skinNormalsDualQuat} blend those instead, which keeps the volume. The joints must be
 * rigid (a rotation and a translation, no scale); a scale or shear in a joint matrix is dropped
 * or distorted, so use linear blending for skeletons that scale.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Skeleton skeleton = new Skeleton(new int[] {-1}, new float[] {0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f});
 * Pose pose = new Pose(skeleton);
 * Mat4fArray joints = new Mat4fArray(1);
 * joints.add(Mat4f.IDENTITY);
 * Skinning.jointMatrices(skeleton, pose, new float[16], joints);          // world * inverse bind, one matrix per joint
 * float[] jointData = new float[16];
 * joints.get(0).writeTo(jointData, 0);                                     // 16 floats per joint
 * float[] positions = {1f, 2f, 3f};
 * int[] jointIndices = {0, 0, 0, 0};                                       // four joints per vertex
 * float[] weights = {1f, 0f, 0f, 0f};
 * float[] skinned = new float[3];
 * Skinning.skinPositions(jointData, positions, jointIndices, weights, 1, skinned);
 * }</pre>
 */
public final class Skinning {

    /**
     * Bytes of one joint matrix in a {@code mat4[]} buffer (std140 and std430 agree).
     */
    public static final int JOINT_MATRIX_BYTES = 64;

    private Skinning() {
    }

    /**
     * Computes the world matrix of every joint of {@code pose} into {@code worldOut} (16 floats per
     * joint, at least {@code 16 * jointCount}), in one forward pass.
     *
     * @param skeleton the skeleton; must not be {@code null}
     * @param pose the pose; must not be {@code null}
     * @param worldOut the world out
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
     * Computes {@code world * inverseBind} for every joint into {@code out} ({@code out} is resized
     * to the joint count).
     *
     * @param skeleton the skeleton; must not be {@code null}
     * @param pose the pose; must not be {@code null}
     * @param worldScratch scratch of at least {@code 16 * jointCount} floats, reused between calls
     *     so nothing is allocated
     * @param out receives the result; must not be {@code null}
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
     * Skins {@code vertexCount} positions ({@code x, y, z} each) with linear blend skinning: the
     * sum over the vertex's four joints of {@code weight * (jointMatrix * position)}.
     *
     * <p>Weights are used as given (they should sum to 1).
     *
     * @param jointMatrices the array of {@link #jointMatrices}, 16 floats per joint
     * @param positions the positions
     * @param joints        four joint indices per vertex
     * @param weights       four weights per vertex
     * @param vertexCount the number of vertices
     * @param out receives the result
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
     * Skins unit normals with the rotation part of the joint matrices and renormalises the result
     * (a zero result stays zero).
     *
     * <p>See the class comment for the non-uniform scale caveat.
     *
     * @param jointMatrices the joint matrices (at least 11 elements)
     * @param normals the normals
     * @param joints the joints
     * @param weights the weights
     * @param vertexCount the number of vertices
     * @param out receives the result
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

    // ---------------------------------------------------------------- dual quaternion skinning

    /**
     * Converts rigid joint matrices to dual quaternions: for each joint the rotation of the
     * upper-left 3x3 block as a unit quaternion {@code (x, y, z, w)} and then the dual part
     * {@code 0.5 * (t, 0) * rotation} for the translation {@code t}, eight floats in all.
     *
     * <p>The matrices must be rigid (a rotation and a translation): a scale or a shear changes the
     * rotation that is read from them. The sign of a quaternion is whatever the conversion gives;
     * {@link #skinPositionsDualQuat} aligns the signs of the joints it blends.
     *
     * @param jointMatrices the array of {@link #jointMatrices}, 16 floats per joint, column-major;
     *     must not be {@code null}
     * @param jointCount the number of joints to convert
     * @param out receives eight floats per joint; must not be {@code null}
     * @throws ArrayIndexOutOfBoundsException if {@code jointMatrices} holds fewer than
     *     {@code 16 * jointCount} floats or {@code out} fewer than {@code 8 * jointCount}
     */
    public static void jointDualQuaternions(float[] jointMatrices, int jointCount, float[] out) {
        for (int j = 0; j < jointCount; j++) {
            int m = j * 16, o = j * 8;
            float m00 = jointMatrices[m], m01 = jointMatrices[m + 1], m02 = jointMatrices[m + 2];
            float m10 = jointMatrices[m + 4], m11 = jointMatrices[m + 5], m12 = jointMatrices[m + 6];
            float m20 = jointMatrices[m + 8], m21 = jointMatrices[m + 9], m22 = jointMatrices[m + 10];
            float x, y, z, w;
            float trace = m00 + m11 + m22;
            if (trace > 0f) {
                float s = (float) Math.sqrt(trace + 1f) * 2f;
                w = 0.25f * s;
                x = (m12 - m21) / s;
                y = (m20 - m02) / s;
                z = (m01 - m10) / s;
            } else if (m00 > m11 && m00 > m22) {
                float s = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
                w = (m12 - m21) / s;
                x = 0.25f * s;
                y = (m10 + m01) / s;
                z = (m20 + m02) / s;
            } else if (m11 > m22) {
                float s = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
                w = (m20 - m02) / s;
                x = (m10 + m01) / s;
                y = 0.25f * s;
                z = (m21 + m12) / s;
            } else {
                float s = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
                w = (m01 - m10) / s;
                x = (m20 + m02) / s;
                y = (m21 + m12) / s;
                z = 0.25f * s;
            }
            float inv = 1f / (float) Math.sqrt(x * x + y * y + z * z + w * w);
            x *= inv;
            y *= inv;
            z *= inv;
            w *= inv;
            float tx = jointMatrices[m + 12], ty = jointMatrices[m + 13], tz = jointMatrices[m + 14];
            out[o] = x;
            out[o + 1] = y;
            out[o + 2] = z;
            out[o + 3] = w;
            // 0.5 * (t, 0) * q
            out[o + 4] = 0.5f * (tx * w + ty * z - tz * y);
            out[o + 5] = 0.5f * (-tx * z + ty * w + tz * x);
            out[o + 6] = 0.5f * (tx * y - ty * x + tz * w);
            out[o + 7] = 0.5f * (-tx * x - ty * y - tz * z);
        }
    }

    /**
     * Skins {@code vertexCount} positions with dual quaternion blending: the weighted sum of the
     * vertex's four joint dual quaternions (each taken with the sign that puts it in the same
     * hemisphere as the first joint with a non-zero weight), normalised, applied as a rotation
     * and a translation.
     *
     * <p>Where the weights mix joints that differ by a twist this keeps the volume that
     * {@link #skinPositions} loses. Weights are used as given (they should sum to 1); a vertex
     * whose weights are all zero is moved to the origin of nothing, that is, copied unchanged.
     *
     * @param dualQuaternions the array of {@link #jointDualQuaternions}, eight floats per joint;
     *     must not be {@code null}
     * @param positions the positions, {@code x, y, z} each; must not be {@code null}
     * @param joints four joint indices per vertex; must not be {@code null}
     * @param weights four weights per vertex; must not be {@code null}
     * @param vertexCount the number of vertices
     * @param out receives the skinned positions; may be {@code positions}; must not be
     *     {@code null}
     * @throws ArrayIndexOutOfBoundsException if an array is too short for the vertices or a joint
     *     index is out of range
     */
    public static void skinPositionsDualQuat(float[] dualQuaternions, float[] positions, int[] joints, float[] weights, int vertexCount, float[] out) {
        for (int v = 0; v < vertexCount; v++) {
            float x = positions[v * 3], y = positions[v * 3 + 1], z = positions[v * 3 + 2];
            float rx = 0f, ry = 0f, rz = 0f, rw = 0f, dx = 0f, dy = 0f, dz = 0f, dw = 0f;
            float refX = 0f, refY = 0f, refZ = 0f, refW = 0f;
            boolean haveReference = false;
            for (int k = 0; k < 4; k++) {
                float w = weights[v * 4 + k];
                if (w == 0f) {
                    continue;
                }
                int q = joints[v * 4 + k] * 8;
                float qx = dualQuaternions[q], qy = dualQuaternions[q + 1], qz = dualQuaternions[q + 2], qw = dualQuaternions[q + 3];
                if (!haveReference) {
                    refX = qx;
                    refY = qy;
                    refZ = qz;
                    refW = qw;
                    haveReference = true;
                }
                float s = qx * refX + qy * refY + qz * refZ + qw * refW < 0f ? -w : w;
                rx += s * qx;
                ry += s * qy;
                rz += s * qz;
                rw += s * qw;
                dx += s * dualQuaternions[q + 4];
                dy += s * dualQuaternions[q + 5];
                dz += s * dualQuaternions[q + 6];
                dw += s * dualQuaternions[q + 7];
            }
            if (!haveReference) {
                out[v * 3] = x;
                out[v * 3 + 1] = y;
                out[v * 3 + 2] = z;
                continue;
            }
            float inv = 1f / (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
            rx *= inv;
            ry *= inv;
            rz *= inv;
            rw *= inv;
            dx *= inv;
            dy *= inv;
            dz *= inv;
            dw *= inv;
            // rotate: p + 2 r.xyz x (r.xyz x p + r.w p)
            float cx = ry * z - rz * y + rw * x, cy = rz * x - rx * z + rw * y, cz = rx * y - ry * x + rw * z;
            float ox = x + 2f * (ry * cz - rz * cy);
            float oy = y + 2f * (rz * cx - rx * cz);
            float oz = z + 2f * (rx * cy - ry * cx);
            // translate: 2 * (r.w d.xyz - d.w r.xyz + r.xyz x d.xyz)
            ox += 2f * (rw * dx - dw * rx + ry * dz - rz * dy);
            oy += 2f * (rw * dy - dw * ry + rz * dx - rx * dz);
            oz += 2f * (rw * dz - dw * rz + rx * dy - ry * dx);
            out[v * 3] = ox;
            out[v * 3 + 1] = oy;
            out[v * 3 + 2] = oz;
        }
    }

    /**
     * Skins {@code vertexCount} unit normals with the rotation of the blended dual quaternion (the
     * same blend as {@link #skinPositionsDualQuat}) and renormalises the result.
     *
     * @param dualQuaternions the array of {@link #jointDualQuaternions}, eight floats per joint;
     *     must not be {@code null}
     * @param normals the normals, {@code x, y, z} each; must not be {@code null}
     * @param joints four joint indices per vertex; must not be {@code null}
     * @param weights four weights per vertex; must not be {@code null}
     * @param vertexCount the number of vertices
     * @param out receives the skinned normals; may be {@code normals}; must not be {@code null}
     * @throws ArrayIndexOutOfBoundsException if an array is too short for the vertices or a joint
     *     index is out of range
     */
    public static void skinNormalsDualQuat(float[] dualQuaternions, float[] normals, int[] joints, float[] weights, int vertexCount, float[] out) {
        for (int v = 0; v < vertexCount; v++) {
            float x = normals[v * 3], y = normals[v * 3 + 1], z = normals[v * 3 + 2];
            float rx = 0f, ry = 0f, rz = 0f, rw = 0f;
            float refX = 0f, refY = 0f, refZ = 0f, refW = 0f;
            boolean haveReference = false;
            for (int k = 0; k < 4; k++) {
                float w = weights[v * 4 + k];
                if (w == 0f) {
                    continue;
                }
                int q = joints[v * 4 + k] * 8;
                float qx = dualQuaternions[q], qy = dualQuaternions[q + 1], qz = dualQuaternions[q + 2], qw = dualQuaternions[q + 3];
                if (!haveReference) {
                    refX = qx;
                    refY = qy;
                    refZ = qz;
                    refW = qw;
                    haveReference = true;
                }
                float s = qx * refX + qy * refY + qz * refZ + qw * refW < 0f ? -w : w;
                rx += s * qx;
                ry += s * qy;
                rz += s * qz;
                rw += s * qw;
            }
            if (!haveReference) {
                out[v * 3] = x;
                out[v * 3 + 1] = y;
                out[v * 3 + 2] = z;
                continue;
            }
            float inv = 1f / (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
            rx *= inv;
            ry *= inv;
            rz *= inv;
            rw *= inv;
            float cx = ry * z - rz * y + rw * x, cy = rz * x - rx * z + rw * y, cz = rx * y - ry * x + rw * z;
            float ox = x + 2f * (ry * cz - rz * cy);
            float oy = y + 2f * (rz * cx - rx * cz);
            float oz = z + 2f * (rx * cy - ry * cx);
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
     * Packs four weights (renormalised to sum to 1; negative or NaN weights count as 0) into one
     * int of four unsigned bytes, weight 0 in the lowest byte.
     *
     * <p>The four bytes sum to exactly 255, so the unpacked weights sum to 1 again. All-zero
     * weights pack as full weight on the first joint.
     *
     * @param weights the weights
     * @param offset the index of the first element to read or write
     * @return the four weights packed into one int
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

    /**
     * Unpacks four weights packed by {@link #packWeights} into {@code out[offset..offset+3]}.
     *
     * @param packed the packed value
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public static void unpackWeights(int packed, float[] out, int offset) {
        for (int k = 0; k < 4; k++) {
            out[offset + k] = ((packed >>> (8 * k)) & 0xFF) / 255f;
        }
    }
}
