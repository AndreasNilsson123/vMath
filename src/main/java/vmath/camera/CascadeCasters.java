package vmath.camera;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cascades.Cascade;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.spatial.CullContext;
import vmath.spatial.CullStage;

/**
 * A {@link CullStage} that keeps only the objects that can throw a shadow onto what the camera sees in one cascade.
 *
 * <p>A cascade's own frustum ({@link Cascade#frustum()}) is the volume the shadow map covers: a box around the slice, pushed toward
 * the light by the caster distance. That is the right volume for <em>rendering</em> the map, but it is much bigger than
 * necessary for <em>deciding what to render</em>, because a stabilised cascade is fitted around the slice's bounding sphere.
 * This stage tests each object against the slice itself: in light space the light travels straight down, so an object can only
 * shadow the slice if
 * <ul>
 *   <li>its footprint (its box's extent along the light's two sideways axes) overlaps the slice's footprint, and</li>
 *   <li>it is not entirely on the far side of the slice: some part of it is at least as close to the light as the slice's most
 *       distant point.</li>
 * </ul>
 * Both tests use axis-aligned boxes in light space, so they are conservative: an object that shadows any point of the slice is
 * never removed (the tests check this by sampling shadow rays). {@code margin} widens the footprint test by a light-space distance,
 * typically the shadow filter radius, so that blurred penumbrae are not clipped.
 *
 * <p>The stage does not look at the depth range of the shadow map. Combine it with a frustum stage fed with
 * {@link Cascade#frustum()} (through the {@link CullContext}) to also drop casters beyond the map's near plane:
 * {@code CullPipeline.of(new CullStages.Frustum(), new CascadeCasters(camera, cascade, margin))}, run with a context whose frustum
 * is the cascade's.
 *
 * <p>Instances are immutable; {@code cull} allocates nothing.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class CascadeCasters implements CullStage {

    // world -> light space, one row per light-space axis: x' = rx0*x + rx1*y + rx2*z + tx, and likewise y', z'
    private final float rx0;
    private final float rx1;
    private final float rx2;
    private final float ry0;
    private final float ry1;
    private final float ry2;
    private final float rz0;
    private final float rz1;
    private final float rz2;
    private final float tx;
    private final float ty;
    private final float tz;

    // the camera's slice in light space
    private final float sliceMinX;
    private final float sliceMinY;
    private final float sliceMinZ;
    private final float sliceMaxX;
    private final float sliceMaxY;

    /**
     * A stage for {@code cascade}, cut from {@code camera}'s view. {@code margin} (at least 0, a light-space distance such as the shadow filter radius) widens the footprint test.
     */
    public CascadeCasters(Cameraf camera, Cascade cascade, float margin) {
        if (!(margin >= 0f)) {
            throw new IllegalArgumentException("margin must be >= 0: " + margin);
        }
        Mat4f lv = cascade.lightView();
        Vec3f t = lv.transformPosition(Vec3f.ZERO);
        Vec3f e0 = lv.transformPosition(new Vec3f(1f, 0f, 0f)).sub(t);
        Vec3f e1 = lv.transformPosition(new Vec3f(0f, 1f, 0f)).sub(t);
        Vec3f e2 = lv.transformPosition(new Vec3f(0f, 0f, 1f)).sub(t);
        rx0 = e0.x();
        rx1 = e1.x();
        rx2 = e2.x();
        ry0 = e0.y();
        ry1 = e1.y();
        ry2 = e2.y();
        rz0 = e0.z();
        rz1 = e1.z();
        rz2 = e2.z();
        tx = t.x();
        ty = t.y();
        tz = t.z();

        float th = (float) Math.tan(camera.fovy() * 0.5f);
        Vec3f fwd = camera.forward(), right = camera.right(), up = camera.up();
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < 8; i++) {
            float d = (i & 4) == 0 ? cascade.sliceNear() : cascade.sliceFar();
            float h = th * d;
            float w = h * camera.aspect();
            Vec3f corner = camera.position().add(fwd.mul(d))
                    .add(right.mul((i & 1) == 0 ? -w : w)).add(up.mul((i & 2) == 0 ? -h : h));
            Vec3f p = lv.transformPosition(corner);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        // a few ulps of slack for the rounding of the two transformations, on top of the caller's margin
        float slack = 1e-4f * Math.max(1f, Math.max(Math.max(Math.abs(maxX), Math.abs(minX)), Math.max(Math.abs(maxY), Math.abs(minY))));
        sliceMinX = minX - margin - slack;
        sliceMinY = minY - margin - slack;
        sliceMaxX = maxX + margin + slack;
        sliceMaxY = maxY + margin + slack;
        sliceMinZ = minZ - slack;
    }

    @Override
    public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = visible.words();
        for (int i = visible.nextSetBit(0); i >= 0 && i < bounds.size(); i = visible.nextSetBit(i + 1)) {
            float cx = (x0[i] + x1[i]) * 0.5f, cy = (y0[i] + y1[i]) * 0.5f, cz = (z0[i] + z1[i]) * 0.5f;
            float hx = (x1[i] - x0[i]) * 0.5f, hy = (y1[i] - y0[i]) * 0.5f, hz = (z1[i] - z0[i]) * 0.5f;
            // centre and half-extent of the box's axis-aligned bound in light space (Arvo)
            float lx = rx0 * cx + rx1 * cy + rx2 * cz + tx;
            float ly = ry0 * cx + ry1 * cy + ry2 * cz + ty;
            float lz = rz0 * cx + rz1 * cy + rz2 * cz + tz;
            float ex = Math.abs(rx0) * hx + Math.abs(rx1) * hy + Math.abs(rx2) * hz;
            float ey = Math.abs(ry0) * hx + Math.abs(ry1) * hy + Math.abs(ry2) * hz;
            float ez = Math.abs(rz0) * hx + Math.abs(rz1) * hy + Math.abs(rz2) * hz;
            boolean casts = lx + ex >= sliceMinX && lx - ex <= sliceMaxX
                    && ly + ey >= sliceMinY && ly - ey <= sliceMaxY
                    && lz + ez >= sliceMinZ;
            // NaN bounds compare false everywhere above: keep what we cannot judge
            if (!casts && lx == lx && ly == ly && lz == lz && ex == ex && ey == ey && ez == ez) {
                words[i >>> 6] &= ~(1L << i);
            }
        }
    }
}
