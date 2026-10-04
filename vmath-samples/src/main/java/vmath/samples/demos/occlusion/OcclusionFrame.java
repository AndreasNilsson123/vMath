package vmath.samples.demos.occlusion;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import vmath.bulk.BoundsArray;
import vmath.bulk.IntList;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.Rayf;
import vmath.occlusion.DepthBuffer;
import vmath.occlusion.OcclusionStage;
import vmath.samples.framework.Scenes;
import vmath.spatial.BvhQuery;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.StaticBvh;

/**
 * The CPU side of one frame of the {@link OcclusionDemo}: the frustum kernel, the choice of
 * occluders, their rasterisation into the depth buffer, the occlusion test, and the ray check
 * that proves the test did not remove anything visible.
 *
 * <p>It does not use OpenGL, so the same code runs in the demo and in a unit test.
 *
 * <p><b>The steps.</b> The frustum kernel of the library's {@link CullPipeline} leaves the boxes
 * in the view. The buildings among them within {@value #OCCLUDER_RANGE} metres of the camera (at
 * most {@value #MAX_OCCLUDERS}) become the occluders and are rasterised into the
 * {@link DepthBuffer}. Every box that is left is then tested against the buffer's Hi-Z pyramid,
 * with the library's {@link OcclusionStage} or, with an executor, by several threads that each
 * take a range of whole words of the visibility set (a finished buffer is documented as safe to
 * read from any number of threads, and no two threads write the same word).
 *
 * <p><b>The check.</b> {@link #verify} takes the boxes that the test removed, up to
 * {@value #VERIFY_SAMPLES} of them, and shoots a ray from the camera at nine points of each (the
 * corners and the centre) that lie inside the view with a half metre margin. A removed box is
 * hidden only if an occluder that was rasterised stops every one of those rays, so a free ray is
 * a box that is visible and was removed. Points off screen are not evidence: the depth buffer
 * decides only what is on the screen.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is used by the render thread, which hands ranges
 * of the visibility set to the executor and waits for them.
 */
final class OcclusionFrame {

    static final float OCCLUDER_RANGE = 300f;
    static final int MAX_OCCLUDERS = 3000;
    static final int VERIFY_SAMPLES = 200;
    private static final float VIEW_MARGIN = 0.5f;

    private final Scenes.Blocks city;
    private final BoundsArray bounds;
    private final VisibilitySet visible;
    private final VisibilitySet before;
    private final VisibilitySet scratch;
    private final IntList occluders = new IntList(4096);
    private final IntList removed = new IntList(1024);
    private final BoundsArray used = new BoundsArray(4096);
    private final BvhQuery.BvhHit hit = new BvhQuery.BvhHit();
    private final float[] planes = new float[24];
    private final CullPipeline frustumPipeline = CullPipeline.of(new CullStages.Frustum());
    private final DepthBuffer depth;
    private final OcclusionStage stage;
    private final ExecutorService executor;
    private final int threads;
    private int frustumCount;
    private int finalCount;
    private long frustumNs;
    private long chooseNs;
    private long rasterNs;
    private long testNs;
    private long checked;
    private long violations;

    /**
     * Creates the frame state for a city.
     *
     * @param city the scene; must not be {@code null}
     * @param depthWidth the width of the depth buffer in pixels; its height is half of it
     * @param executor the threads that test the boxes, or {@code null} to test on the calling
     *     thread with the library's stage
     * @param threads the number of ranges to split the test into when there is an executor
     */
    OcclusionFrame(Scenes.Blocks city, int depthWidth, ExecutorService executor, int threads) {
        this.city = city;
        this.bounds = city.bounds();
        this.visible = new VisibilitySet(bounds.size());
        this.before = new VisibilitySet(bounds.size());
        this.scratch = new VisibilitySet(bounds.size());
        this.depth = new DepthBuffer(depthWidth, depthWidth / 2);
        this.stage = new OcclusionStage(depth);
        this.executor = executor;
        this.threads = threads;
    }

    VisibilitySet visible() {
        return visible;
    }

    DepthBuffer depth() {
        return depth;
    }

    IntList occluders() {
        return occluders;
    }

    int frustumCount() {
        return frustumCount;
    }

    int finalCount() {
        return finalCount;
    }

    long frustumNs() {
        return frustumNs;
    }

    long chooseNs() {
        return chooseNs;
    }

    long rasterNs() {
        return rasterNs;
    }

    long testNs() {
        return testNs;
    }

    long checked() {
        return checked;
    }

    long violations() {
        return violations;
    }

    /**
     * Does the culling of one frame and leaves the result in {@link #visible}.
     *
     * @param cam the camera to cull for; must not be {@code null}
     * @param height the height of the viewport in pixels
     * @param withOcclusion whether to run the occlusion steps; without them the result is the
     *     frustum's
     */
    void cull(Cameraf cam, int height, boolean withOcclusion) {
        long t0 = System.nanoTime();
        CullContext cull = CullContext.perspective(cam.frustum(), cam.position(), cam.fovy(), height);
        frustumCount = frustumPipeline.run(cull, bounds, visible);
        long t1 = System.nanoTime();
        long t2 = t1, t3 = t1, t4 = t1;
        if (withOcclusion) {
            chooseOccluders(cam);
            t2 = System.nanoTime();
            depth.begin(cam.viewProjection(), cam.near());
            for (int k = 0; k < occluders.size(); k++) {
                int i = occluders.get(k);
                depth.addBox(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i));
            }
            t3 = System.nanoTime();
            before.copyFrom(visible);
            test(cull);
            t4 = System.nanoTime();
        }
        finalCount = visible.count();
        frustumNs = t1 - t0;
        chooseNs = t2 - t1;
        rasterNs = t3 - t2;
        testNs = t4 - t3;
    }

    /**
     * Picks the occluders: the buildings that are in the frustum and within the occluder range.
     * Buildings are the first boxes of the city, so the scan stops after them.
     */
    private void chooseOccluders(Cameraf cam) {
        occluders.clear();
        Vec3f p = cam.position();
        float cx = p.x(), cy = p.y(), cz = p.z();
        float r2 = OCCLUDER_RANGE * OCCLUDER_RANGE;
        int n = city.buildings();
        for (int i = visible.nextSetBit(0); i >= 0 && i < n && occluders.size() < MAX_OCCLUDERS; i = visible.nextSetBit(i + 1)) {
            float dx = Math.max(Math.max(bounds.minX(i) - cx, 0f), cx - bounds.maxX(i));
            float dy = Math.max(Math.max(bounds.minY(i) - cy, 0f), cy - bounds.maxY(i));
            float dz = Math.max(Math.max(bounds.minZ(i) - cz, 0f), cz - bounds.maxZ(i));
            if (dx * dx + dy * dy + dz * dz <= r2) {
                occluders.add(i);
            }
        }
    }

    /**
     * Removes the boxes that are hidden behind the occluders, with the library's stage on one
     * thread or with the ranges of the executor.
     */
    private void test(CullContext cull) {
        if (executor == null) {
            stage.cull(cull, bounds, visible);
            return;
        }
        depth.finish();
        int n = bounds.size();
        long[] words = visible.words();
        int wordCount = (n + 63) >>> 6;
        int per = (wordCount + threads - 1) / threads;
        CountDownLatch done = new CountDownLatch(threads);
        for (int part = 0; part < threads; part++) {
            int from = part * per, to = Math.min(wordCount, from + per);
            executor.execute(() -> {
                try {
                    float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
                    float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
                    for (int w = from; w < to; w++) {
                        long bits = words[w];
                        while (bits != 0L) {
                            int b = Long.numberOfTrailingZeros(bits);
                            bits &= bits - 1;
                            int i = (w << 6) + b;
                            if (i < n && depth.isHidden(x0[i], y0[i], z0[i], x1[i], y1[i], z1[i])) {
                                words[w] &= ~(1L << b);
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while testing against the depth buffer", e);
        }
    }

    /**
     * Checks a sample of the boxes that the last {@link #cull} removed.
     *
     * @param cam the camera that the last cull used; must not be {@code null}
     * @throws IllegalStateException if a removed box has a point inside the view that a ray from
     *     the camera reaches without meeting a rasterised occluder, which means that a visible box
     *     was removed
     */
    void verify(Cameraf cam) {
        scratch.copyFrom(before);
        scratch.andNot(visible);
        removed.clear();
        scratch.toIndices(removed);
        used.clear();
        for (int k = 0; k < occluders.size(); k++) {
            int i = occluders.get(k);
            used.add(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i));
        }
        if (used.size() == 0 || removed.size() == 0) {
            return;
        }
        BvhQuery query = new BvhQuery(StaticBvh.build(used));
        Vec3f eye = cam.position();
        int step = Math.max(1, removed.size() / VERIFY_SAMPLES);
        for (int k = 0; k < removed.size(); k += step) {
            int i = removed.get(k);
            checked++;
            for (int c = 0; c < 9; c++) {
                float x = c == 8 ? (bounds.minX(i) + bounds.maxX(i)) * 0.5f : (c & 1) == 0 ? bounds.minX(i) : bounds.maxX(i);
                float y = c == 8 ? (bounds.minY(i) + bounds.maxY(i)) * 0.5f : (c & 2) == 0 ? bounds.minY(i) : bounds.maxY(i);
                float z = c == 8 ? (bounds.minZ(i) + bounds.maxZ(i)) * 0.5f : (c & 4) == 0 ? bounds.minZ(i) : bounds.maxZ(i);
                if (!insideView(cam, x, y, z)) {
                    continue; // off screen: the depth buffer only decides what is on it
                }
                Rayf ray = Rayf.through(eye, new Vec3f(x, y, z));
                if (!query.raycastBounds(ray, 0.999f, used, hit)) {
                    violations++;
                    throw new IllegalStateException("the occlusion culling removed box " + i + " " + bounds.get(i) + ", but the ray to its point " + c + " is not blocked (camera "
                            + eye + ", " + occluders.size() + " occluders, " + removed.size() + " removed)");
                }
            }
        }
    }

    /**
     * Tells whether a point is inside the camera's view by a margin of half a metre from every
     * plane, so that a point that rounding could put on either side of the screen edge is not
     * used as evidence.
     */
    private boolean insideView(Cameraf cam, float x, float y, float z) {
        cam.frustum().writeTo(planes, 0);
        for (int p = 0; p < 24; p += 4) {
            if (planes[p] * x + planes[p + 1] * y + planes[p + 2] * z + planes[p + 3] < VIEW_MARGIN) {
                return false;
            }
        }
        return true;
    }
}
