package vmath.samples.demos.sculpt;

import vmath.geo.Sdf;
import vmath.geo.Sdfs;

/**
 * A signed distance field stored on a regular grid, which is what a sculpting tool edits: reading
 * it is a trilinear interpolation (so it is an {@link Sdf} that the library's meshing and ray
 * casting accept), and a brush stroke replaces the samples near the brush by the result of a CSG
 * operation from the library, {@code Sdfs.smoothUnion} to add material and
 * {@code Sdfs.smoothSubtract} to carve it.
 *
 * <p>The grid has {@code n} cells along each axis between {@code -half} and {@code +half}, so
 * {@code (n + 1)} samples; sample {@code (i, j, k)} sits at {@code -half + i h} with
 * {@code h = 2 half / n}. Meshing the field with a grid of the same size reads exactly the stored
 * samples. Outside the grid the field is the value at the nearest point of the grid plus the
 * distance to that point, which keeps it a 1-Lipschitz bound for sphere tracing.
 *
 * <p>The class does not use OpenGL.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: a stroke changes the samples while a read sees them.
 */
final class SculptField implements Sdf {

    private final int n;
    private final float half;
    private final float h;
    private final float[] samples;

    /**
     * Samples an initial shape onto a new grid.
     *
     * @param n the number of cells along each axis; at least 4
     * @param half half the edge length of the cube that the grid covers; positive
     * @param initial the shape to start from; must not be {@code null}
     */
    SculptField(int n, float half, Sdf initial) {
        if (n < 4 || !(half > 0f)) {
            throw new IllegalArgumentException("a sculpt field needs at least 4 cells and a positive size: " + n + ", " + half);
        }
        this.n = n;
        this.half = half;
        this.h = 2f * half / n;
        this.samples = new float[(n + 1) * (n + 1) * (n + 1)];
        for (int k = 0; k <= n; k++) {
            for (int j = 0; j <= n; j++) {
                for (int i = 0; i <= n; i++) {
                    samples[index(i, j, k)] = initial.distance(-half + i * h, -half + j * h, -half + k * h);
                }
            }
        }
    }

    private int index(int i, int j, int k) {
        return (k * (n + 1) + j) * (n + 1) + i;
    }

    int cells() {
        return n;
    }

    float half() {
        return half;
    }

    float cellSize() {
        return h;
    }

    /**
     * Reads one stored sample.
     *
     * @param i the x index, 0 to {@code n}
     * @param j the y index, 0 to {@code n}
     * @param k the z index, 0 to {@code n}
     * @return the stored distance
     */
    float sample(int i, int j, int k) {
        return samples[index(i, j, k)];
    }

    @Override
    public float distance(float x, float y, float z) {
        float cx = Math.max(-half, Math.min(half, x)), cy = Math.max(-half, Math.min(half, y)), cz = Math.max(-half, Math.min(half, z));
        float gx = (cx + half) / h, gy = (cy + half) / h, gz = (cz + half) / h;
        int i = Math.min(n - 1, (int) gx), j = Math.min(n - 1, (int) gy), k = Math.min(n - 1, (int) gz);
        float fx = gx - i, fy = gy - j, fz = gz - k;
        float c00 = samples[index(i, j, k)] * (1f - fx) + samples[index(i + 1, j, k)] * fx;
        float c10 = samples[index(i, j + 1, k)] * (1f - fx) + samples[index(i + 1, j + 1, k)] * fx;
        float c01 = samples[index(i, j, k + 1)] * (1f - fx) + samples[index(i + 1, j, k + 1)] * fx;
        float c11 = samples[index(i, j + 1, k + 1)] * (1f - fx) + samples[index(i + 1, j + 1, k + 1)] * fx;
        float inside = (c00 * (1f - fy) + c10 * fy) * (1f - fz) + (c01 * (1f - fy) + c11 * fy) * fz;
        float dx = x - cx, dy = y - cy, dz = z - cz;
        return inside + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Applies one brush stamp: a sphere that is merged into the field with a smooth union or cut
     * out of it with a smooth subtraction, on the samples within reach of the sphere.
     *
     * @param add {@code true} to add material, {@code false} to carve it away
     * @param cx the x coordinate of the brush centre
     * @param cy the y coordinate of the brush centre
     * @param cz the z coordinate of the brush centre
     * @param radius the radius of the brush sphere; positive
     * @param smooth the width of the smooth blend between the brush and the shape; positive
     * @return the number of samples that were rewritten
     */
    int stamp(boolean add, float cx, float cy, float cz, float radius, float smooth) {
        Sdf brush = Sdfs.sphere(cx, cy, cz, radius);
        Sdf op = add ? Sdfs.smoothUnion(this, brush, smooth) : Sdfs.smoothSubtract(this, brush, smooth);
        float reach = radius + smooth + h;
        int i0 = clamp((int) Math.floor((cx - reach + half) / h)), i1 = clamp((int) Math.ceil((cx + reach + half) / h));
        int j0 = clamp((int) Math.floor((cy - reach + half) / h)), j1 = clamp((int) Math.ceil((cy + reach + half) / h));
        int k0 = clamp((int) Math.floor((cz - reach + half) / h)), k1 = clamp((int) Math.ceil((cz + reach + half) / h));
        int written = 0;
        for (int k = k0; k <= k1; k++) {
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    samples[index(i, j, k)] = op.distance(-half + i * h, -half + j * h, -half + k * h);
                    written++;
                }
            }
        }
        return written;
    }

    private int clamp(int i) {
        return Math.max(0, Math.min(n, i));
    }
}
