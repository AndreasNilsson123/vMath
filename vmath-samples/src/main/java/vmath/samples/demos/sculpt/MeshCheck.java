package vmath.samples.demos.sculpt;

import java.util.Arrays;

/**
 * Checks that a triangle mesh is closed and consistently wound: every directed edge {@code a -> b}
 * of a triangle must be met by exactly one triangle that has the edge {@code b -> a}.
 *
 * <p>This is the property that the library documents for {@code SurfaceNets} when the solid lies
 * inside the sampled box, and what a mesh needs to be a solid for {@code MassProperties.ofMesh} or
 * for a renderer that culls back faces. The class keeps its sort buffer between calls, so a check
 * after the first allocates nothing unless the mesh grew.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it owns the buffer that {@link #openEdges} sorts.
 */
final class MeshCheck {

    private long[] keys = new long[0];
    private long[] reversed = new long[0];
    private int repeated;
    private int unpaired;

    /**
     * Reads the number of repeated directed edges found by the last {@link #openEdges}: two
     * triangles that run along the same edge in the same direction, which is what two sheets of the
     * surface inside one cell look like.
     *
     * @return the count
     */
    int repeated() {
        return repeated;
    }

    /**
     * Reads the number of directed edges without a partner found by the last {@link #openEdges}.
     *
     * @return the count
     */
    int unpaired() {
        return unpaired;
    }

    /**
     * Counts the directed edges that have no partner, and the pairs of triangles that share a
     * directed edge (which is a winding error).
     *
     * @param indices the triangle indices, three per triangle; must not be {@code null}
     * @param triangleCount the number of triangles
     * @param vertexCount the number of vertices that the indices refer to
     * @return the number of directed edges without a partner plus the number of repeated directed
     *     edges; 0 for a closed, consistently wound mesh
     */
    int openEdges(int[] indices, int triangleCount, int vertexCount) {
        int edges = triangleCount * 3;
        if (keys.length < edges) {
            keys = new long[edges];
            reversed = new long[edges];
        }
        long v = vertexCount;
        for (int t = 0; t < triangleCount; t++) {
            for (int e = 0; e < 3; e++) {
                long a = indices[t * 3 + e], b = indices[t * 3 + (e + 1) % 3];
                keys[t * 3 + e] = a * v + b;
                reversed[t * 3 + e] = b * v + a;
            }
        }
        Arrays.sort(keys, 0, edges);
        repeated = 0;
        unpaired = 0;
        for (int i = 1; i < edges; i++) {
            if (keys[i] == keys[i - 1]) {
                repeated++;
            }
        }
        for (int i = 0; i < edges; i++) {
            if (Arrays.binarySearch(keys, 0, edges, reversed[i]) < 0) {
                unpaired++;
            }
        }
        return repeated + unpaired;
    }
}
