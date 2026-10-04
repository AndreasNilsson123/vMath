package vmath.samples.demos.lights;

import vmath.camera.ClusterGrid;
import vmath.camera.ClusterLights;
import vmath.core.Mat4f;
import vmath.util.Rng;

/**
 * A crowd of point lights that drift over a city, and their assignment to the clusters of a view
 * with the library's {@link ClusterLights}.
 *
 * <p>Every light has a fixed home, range and colour (from a seeded generator) and moves on a small
 * circle around its home, so a run depends on the time only. {@link #assign} moves the lights, puts
 * their positions into view space with a view matrix, hands them to {@code ClusterLights} and
 * assigns them to a {@link ClusterGrid}; afterwards the world-space records for the shader are in
 * {@link #lightData()}.
 *
 * <p>The class does not use OpenGL and allocates nothing in {@link #assign} once the lists have
 * grown.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: {@link #assign} overwrites the data that the getters
 * return.
 */
final class LightField {

    private final int count;
    private final float[] homeX;
    private final float[] homeY;
    private final float[] homeZ;
    private final float[] range;
    private final float[] phase;
    private final float[] radius;
    private final float[] lightData;
    private final ClusterLights lights = new ClusterLights();

    /**
     * Scatters lights over a square.
     *
     * @param count the number of lights; at least 1
     * @param extent half the side of the square in metres; positive
     * @param seed the seed of the generator
     */
    LightField(int count, float extent, long seed) {
        this.count = count;
        homeX = new float[count];
        homeY = new float[count];
        homeZ = new float[count];
        range = new float[count];
        phase = new float[count];
        radius = new float[count];
        lightData = new float[count * 8];
        Rng rng = new Rng(seed);
        for (int i = 0; i < count; i++) {
            homeX[i] = (float) rng.nextDouble(-extent, extent);
            homeZ[i] = (float) rng.nextDouble(-extent, extent);
            homeY[i] = (float) rng.nextDouble(1.0, 7.0);
            range[i] = (float) rng.nextDouble(5.0, 11.0);
            phase[i] = (float) rng.nextDouble(0.0, 2.0 * Math.PI);
            radius[i] = (float) rng.nextDouble(1.0, 5.0);
            float h = (float) rng.nextDouble();
            float r = Math.max(0f, Math.min(1f, Math.abs(h * 6f - 3f) - 1f)), g = Math.max(0f, Math.min(1f, 2f - Math.abs(h * 6f - 2f))), b = Math.max(0f, Math.min(1f, 2f - Math.abs(h * 6f - 4f)));
            float intensity = (float) rng.nextDouble(1.5, 4.0);
            lightData[i * 8 + 4] = (0.25f + 0.75f * r) * intensity;
            lightData[i * 8 + 5] = (0.25f + 0.75f * g) * intensity;
            lightData[i * 8 + 6] = (0.25f + 0.75f * b) * intensity;
        }
    }

    int count() {
        return count;
    }

    /**
     * Gives the assignment of the last {@link #assign}.
     *
     * @return the cluster lights; its counts and offsets are those of the last grid
     */
    ClusterLights assignment() {
        return lights;
    }

    /**
     * Reads the light records for the shader: per light {@code position.xyz, range} and then
     * {@code color.rgb, 0}, eight floats, positions in world space.
     *
     * @return the live array of {@code 8 * count} floats
     */
    float[] lightData() {
        return lightData;
    }

    /**
     * Moves the lights to their positions at a time and assigns them to the clusters of a grid.
     *
     * @param time the time in seconds
     * @param view the view matrix of the camera, which takes world space to view space; must not be
     *     {@code null}
     * @param grid the cluster grid of the view; must not be {@code null}
     */
    void assign(double time, Mat4f view, ClusterGrid grid) {
        lights.clearLights();
        float m00 = view.m00(), m10 = view.m10(), m20 = view.m20(), m30 = view.m30();
        float m01 = view.m01(), m11 = view.m11(), m21 = view.m21(), m31 = view.m31();
        float m02 = view.m02(), m12 = view.m12(), m22 = view.m22(), m32 = view.m32();
        for (int i = 0; i < count; i++) {
            float a = (float) (time * 0.4 + phase[i]);
            float x = homeX[i] + radius[i] * (float) Math.cos(a), y = homeY[i], z = homeZ[i] + radius[i] * (float) Math.sin(a);
            lightData[i * 8] = x;
            lightData[i * 8 + 1] = y;
            lightData[i * 8 + 2] = z;
            lightData[i * 8 + 3] = range[i];
            lights.addPoint(m00 * x + m10 * y + m20 * z + m30, m01 * x + m11 * y + m21 * z + m31, m02 * x + m12 * y + m22 * z + m32, range[i]);
        }
        lights.assign(grid);
    }
}
