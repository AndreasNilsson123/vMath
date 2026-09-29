package vmath.mesh;

import java.util.Arrays;
import vmath.geo.Aabbf;

/**
 * An indexed triangle mesh stored as plain arrays, one array per vertex attribute (no per-vertex objects), so a mesh of a million
 * vertices is a handful of {@code float[]} and one {@code int[]}.
 *
 * <p><b>Streams.</b> Positions and the triangle index list always exist. Normals ({@code 3} floats per vertex), tangents
 * ({@code 4}: the tangent direction and a handedness sign in {@code w}, so that {@code bitangent = cross(normal, tangent) * w}) and up
 * to {@link #MAX_UV_SETS} UV sets ({@code 2} floats) are optional and switched on with {@code enable...}; an enabled stream has one
 * entry for every vertex, zero-filled until set.
 *
 * <p><b>Conventions.</b> Triangles are counter-clockwise seen from outside (the face normal is
 * {@code cross(b - a, c - a)}), matching the rest of the library. {@code indices()} and the attribute arrays are the live storage:
 * they are longer than the used part (see {@link #vertexCount()} and {@link #indexCount()}) and may be replaced when the mesh grows,
 * so fetch them again after adding vertices or triangles.
 *
 * <p>Not thread-safe for writes; concurrent reads of a finished mesh are fine.
 */
public final class Mesh {

    /** Most UV sets a mesh can carry. */
    public static final int MAX_UV_SETS = 4;

    private float[] positions;
    private float[] normals;
    private float[] tangents;
    private final float[][] uvs = new float[MAX_UV_SETS][];
    private int[] indices;
    private int vertexCount;
    private int indexCount;

    public Mesh() {
        this(16, 16);
    }

    /** A mesh with room for the given numbers of vertices and triangles (it grows when they are exceeded). */
    public Mesh(int vertexCapacity, int triangleCapacity) {
        int vc = Math.max(vertexCapacity, 4);
        positions = new float[vc * 3];
        indices = new int[Math.max(triangleCapacity, 1) * 3];
    }

    // ---------------------------------------------------------------- sizes

    public int vertexCount() {
        return vertexCount;
    }

    public int triangleCount() {
        return indexCount / 3;
    }

    /** Number of used entries of {@link #indices()} (three per triangle). */
    public int indexCount() {
        return indexCount;
    }

    public int vertexCapacity() {
        return positions.length / 3;
    }

    // ---------------------------------------------------------------- building

    /** Adds a vertex and returns its index. Enabled attribute streams get a zero entry for it. */
    public int addVertex(float x, float y, float z) {
        ensureVertexCapacity(vertexCount + 1);
        int i = vertexCount++;
        positions[i * 3] = x;
        positions[i * 3 + 1] = y;
        positions[i * 3 + 2] = z;
        if (normals != null) { // reused capacity after clear() may hold old values
            Arrays.fill(normals, i * 3, i * 3 + 3, 0f);
        }
        if (tangents != null) {
            Arrays.fill(tangents, i * 4, i * 4 + 4, 0f);
        }
        for (int s = 0; s < MAX_UV_SETS; s++) {
            if (uvs[s] != null) {
                Arrays.fill(uvs[s], i * 2, i * 2 + 2, 0f);
            }
        }
        return i;
    }

    /** Adds a vertex with a normal and a first UV set (both streams are enabled if they were not). */
    public int addVertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        enableNormals();
        enableUvs(0);
        int i = addVertex(x, y, z);
        setNormal(i, nx, ny, nz);
        setUv(0, i, u, v);
        return i;
    }

    /** Appends a copy of vertex {@code src} with all of its enabled attributes and returns the new index. */
    public int copyVertex(int src) {
        requireVertex(src);
        ensureVertexCapacity(vertexCount + 1);
        int i = vertexCount++;
        System.arraycopy(positions, src * 3, positions, i * 3, 3);
        if (normals != null) {
            System.arraycopy(normals, src * 3, normals, i * 3, 3);
        }
        if (tangents != null) {
            System.arraycopy(tangents, src * 4, tangents, i * 4, 4);
        }
        for (int s = 0; s < MAX_UV_SETS; s++) {
            if (uvs[s] != null) {
                System.arraycopy(uvs[s], src * 2, uvs[s], i * 2, 2);
            }
        }
        return i;
    }

    /** Adds a triangle by vertex indices, counter-clockwise seen from outside. */
    public void addTriangle(int a, int b, int c) {
        if (a < 0 || b < 0 || c < 0 || a >= vertexCount || b >= vertexCount || c >= vertexCount) {
            throw new IllegalArgumentException("triangle " + a + "," + b + "," + c + " refers to a missing vertex (count " + vertexCount + ")");
        }
        if (indexCount + 3 > indices.length) {
            indices = Arrays.copyOf(indices, Math.max(indices.length * 2, indexCount + 3));
        }
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    /** Adds the two triangles of a quad {@code a, b, c, d} given counter-clockwise: {@code (a, b, c)} and {@code (a, c, d)}. */
    public void addQuad(int a, int b, int c, int d) {
        addTriangle(a, b, c);
        addTriangle(a, c, d);
    }

    /** Empties the mesh but keeps its arrays and enabled streams. */
    public void clear() {
        vertexCount = 0;
        indexCount = 0;
    }

    // ---------------------------------------------------------------- streams

    public boolean hasNormals() {
        return normals != null;
    }

    public boolean hasTangents() {
        return tangents != null;
    }

    public boolean hasUvs(int set) {
        checkSet(set);
        return uvs[set] != null;
    }

    /** Switches the normal stream on (no effect if it is on). */
    public void enableNormals() {
        if (normals == null) {
            normals = new float[positions.length];
        }
    }

    /** Switches the tangent stream on (no effect if it is on). */
    public void enableTangents() {
        if (tangents == null) {
            tangents = new float[positions.length / 3 * 4];
        }
    }

    /** Switches UV set {@code set} on (no effect if it is on). */
    public void enableUvs(int set) {
        checkSet(set);
        if (uvs[set] == null) {
            uvs[set] = new float[positions.length / 3 * 2];
        }
    }

    /** Switches a stream off and frees it. */
    public void disableNormals() {
        normals = null;
    }

    public void disableTangents() {
        tangents = null;
    }

    public void disableUvs(int set) {
        checkSet(set);
        uvs[set] = null;
    }

    private static void checkSet(int set) {
        if (set < 0 || set >= MAX_UV_SETS) {
            throw new IllegalArgumentException("uv set must be in [0, " + (MAX_UV_SETS - 1) + "]: " + set);
        }
    }

    private void requireVertex(int i) {
        if (i < 0 || i >= vertexCount) {
            throw new IndexOutOfBoundsException("vertex " + i + " of " + vertexCount);
        }
    }

    public void setPosition(int i, float x, float y, float z) {
        requireVertex(i);
        positions[i * 3] = x;
        positions[i * 3 + 1] = y;
        positions[i * 3 + 2] = z;
    }

    public void setNormal(int i, float x, float y, float z) {
        requireVertex(i);
        requireStream(normals, "normals");
        normals[i * 3] = x;
        normals[i * 3 + 1] = y;
        normals[i * 3 + 2] = z;
    }

    public void setTangent(int i, float x, float y, float z, float w) {
        requireVertex(i);
        requireStream(tangents, "tangents");
        tangents[i * 4] = x;
        tangents[i * 4 + 1] = y;
        tangents[i * 4 + 2] = z;
        tangents[i * 4 + 3] = w;
    }

    public void setUv(int set, int i, float u, float v) {
        requireVertex(i);
        checkSet(set);
        requireStream(uvs[set], "uv set " + set);
        uvs[set][i * 2] = u;
        uvs[set][i * 2 + 1] = v;
    }

    private static void requireStream(float[] s, String name) {
        if (s == null) {
            throw new IllegalStateException(name + " are not enabled");
        }
    }

    // ---------------------------------------------------------------- live arrays

    /** Vertex positions, {@code x, y, z} per vertex. Live storage, longer than the used part. */
    public float[] positions() {
        return positions;
    }

    /** Vertex normals, or {@code null} if not enabled. Live storage. */
    public float[] normals() {
        return normals;
    }

    /** Vertex tangents ({@code x, y, z, w}), or {@code null} if not enabled. Live storage. */
    public float[] tangents() {
        return tangents;
    }

    /** UV set {@code set} ({@code u, v} per vertex), or {@code null} if not enabled. Live storage. */
    public float[] uvs(int set) {
        checkSet(set);
        return uvs[set];
    }

    /** Triangle indices, three per triangle. Live storage, longer than {@link #indexCount()}. */
    public int[] indices() {
        return indices;
    }

    // ---------------------------------------------------------------- queries

    /** The axis-aligned box around all vertices; {@link Aabbf#EMPTY} when there are none. */
    public Aabbf bounds() {
        if (vertexCount == 0) {
            return Aabbf.EMPTY;
        }
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        float x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0; i < vertexCount; i++) {
            float x = positions[i * 3], y = positions[i * 3 + 1], z = positions[i * 3 + 2];
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
        }
        return new Aabbf(x0, y0, z0, x1, y1, z1);
    }

    /** Sum of the triangles' areas (in double). */
    public double surfaceArea() {
        double area = 0;
        for (int t = 0; t < indexCount; t += 3) {
            area += 0.5 * crossLength(indices[t], indices[t + 1], indices[t + 2]);
        }
        return area;
    }

    /**
     * Signed volume enclosed by the mesh (in double), by summing signed tetrahedra to the origin: positive for a closed mesh whose
     * triangles wind counter-clockwise seen from outside, negative if it is inside out, and meaningless for open meshes.
     */
    public double signedVolume() {
        double v = 0;
        for (int t = 0; t < indexCount; t += 3) {
            int a = indices[t] * 3, b = indices[t + 1] * 3, c = indices[t + 2] * 3;
            double ax = positions[a], ay = positions[a + 1], az = positions[a + 2];
            double bx = positions[b], by = positions[b + 1], bz = positions[b + 2];
            double cx = positions[c], cy = positions[c + 1], cz = positions[c + 2];
            v += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
        }
        return v / 6.0;
    }

    private double crossLength(int ia, int ib, int ic) {
        int a = ia * 3, b = ib * 3, c = ic * 3;
        double ux = positions[b] - positions[a], uy = positions[b + 1] - positions[a + 1], uz = positions[b + 2] - positions[a + 2];
        double vx = positions[c] - positions[a], vy = positions[c + 1] - positions[a + 1], vz = positions[c + 2] - positions[a + 2];
        double x = uy * vz - uz * vy, y = uz * vx - ux * vz, z = ux * vy - uy * vx;
        return Math.sqrt(x * x + y * y + z * z);
    }

    // ---------------------------------------------------------------- internals

    private void ensureVertexCapacity(int n) {
        int cap = positions.length / 3;
        if (n <= cap) {
            return;
        }
        int c = Math.max(cap * 2, n);
        positions = Arrays.copyOf(positions, c * 3);
        if (normals != null) {
            normals = Arrays.copyOf(normals, c * 3);
        }
        if (tangents != null) {
            tangents = Arrays.copyOf(tangents, c * 4);
        }
        for (int s = 0; s < MAX_UV_SETS; s++) {
            if (uvs[s] != null) {
                uvs[s] = Arrays.copyOf(uvs[s], c * 2);
            }
        }
    }

    /**
     * Replaces the stored data with {@code vertices} vertices and {@code indexEntries} index entries taken over from the given
     * arrays without copying (used by {@link MeshTools} when it rebuilds a mesh). Streams that are {@code null} are switched off.
     */
    void adopt(float[] positions, float[] normals, float[] tangents, float[][] uvSets, int vertices, int[] indices, int indexEntries) {
        this.positions = positions;
        this.normals = normals;
        this.tangents = tangents;
        for (int s = 0; s < MAX_UV_SETS; s++) {
            this.uvs[s] = uvSets != null && s < uvSets.length ? uvSets[s] : null;
        }
        this.indices = indices;
        this.vertexCount = vertices;
        this.indexCount = indexEntries;
    }
}
