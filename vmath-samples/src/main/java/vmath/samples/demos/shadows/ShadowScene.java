package vmath.samples.demos.shadows;

import java.util.List;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.lighting.CascadeCasters;
import vmath.camera.Cameraf;
import vmath.lighting.Cascades;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Intersectionf;
import vmath.geo.Rayf;
import vmath.samples.framework.Scenes;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;
import vmath.util.Rng;

/**
 * The data side of the cascaded shadow demo, without OpenGL: a city of boxes, a sun, the cascades
 * that the library fits to a camera and the boxes that can cast a shadow into each of them.
 *
 * <p><b>Cascades.</b> {@link #fit} splits the camera's view range with {@code Cascades.fitAll} (a
 * blend of logarithmic and uniform spacing, each slice fitted with a light-space orthographic
 * projection whose origin is snapped to whole shadow-map texels).
 *
 * <p><b>Casters.</b> For each cascade the boxes are culled in two stages: the frustum of the
 * cascade's orthographic projection ({@code Cascade.frustum()}, which also bounds the depth range of
 * the map) and {@code CascadeCasters}, which keeps the boxes whose shadow can fall into the slice of
 * the view: everything between the slice and the light, even if it lies outside the view. A box
 * outside the frustum of the view can therefore still be a caster.
 *
 * <p><b>Ground truth.</b> {@link #castsIntoSlice} answers the question that the culling is built
 * for by brute force: it takes points of a slice and traces a ray from each towards the light
 * against every box; {@link #shadowed} is the same ray for one point.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: an instance keeps the cascades and the visibility sets
 * of the last fit.
 */
final class ShadowScene {

    private final BoundsArray bounds;
    private final int groundIndex;
    private final int count;
    private final int mapSize;
    private final float lambda;
    private final float distance;
    private final boolean stabilize;
    private final float casterDistance;
    private final CullStages.Frustum frustumStage;
    private final CullPipeline frustumPipeline;
    private final VisibilitySet viewVisible;
    private final VisibilitySet[] casters;
    private final int[] casterCounts;
    private final int[] frustumOnlyCounts;
    private final VisibilitySet scratch;
    private List<Cascades.Cascade> cascades = List.of();
    private Vec3f light = new Vec3f(0f, -1f, 0f);
    private int viewCount;

    /**
     * Creates the scene.
     *
     * @param blocks the number of buildings along a side of the city, at least 2
     * @param props the street props per building, at least 0
     * @param cascades the number of cascades, 1 to 8
     * @param mapSize the width of a shadow map in texels
     * @param lambda the blend of logarithmic (1) and uniform (0) split spacing, 0 to 1
     * @param distance the shadow distance in metres: the far end of the last cascade
     * @param stabilize whether to snap the cascades to texels (no shimmer when the camera moves)
     */
    ShadowScene(int blocks, int props, int cascades, int mapSize, float lambda, float distance, boolean stabilize) {
        Scenes.Blocks city = Scenes.blocks(blocks, props);
        this.bounds = city.bounds();
        this.groundIndex = bounds.size() - 1;
        this.count = cascades;
        this.mapSize = mapSize;
        this.lambda = lambda;
        this.distance = distance;
        this.stabilize = stabilize;
        this.casterDistance = 400f;
        // a frustum stage owns the scratch memory of its kernel (tens of kilobytes), so there is one for all the passes of a frame
        this.frustumStage = new CullStages.Frustum(FrustumKernels.best());
        this.frustumPipeline = CullPipeline.of(frustumStage);
        this.viewVisible = new VisibilitySet(bounds.size());
        this.scratch = new VisibilitySet(bounds.size());
        this.casters = new VisibilitySet[cascades];
        for (int i = 0; i < cascades; i++) {
            casters[i] = new VisibilitySet(bounds.size());
        }
        this.casterCounts = new int[cascades];
        this.frustumOnlyCounts = new int[cascades];
    }

    /**
     * Gives the boxes of the city; the last one is the ground.
     *
     * @return the boxes
     */
    BoundsArray bounds() {
        return bounds;
    }

    /**
     * Gives the index of the ground box.
     *
     * @return the index, the last box
     */
    int groundIndex() {
        return groundIndex;
    }

    /**
     * Gives the number of boxes including the ground.
     *
     * @return the number of boxes
     */
    int size() {
        return bounds.size();
    }

    /**
     * Gives the direction in which the light travels, as a unit vector, for a time of day.
     *
     * @param t the time in seconds; one turn of the sun takes 120 s
     * @return the direction from the sun towards the scene, pointing down
     */
    static Vec3f sunDirection(double t) {
        double azimuth = t * (2.0 * Math.PI / 120.0) + 0.6;
        double elevation = Math.toRadians(38.0 + 14.0 * Math.sin(t * 0.21));
        float x = (float) (-Math.cos(elevation) * Math.cos(azimuth));
        float z = (float) (-Math.cos(elevation) * Math.sin(azimuth));
        return new Vec3f(x, (float) -Math.sin(elevation), z);
    }

    private void markAll(VisibilitySet set) {
        set.clearAll();
        set.setAll(bounds.size());
    }

    /**
     * Fits the cascades to a camera and works out the boxes in view and the casters of every
     * cascade.
     *
     * @param camera the camera whose view is split; must not be {@code null}
     * @param lightDirection the direction in which the light travels; must not be {@code null}
     * @param footprint whether to apply {@code CascadeCasters} after the frustum of the cascade; with
     *     {@code false} every box in the frustum of a cascade is a caster
     */
    void fit(Cameraf camera, Vec3f lightDirection, boolean footprint) {
        light = lightDirection.normalize();
        cascades = Cascades.fitAll(camera, count, lambda, distance, light, mapSize, stabilize, casterDistance, DepthRange.NEGATIVE_ONE_TO_ONE);
        markAll(viewVisible);
        viewCount = frustumPipeline.run(CullContext.perspective(camera.frustum(), camera.position(), camera.fovy(), 1080), bounds, viewVisible);
        for (int i = 0; i < count; i++) {
            Cascades.Cascade c = cascades.get(i);
            CullContext ctx = new CullContext(c.frustum(), camera.position(), 1f);
            markAll(scratch);
            frustumOnlyCounts[i] = frustumPipeline.run(ctx, bounds, scratch);
            if (footprint) {
                markAll(casters[i]);
                CullPipeline.of(frustumStage, new CascadeCasters(camera, c, 1f)).run(ctx, bounds, casters[i]);
            } else {
                casters[i].copyFrom(scratch);
            }
            casterCounts[i] = casters[i].count();
        }
    }

    /**
     * Gives the cascades of the last fit.
     *
     * @return the cascades, nearest first
     */
    List<Cascades.Cascade> cascades() {
        return cascades;
    }

    /**
     * Gives the direction of the light of the last fit.
     *
     * @return a unit vector
     */
    Vec3f light() {
        return light;
    }

    /**
     * Gives the boxes in the view frustum of the last fit.
     *
     * @return the set; valid until the next fit
     */
    VisibilitySet viewVisible() {
        return viewVisible;
    }

    /**
     * Gives the number of boxes in the view frustum of the last fit.
     *
     * @return the count
     */
    int viewCount() {
        return viewCount;
    }

    /**
     * Gives the boxes that can cast a shadow into a cascade, after both culling stages.
     *
     * @param cascade the index of the cascade
     * @return the set; valid until the next fit
     */
    VisibilitySet casters(int cascade) {
        return casters[cascade];
    }

    /**
     * Gives the number of casters of a cascade.
     *
     * @param cascade the index of the cascade
     * @return the count
     */
    int casterCount(int cascade) {
        return casterCounts[cascade];
    }

    /**
     * Gives the number of boxes in the frustum of a cascade before the footprint test.
     *
     * @param cascade the index of the cascade
     * @return the count
     */
    int frustumOnlyCount(int cascade) {
        return frustumOnlyCounts[cascade];
    }

    /**
     * Tells which box shadows a point: the first box that the ray from the point to the light hits.
     *
     * @param x the x of the point
     * @param y the y of the point
     * @param z the z of the point
     * @param toLight the unit direction towards the light; must not be {@code null}
     * @param skip the box to ignore, the one that the point lies on, or -1
     * @return the index of a box that is hit, or -1 if the light reaches the point
     */
    int shadowed(float x, float y, float z, Vec3f toLight, int skip) {
        Rayf ray = Rayf.of(new Vec3f(x, y, z), toLight);
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs(), x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        for (int i = 0; i < bounds.size(); i++) {
            if (i == skip) {
                continue;
            }
            float t = Intersectionf.rayAabb(ray, new Aabbf(x0[i], y0[i], z0[i], x1[i], y1[i], z1[i]), 1000f);
            if (t != Float.POSITIVE_INFINITY && t > 1e-3f) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks the culling against brute force: points of the slice of a cascade are traced to the
     * light, and every box that shadows one of them must be among the casters.
     *
     * @param cascade the index of the cascade
     * @param camera the camera that the cascades were fitted to; must not be {@code null}
     * @param points how many points of the slice to try
     * @param seed the seed of the points
     * @return the number of points that were in shadow and the number of those whose shadowing box
     *     was missing from the casters, as two values
     */
    int[] castsIntoSlice(int cascade, Cameraf camera, int points, long seed) {
        Cascades.Cascade c = cascades.get(cascade);
        Rng rng = new Rng(seed);
        Vec3f toLight = light.negate();
        float t = (float) Math.tan(camera.fovy() * 0.5f);
        int inShadow = 0, missed = 0;
        for (int n = 0; n < points; n++) {
            float d = (float) rng.nextDouble(c.sliceNear(), c.sliceFar());
            float x = (float) rng.nextDouble(-1.0, 1.0), y = (float) rng.nextDouble(-1.0, 1.0);
            Vec3f p = camera.position().add(camera.forward().mul(d)).add(camera.right().mul(x * t * d * camera.aspect())).add(camera.up().mul(y * t * d));
            int hit = shadowed(p.x(), p.y(), p.z(), toLight, -1);
            if (hit >= 0) {
                inShadow++;
                if (!casters[cascade].get(hit)) {
                    missed++;
                }
            }
        }
        return new int[] {inShadow, missed};
    }
}
