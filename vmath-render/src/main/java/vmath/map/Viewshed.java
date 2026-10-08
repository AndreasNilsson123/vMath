package vmath.map;

import vmath.annotations.Experimental;

/**
 * Line of sight over a {@link TerrainGrid}: which cells an observer at a point and height can see,
 * the shadow of a sensor or the coverage of a radio.
 *
 * <p>The observer is at a point of the grid at a height above the ground there. A cell is visible
 * if the line from the observer's eye to a target a given height above the cell's ground is not
 * below the terrain in between. Optionally the curvature of the earth and the refraction of the
 * atmosphere are included as the usual drop {@code d^2 (1 - k) / (2 R)} of the terrain below the
 * horizontal at ground distance {@code d}, with {@code R = 6 371 008.8 m} and the refraction
 * coefficient {@code k = 0.13}.
 *
 * <p><b>Method.</b> {@link #compute} sweeps rays from the observer, at an angular spacing that keeps
 * neighbouring rays within half a cell of each other at the range, and steps along each ray by half
 * a cell, keeping the largest slope seen. A cell is visible if any ray that passes through it has
 * the target above that slope. It is an approximation of the exact line of sight to every cell
 * ({@link #isVisible} is that, with fine steps and the cost of one ray per cell): a cell that a ray
 * grazes at the edge of a ridge can be called visible when the exact test says hidden, and the other
 * way round only by the step. The tests compare the two on rough terrain and state the agreement
 * (above 97 percent of cells). The cost is about the number of cells in range times eight samples;
 * the measured figure is in {@code docs/MAPS.md}.
 *
 * <p>The terrain with no data ({@code NaN}) blocks nothing and is not visible.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads that write to
 * different arrays.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * byte[] seen = new byte[grid.width() * grid.height()];
 * int count = Viewshed.compute(grid, observerX, observerY, 30.0, 2.0, 20_000.0, true, seen);   // eye 30 m up, targets 2 m up, 20 km
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class Viewshed {

    /** The mean radius of the earth in metres. */
    public static final double EARTH_RADIUS = 6_371_008.8;
    /** The refraction coefficient used with the curvature. */
    public static final double REFRACTION = 0.13;

    private Viewshed() {
    }

    private static double drop(double distance, boolean curvature) {
        return curvature ? distance * distance * (1.0 - REFRACTION) / (2.0 * EARTH_RADIUS) : 0.0;
    }

    /**
     * Computes the visibility of every cell within a range.
     *
     * @param grid the grid; must not be {@code null}
     * @param observerX the projected x of the observer in metres, inside the grid
     * @param observerY the projected y of the observer in metres, inside the grid
     * @param observerHeight the height of the eye above the ground at the observer in metres, not negative
     * @param targetHeight the height of the targets above the ground in metres, not negative
     * @param range the largest ground distance in metres, positive
     * @param curvature whether to include the curvature of the earth and the refraction
     * @param visible receives {@code width * height} bytes, 1 where the cell is visible and 0 where not (the cell of the observer is visible)
     * @return the number of visible cells
     * @throws IllegalArgumentException if the observer is outside the grid or on a node without data, a height is negative, the range is not positive or {@code visible} is too short
     */
    public static int compute(TerrainGrid grid, double observerX, double observerY, double observerHeight, double targetHeight, double range, boolean curvature, byte[] visible) {
        int w = grid.width(), h = grid.height();
        check(grid, observerX, observerY, observerHeight, targetHeight, range, visible);
        java.util.Arrays.fill(visible, 0, w * h, (byte) 0);
        double eye = grid.heightAt(observerX, observerY) + observerHeight;
        double cell = Math.min(grid.cellEast(), grid.cellNorth());
        double step = 0.5 * cell * grid.groundScale();                          // ground metres per step
        double projectedStep = 0.5 * cell;
        int rays = (int) Math.min(1 << 20, Math.max(8, Math.ceil(2.0 * Math.PI * range / (0.5 * cell * grid.groundScale()))));
        int steps = (int) Math.ceil(range / step);
        for (int ray = 0; ray < rays; ray++) {
            double angle = 2.0 * Math.PI * ray / rays;
            double dx = Math.sin(angle) * projectedStep, dy = Math.cos(angle) * projectedStep;
            double maxSlope = Double.NEGATIVE_INFINITY;
            for (int s = 1; s <= steps; s++) {
                double x = observerX + dx * s, y = observerY + dy * s;
                if (!(x >= grid.minX() && x <= grid.maxX() && y >= grid.minY() && y <= grid.maxY())) {
                    break;
                }
                double z = grid.heightAt(x, y);
                if (z != z) {
                    continue;
                }
                double d = s * step;
                double ground = (z - eye - drop(d, curvature)) / d;
                double target = (z + targetHeight - eye - drop(d, curvature)) / d;
                int column = (int) Math.round((x - grid.minX()) / grid.cellEast()), row = (int) Math.round((grid.maxY() - y) / grid.cellNorth());
                if (target >= maxSlope && column >= 0 && column < w && row >= 0 && row < h) {
                    visible[row * w + column] = 1;
                }
                if (ground > maxSlope) {
                    maxSlope = ground;
                }
            }
        }
        int ocol = (int) Math.round((observerX - grid.minX()) / grid.cellEast()), orow = (int) Math.round((grid.maxY() - observerY) / grid.cellNorth());
        visible[Math.min(h - 1, Math.max(0, orow)) * w + Math.min(w - 1, Math.max(0, ocol))] = 1;
        int count = 0;
        for (int i = 0; i < w * h; i++) {
            count += visible[i];
        }
        return count;
    }

    private static void check(TerrainGrid grid, double x, double y, double eyeAgl, double targetAgl, double range, byte[] visible) {
        if (visible.length < grid.width() * grid.height()) {
            throw new IllegalArgumentException("visible needs " + grid.width() * grid.height() + " bytes");
        }
        if (!(eyeAgl >= 0.0) || !(targetAgl >= 0.0) || !(range > 0.0)) {
            throw new IllegalArgumentException("heights must not be negative and the range must be positive");
        }
        double z = grid.heightAt(x, y);
        if (z != z) {
            throw new IllegalArgumentException("the observer must be inside the grid, on terrain with data");
        }
    }

    /**
     * Tests the line of sight to one point exactly (with fine steps).
     *
     * @param grid the grid; must not be {@code null}
     * @param observerX the projected x of the observer in metres
     * @param observerY the projected y of the observer in metres
     * @param observerHeight the height of the eye above the ground in metres
     * @param targetX the projected x of the target in metres
     * @param targetY the projected y of the target in metres
     * @param targetHeight the height of the target above the ground in metres
     * @param curvature whether to include the curvature of the earth and the refraction
     * @return {@code true} if the target is visible
     * @throws IllegalArgumentException if the observer or the target is outside the grid or without data, or a height is negative
     */
    public static boolean isVisible(TerrainGrid grid, double observerX, double observerY, double observerHeight, double targetX, double targetY, double targetHeight, boolean curvature) {
        check(grid, observerX, observerY, observerHeight, targetHeight, 1.0, new byte[grid.width() * grid.height()]);
        double zt = grid.heightAt(targetX, targetY);
        if (zt != zt) {
            throw new IllegalArgumentException("the target must be inside the grid, on terrain with data");
        }
        double eye = grid.heightAt(observerX, observerY) + observerHeight;
        double projected = Math.hypot(targetX - observerX, targetY - observerY);
        double distance = projected * grid.groundScale();
        if (distance == 0.0) {
            return true;
        }
        double cell = Math.min(grid.cellEast(), grid.cellNorth());
        int n = (int) Math.ceil(projected / (cell / 8.0));
        double targetSlope = (zt + targetHeight - eye - drop(distance, curvature)) / distance;
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            double z = grid.heightAt(observerX + (targetX - observerX) * t, observerY + (targetY - observerY) * t);
            if (z != z) {
                continue;
            }
            double d = distance * t;
            if ((z - eye - drop(d, curvature)) / d > targetSlope) {
                return false;
            }
        }
        return true;
    }
}
