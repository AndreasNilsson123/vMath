package vmath.mesh;

import java.util.Arrays;
import vmath.geo.Aabbf;

/**
 * An indexed triangle mesh stored as plain arrays, one array per vertex attribute (no per-vertex
 * objects), so a mesh of a million vertices is a handful of {@code float[]} and one {@code int[]}.
 *
 * <p><b>Streams.</b> Positions and the triangle index list always exist. Normals ({@code 3} floats
 * per vertex), tangents ({@code 4}: the tangent direction and a handedness sign in {@code w}, so
 * that {@code bitangent = cross(normal, tangent) * w}) and up to {@link #MAX_UV_SETS} UV sets
 * ({@code 2} floats) are optional and switched on with {@code enable...}; an enabled stream has one
 * entry for every vertex, zero-filled until set.
 *
 * <p><b>Conventions.</b> Triangles are counter-clockwise seen from outside (the face normal is
 * {@code cross(b - a, c - a)}), matching the rest of the library. {@code indices()} and the
 * attribute arrays are the live storage: they are longer than the used part (see
 * {@link #vertexCount()} and {@link #indexCount()}) and may be replaced when the mesh grows, so
 * fetch them again after adding vertices or triangles.
 *
 * <p>Not thread-safe for writes; concurrent reads of a finished mesh are fine.
 *
 * <p><b>Thread safety.</b> Not thread-safe for writes. Concurrent reads of a finished mesh are safe
 * as long as no thread modifies it; the arrays returned by the accessors are live, not copies.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mesh mesh = new Mesh();
 * int a = mesh.addVertex(0f, 0f, 0f);
 * int b = mesh.addVertex(1f, 0f, 0f);
 * int c = mesh.addVertex(0f, 1f, 0f);
 * mesh.addTriangle(a, b, c);                                                                 // counter-clockwise seen from outside
 * MeshTools.computeSmoothNormals(mesh);
 * String problem = mesh.validate();                                                          // null when consistent
 * }</pre>
 */
public final class Mesh {

    /**
     * Most UV sets a mesh can carry.
     */
    public static final int MAX_UV_SETS = 4;

    private float[] positions;
    private float[] normals;
    private float[] tangents;
    private final float[][] uvs = new float[MAX_UV_SETS][];
    private int[] indices;
    private int vertexCount;
    private int indexCount;

    /**
     * Creates an empty mesh with room for 16 vertices and 16 triangles; it grows as they are added.
     */
    public Mesh() {
        this(16, 16);
    }

    /**
     * Creates a mesh with room for the given numbers of vertices and triangles (it grows when they
     * are exceeded).
     *
     * @param vertexCapacity the vertex capacity
     * @param triangleCapacity the triangle capacity
     */
    public Mesh(int vertexCapacity, int triangleCapacity) {
        int vc = Math.max(vertexCapacity, 4);
        positions = new float[vc * 3];
        indices = new int[Math.max(triangleCapacity, 1) * 3];
    }

    /**
     * Duplicates the mesh with its own arrays, trimmed to the used part, so that the copy can be
     * changed without affecting the original.
     *
     * @return an independent copy of this mesh: every stream and the indices, trimmed to the used
     *     part
     */
    public Mesh copy() {
        Mesh m = new Mesh(Math.max(vertexCount, 4), Math.max(indexCount / 3, 1));
        m.positions = java.util.Arrays.copyOf(positions, Math.max(vertexCount, 4) * 3);
        if (normals != null) {
            m.normals = java.util.Arrays.copyOf(normals, m.positions.length);
        }
        if (tangents != null) {
            m.tangents = java.util.Arrays.copyOf(tangents, Math.max(vertexCount, 4) * 4);
        }
        for (int s = 0; s < MAX_UV_SETS; s++) {
            if (uvs[s] != null) {
                m.uvs[s] = java.util.Arrays.copyOf(uvs[s], Math.max(vertexCount, 4) * 2);
            }
        }
        m.indices = java.util.Arrays.copyOf(indices, Math.max(indexCount, 3));
        m.vertexCount = vertexCount;
        m.indexCount = indexCount;
        return m;
    }

    // ---------------------------------------------------------------- sizes

    /**
     * Counts the vertices of the mesh.
     *
     * @return the number of vertices
     */
    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Counts the triangles of the mesh.
     *
     * @return the number of triangles
     */
    public int triangleCount() {
        return indexCount / 3;
    }

    /**
     * Counts the used entries of the index array.
     *
     * @return number of used entries of {@link #indices()} (three per triangle)
     */
    public int indexCount() {
        return indexCount;
    }

    /**
     * Reports how many vertices fit before the arrays are reallocated, which matters when building
     * a mesh incrementally.
     *
     * @return the number of vertices the mesh can hold before it grows
     */
    public int vertexCapacity() {
        return positions.length / 3;
    }

    // ---------------------------------------------------------------- building

    /**
     * Adds a vertex and returns its index.
     *
     * <p>Enabled attribute streams get a zero entry for it.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return its index
     */
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

    /**
     * Adds a vertex with a normal and a first UV set (both streams are enabled if they were not).
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param u the u texture coordinate
     * @param v the v texture coordinate
     * @return the index of the new vertex
     */
    public int addVertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        enableNormals();
        enableUvs(0);
        int i = addVertex(x, y, z);
        setNormal(i, nx, ny, nz);
        setUv(0, i, u, v);
        return i;
    }

    /**
     * Appends a copy of vertex {@code src} with all of its enabled attributes and returns the new
     * index.
     *
     * @param src the index of the vertex to copy
     * @return the new index
     */
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

    /**
     * Adds a triangle by vertex indices, counter-clockwise seen from outside.
     *
     * @param a the index of the first vertex
     * @param b the index of the second vertex
     * @param c the index of the third vertex
     * @throws IllegalArgumentException if a vertex index refers to a vertex that does not exist
     */
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

    /**
     * Adds the two triangles of a quad {@code a, b, c, d} given counter-clockwise:
     * {@code (a, b, c)} and {@code (a, c, d)}.
     *
     * @param a the index of the first vertex
     * @param b the index of the second vertex
     * @param c the index of the third vertex
     * @param d the index of the fourth vertex
     */
    public void addQuad(int a, int b, int c, int d) {
        addTriangle(a, b, c);
        addTriangle(a, c, d);
    }

    /**
     * Empties the mesh but keeps its arrays and enabled streams.
     */
    public void clear() {
        vertexCount = 0;
        indexCount = 0;
    }

    // ---------------------------------------------------------------- streams

    /**
     * Returns whether the mesh has a normal stream.
     *
     * @return {@code true} if the mesh has a normal stream
     */
    public boolean hasNormals() {
        return normals != null;
    }

    /**
     * Returns whether the mesh has a tangent stream (four floats per vertex, the fourth is the
     * handedness).
     *
     * @return {@code true} if the mesh has a tangent stream (four floats per vertex, the fourth is
     *     the handedness)
     */
    public boolean hasTangents() {
        return tangents != null;
    }

    /**
     * Returns whether UV set {@code set} is on.
     *
     * @param set the set
     * @return {@code true} if UV set {@code set} is on
     */
    public boolean hasUvs(int set) {
        checkSet(set);
        return uvs[set] != null;
    }

    /**
     * Switches the normal stream on (no effect if it is on).
     */
    public void enableNormals() {
        if (normals == null) {
            normals = new float[positions.length];
        }
    }

    /**
     * Switches the tangent stream on (no effect if it is on).
     */
    public void enableTangents() {
        if (tangents == null) {
            tangents = new float[positions.length / 3 * 4];
        }
    }

    /**
     * Switches UV set {@code set} on (no effect if it is on).
     *
     * @param set the set
     */
    public void enableUvs(int set) {
        checkSet(set);
        if (uvs[set] == null) {
            uvs[set] = new float[positions.length / 3 * 2];
        }
    }

    /**
     * Switches a stream off and frees it.
     */
    public void disableNormals() {
        normals = null;
    }

    /**
     * Switches the tangent stream off and frees it.
     */
    public void disableTangents() {
        tangents = null;
    }

    /**
     * Switches UV set {@code set} off and frees it.
     *
     * @param set the set
     */
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

    /**
     * Sets the position of vertex {@code i}; {@link IndexOutOfBoundsException} for a vertex that
     * does not exist.
     *
     * @param i the index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void setPosition(int i, float x, float y, float z) {
        requireVertex(i);
        positions[i * 3] = x;
        positions[i * 3 + 1] = y;
        positions[i * 3 + 2] = z;
    }

    /**
     * Sets the normal of vertex {@code i}; the normal stream must be on
     * ({@link IllegalStateException} otherwise).
     *
     * @param i the index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void setNormal(int i, float x, float y, float z) {
        requireVertex(i);
        requireStream(normals, "normals");
        normals[i * 3] = x;
        normals[i * 3 + 1] = y;
        normals[i * 3 + 2] = z;
    }

    /**
     * Sets the tangent of vertex {@code i} ({@code w} is the handedness); the tangent stream must
     * be on ({@link IllegalStateException} otherwise).
     *
     * @param i the index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param w the w component
     */
    public void setTangent(int i, float x, float y, float z, float w) {
        requireVertex(i);
        requireStream(tangents, "tangents");
        tangents[i * 4] = x;
        tangents[i * 4 + 1] = y;
        tangents[i * 4 + 2] = z;
        tangents[i * 4 + 3] = w;
    }

    /**
     * Sets texture coordinate {@code set} of vertex {@code i}; the UV set must be on
     * ({@link IllegalStateException} otherwise).
     *
     * @param set the set
     * @param i the index
     * @param u the u texture coordinate
     * @param v the v texture coordinate
     */
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

    /**
     * Exposes the position array, a live internal array that is longer than the used part and that
     * changes when the mesh grows.
     *
     * <p>Live storage, longer than the used part.
     *
     * @return vertex positions, {@code x, y, z} per vertex
     */
    public float[] positions() {
        return positions;
    }

    /**
     * Exposes the normal array, a live internal array, when normals are enabled.
     *
     * <p>Live storage.
     *
     * @return vertex normals, or {@code null} if not enabled
     */
    public float[] normals() {
        return normals;
    }

    /**
     * Exposes the tangent array, a live internal array, when tangents are enabled.
     *
     * <p>Live storage.
     *
     * @return vertex tangents ({@code x, y, z, w}), or {@code null} if not enabled
     */
    public float[] tangents() {
        return tangents;
    }

    /**
     * Exposes the texture coordinates of one set, a live internal array, when that set is enabled.
     *
     * <p>Live storage.
     *
     * @param set the set
     * @return UV set {@code set} ({@code u, v} per vertex), or {@code null} if not enabled
     */
    public float[] uvs(int set) {
        checkSet(set);
        return uvs[set];
    }

    /**
     * Exposes the index array, a live internal array that is longer than the used part and that
     * changes when the mesh grows.
     *
     * <p>Live storage, longer than {@link #indexCount()}.
     *
     * @return triangle indices, three per triangle
     */
    public int[] indices() {
        return indices;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Checks the invariants that direct writes into the live arrays ({@link #positions()},
     * {@link #indices()}, ...) can break: every stream is long enough for the vertices, the index
     * count is a multiple of three, and every index names an existing vertex.
     *
     * <p>Positions are not checked for NaN (a mesh may carry them). Costs O(indices).
     *
     * @return a description of the first problem found, or {@code null} if the mesh is consistent
     */
    public String validate() {
        if (positions.length < vertexCount * 3) {
            return "positions hold " + positions.length + " floats, fewer than 3 per vertex for " + vertexCount + " vertices";
        }
        if (normals != null && normals.length < vertexCount * 3) {
            return "normals hold " + normals.length + " floats, fewer than 3 per vertex for " + vertexCount + " vertices";
        }
        if (tangents != null && tangents.length < vertexCount * 4) {
            return "tangents hold " + tangents.length + " floats, fewer than 4 per vertex for " + vertexCount + " vertices";
        }
        for (int s = 0; s < MAX_UV_SETS; s++) {
            if (uvs[s] != null && uvs[s].length < vertexCount * 2) {
                return "uv set " + s + " holds " + uvs[s].length + " floats, fewer than 2 per vertex for " + vertexCount + " vertices";
            }
        }
        if (indexCount % 3 != 0 || indexCount > indices.length) {
            return "the index count " + indexCount + " is not a whole number of triangles inside an index array of " + indices.length;
        }
        for (int i = 0; i < indexCount; i++) {
            if (indices[i] < 0 || indices[i] >= vertexCount) {
                return "index " + i + " is " + indices[i] + ", outside the " + vertexCount + " vertices";
            }
        }
        return null;
    }

    /**
     * Computes the axis-aligned box of all vertices by scanning the positions, so the cost is
     * linear in the vertex count.
     *
     * @return the axis-aligned box around all vertices; {@link Aabbf#EMPTY} when there are none
     */
    public Aabbf bounds() {
        if (vertexCount == 0) {
            return Aabbf.EMPTY;
        }
        return Aabbf.fromPoints(positions, 0, vertexCount);
    }

    /**
     * Sums the triangle areas in double precision; the cost is linear in the triangle count.
     *
     * @return sum of the triangles' areas (in double)
     */
    public double surfaceArea() {
        double area = 0;
        for (int t = 0; t < indexCount; t += 3) {
            area += 0.5 * crossLength(indices[t], indices[t + 1], indices[t + 2]);
        }
        return area;
    }

    /**
     * Sums signed tetrahedron volumes in double precision, which gives the volume of a closed mesh;
     * its sign shows whether the winding faces outwards, and open meshes give meaningless values.
     *
     * @return signed volume enclosed by the mesh (in double), by summing signed tetrahedra to the
     *     origin: positive for a closed mesh whose triangles wind counter-clockwise seen from
     *     outside, negative if it is inside out, and meaningless for open meshes
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
     * Replaces the stored data with {@code vertices} vertices and {@code indexEntries} index
     * entries taken over from the given arrays without copying (used by {@link MeshTools} when it
     * rebuilds a mesh).
     *
     * <p>Streams that are {@code null} are switched off.
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
