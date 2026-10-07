package vmath.lighting;

import vmath.annotations.Experimental;
import vmath.core.ClipSpace;
import vmath.camera.Cameraf;
import vmath.camera.OrthoCameraf;

/**
 * The cluster grid of clustered (and tiled) forward lighting: the view frustum cut into tiles in x
 * and y and into slices in depth, so that every fragment finds the few lights that can reach it
 * from one lookup.
 *
 * <p>This class is the arithmetic only (grid size, which cluster a view-space position or a pixel
 * and depth falls in, the bounds of every cluster); {@link ClusterLights} assigns lights to the
 * clusters and the formulas here are the ones the GLSL in {@code docs/CAMERA.md} uses.
 *
 * <p><b>Slices.</b> Depth is the distance along the view direction, positive in front of the
 * camera. The slices are exponential, {@code boundary(k) = near * (far / near)^(k / slices)}, so
 * each slice is the same factor deeper than the one before (about the same screen-space
 * proportions), and a fragment finds its slice with
 * {@code floor(ln(depth) * sliceScale + sliceBias)}, one logarithm. Depths outside
 * {@code [near, far]} clamp to the first and last slice.
 *
 * <p><b>Tiles.</b> A tile is {@code tilePixels} wide and high (the last row and column may be
 * smaller). Tile rows are counted in the direction pixel coordinates run: with {@code yDown}
 * (Vulkan, D3D window coordinates) row 0 is the top of the screen, otherwise the bottom (OpenGL
 * {@code gl_FragCoord}).
 *
 * <p><b>Cluster index</b> is {@code (slice * tilesY + row) * tilesX + column}, slices outermost, so
 * the clusters of one depth slice are contiguous.
 *
 * <p><b>Orthographic views.</b> A grid made from an {@link OrthoCameraf} ({@link #orthographic()}) has
 * tiles that do not widen with distance (a tile is a fixed rectangle of view-space x and y), slices
 * that are <em>linear</em> in depth, {@code boundary(k) = near + (far - near) * k / slices}, found
 * with {@code floor(depth * sliceScale + sliceBias)} and no logarithm, and clusters that are boxes.
 * The tangent accessors ({@link #tanHalfFovX()}, {@link #tanHalfFovY()}, {@link #slopes}) have no
 * meaning there and throw; {@link #viewEdges} gives the tile edges instead.
 *
 * <p>Everything is view space (x right, y up, z negative forward, like
 * {@link Cameraf#viewPositionFromDepth}).
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Cameraf camera = Cameraf.lookingAt(new Vec3f(0f, 0f, 5f), Vec3f.ZERO, Vec3f.UNIT_Y, 1f, 16f / 9f, 0.1f, 100f, DepthRange.of(ClipSpace.OPENGL));
 * ClusterGrid grid = ClusterGrid.of(camera, 1920, 1080, 64, 24, 100f, false);   // 64-pixel tiles, 24 depth slices
 * int cluster = grid.clusterOf(960f, 540f, 10f);                                 // pixel and view depth to cluster index
 * int count = grid.clusterCount();
 * }</pre>
 */
@Experimental("the grid layout and the set of helpers may change")
public final class ClusterGrid {

    private final int viewportWidth, viewportHeight, tilePixels, tilesX, tilesY, slices;
    private final boolean yDown, orthographic;
    private final float near, far, tanX, tanY, sliceScale, sliceBias;
    private final float viewLeft, viewRight, viewBottom, viewTop;   // orthographic grids: the box of the view in view-space x and y
    private final float[] sliceDepth;   // slices + 1 boundaries
    private final float[] columnNdc;    // tilesX + 1 ndc x edges, increasing
    private final float[] colLowSlope, colHighSlope, rowLowSlope, rowHighSlope; // x / depth and y / depth at the tile edges (orthographic: x and y)

    // a perspective grid takes (fovy, aspect, -, -), an orthographic one the edges of the box (left, right, bottom, top)
    private ClusterGrid(int width, int height, int tile, int slices, boolean yDown, boolean orthographic, float near, float far, float p0, float p1, float p2, float p3) {
        if (width < 1 || height < 1 || tile < 1 || slices < 1) {
            throw new IllegalArgumentException("viewport, tile size and slice count must be positive");
        }
        if (orthographic) {
            if (!(Float.isFinite(p0) && Float.isFinite(p1) && Float.isFinite(p2) && Float.isFinite(p3) && p1 > p0 && p3 > p2) || !Float.isFinite(near) || !(far > near) || Float.isInfinite(far)) {
                throw new IllegalArgumentException("need finite edges with left < right and bottom < top and near < far < infinity: " + p0 + " " + p1 + " " + p2 + " " + p3 + ", " + near + ", " + far);
            }
        } else if (!(p0 > 0f && p0 < (float) Math.PI) || !(p1 > 0f) || !(near > 0f) || !(far > near) || Float.isInfinite(far)) {
            throw new IllegalArgumentException("need 0 < fovy < pi, aspect > 0 and 0 < near < far < infinity: " + p0 + ", " + p1 + ", " + near + ", " + far);
        }
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.tilePixels = tile;
        this.tilesX = (width + tile - 1) / tile;
        this.tilesY = (height + tile - 1) / tile;
        this.slices = slices;
        this.yDown = yDown;
        this.orthographic = orthographic;
        this.near = near;
        this.far = far;
        this.viewLeft = orthographic ? p0 : Float.NaN;
        this.viewRight = orthographic ? p1 : Float.NaN;
        this.viewBottom = orthographic ? p2 : Float.NaN;
        this.viewTop = orthographic ? p3 : Float.NaN;
        this.tanY = orthographic ? Float.NaN : (float) Math.tan(p0 * 0.5f);
        this.tanX = orthographic ? Float.NaN : tanY * p1;
        sliceDepth = new float[slices + 1];
        if (orthographic) {
            this.sliceScale = (float) (slices / ((double) far - near));
            this.sliceBias = (float) (-(double) near * slices / ((double) far - near));
            for (int k = 0; k <= slices; k++) {
                sliceDepth[k] = k == slices ? far : (float) (near + ((double) far - near) * k / slices);
            }
        } else {
            double logRatio = Math.log((double) far / near);
            this.sliceScale = (float) (slices / logRatio);
            this.sliceBias = (float) (-slices * Math.log(near) / logRatio);
            for (int k = 0; k <= slices; k++) {
                sliceDepth[k] = k == slices ? far : (float) (near * Math.pow((double) far / near, (double) k / slices));
            }
        }
        columnNdc = new float[tilesX + 1];
        for (int i = 0; i <= tilesX; i++) {
            columnNdc[i] = -1f + 2f * Math.min(i * tile, width) / width;
        }
        colLowSlope = new float[tilesX];
        colHighSlope = new float[tilesX];
        for (int i = 0; i < tilesX; i++) {
            colLowSlope[i] = orthographic ? viewX(columnNdc[i]) : columnNdc[i] * tanX;
            colHighSlope[i] = orthographic ? viewX(columnNdc[i + 1]) : columnNdc[i + 1] * tanX;
        }
        rowLowSlope = new float[tilesY];
        rowHighSlope = new float[tilesY];
        for (int j = 0; j < tilesY; j++) {
            rowLowSlope[j] = orthographic ? viewY(rowLow(j)) : rowLow(j) * tanY;
            rowHighSlope[j] = orthographic ? viewY(rowHigh(j)) : rowHigh(j) * tanY;
        }
    }

    private float viewX(float ndc) {
        return viewLeft + (ndc + 1f) * 0.5f * (viewRight - viewLeft);
    }

    private float viewY(float ndc) {
        return viewBottom + (ndc + 1f) * 0.5f * (viewTop - viewBottom);
    }

    /**
     * Creates a cluster grid from a camera and a viewport.
     *
     * <p>{@code far} is the far plane of the clusters (finite, usually the shadow or light range
     * rather than an infinite camera far plane); the near plane and field of view come from the
     * camera.
     *
     * @param camera the camera; must not be {@code null}
     * @param viewportWidth the viewport width
     * @param viewportHeight the viewport height
     * @param tilePixels the tile pixels
     * @param slices the slices
     * @param far the distance to the far plane
     * @param yDown whether y down
     * @return a grid for a camera
     */
    public static ClusterGrid of(Cameraf camera, int viewportWidth, int viewportHeight, int tilePixels, int slices, float far, boolean yDown) {
        return new ClusterGrid(viewportWidth, viewportHeight, tilePixels, slices, yDown, false, camera.near(), Math.min(far, camera.far()), camera.fovy(), camera.aspect(), 0f, 0f);
    }

    /**
     * Creates a cluster grid from a camera and a viewport for the clip space of a graphics API.
     *
     * <p>As {@link #of(Cameraf, int, int, int, int, float, boolean)} with {@code yDown} taken from
     * {@code space}; the depth range of the clip space does not matter for a cluster grid.
     *
     * @param camera the camera; must not be {@code null}
     * @param viewportWidth the viewport width
     * @param viewportHeight the viewport height
     * @param tilePixels the tile pixels
     * @param slices the slices
     * @param far the distance to the far plane
     * @param space the clip space whose y direction the grid follows; must not be {@code null}
     * @return a grid for a camera
     */
    public static ClusterGrid of(Cameraf camera, int viewportWidth, int viewportHeight, int tilePixels, int slices, float far, ClipSpace space) {
        return of(camera, viewportWidth, viewportHeight, tilePixels, slices, far, space.yDown());
    }

    /**
     * Creates a cluster grid from field of view, aspect ratio and depth range, for when no
     * {@link Cameraf} is at hand.
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param viewportWidth the viewport width
     * @param viewportHeight the viewport height
     * @param tilePixels the tile pixels
     * @param slices the slices
     * @param yDown whether y down
     * @return a grid from a vertical field of view (radians), an aspect ratio and the depth range
     */
    public static ClusterGrid of(float fovy, float aspect, float near, float far, int viewportWidth, int viewportHeight, int tilePixels, int slices, boolean yDown) {
        return new ClusterGrid(viewportWidth, viewportHeight, tilePixels, slices, yDown, false, near, far, fovy, aspect, 0f, 0f);
    }

    /**
     * Creates a cluster grid from field of view, aspect ratio and depth range for the clip space of
     * a graphics API.
     *
     * <p>As {@link #of(float, float, float, float, int, int, int, int, boolean)} with
     * {@code yDown} taken from {@code space}.
     *
     * @param fovy the vertical field of view in radians
     * @param aspect the aspect ratio, width divided by height
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param viewportWidth the viewport width
     * @param viewportHeight the viewport height
     * @param tilePixels the tile pixels
     * @param slices the slices
     * @param space the clip space whose y direction the grid follows; must not be {@code null}
     * @return a grid from a vertical field of view (radians), an aspect ratio and the depth range
     */
    public static ClusterGrid of(float fovy, float aspect, float near, float far, int viewportWidth, int viewportHeight, int tilePixels, int slices, ClipSpace space) {
        return of(fovy, aspect, near, far, viewportWidth, viewportHeight, tilePixels, slices, space.yDown());
    }

    /**
     * Creates a cluster grid for an orthographic camera and a viewport: tiles that are fixed
     * rectangles of the view box and slices that are linear in depth.
     *
     * <p>{@code far} is the far plane of the clusters (finite); the box and the near plane come
     * from the camera, which may have a near plane at zero or behind the camera.
     *
     * @param camera the orthographic camera; must not be {@code null}
     * @param viewportWidth the viewport width
     * @param viewportHeight the viewport height
     * @param tilePixels the tile size in pixels
     * @param slices the number of depth slices
     * @param far the distance to the far plane of the clusters, at most the camera's far plane is used
     * @param yDown whether pixel row 0 is at the top of the screen
     * @return an orthographic grid
     * @throws IllegalArgumentException if a size is not positive or the depth range is empty
     */
    public static ClusterGrid of(OrthoCameraf camera, int viewportWidth, int viewportHeight, int tilePixels, int slices, float far, boolean yDown) {
        return new ClusterGrid(viewportWidth, viewportHeight, tilePixels, slices, yDown, true, camera.near(), Math.min(far, camera.far()), camera.left(), camera.right(), camera.bottom(),
                camera.top());
    }

    /**
     * Creates a cluster grid for an orthographic camera and the clip space of a graphics API.
     *
     * <p>As {@link #of(OrthoCameraf, int, int, int, int, float, boolean)} with {@code yDown} taken
     * from {@code space}.
     *
     * @param camera the orthographic camera; must not be {@code null}
     * @param viewportWidth the viewport width
     * @param viewportHeight the viewport height
     * @param tilePixels the tile size in pixels
     * @param slices the number of depth slices
     * @param far the distance to the far plane of the clusters
     * @param space the clip space whose y direction the grid follows; must not be {@code null}
     * @return an orthographic grid
     */
    public static ClusterGrid of(OrthoCameraf camera, int viewportWidth, int viewportHeight, int tilePixels, int slices, float far, ClipSpace space) {
        return of(camera, viewportWidth, viewportHeight, tilePixels, slices, far, space.yDown());
    }

    /**
     * Tells whether the grid is orthographic.
     *
     * @return {@code true} for a grid made from an {@link OrthoCameraf}
     */
    public boolean orthographic() {
        return orthographic;
    }

    /**
     * Gives the box of an orthographic view.
     *
     * @param out receives {@code left, right, bottom, top} in view-space units
     * @throws IllegalStateException if the grid is perspective
     */
    public void viewBox(float[] out) {
        requireOrthographic();
        out[0] = viewLeft;
        out[1] = viewRight;
        out[2] = viewBottom;
        out[3] = viewTop;
    }

    /**
     * Gives the view-space edges of a tile of an orthographic grid, the counterpart of
     * {@link #slopes} (which is {@code x / depth} and does not exist without a perspective).
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @param out receives {@code xLow, xHigh, yLow, yHigh} at {@code out[offset..offset + 3]}
     * @param offset the index of the first element to write
     * @throws IllegalStateException if the grid is perspective
     */
    public void viewEdges(int column, int row, float[] out, int offset) {
        requireOrthographic();
        out[offset] = colLowSlope[column];
        out[offset + 1] = colHighSlope[column];
        out[offset + 2] = rowLowSlope[row];
        out[offset + 3] = rowHighSlope[row];
    }

    private void requireOrthographic() {
        if (!orthographic) {
            throw new IllegalStateException("only an orthographic grid has view-space tile edges");
        }
    }

    private void requirePerspective(String what) {
        if (orthographic) {
            throw new IllegalStateException(what + " has no meaning for an orthographic grid: tiles do not widen with depth (see viewBox and viewEdges)");
        }
    }

    /**
     * Counts the tile columns.
     *
     * @return the number of tile columns
     */
    public int tilesX() {
        return tilesX;
    }

    /**
     * Counts the tile rows.
     *
     * @return the number of tile rows
     */
    public int tilesY() {
        return tilesY;
    }

    /**
     * Counts the depth slices.
     *
     * @return the number of depth slices
     */
    public int slices() {
        return slices;
    }

    /**
     * Counts the clusters of the grid.
     *
     * @return the total number of clusters: {@code tilesX * tilesY * slices}
     */
    public int clusterCount() {
        return tilesX * tilesY * slices;
    }

    /**
     * Exposes the tile size in pixels.
     *
     * @return the side of a tile in pixels
     */
    public int tilePixels() {
        return tilePixels;
    }

    /**
     * Exposes the viewport width in pixels.
     *
     * @return the viewport width in pixels
     */
    public int viewportWidth() {
        return viewportWidth;
    }

    /**
     * Exposes the viewport height in pixels.
     *
     * @return the viewport height in pixels
     */
    public int viewportHeight() {
        return viewportHeight;
    }

    /**
     * Exposes the depth at which the first slice starts.
     *
     * @return the view distance where the first slice starts
     */
    public float near() {
        return near;
    }

    /**
     * Exposes the depth at which the last slice ends.
     *
     * @return the view distance where the last slice ends
     */
    public float far() {
        return far;
    }

    /**
     * Returns whether pixel row 0 is at the top of the screen (NDC y = +1), as in a Vulkan-style
     * framebuffer.
     *
     * @return {@code true} if pixel row 0 is at the top of the screen (NDC y = +1), as in a
     *     Vulkan-style framebuffer
     */
    public boolean yDown() {
        return yDown;
    }

    /**
     * Exposes the horizontal half-extent per unit of depth, which is used to place the tile planes.
     *
     * @return {@code tan(fovy / 2) * aspect}: the view-space x extent per unit of depth at the edge
     *     of the screen
     */
    public float tanHalfFovX() {
        requirePerspective("tanHalfFovX");
        return tanX;
    }

    /**
     * Exposes the vertical half-extent per unit of depth, which is used to place the tile planes.
     *
     * @return {@code tan(fovy / 2)}: the view-space y extent per unit of depth at the edge of the
     *     screen
     */
    public float tanHalfFovY() {
        requirePerspective("tanHalfFovY");
        return tanY;
    }

    /**
     * Exposes the multiplier of the logarithmic depth slicing, which shaders need to find a
     * fragment's slice.
     *
     * @return the multiplier of {@code ln(depth)} in the slice formula
     *     {@code floor(ln(depth) * sliceScale + sliceBias)}
     */
    public float sliceScale() {
        return sliceScale;
    }

    /**
     * Exposes the offset of the logarithmic depth slicing, which shaders need to find a fragment's
     * slice.
     *
     * @return the constant in the slice formula {@code floor(ln(depth) * sliceScale + sliceBias)}
     */
    public float sliceBias() {
        return sliceBias;
    }

    /**
     * Computes the flat index of a cluster from its column, row and slice.
     *
     * <p>The arguments are not checked.
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @param slice the slice
     * @return the linear index of a cluster: {@code (slice * tilesY + row) * tilesX + column}
     */
    public int index(int column, int row, int slice) {
        return (slice * tilesY + row) * tilesX + column;
    }

    /**
     * Computes the depth at which a slice starts, from the exponential slicing.
     *
     * @param k the boundary index, from 0 to the number of slices
     * @return depth of boundary {@code k}, between slice {@code k - 1} and slice {@code k}:
     *     boundary 0 is {@code near}, boundary {@code slices} is {@code far}
     */
    public float sliceBoundary(int k) {
        return sliceDepth[k];
    }

    /**
     * Finds the slice for a depth with the logarithmic slicing formula; the result is clamped to
     * the valid slices.
     *
     * <p>NaN gives slice 0.
     *
     * @param depth the depth
     * @return the slice of a depth (distance along the view direction), clamped to
     *     {@code [0, slices - 1]}
     */
    public int sliceOf(float depth) {
        if (!(depth > near)) {
            return 0;
        }
        int s = (int) Math.floor((orthographic ? depth : Math.log(depth)) * sliceScale + sliceBias);
        return s < 0 ? 0 : Math.min(s, slices - 1);
    }

    /**
     * Finds the slice for a depth stored in a depth buffer by first converting it back to view
     * distance, for whatever depth convention the camera uses.
     *
     * @param camera the camera; must not be {@code null}
     * @param ndcDepth the ndc depth
     * @return the slice that an NDC depth value of {@code camera}'s projection falls in (any depth
     *     convention, finite or infinite far plane)
     */
    public int sliceOfNdcDepth(Cameraf camera, float ndcDepth) {
        return sliceOf(camera.linearizeDepth(ndcDepth));
    }

    /**
     * Finds the slice for a depth stored in a depth buffer of an orthographic camera (depth is
     * linear there).
     *
     * @param camera the orthographic camera; must not be {@code null}
     * @param ndcDepth the ndc depth
     * @return the slice that an NDC depth value of {@code camera}'s projection falls in
     */
    public int sliceOfNdcDepth(OrthoCameraf camera, float ndcDepth) {
        return sliceOf(camera.linearizeDepth(ndcDepth));
    }

    /**
     * Finds the tile column of a pixel x coordinate; the result is clamped to the grid.
     *
     * @param x the x component
     * @return column of a pixel x coordinate, clamped to the grid
     */
    public int columnOfPixel(float x) {
        int c = (int) (x / tilePixels);
        return c < 0 ? 0 : Math.min(c, tilesX - 1);
    }

    /**
     * Finds the tile row of a pixel y coordinate; the result is clamped to the grid.
     *
     * @param y the y component
     * @return row of a pixel y coordinate (counted in the direction the pixel coordinates run),
     *     clamped to the grid
     */
    public int rowOfPixel(float y) {
        int r = (int) (y / tilePixels);
        return r < 0 ? 0 : Math.min(r, tilesY - 1);
    }

    /**
     * Finds the cluster of a fragment from its pixel coordinates and its view depth, the lookup a
     * lighting shader performs.
     *
     * @param pixelX the pixel x
     * @param pixelY the pixel y
     * @param depth the depth
     * @return the cluster of a fragment: pixel coordinates (as {@code gl_FragCoord.xy}) and the
     *     depth along the view direction
     */
    public int clusterOf(float pixelX, float pixelY, float depth) {
        return index(columnOfPixel(pixelX), rowOfPixel(pixelY), sliceOf(depth));
    }

    /**
     * Finds the cluster of a position that is already in view space, and rejects positions that no
     * cluster can contain.
     *
     * <p>Depths beyond {@code far} and before {@code near} clamp.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return the cluster of a view-space position, or -1 when it is behind the camera or outside
     *     the field of view
     */
    public int clusterOfViewPosition(float x, float y, float z) {
        float depth = -z;
        float ndcX, ndcY;
        if (orthographic) {
            ndcX = (x - viewLeft) / (viewRight - viewLeft) * 2f - 1f;
            ndcY = (y - viewBottom) / (viewTop - viewBottom) * 2f - 1f;
        } else if (!(depth > 0f)) {
            return -1;
        } else {
            ndcX = x / (depth * tanX);
            ndcY = y / (depth * tanY);
        }
        if (!(ndcX >= -1f && ndcX <= 1f && ndcY >= -1f && ndcY <= 1f)) {
            return -1;
        }
        float px = (ndcX * 0.5f + 0.5f) * viewportWidth;
        float py = yDown ? (0.5f - ndcY * 0.5f) * viewportHeight : (ndcY * 0.5f + 0.5f) * viewportHeight;
        return index(columnOfPixel(px), rowOfPixel(py), sliceOf(depth));
    }

    /**
     * Computes the NDC x range {@code [lo, hi]} of a tile column and writes it to
     * {@code out[0..1]}.
     *
     * @param column the column, counted from 0
     * @param out receives the result in {@code [0, 2)}
     */
    public void columnRange(int column, float[] out) {
        out[0] = columnNdc[column];
        out[1] = columnNdc[column + 1];
    }

    /**
     * Computes the NDC y range {@code [lo, hi]} of a tile row (with {@code yDown} row 0 is the top,
     * so its range is the highest) and writes it to {@code out[0..1]}.
     *
     * @param row the row, counted from 0
     * @param out receives the result in {@code [0, 2)}
     */
    public void rowRange(int row, float[] out) {
        out[0] = rowLow(row);
        out[1] = rowHigh(row);
    }

    private float rowLow(int row) {
        return yDown ? 1f - 2f * Math.min((row + 1) * tilePixels, viewportHeight) / viewportHeight : -1f + 2f * Math.min(row * tilePixels, viewportHeight) / viewportHeight;
    }

    private float rowHigh(int row) {
        return yDown ? 1f - 2f * Math.min(row * tilePixels, viewportHeight) / viewportHeight : -1f + 2f * Math.min((row + 1) * tilePixels, viewportHeight) / viewportHeight;
    }

    /**
     * Computes the view-space box around a cluster: x and y from the tile edges at the two slice
     * depths (the bounding box of the frustum slice, so a little larger than it), z from
     * {@code -far side} to {@code -near side}.
     *
     * <p>Writes {@code minX, minY, minZ, maxX, maxY, maxZ} to {@code out[offset..offset + 5]}.
     *
     * @param cluster the cluster index
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void bounds(int cluster, float[] out, int offset) {
        int column = cluster % tilesX, row = (cluster / tilesX) % tilesY, slice = cluster / (tilesX * tilesY);
        bounds(column, row, slice, sliceDepth[slice], sliceDepth[slice + 1], out, offset);
    }

    /**
     * As {@link #bounds(int, float[], int)} for explicit depths of the two sides (used when a
     * tile's own depth range replaces the slice, see {@link ClusterLights#assignTiled}).
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @param slice the slice
     * @param depthNear the depth near
     * @param depthFar the depth far
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void bounds(int column, int row, int slice, float depthNear, float depthFar, float[] out, int offset) {
        float xl = colLowSlope[column], xh = colHighSlope[column], yl = rowLowSlope[row], yh = rowHighSlope[row];
        if (orthographic) {
            out[offset] = xl;
            out[offset + 1] = yl;
            out[offset + 2] = -depthFar;
            out[offset + 3] = xh;
            out[offset + 4] = yh;
            out[offset + 5] = -depthNear;
            return;
        }
        out[offset] = xl >= 0f ? xl * depthNear : xl * depthFar;
        out[offset + 1] = yl >= 0f ? yl * depthNear : yl * depthFar;
        out[offset + 2] = -depthFar;
        out[offset + 3] = xh >= 0f ? xh * depthFar : xh * depthNear;
        out[offset + 4] = yh >= 0f ? yh * depthFar : yh * depthNear;
        out[offset + 5] = -depthNear;
    }

    /**
     * Computes the four side planes of a tile as slopes: {@code x / depth} at the left and right
     * edge and {@code y / depth} at the bottom and top edge, written to
     * {@code out[offset..offset + 3]} as {@code xLow, xHigh, yLow, yHigh}.
     *
     * <p>The tile is the set of points whose slopes lie between them, so its side planes are
     * {@code x = slope * depth} through the camera.
     *
     * @param column the column, counted from 0
     * @param row the row, counted from 0
     * @param out receives the result
     * @param offset the index of the first element to read or write
     */
    public void slopes(int column, int row, float[] out, int offset) {
        requirePerspective("slopes");
        out[offset] = colLowSlope[column];
        out[offset + 1] = colHighSlope[column];
        out[offset + 2] = rowLowSlope[row];
        out[offset + 3] = rowHighSlope[row];
    }

    /**
     * Generates GLSL source for the fragment-side lookup, using the same formulas as
     * {@link #clusterOf} so that CPU and GPU agree; the constants are baked in, so regenerate the
     * text when the grid changes.
     *
     * <p>{@code fragCoord} must be in the pixel convention the grid was built for
     * ({@link #yDown()}) and {@code viewDepth} is the distance along the view direction, for
     * example {@code -viewPosition.z}. Compiled (with glslang, in {@code ShaderCompileTest}) but
     * never run; the other tests check the text against the numbers.
     *
     * @return GLSL for the fragment-side lookup of this grid: constants and a function
     *     {@code clusterIndex(fragCoord, viewDepth)} that compute {@link #clusterOf} with the same
     *     formulas (one logarithm for the slice)
     */
    public String glslLookup() {
        if (orthographic) {
            return "const uvec3 CLUSTER_GRID = uvec3(" + tilesX + "u, " + tilesY + "u, " + slices + "u);\n"
                    + "const float CLUSTER_TILE = " + glslFloat(tilePixels) + ";\n"
                    + "const float CLUSTER_SLICE_SCALE = " + glslFloat(sliceScale) + ";\n"
                    + "const float CLUSTER_SLICE_BIAS = " + glslFloat(sliceBias) + ";\n"
                    + "uint clusterIndex(vec2 fragCoord, float viewDepth) {\n"
                    + "    uvec2 tile = min(uvec2(fragCoord / CLUSTER_TILE), CLUSTER_GRID.xy - 1u);\n"
                    + "    float slice = clamp(floor(viewDepth * CLUSTER_SLICE_SCALE + CLUSTER_SLICE_BIAS), 0.0, float(CLUSTER_GRID.z) - 1.0);\n"
                    + "    return (uint(slice) * CLUSTER_GRID.y + tile.y) * CLUSTER_GRID.x + tile.x;\n"
                    + "}\n";
        }
        return "const uvec3 CLUSTER_GRID = uvec3(" + tilesX + "u, " + tilesY + "u, " + slices + "u);\n"
                + "const float CLUSTER_TILE = " + glslFloat(tilePixels) + ";\n"
                + "const float CLUSTER_SLICE_SCALE = " + glslFloat(sliceScale) + ";\n"
                + "const float CLUSTER_SLICE_BIAS = " + glslFloat(sliceBias) + ";\n"
                + "uint clusterIndex(vec2 fragCoord, float viewDepth) {\n"
                + "    uvec2 tile = min(uvec2(fragCoord / CLUSTER_TILE), CLUSTER_GRID.xy - 1u);\n"
                + "    float slice = clamp(floor(log(viewDepth) * CLUSTER_SLICE_SCALE + CLUSTER_SLICE_BIAS), 0.0, float(CLUSTER_GRID.z) - 1.0);\n"
                + "    return (uint(slice) * CLUSTER_GRID.y + tile.y) * CLUSTER_GRID.x + tile.x;\n"
                + "}\n";
    }

    private static String glslFloat(float v) {
        String t = Float.toString(v);
        return t.contains(".") || t.contains("E") || t.contains("N") || t.contains("I") ? t : t + ".0";
    }

    /**
     * Fills {@code out} (six floats per cluster) with the bounds of every cluster: the buffer a
     * compute shader would build or a CPU-side light assignment reads.
     *
     * @param out receives the result
     */
    public void fillBounds(float[] out) {
        int n = clusterCount();
        for (int c = 0; c < n; c++) {
            bounds(c, out, c * 6);
        }
    }
}
