package vmath.core;

/**
 * A floating origin: a point in a world held in {@code double} that follows the camera in whole
 * steps of a grid, so that the data the GPU sees can be kept as {@code float} relative to it and
 * only needs to be shifted when the origin jumps.
 *
 * <p>Subtracting the camera position from every object each frame ({@link Rebase}) is always
 * correct but touches everything every frame. A floating origin trades that for a rare shift:
 * float data is kept relative to {@link #origin()}, and {@link #update} moves the origin to the
 * nearest grid point only when the camera has moved farther from it than a threshold. Then
 * {@link #lastShift()} says by how much the origin moved, and the relative data is shifted by that
 * ({@link Rebase#shiftPositions}). The origin always lies on the grid, so a cell size that is a
 * power of two makes the shifts exact in {@code float}.
 *
 * <p>The threshold is larger than the cell, so a camera that has just caused a shift sits close to
 * the new origin and a camera hovering at the border does not cause a shift every frame.
 *
 * <p><b>Thread safety.</b> Not thread-safe: {@link #update} changes the state, so call it from one
 * thread, once per frame, and read the results from the same thread or after a safe hand-off.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * FloatingOrigin origin = new FloatingOrigin(1024.0, 4096.0);                // snaps to multiples of 1024
 * float[] positions = {10f, 0f, 0f};                                         // relative to origin.origin()
 * if (origin.update(new Vec3d(5000.0, 0.0, 0.0))) {                          // the camera has gone far enough
 *     Rebase.shiftPositions(positions, 0, 1, origin.lastShift());            // the relative data follows the origin
 * }
 * Vec3f local = origin.toLocal(new Vec3d(5010.0, 0.0, 0.0));                 // a world position, relative to the new origin
 * }</pre>
 */
public final class FloatingOrigin {

    private final double cell;
    private final double threshold;
    private double ox;
    private double oy;
    private double oz;
    private double shiftX;
    private double shiftY;
    private double shiftZ;
    private long generation;

    /**
     * Creates a floating origin at the world origin.
     *
     * @param cellSize the grid the origin snaps to, in world units; a power of two keeps the shifts
     *     exact in {@code float}
     * @param threshold how far the camera may be from the origin, on any axis, before the origin
     *     moves; at least the cell size
     * @throws IllegalArgumentException if {@code cellSize} is not positive and finite, or
     *     {@code threshold} is smaller than {@code cellSize} or not finite
     */
    public FloatingOrigin(double cellSize, double threshold) {
        if (!(cellSize > 0.0) || Double.isInfinite(cellSize)) {
            throw new IllegalArgumentException("the cell size must be positive and finite: " + cellSize);
        }
        if (!(threshold >= cellSize) || Double.isInfinite(threshold)) {
            throw new IllegalArgumentException("the threshold must be finite and at least the cell size " + cellSize + ": " + threshold);
        }
        this.cell = cellSize;
        this.threshold = threshold;
    }

    /**
     * Follows the camera: moves the origin to the grid point nearest to the camera when the camera
     * is farther from the origin than the threshold on any axis, and does nothing otherwise.
     *
     * <p>A position that is not finite changes nothing. The movement is available from
     * {@link #lastShift()} until the next call.
     *
     * @param camera the camera position in world coordinates; must not be {@code null}
     * @return {@code true} if the origin moved
     */
    public boolean update(Vec3d camera) {
        shiftX = 0.0;
        shiftY = 0.0;
        shiftZ = 0.0;
        if (!camera.isFinite()) {
            return false;
        }
        if (Math.abs(camera.x() - ox) <= threshold && Math.abs(camera.y() - oy) <= threshold && Math.abs(camera.z() - oz) <= threshold) {
            return false;
        }
        double nx = Math.rint(camera.x() / cell) * cell;
        double ny = Math.rint(camera.y() / cell) * cell;
        double nz = Math.rint(camera.z() / cell) * cell;
        shiftX = nx - ox;
        shiftY = ny - oy;
        shiftZ = nz - oz;
        ox = nx;
        oy = ny;
        oz = nz;
        generation++;
        return true;
    }

    /**
     * Reads the current origin.
     *
     * @return the origin in world coordinates, always on the grid
     */
    public Vec3d origin() {
        return new Vec3d(ox, oy, oz);
    }

    /**
     * Reads how far the last {@link #update} moved the origin, which is what the data that is
     * relative to the origin has to be shifted by.
     *
     * @return the new origin minus the old one; zero if the last update did not move it
     */
    public Vec3d lastShift() {
        return new Vec3d(shiftX, shiftY, shiftZ);
    }

    /**
     * Counts how often the origin has moved, which lets a consumer notice that it missed a shift.
     *
     * @return the number of updates that moved the origin
     */
    public long generation() {
        return generation;
    }

    /**
     * Converts a world position to a position relative to the origin, subtracting in double.
     *
     * @param world the position in world coordinates; must not be {@code null}
     * @return the position relative to {@link #origin()}, narrowed to {@code float}
     */
    public Vec3f toLocal(Vec3d world) {
        return new Vec3f((float) (world.x() - ox), (float) (world.y() - oy), (float) (world.z() - oz));
    }

    /**
     * Converts a position relative to the origin back to world coordinates.
     *
     * @param local the position relative to {@link #origin()}; must not be {@code null}
     * @return the position in world coordinates, in double
     */
    public Vec3d toWorld(Vec3f local) {
        return new Vec3d(ox + local.x(), oy + local.y(), oz + local.z());
    }
}
