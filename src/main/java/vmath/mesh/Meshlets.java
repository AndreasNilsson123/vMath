package vmath.mesh;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.gl.GpuWriter;
import vmath.spatial.ConeCull;

/**
 * A mesh cut into <b>meshlets</b>: small clusters of triangles (by default up to 64 vertices and 124 triangles, the sizes that suit mesh shaders and
 * cluster culling) each with a bounding sphere and a normal cone, so a whole cluster can be frustum culled, occlusion culled or back-face culled with one test.
 *
 * <p><b>Layout</b> (the same shape as meshoptimizer's and what a mesh shader wants): {@link #vertices()} is the concatenation of the meshlets' vertex lists
 * (entries are vertex indices of the source mesh); {@link #triangles()} is the concatenation of their triangles as three 8-bit local indices each, into the
 * meshlet's own vertex list. Meshlet {@code m} owns {@code vertexCount(m)} vertex entries from {@code vertexOffset(m)} and {@code triangleCount(m)} triangles
 * from {@code triangleOffset(m)} (a triangle offset counts triangles, so the bytes start at {@code 3 * triangleOffset}).
 *
 * <p><b>Building.</b> A greedy grower: start at the first unused triangle, then repeatedly take the adjacent triangle that needs the fewest new vertices,
 * ties going to the one nearest the meshlet's centre, until a limit is hit. It is a good-locality heuristic, not an optimal partition; the tests report the
 * average fill. Triangle winding is preserved (a meshlet triangle is the source triangle with its corners renamed).
 *
 * <p><b>Bounds.</b> The sphere is the centre of the meshlet's vertex box with the radius reaching the farthest vertex (conservative, not minimal). The cone is
 * {@link ConeCull#computeCone}: axis and cutoff (the sine of the half-angle), with cutoff 1 (never culls) when the triangle normals spread too wide. The pair
 * plugs straight into {@link ConeCull#backfacing} and {@link ConeCull.Clusters}.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads freely. The arrays it hands out are its own storage: do
 * not modify them.
 */
@Experimental("the builder heuristic may change; the layout follows common mesh-shader conventions")
public final class Meshlets {

    /** Bytes of one meshlet descriptor written by {@link #writeDescriptors}: four 32-bit unsigned ints. */
    public static final int DESCRIPTOR_BYTES = 16;
    /** Bytes of one bounds record written by {@link #writeBounds}: two vec4s. */
    public static final int BOUNDS_BYTES = 32;

    /** How many unused triangles the seed choice looks at. */
    private static final int SEED_WINDOW = 32;

    private final int count;
    private final int[] vertexOffset, vertexCount, triangleOffset, triangleCount;
    private final int[] vertices;
    private final byte[] triangles;
    private final float[] sphere; // cx, cy, cz, radius per meshlet
    private final float[] cone;   // ax, ay, az, cutoff per meshlet

    private Meshlets(int count, int[] vo, int[] vc, int[] to, int[] tc, int[] vertices, byte[] triangles, float[] sphere, float[] cone) {
        this.count = count;
        this.vertexOffset = vo;
        this.vertexCount = vc;
        this.triangleOffset = to;
        this.triangleCount = tc;
        this.vertices = vertices;
        this.triangles = triangles;
        this.sphere = sphere;
        this.cone = cone;
    }

    /** Builds meshlets of at most 64 vertices and 124 triangles. */
    public static Meshlets build(Mesh mesh) {
        return build(mesh, 64, 124);
    }

    /**
     * Builds meshlets with the given limits.
     *
     * @param maxVertices at most 255 (local indices are bytes), at least 3
     * @param maxTriangles at least 1
     */
    public static Meshlets build(Mesh mesh, int maxVertices, int maxTriangles) {
        if (maxVertices < 3 || maxVertices > 255 || maxTriangles < 1) {
            throw new IllegalArgumentException("maxVertices must be in 3..255 and maxTriangles at least 1: " + maxVertices + ", " + maxTriangles);
        }
        int tris = mesh.triangleCount(), nv = mesh.vertexCount();
        float[] pos = mesh.positions();
        int[] idx = mesh.indices();
        // vertex -> triangles
        int[] start = new int[nv + 1];
        for (int i = 0; i < tris * 3; i++) {
            start[idx[i] + 1]++;
        }
        for (int v = 0; v < nv; v++) {
            start[v + 1] += start[v];
        }
        int[] fill = Arrays.copyOf(start, nv), adj = new int[tris * 3];
        for (int t = 0; t < tris; t++) {
            for (int k = 0; k < 3; k++) {
                adj[fill[idx[t * 3 + k]]++] = t;
            }
        }
        boolean[] used = new boolean[tris];
        int[] localOf = new int[nv];
        Arrays.fill(localOf, -1);

        int cap = Math.max(1, tris / Math.max(1, maxTriangles / 2) + 1);
        int[] vo = new int[cap], vc = new int[cap], to = new int[cap], tc = new int[cap];
        float[] sphere = new float[cap * 4], cone = new float[cap * 4];
        int[] allVertices = new int[Math.max(1, tris * 3)];
        byte[] allTriangles = new byte[Math.max(1, tris * 3)];
        int nVert = 0, nTri = 0, meshlets = 0;
        int nextSeed = 0;
        int[] candidates = new int[16];
        float[] normals = new float[maxTriangles * 3];

        while (true) {
            while (nextSeed < tris && used[nextSeed]) {
                nextSeed++;
            }
            if (nextSeed == tris) {
                break;
            }
            // seed: among the next few unused triangles, the one with the fewest unused neighbours, so regions are eaten from their edges inwards
            int seed = nextSeed, seedNeighbours = Integer.MAX_VALUE, looked = 0;
            for (int t = nextSeed; t < tris && looked < SEED_WINDOW; t++) {
                if (used[t]) {
                    continue;
                }
                looked++;
                int live = 0;
                for (int k = 0; k < 3; k++) {
                    int v = idx[t * 3 + k];
                    for (int a = start[v]; a < start[v + 1]; a++) {
                        if (!used[adj[a]] && adj[a] != t) {
                            live++;
                        }
                    }
                }
                if (live < seedNeighbours) {
                    seedNeighbours = live;
                    seed = t;
                }
            }
            if (meshlets == vo.length) {
                int c = meshlets * 2;
                vo = Arrays.copyOf(vo, c);
                vc = Arrays.copyOf(vc, c);
                to = Arrays.copyOf(to, c);
                tc = Arrays.copyOf(tc, c);
                sphere = Arrays.copyOf(sphere, c * 4);
                cone = Arrays.copyOf(cone, c * 4);
            }
            int firstVertex = nVert, firstTriangle = nTri;
            int localVerts = 0, localTris = 0, candCount = 0;
            double cx = 0, cy = 0, cz = 0; // running centroid of the meshlet's triangle centres
            int next = seed;
            while (next >= 0) {
                int t = next;
                used[t] = true;
                for (int k = 0; k < 3; k++) {
                    int v = idx[t * 3 + k];
                    if (localOf[v] < 0) {
                        localOf[v] = localVerts++;
                        allVertices[nVert++] = v;
                        // the triangles around a new vertex become candidates
                        for (int a = start[v]; a < start[v + 1]; a++) {
                            if (!used[adj[a]]) {
                                if (candCount == candidates.length) {
                                    candidates = Arrays.copyOf(candidates, candCount * 2);
                                }
                                candidates[candCount++] = adj[a];
                            }
                        }
                    }
                    allTriangles[nTri * 3 + k] = (byte) localOf[v];
                }
                nTri++;
                int o = localTris * 3;
                double tx = (pos[idx[t * 3] * 3] + pos[idx[t * 3 + 1] * 3] + pos[idx[t * 3 + 2] * 3]) / 3.0;
                double ty = (pos[idx[t * 3] * 3 + 1] + pos[idx[t * 3 + 1] * 3 + 1] + pos[idx[t * 3 + 2] * 3 + 1]) / 3.0;
                double tz = (pos[idx[t * 3] * 3 + 2] + pos[idx[t * 3 + 1] * 3 + 2] + pos[idx[t * 3 + 2] * 3 + 2]) / 3.0;
                cx = (cx * localTris + tx) / (localTris + 1);
                cy = (cy * localTris + ty) / (localTris + 1);
                cz = (cz * localTris + tz) / (localTris + 1);
                unitNormal(pos, idx, t, normals, o);
                localTris++;
                next = -1;
                if (localTris == maxTriangles) {
                    break;
                }
                // the best candidate that still fits: fewest new vertices, then nearest to the centre
                int bestNew = 4, bestSlot = -1;
                double bestDist = Double.MAX_VALUE;
                int w = 0;
                for (int c = 0; c < candCount; c++) {
                    int u = candidates[c];
                    if (used[u]) {
                        continue;
                    }
                    candidates[w++] = u; // compact the live ones as we go
                    int fresh = 0;
                    int a = idx[u * 3], b = idx[u * 3 + 1], d = idx[u * 3 + 2];
                    if (localOf[a] < 0) {
                        fresh++;
                    }
                    if (localOf[b] < 0 && b != a) {
                        fresh++;
                    }
                    if (localOf[d] < 0 && d != a && d != b) {
                        fresh++;
                    }
                    if (localVerts + fresh > maxVertices) {
                        continue;
                    }
                    double ux = (pos[a * 3] + pos[b * 3] + pos[d * 3]) / 3.0 - cx;
                    double uy = (pos[a * 3 + 1] + pos[b * 3 + 1] + pos[d * 3 + 1]) / 3.0 - cy;
                    double uz = (pos[a * 3 + 2] + pos[b * 3 + 2] + pos[d * 3 + 2]) / 3.0 - cz;
                    double dist = ux * ux + uy * uy + uz * uz;
                    if (fresh < bestNew || (fresh == bestNew && dist < bestDist)) {
                        bestNew = fresh;
                        bestDist = dist;
                        bestSlot = w - 1;
                    }
                }
                candCount = w;
                if (bestSlot >= 0) {
                    next = candidates[bestSlot];
                }
            }
            // close the meshlet
            vo[meshlets] = firstVertex;
            vc[meshlets] = localVerts;
            to[meshlets] = firstTriangle;
            tc[meshlets] = localTris;
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
            for (int i = firstVertex; i < nVert; i++) {
                int v = allVertices[i];
                x0 = Math.min(x0, pos[v * 3]);
                y0 = Math.min(y0, pos[v * 3 + 1]);
                z0 = Math.min(z0, pos[v * 3 + 2]);
                x1 = Math.max(x1, pos[v * 3]);
                y1 = Math.max(y1, pos[v * 3 + 1]);
                z1 = Math.max(z1, pos[v * 3 + 2]);
            }
            float sx = (x0 + x1) * 0.5f, sy = (y0 + y1) * 0.5f, sz = (z0 + z1) * 0.5f;
            double r2 = 0;
            for (int i = firstVertex; i < nVert; i++) {
                int v = allVertices[i];
                double dx = pos[v * 3] - sx, dy = pos[v * 3 + 1] - sy, dz = pos[v * 3 + 2] - sz;
                r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
                localOf[v] = -1;
            }
            sphere[meshlets * 4] = sx;
            sphere[meshlets * 4 + 1] = sy;
            sphere[meshlets * 4 + 2] = sz;
            sphere[meshlets * 4 + 3] = (float) Math.nextUp(Math.sqrt(r2)) * (1f + 1e-6f);
            ConeCull.computeCone(normals, 0, localTris, cone, meshlets * 4);
            meshlets++;
        }
        return new Meshlets(meshlets, Arrays.copyOf(vo, meshlets), Arrays.copyOf(vc, meshlets), Arrays.copyOf(to, meshlets), Arrays.copyOf(tc, meshlets),
                Arrays.copyOf(allVertices, nVert), Arrays.copyOf(allTriangles, nTri * 3), Arrays.copyOf(sphere, meshlets * 4), Arrays.copyOf(cone, meshlets * 4));
    }

    private static void unitNormal(float[] pos, int[] idx, int t, float[] out, int o) {
        int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
        double ex = pos[b] - pos[a], ey = pos[b + 1] - pos[a + 1], ez = pos[b + 2] - pos[a + 2];
        double fx = pos[c] - pos[a], fy = pos[c + 1] - pos[a + 1], fz = pos[c + 2] - pos[a + 2];
        double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            out[o] = (float) (nx / len);
            out[o + 1] = (float) (ny / len);
            out[o + 2] = (float) (nz / len);
        } else {
            out[o] = 0f; // a degenerate triangle faces nowhere; its zero normal does not widen the cone
            out[o + 1] = 0f;
            out[o + 2] = 0f;
        }
    }

    // ---------------------------------------------------------------- access

    public int count() {
        return count;
    }

    public int vertexOffset(int meshlet) {
        return vertexOffset[meshlet];
    }

    public int vertexCount(int meshlet) {
        return vertexCount[meshlet];
    }

    /** Offset of the meshlet's first triangle, in triangles. */
    public int triangleOffset(int meshlet) {
        return triangleOffset[meshlet];
    }

    public int triangleCount(int meshlet) {
        return triangleCount[meshlet];
    }

    /** The concatenated vertex lists (source-mesh vertex indices); live array. */
    public int[] vertices() {
        return vertices;
    }

    /** The concatenated triangles, three local indices (each below the meshlet's vertex count) per triangle; live array. */
    public byte[] triangles() {
        return triangles;
    }

    /** Bounding sphere {@code centre x, y, z, radius} of a meshlet. */
    public float sphereX(int m) {
        return sphere[m * 4];
    }

    public float sphereY(int m) {
        return sphere[m * 4 + 1];
    }

    public float sphereZ(int m) {
        return sphere[m * 4 + 2];
    }

    public float sphereRadius(int m) {
        return sphere[m * 4 + 3];
    }

    /** Normal cone axis (unit, meaningless when {@link #coneCutoff} is 1). */
    public float coneAxisX(int m) {
        return cone[m * 4];
    }

    public float coneAxisY(int m) {
        return cone[m * 4 + 1];
    }

    public float coneAxisZ(int m) {
        return cone[m * 4 + 2];
    }

    /** Sine of the cone's half-angle; 1 means the cluster has no useful cone and is never back-face culled. */
    public float coneCutoff(int m) {
        return cone[m * 4 + 3];
    }

    /** The source-mesh vertex indices of triangle {@code t} of meshlet {@code m}, written to {@code out[0..2]}. */
    public void triangle(int m, int t, int[] out) {
        int base = (triangleOffset[m] + t) * 3;
        for (int k = 0; k < 3; k++) {
            out[k] = vertices[vertexOffset[m] + (triangles[base + k] & 0xFF)];
        }
    }

    /** Adds every meshlet to {@code clusters} (sphere and cone) in meshlet order, so cluster {@code i} is meshlet {@code i} of this set. */
    public void addTo(ConeCull.Clusters clusters) {
        for (int m = 0; m < count; m++) {
            clusters.add(sphereX(m), sphereY(m), sphereZ(m), sphereRadius(m), coneAxisX(m), coneAxisY(m), coneAxisZ(m), coneCutoff(m));
        }
    }

    // ---------------------------------------------------------------- GPU upload

    /** Writes one 16-byte descriptor per meshlet at {@code offset}: {@code uint vertexOffset, vertexCount, triangleOffset, triangleCount}. */
    public void writeDescriptors(MemorySegment dst, long offset) {
        for (int m = 0; m < count; m++) {
            long o = offset + (long) m * DESCRIPTOR_BYTES;
            GpuWriter.putInt(dst, o, vertexOffset[m]);
            GpuWriter.putInt(dst, o + 4, vertexCount[m]);
            GpuWriter.putInt(dst, o + 8, triangleOffset[m]);
            GpuWriter.putInt(dst, o + 12, triangleCount[m]);
        }
    }

    /** Writes one 32-byte bounds record per meshlet at {@code offset}: {@code vec4(sphere centre, radius)}, {@code vec4(cone axis, cutoff)}. */
    public void writeBounds(MemorySegment dst, long offset) {
        for (int m = 0; m < count; m++) {
            long o = offset + (long) m * BOUNDS_BYTES;
            for (int k = 0; k < 4; k++) {
                GpuWriter.putFloat(dst, o + 4L * k, sphere[m * 4 + k]);
                GpuWriter.putFloat(dst, o + 16 + 4L * k, cone[m * 4 + k]);
            }
        }
    }
}
