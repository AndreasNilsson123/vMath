package vmath.occlusion;

import vmath.annotations.Experimental;

/**
 * The occlusion queries that {@link CoherentCulling} asks for: the one place where the culling
 * meets a renderer. The library makes no graphics call, so the engine implements this with
 * {@code glBeginQuery} on {@code GL_ANY_SAMPLES_PASSED} or {@code GL_SAMPLES_PASSED} (or a
 * Vulkan occlusion query pool) and the renderer draws the bounding box of the node with colour and
 * depth writes off; a test implements it with a software raster.
 *
 * <p>The contract is that of a GPU query: the box is tested against the depth buffer <em>as it is when
 * {@link #issue} is called</em>, in the order of the commands, so what the culling has rendered
 * before the call is what hides the box; the answer comes later, and asking for it before it is
 * ready may block.
 *
 * <p><b>Thread safety.</b> Implementations are called from the one thread that culls.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * OcclusionQueries queries = new OcclusionQueries() {
 *     public int issue(float x0, float y0, float z0, float x1, float y1, float z1) { return startQueryForBox(x0, y0, z0, x1, y1, z1); }
 *     public boolean isReady(int query) { return queryResultAvailable(query); }
 *     public int visibleSamples(int query) { return waitForQueryResult(query); }
 * };
 * }</pre>
 */
@Experimental("new in 0.2: the temporal culling may change")
public interface OcclusionQueries {

    /**
     * Starts a query for a box: the box is drawn against the current depth buffer without writing it.
     *
     * @param minX the minimum x of the box
     * @param minY the minimum y
     * @param minZ the minimum z
     * @param maxX the maximum x
     * @param maxY the maximum y
     * @param maxZ the maximum z
     * @return a handle for the query, valid until its result has been read
     */
    int issue(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

    /**
     * Tells whether the result of a query can be read without waiting.
     *
     * @param query the handle from {@link #issue}
     * @return {@code true} if {@link #visibleSamples} will not block
     */
    boolean isReady(int query);

    /**
     * Reads the result of a query, waiting for it if necessary, and releases the handle.
     *
     * @param query the handle from {@link #issue}; it is not used again afterwards
     * @return the number of samples (pixels) of the box that passed the depth test; 0 means the box is hidden
     */
    int visibleSamples(int query);
}
