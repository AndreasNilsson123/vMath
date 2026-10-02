package vmath.camera;

import vmath.annotations.Experimental;

/**
 * The cluster grid of clustered (and tiled) forward lighting: the view frustum cut into tiles in x and y and into slices in depth, so that every fragment finds the
 * few lights that can reach it from one lookup. This class is the arithmetic only (grid size, which cluster a view-space position or a pixel and depth falls in, the
 * bounds of every cluster); {@link ClusterLights} assigns lights to the clusters and the formulas here are the ones the GLSL in {@code docs/CAMERA.md} uses.
 *
 * <p><b>Slices.</b> Depth is the distance along the view direction, positive in front of the camera. The slices are exponential,
 * {@code boundary(k) = near * (far / near)^(k / slices)}, so each slice is the same factor deeper than the one before (about the same screen-space proportions), and a
 * fragment finds its slice with {@code floor(ln(depth) * sliceScale + sliceBias)}, one logarithm. Depths outside {@code [near, far]} clamp to the first and last slice.
 *
 * <p><b>Tiles.</b> A tile is {@code tilePixels} wide and high (the last row and column may be smaller). Tile rows are counted in the direction pixel coordinates
 * run: with {@code yDown} (Vulkan, D3D window coordinates) row 0 is the top of the screen, otherwise the bottom (OpenGL {@code gl_FragCoord}).
 *
 * <p><b>Cluster index</b> is {@code (slice * tilesY + row) * tilesX + column}, slices outermost, so the clusters of one depth slice are contiguous.
 *
 * <p>Perspective projections only. Everything is view space (x right, y up, z negative forward, like {@link Cameraf#viewPositionFromDepth}).
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads freely. The arrays it hands out are its own storage: do
 * not modify them.
 */
@Experimental("the grid layout and the set of helpers may change")
public final class ClusterGrid {

    private final int viewportWidth, viewportHeight, tilePixels, tilesX, tilesY, slices;
    private final boolean yDown;
    private final float near, far, tanX, tanY, sliceScale, sliceBias;
    private final float[] sliceDepth;   // slices + 1 boundaries
    private final float[] columnNdc;    // tilesX + 1 ndc x edges, increasing
    private final float[] colLowSlope, colHighSlope, rowLowSlope, rowHighSlope; // x / depth and y / depth at the tile edges

    private ClusterGrid(int width, int height, int tile, int slices, boolean yDown, float fovy, float aspect, float near, float far) {
        if (width < 1 || height < 1 || tile < 1 || slices < 1) {
            throw new IllegalArgumentException("viewport, tile size and slice count must be positive");
        }
        if (!(fovy > 0f && fovy < (float) Math.PI) || !(aspect > 0f) || !(near > 0f) || !(far > near) || Float.isInfinite(far)) {
            throw new IllegalArgumentException("need 0 < fovy < pi, aspect > 0 and 0 < near < far < infinity: " + fovy + ", " + aspect + ", " + near + ", " + far);
        }
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.tilePixels = tile;
        this.tilesX = (width + tile - 1) / tile;
        this.tilesY = (height + tile - 1) / tile;
        this.slices = slices;
        this.yDown = yDown;
        this.near = near;
        this.far = far;
        this.tanY = (float) Math.tan(fovy * 0.5f);
        this.tanX = tanY * aspect;
        double logRatio = Math.log((double) far / near);
        this.sliceScale = (float) (slices / logRatio);
        this.sliceBias = (float) (-slices * Math.log(near) / logRatio);
        sliceDepth = new float[slices + 1];
        for (int k = 0; k <= slices; k++) {
            sliceDepth[k] = k == slices ? far : (float) (near * Math.pow((double) far / near, (double) k / slices));
        }
        columnNdc = new float[tilesX + 1];
        for (int i = 0; i <= tilesX; i++) {
            columnNdc[i] = -1f + 2f * Math.min(i * tile, width) / width;
        }
        colLowSlope = new float[tilesX];
        colHighSlope = new float[tilesX];
        for (int i = 0; i < tilesX; i++) {
            colLowSlope[i] = columnNdc[i] * tanX;
            colHighSlope[i] = columnNdc[i + 1] * tanX;
        }
        rowLowSlope = new float[tilesY];
        rowHighSlope = new float[tilesY];
        for (int j = 0; j < tilesY; j++) {
            rowLowSlope[j] = rowLow(j) * tanY;
            rowHighSlope[j] = rowHigh(j) * tanY;
        }
    }

    /**
     * A grid for a camera. {@code far} is the far plane of the clusters (finite, usually the shadow or light range rather than an infinite camera far plane); the near
     * plane and field of view come from the camera.
     */
    public static ClusterGrid of(Cameraf camera, int viewportWidth, int viewportHeight, int tilePixels, int slices, float far, boolean yDown) {
        return new ClusterGrid(viewportWidth, viewportHeight, tilePixels, slices, yDown, camera.fovy(), camera.aspect(), camera.near(), Math.min(far, camera.far()));
    }

    /** A grid from a vertical field of view (radians), an aspect ratio and the depth range. */
    public static ClusterGrid of(float fovy, float aspect, float near, float far, int viewportWidth, int viewportHeight, int tilePixels, int slices, boolean yDown) {
        return new ClusterGrid(viewportWidth, viewportHeight, tilePixels, slices, yDown, fovy, aspect, near, far);
    }

    public int tilesX() {
        return tilesX;
    }

    public int tilesY() {
        return tilesY;
    }

    public int slices() {
        return slices;
    }

    public int clusterCount() {
        return tilesX * tilesY * slices;
    }

    public int tilePixels() {
        return tilePixels;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public float near() {
        return near;
    }

    public float far() {
        return far;
    }

    public boolean yDown() {
        return yDown;
    }

    /** {@code tan(fovy / 2) * aspect}: the view-space x extent per unit of depth at the edge of the screen. */
    public float tanHalfFovX() {
        return tanX;
    }

    public float tanHalfFovY() {
        return tanY;
    }

    /** The multiplier of {@code ln(depth)} in the slice formula {@code floor(ln(depth) * sliceScale + sliceBias)}. */
    public float sliceScale() {
        return sliceScale;
    }

    public float sliceBias() {
        return sliceBias;
    }

    public int index(int column, int row, int slice) {
        return (slice * tilesY + row) * tilesX + column;
    }

    /** Depth of boundary {@code k}, between slice {@code k - 1} and slice {@code k}: boundary 0 is {@code near}, boundary {@code slices} is {@code far}. */
    public float sliceBoundary(int k) {
        return sliceDepth[k];
    }

    /** The slice of a depth (distance along the view direction), clamped to {@code [0, slices - 1]}. NaN gives slice 0. */
    public int sliceOf(float depth) {
        if (!(depth > near)) {
            return 0;
        }
        int s = (int) Math.floor(Math.log(depth) * sliceScale + sliceBias);
        return s < 0 ? 0 : Math.min(s, slices - 1);
    }

    /** The slice that an NDC depth value of {@code camera}'s projection falls in (any depth convention, finite or infinite far plane). */
    public int sliceOfNdcDepth(Cameraf camera, float ndcDepth) {
        return sliceOf(camera.linearizeDepth(ndcDepth));
    }

    /** Column of a pixel x coordinate, clamped to the grid. */
    public int columnOfPixel(float x) {
        int c = (int) (x / tilePixels);
        return c < 0 ? 0 : Math.min(c, tilesX - 1);
    }

    /** Row of a pixel y coordinate (counted in the direction the pixel coordinates run), clamped to the grid. */
    public int rowOfPixel(float y) {
        int r = (int) (y / tilePixels);
        return r < 0 ? 0 : Math.min(r, tilesY - 1);
    }

    /** The cluster of a fragment: pixel coordinates (as {@code gl_FragCoord.xy}) and the depth along the view direction. */
    public int clusterOf(float pixelX, float pixelY, float depth) {
        return index(columnOfPixel(pixelX), rowOfPixel(pixelY), sliceOf(depth));
    }

    /** The cluster of a view-space position, or -1 when it is behind the camera or outside the field of view. Depths beyond {@code far} and before {@code near} clamp. */
    public int clusterOfViewPosition(float x, float y, float z) {
        float depth = -z;
        if (!(depth > 0f)) {
            return -1;
        }
        float ndcX = x / (depth * tanX), ndcY = y / (depth * tanY);
        if (!(ndcX >= -1f && ndcX <= 1f && ndcY >= -1f && ndcY <= 1f)) {
            return -1;
        }
        float px = (ndcX * 0.5f + 0.5f) * viewportWidth;
        float py = yDown ? (0.5f - ndcY * 0.5f) * viewportHeight : (ndcY * 0.5f + 0.5f) * viewportHeight;
        return index(columnOfPixel(px), rowOfPixel(py), sliceOf(depth));
    }

    /** NDC x range {@code [lo, hi]} of a tile column, written to {@code out[0..1]}. */
    public void columnRange(int column, float[] out) {
        out[0] = columnNdc[column];
        out[1] = columnNdc[column + 1];
    }

    /** NDC y range {@code [lo, hi]} of a tile row (with {@code yDown} row 0 is the top, so its range is the highest), written to {@code out[0..1]}. */
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
     * The view-space box around a cluster: x and y from the tile edges at the two slice depths (the bounding box of the frustum slice, so a little larger than it),
     * z from {@code -far side} to {@code -near side}. Writes {@code minX, minY, minZ, maxX, maxY, maxZ} to {@code out[offset..offset + 5]}.
     */
    public void bounds(int cluster, float[] out, int offset) {
        int column = cluster % tilesX, row = (cluster / tilesX) % tilesY, slice = cluster / (tilesX * tilesY);
        bounds(column, row, slice, sliceDepth[slice], sliceDepth[slice + 1], out, offset);
    }

    /** As {@link #bounds(int, float[], int)} for explicit depths of the two sides (used when a tile's own depth range replaces the slice, see {@link ClusterLights#assignTiled}). */
    public void bounds(int column, int row, int slice, float depthNear, float depthFar, float[] out, int offset) {
        float xl = colLowSlope[column], xh = colHighSlope[column], yl = rowLowSlope[row], yh = rowHighSlope[row];
        out[offset] = xl >= 0f ? xl * depthNear : xl * depthFar;
        out[offset + 1] = yl >= 0f ? yl * depthNear : yl * depthFar;
        out[offset + 2] = -depthFar;
        out[offset + 3] = xh >= 0f ? xh * depthFar : xh * depthNear;
        out[offset + 4] = yh >= 0f ? yh * depthFar : yh * depthNear;
        out[offset + 5] = -depthNear;
    }

    /**
     * The four side planes of a tile as slopes: {@code x / depth} at the left and right edge and {@code y / depth} at the bottom and top edge, written to
     * {@code out[offset..offset + 3]} as {@code xLow, xHigh, yLow, yHigh}. The tile is the set of points whose slopes lie between them, so its side planes are
     * {@code x = slope * depth} through the camera.
     */
    public void slopes(int column, int row, float[] out, int offset) {
        out[offset] = colLowSlope[column];
        out[offset + 1] = colHighSlope[column];
        out[offset + 2] = rowLowSlope[row];
        out[offset + 3] = rowHighSlope[row];
    }

    /**
     * GLSL for the fragment-side lookup of this grid: constants and a function {@code clusterIndex(fragCoord, viewDepth)} that compute {@link #clusterOf} with the same
     * formulas (one logarithm for the slice). {@code fragCoord} must be in the pixel convention the grid was built for ({@link #yDown()}) and {@code viewDepth} is the
     * distance along the view direction, for example {@code -viewPosition.z}. Not compiled or run by the library's tests, which check the text against the numbers.
     */
    public String glslLookup() {
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

    /** Fills {@code out} (six floats per cluster) with the bounds of every cluster: the buffer a compute shader would build or a CPU-side light assignment reads. */
    public void fillBounds(float[] out) {
        int n = clusterCount();
        for (int c = 0; c < n; c++) {
            bounds(c, out, c * 6);
        }
    }
}
