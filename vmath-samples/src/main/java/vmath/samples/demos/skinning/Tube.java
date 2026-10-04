package vmath.samples.demos.skinning;

/**
 * An open tube along the x axis, skinned to two joints: the vertices at one end follow joint 0,
 * those at the other end follow joint 1, and a band around the middle blends the two. Twisting
 * joint 1 about the x axis is the classic test of a skinning method: linear blending pinches the
 * band to a point at half a turn, dual quaternion blending keeps its radius.
 *
 * <p>The vertex data is interleaved as {@code x, y, z, nx, ny, nz, w1} (seven floats), where
 * {@code w1} is the weight of joint 1 and the weight of joint 0 is {@code 1 - w1}; the CPU arrays
 * for the library's {@code Skinning} reference have the usual four joints and four weights per
 * vertex. The class does not use OpenGL.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable after construction: the arrays are not modified, so a tube
 * can be read by several threads.
 */
final class Tube {

    static final float LENGTH = 4f;
    static final float RADIUS = 0.5f;
    /**
     * The half width of the blend band around the middle.
     */
    static final float BAND = 0.8f;

    private final int rings;
    private final int around;
    private final float[] interleaved;
    private final float[] positions;
    private final float[] normals;
    private final int[] joints;
    private final float[] weights;
    private final int[] indices;

    /**
     * Builds a tube.
     *
     * @param rings the number of rings along the tube, odd so that one ring is exactly in the
     *     middle; at least 3
     * @param around the number of vertices in each ring; at least 3
     */
    Tube(int rings, int around) {
        if (rings < 3 || rings % 2 == 0 || around < 3) {
            throw new IllegalArgumentException("a tube needs an odd number of rings (at least 3) and at least 3 vertices around: " + rings + " x " + around);
        }
        this.rings = rings;
        this.around = around;
        int n = rings * around;
        interleaved = new float[n * 7];
        positions = new float[n * 3];
        normals = new float[n * 3];
        joints = new int[n * 4];
        weights = new float[n * 4];
        for (int r = 0; r < rings; r++) {
            float x = -LENGTH * 0.5f + LENGTH * r / (rings - 1);
            float t = Math.max(0f, Math.min(1f, (x + BAND) / (2f * BAND)));
            float w1 = t * t * (3f - 2f * t);
            for (int a = 0; a < around; a++) {
                double phi = 2.0 * Math.PI * a / around;
                float ny = (float) Math.cos(phi), nz = (float) Math.sin(phi);
                int v = r * around + a;
                positions[v * 3] = x;
                positions[v * 3 + 1] = RADIUS * ny;
                positions[v * 3 + 2] = RADIUS * nz;
                normals[v * 3 + 1] = ny;
                normals[v * 3 + 2] = nz;
                joints[v * 4] = 0;
                joints[v * 4 + 1] = 1;
                weights[v * 4] = 1f - w1;
                weights[v * 4 + 1] = w1;
                for (int k = 0; k < 3; k++) {
                    interleaved[v * 7 + k] = positions[v * 3 + k];
                    interleaved[v * 7 + 3 + k] = normals[v * 3 + k];
                }
                interleaved[v * 7 + 6] = w1;
            }
        }
        indices = new int[(rings - 1) * around * 6];
        int k = 0;
        for (int r = 0; r < rings - 1; r++) {
            for (int a = 0; a < around; a++) {
                int a1 = (a + 1) % around;
                int v00 = r * around + a, v01 = r * around + a1, v10 = (r + 1) * around + a, v11 = (r + 1) * around + a1;
                indices[k++] = v00;
                indices[k++] = v10;
                indices[k++] = v11;
                indices[k++] = v00;
                indices[k++] = v11;
                indices[k++] = v01;
            }
        }
    }

    int vertexCount() {
        return rings * around;
    }

    int ringCount() {
        return rings;
    }

    int aroundCount() {
        return around;
    }

    /**
     * Gives the index of the first vertex of the ring in the middle, where the two joints weigh
     * the same.
     *
     * @return the vertex index; the ring has {@link #aroundCount()} vertices
     */
    int middleRing() {
        return (rings / 2) * around;
    }

    float[] interleaved() {
        return interleaved;
    }

    float[] positions() {
        return positions;
    }

    float[] normals() {
        return normals;
    }

    int[] joints() {
        return joints;
    }

    float[] weights() {
        return weights;
    }

    int[] indices() {
        return indices;
    }
}
