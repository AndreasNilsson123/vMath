package vmath.mesh;

import java.util.HashMap;
import java.util.Map;

/**
 * Procedural meshes: plane, box, UV sphere, icosphere, capsule, cylinder, cone and torus.
 *
 * <p>Every mesh has normals, a first UV set and tangents, is centred on the origin with +Y up, and
 * winds counter-clockwise seen from outside (closed shapes have positive
 * {@link Mesh#signedVolume()}).
 *
 * <p><b>Seams are duplicated vertices, never gaps.</b> Where UVs or normals must differ across an
 * edge (the seam of a sphere, the rim of a cylinder cap, the corners of a box) the vertices are
 * duplicated with <em>bit-identical positions</em>, so the surface is watertight by position (weld
 * them with {@code MeshOptimizer.weld} to merge them) while shading and texturing stay correct.
 * Triangles that would have no area (at the poles, between duplicated rows) are left out.
 *
 * <p>Segment counts are clamped to sensible minimums (3 around, 1 along) instead of throwing.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mesh floor = Primitives.plane(10f, 10f, 10, 10);
 * Mesh ball = Primitives.uvSphere(0.5f, 32, 16);
 * Mesh pill = Primitives.capsule(0.3f, 1f, 16, 8);
 * Mesh ring = Primitives.torus(1f, 0.25f, 48, 16);
 * }</pre>
 */
public final class Primitives {

    private Primitives() {
    }

    // ---------------------------------------------------------------- plane and box

    /**
     * Generates a flat grid in the XZ plane facing up, with normals and texture coordinates.
     *
     * @param width the width
     * @param depth the depth
     * @param cellsX the cells x
     * @param cellsZ the cells z
     * @return a flat rectangle in the XZ plane facing +Y, {@code width} along X and {@code depth}
     *     along Z, in a grid of cells
     */
    public static Mesh plane(float width, float depth, int cellsX, int cellsZ) {
        int nx = Math.max(1, cellsX), nz = Math.max(1, cellsZ);
        Mesh m = new Mesh((nx + 1) * (nz + 1), nx * nz * 2);
        for (int j = 0; j <= nz; j++) {
            for (int i = 0; i <= nx; i++) {
                float u = (float) i / nx, v = (float) j / nz;
                m.addVertex((u - 0.5f) * width, 0f, (0.5f - v) * depth, 0f, 1f, 0f, u, v);
            }
        }
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int a = j * (nx + 1) + i, b = a + 1, c = a + nx + 2, d = a + nx + 1;
                // v grows toward -Z, so a -> b -> c -> d runs counter-clockwise seen from +Y (normal +Y)
                m.addQuad(a, b, c, d);
            }
        }
        return finish(m);
    }

    /**
     * Generates a box with separate vertices per face, so that the faces shade flat and carry their
     * own texture coordinates.
     *
     * @param hx the half extent along x
     * @param hy the half extent along y
     * @param hz the half extent along z
     * @return a box with half-extents {@code hx, hy, hz}: 24 vertices (flat shading), each face
     *     with UVs from 0 to 1
     */
    public static Mesh box(float hx, float hy, float hz) {
        Mesh m = new Mesh(24, 12);
        // each face: normal axis, and the two in-plane axes u, v chosen so that u x v = normal (counter-clockwise from outside)
        float[][] faces = {
                {1, 0, 0, 0, 0, -1, 0, 1, 0},  // +X: u = -Z, v = +Y
                {-1, 0, 0, 0, 0, 1, 0, 1, 0},  // -X
                {0, 1, 0, 1, 0, 0, 0, 0, -1},  // +Y: u = +X, v = -Z
                {0, -1, 0, 1, 0, 0, 0, 0, 1},  // -Y
                {0, 0, 1, 1, 0, 0, 0, 1, 0},   // +Z
                {0, 0, -1, -1, 0, 0, 0, 1, 0}, // -Z
        };
        float[] h = {hx, hy, hz};
        for (float[] f : faces) {
            int base = m.vertexCount();
            for (int k = 0; k < 4; k++) {
                float su = (k == 1 || k == 2) ? 1f : -1f, sv = (k >= 2) ? 1f : -1f;
                float x = (f[0] + su * f[3] + sv * f[6]) * h[0];
                float y = (f[1] + su * f[4] + sv * f[7]) * h[1];
                float z = (f[2] + su * f[5] + sv * f[8]) * h[2];
                m.addVertex(x, y, z, f[0], f[1], f[2], (su + 1) * 0.5f, (sv + 1) * 0.5f);
            }
            m.addQuad(base, base + 1, base + 2, base + 3);
        }
        return finish(m);
    }

    // ---------------------------------------------------------------- spheres

    /**
     * Generates a latitude-longitude sphere with smooth normals and texture coordinates; the
     * triangles shrink towards the poles.
     *
     * @param radius the radius
     * @param segments the number of segments
     * @param rings the rings
     * @return a sphere as {@code rings} bands of {@code segments} quads: smooth UVs and normals,
     *     poles shared per column
     */
    public static Mesh uvSphere(float radius, int segments, int rings) {
        int s = Math.max(3, segments), r = Math.max(2, rings);
        float[] pr = new float[r + 1], py = new float[r + 1], nr = new float[r + 1], ny = new float[r + 1], pv = new float[r + 1];
        for (int j = 0; j <= r; j++) {
            double a = Math.PI * j / r; // 0 at the bottom pole, pi at the top
            boolean pole = j == 0 || j == r;
            pr[j] = pole ? 0f : (float) (radius * Math.sin(a));
            py[j] = j == 0 ? -radius : j == r ? radius : (float) (-radius * Math.cos(a));
            nr[j] = pole ? 0f : (float) Math.sin(a);
            ny[j] = j == 0 ? -1f : j == r ? 1f : (float) -Math.cos(a);
            pv[j] = (float) j / r;
        }
        return finish(revolve(s, pr, py, nr, ny, pv));
    }

    /**
     * Generates a sphere by subdividing an icosahedron, which gives nearly equal triangles and no
     * pole pinching; the triangle count grows by a factor of four per level.
     *
     * <p>UVs are spherical and stretch at the seam.
     *
     * @param radius the radius
     * @param subdivisions the subdivisions
     * @return a sphere made by subdividing an icosahedron: even triangles, no pole pinching
     */
    public static Mesh icoSphere(float radius, int subdivisions) {
        int n = Math.max(0, Math.min(subdivisions, 8));
        double t = (1.0 + Math.sqrt(5.0)) / 2.0;
        double[][] base = {
                {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        int[][] faces = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        Mesh m = new Mesh(12 * (1 << (2 * n)), 20 * (1 << (2 * n)));
        for (double[] p : base) {
            double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            addSpherePoint(m, p[0] / l, p[1] / l, p[2] / l, radius);
        }
        int[] tris = new int[faces.length * 3];
        for (int i = 0; i < faces.length; i++) {
            tris[i * 3] = faces[i][0];
            tris[i * 3 + 1] = faces[i][1];
            tris[i * 3 + 2] = faces[i][2];
        }
        for (int level = 0; level < n; level++) {
            Map<Long, Integer> mid = new HashMap<>();
            int[] next = new int[tris.length * 4];
            for (int i = 0; i < tris.length / 3; i++) {
                int a = tris[i * 3], b = tris[i * 3 + 1], c = tris[i * 3 + 2];
                int ab = midpoint(m, mid, a, b, radius), bc = midpoint(m, mid, b, c, radius), ca = midpoint(m, mid, c, a, radius);
                int o = i * 12;
                next[o] = a;
                next[o + 1] = ab;
                next[o + 2] = ca;
                next[o + 3] = b;
                next[o + 4] = bc;
                next[o + 5] = ab;
                next[o + 6] = c;
                next[o + 7] = ca;
                next[o + 8] = bc;
                next[o + 9] = ab;
                next[o + 10] = bc;
                next[o + 11] = ca;
            }
            tris = next;
        }
        for (int i = 0; i < tris.length; i += 3) {
            m.addTriangle(tris[i], tris[i + 1], tris[i + 2]);
        }
        return finish(m);
    }

    private static int midpoint(Mesh m, Map<Long, Integer> cache, int a, int b, float radius) {
        long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        Integer found = cache.get(key);
        if (found != null) {
            return found;
        }
        float[] p = m.positions();
        double x = (double) p[a * 3] + p[b * 3], y = (double) p[a * 3 + 1] + p[b * 3 + 1], z = (double) p[a * 3 + 2] + p[b * 3 + 2];
        double l = Math.sqrt(x * x + y * y + z * z);
        int id = addSpherePoint(m, x / l, y / l, z / l, radius);
        cache.put(key, id);
        return id;
    }

    private static int addSpherePoint(Mesh m, double x, double y, double z, float radius) {
        float u = (float) (Math.atan2(z, x) / (2 * Math.PI) + 0.5), v = (float) (Math.asin(Math.max(-1, Math.min(1, y))) / Math.PI + 0.5);
        return m.addVertex((float) (x * radius), (float) (y * radius), (float) (z * radius), (float) x, (float) y, (float) z, u, v);
    }

    // ---------------------------------------------------------------- surfaces of revolution

    /**
     * Generates a capsule with a cylindrical middle and hemispherical caps, with smooth normals.
     *
     * @param radius the radius
     * @param cylinderHeight the cylinder height
     * @param segments the number of segments
     * @param hemisphereRings the hemisphere rings
     * @return a capsule: a cylinder of {@code cylinderHeight} between two hemispheres of
     *     {@code radius}, standing on the Y axis
     */
    public static Mesh capsule(float radius, float cylinderHeight, int segments, int hemisphereRings) {
        int s = Math.max(3, segments), h = Math.max(1, hemisphereRings);
        int rows = 2 * (h + 1);
        float[] pr = new float[rows], py = new float[rows], nr = new float[rows], ny = new float[rows], pv = new float[rows];
        float half = cylinderHeight * 0.5f;
        for (int k = 0; k <= h; k++) {
            double a = Math.PI * 0.5 * k / h; // angle from the pole
            boolean pole = k == 0;
            // the equator is exact (cos(pi/2) is not quite 0), so that a capsule of height 0 has coinciding rows
            double sin = k == h ? 1.0 : Math.sin(a), cos = k == h ? 0.0 : Math.cos(a);
            int bottom = k, top = rows - 1 - k;
            pr[bottom] = pole ? 0f : (float) (radius * sin);
            py[bottom] = pole ? -half - radius : (float) (-half - radius * cos);
            nr[bottom] = pole ? 0f : (float) sin;
            ny[bottom] = pole ? -1f : (float) -cos;
            pr[top] = pr[bottom];
            py[top] = -py[bottom];
            nr[top] = nr[bottom];
            ny[top] = -ny[bottom];
        }
        float total = 0f;
        pv[0] = 0f;
        for (int j = 1; j < rows; j++) {
            total += (float) Math.hypot(pr[j] - pr[j - 1], py[j] - py[j - 1]);
            pv[j] = total;
        }
        for (int j = 0; j < rows; j++) {
            pv[j] = total > 0f ? pv[j] / total : (float) j / (rows - 1);
        }
        return finish(revolve(s, pr, py, nr, ny, pv));
    }

    /**
     * Generates a cylinder with separate vertices for the caps, so that they shade flat.
     *
     * @param radius the radius
     * @param height the height
     * @param segments the number of segments
     * @return a cylinder with flat caps (the caps have their own vertices and normals)
     */
    public static Mesh cylinder(float radius, float height, int segments) {
        int s = Math.max(3, segments);
        float half = height * 0.5f;
        float[] pr = {0f, radius, radius, radius, radius, 0f};
        float[] py = {-half, -half, -half, half, half, half};
        float[] nr = {0f, 0f, 1f, 1f, 0f, 0f};
        float[] ny = {-1f, -1f, 0f, 0f, 1f, 1f};
        float[] pv = {0f, 0.2f, 0.2f, 0.8f, 0.8f, 1f};
        return finish(revolve(s, pr, py, nr, ny, pv));
    }

    /**
     * Generates a cone with a flat base and the apex up.
     *
     * @param radius the radius
     * @param height the height
     * @param segments the number of segments
     * @return a cone with a flat base and its apex up, {@code height} tall
     */
    public static Mesh cone(float radius, float height, int segments) {
        int s = Math.max(3, segments);
        float half = height * 0.5f;
        double len = Math.hypot(height, radius);
        float sideR = (float) (height / len), sideY = (float) (radius / len);
        float[] pr = {0f, radius, radius, 0f};
        float[] py = {-half, -half, -half, half};
        float[] nr = {0f, 0f, sideR, sideR};
        float[] ny = {-1f, -1f, sideY, sideY};
        float[] pv = {0f, 0.25f, 0.25f, 1f};
        return finish(revolve(s, pr, py, nr, ny, pv));
    }

    /**
     * Generates a torus with smooth normals and texture coordinates.
     *
     * @param majorRadius the major radius
     * @param minorRadius the minor radius
     * @param majorSegments the major segments
     * @param minorSegments the minor segments
     * @return a torus around the Y axis: {@code majorRadius} from the axis to the tube's centre,
     *     {@code minorRadius} the tube's radius
     */
    public static Mesh torus(float majorRadius, float minorRadius, int majorSegments, int minorSegments) {
        int ms = Math.max(3, majorSegments), ns = Math.max(3, minorSegments);
        Mesh m = new Mesh((ms + 1) * (ns + 1), ms * ns * 2);
        for (int i = 0; i <= ms; i++) {
            double phi = 2 * Math.PI * (i % ms) / ms;
            float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
            for (int j = 0; j <= ns; j++) {
                double theta = 2 * Math.PI * (j % ns) / ns;
                float ct = (float) Math.cos(theta), st = (float) Math.sin(theta);
                m.addVertex((majorRadius + minorRadius * ct) * cp, minorRadius * st, (majorRadius + minorRadius * ct) * sp,
                        ct * cp, st, ct * sp, (float) i / ms, (float) j / ns);
            }
        }
        grid(m, ms, ns);
        return finish(m);
    }

    /**
     * Builds a surface of revolution around Y from a profile: row {@code j} has ring radius
     * {@code pr[j]} at height {@code py[j]}, a normal with radial part {@code nr[j]} and vertical
     * part {@code ny[j]}, and texture coordinate {@code v = pv[j]}; {@code u} runs once around.
     *
     * <p>The last column repeats the first with identical positions.
     */
    private static Mesh revolve(int segments, float[] pr, float[] py, float[] nr, float[] ny, float[] pv) {
        int rows = pr.length;
        Mesh m = new Mesh((segments + 1) * rows, segments * (rows - 1) * 2);
        for (int i = 0; i <= segments; i++) {
            double phi = 2 * Math.PI * (i % segments) / segments;
            float c = (float) Math.cos(phi), sn = (float) Math.sin(phi);
            for (int j = 0; j < rows; j++) {
                m.addVertex(pr[j] * c, py[j], pr[j] * sn, nr[j] * c, ny[j], nr[j] * sn, (float) i / segments, pv[j]);
            }
        }
        // vertex (i, j) is at i * rows + j
        for (int i = 0; i < segments; i++) {
            for (int j = 0; j < rows - 1; j++) {
                int a = i * rows + j, b = a + rows, c = b + 1, d = a + 1;
                quadSkippingEmpty(m, a, b, c, d);
            }
        }
        return m;
    }

    /**
     * Triangles for the {@code (us + 1) x (vs + 1)} vertices laid out u-major (index
     * {@code i * (vs + 1) + j}).
     */
    private static void grid(Mesh m, int us, int vs) {
        for (int i = 0; i < us; i++) {
            for (int j = 0; j < vs; j++) {
                int a = i * (vs + 1) + j, b = a + (vs + 1), c = b + 1, d = a + 1;
                quadSkippingEmpty(m, a, b, c, d);
            }
        }
    }

    private static void quadSkippingEmpty(Mesh m, int a, int b, int c, int d) {
        if (!samePosition(m, a, b) && !samePosition(m, b, c) && !samePosition(m, a, c)) {
            m.addTriangle(a, b, c);
        }
        if (!samePosition(m, a, c) && !samePosition(m, c, d) && !samePosition(m, a, d)) {
            m.addTriangle(a, c, d);
        }
    }

    private static boolean samePosition(Mesh m, int a, int b) {
        float[] p = m.positions();
        return p[a * 3] == p[b * 3] && p[a * 3 + 1] == p[b * 3 + 1] && p[a * 3 + 2] == p[b * 3 + 2];
    }

    // ---------------------------------------------------------------- finishing

    /**
     * Fixes the winding of a closed mesh that came out inside out, then adds tangents.
     */
    private static Mesh finish(Mesh m) {
        boolean closed = isClosedShape(m);
        if (closed && m.signedVolume() < 0) {
            flipWinding(m);
        }
        MeshTools.computeTangents(m, 0);
        return m;
    }

    /**
     * Planes are open; everything else built here is closed.
     *
     * <p>A flat mesh has (almost) zero extent along some axis.
     */
    private static boolean isClosedShape(Mesh m) {
        var b = m.bounds();
        float ex = b.maxX() - b.minX(), ey = b.maxY() - b.minY(), ez = b.maxZ() - b.minZ();
        return Math.min(ex, Math.min(ey, ez)) > 1e-6f;
    }

    private static void flipWinding(Mesh m) {
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            int tmp = idx[t + 1];
            idx[t + 1] = idx[t + 2];
            idx[t + 2] = tmp;
        }
    }
}
