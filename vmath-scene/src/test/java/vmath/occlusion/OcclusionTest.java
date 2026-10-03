package vmath.occlusion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.spatial.CullPipeline;

class OcclusionTest {

    final Rnd rnd = Rnd.create();

    private static final float FOVY = 1.0f;
    private static final float ASPECT = 2f;
    private static final float NEAR = 0.1f;

    private static Mat4f viewProjection(Vec3f eye, Vec3f target) {
        Vec3f dir = target.sub(eye).normalize();
        Vec3f up = Math.abs(dir.y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y;
        return Mat4f.perspective(FOVY, ASPECT, NEAR, 1000f, true).mul(Mat4f.lookAt(eye, target, up));
    }

    private static DepthBuffer buffer(Mat4f vp, Aabbf... occluders) {
        DepthBuffer d = new DepthBuffer(128, 64);
        d.begin(vp, NEAR);
        for (Aabbf o : occluders) {
            d.addBox(o);
        }
        d.finish();
        return d;
    }

    private static Aabbf box(float cx, float cy, float cz, float hx, float hy, float hz) {
        return Aabbf.fromCenterHalfExtent(new Vec3f(cx, cy, cz), new Vec3f(hx, hy, hz));
    }

    // ------------------------------------------------------------ sizing

    @Test
    void pyramidSizing() {
        assertEquals(1, HiZ.mipCount(1, 1));
        assertEquals(9, HiZ.mipCount(256, 128));
        assertEquals(9, HiZ.mipCount(200, 30));
        assertEquals(10, HiZ.mipCount(257, 1));
        assertEquals(200, HiZ.mipSize(200, 0));
        assertEquals(100, HiZ.mipSize(200, 1));
        assertEquals(13, HiZ.mipSize(200, 4));
        assertEquals(1, HiZ.mipSize(200, 8));
        assertEquals(1, HiZ.mipSize(3, 9), "never below one texel");
        assertEquals(0, HiZ.levelFor(3, 2, 4, 9));
        assertEquals(4, HiZ.levelFor(40, 8, 4, 9));
        assertEquals(2, HiZ.levelFor(40, 8, 4, 3), "capped by the number of levels");
        assertThrows(IllegalArgumentException.class, () -> HiZ.mipCount(0, 4));
        assertThrows(IllegalArgumentException.class, () -> HiZ.mipSize(4, -1));
        DepthBuffer d = new DepthBuffer(200, 30);
        assertEquals(HiZ.mipCount(200, 30), d.levels());
    }

    // ------------------------------------------------------------ rasterizer

    @Test
    void aFlatTriangleCoversExactlyTheWholePixelsInsideIt() {
        int w = 96, h = 48;
        DepthBuffer d = new DepthBuffer(w, h);
        Mat4f proj = Mat4f.perspective(FOVY, ASPECT, NEAR, 1000f, true);
        d.begin(proj, NEAR);
        // a triangle in the plane z = -10, seen from the origin
        float[][] tri = {{-9f, -4f}, {11f, -3f}, {1f, 6f}};
        d.addTriangle(tri[0][0], tri[0][1], -10f, tri[1][0], tri[1][1], -10f, tri[2][0], tri[2][1], -10f);
        d.finish();
        double th = Math.tan(FOVY * 0.5);
        double[][] scr = new double[3][2];
        for (int i = 0; i < 3; i++) { // at constant depth the projection is an affine map, so edges stay straight
            scr[i][0] = (tri[i][0] / (10 * th * ASPECT) * 0.5 + 0.5) * w;
            scr[i][1] = (tri[i][1] / (10 * th) * 0.5 + 0.5) * h;
        }
        int covered = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double worst = Double.POSITIVE_INFINITY;
                for (int c = 0; c < 4; c++) {
                    double px = x + (c & 1), py = y + (c >> 1);
                    double minEdge = Double.POSITIVE_INFINITY;
                    for (int e = 0; e < 3; e++) {
                        double[] a = scr[e], b = scr[(e + 1) % 3];
                        double cross = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
                        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                        minEdge = Math.min(minEdge, cross / len); // signed distance in pixels, sign depends on winding
                    }
                    worst = Math.min(worst, minEdge);
                }
                // orientation: use the sign of the triangle area to flip
                double area = (scr[1][0] - scr[0][0]) * (scr[2][1] - scr[0][1]) - (scr[2][0] - scr[0][0]) * (scr[1][1] - scr[0][1]);
                double inside = area > 0 ? worst : 0;
                if (area < 0) {
                    inside = Double.POSITIVE_INFINITY;
                    for (int c = 0; c < 4; c++) {
                        double px = x + (c & 1), py = y + (c >> 1);
                        for (int e = 0; e < 3; e++) {
                            double[] a = scr[e], b = scr[(e + 1) % 3];
                            double cross = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
                            inside = Math.min(inside, -cross / Math.hypot(b[0] - a[0], b[1] - a[1]));
                        }
                    }
                }
                float v = d.invDepth(x, y);
                if (inside > 0.01) {
                    assertTrue(v > 0f, "pixel " + x + "," + y + " lies fully inside but is not covered");
                    assertEquals(0.1f, v, 0.1f * 2e-5f, "constant depth 10 -> 1/w = 0.1");
                    covered++;
                } else if (inside < -0.01) {
                    assertEquals(0f, v, "pixel " + x + "," + y + " is not fully inside and must stay empty");
                }
            }
        }
        assertTrue(covered > 300, "the triangle should cover plenty of pixels, covered " + covered);
        assertEquals(covered, d.coveredPixels(), 40, "only boundary pixels (within 0.01) may differ");
    }

    @Test
    void depthIsTheFarthestPointInThePixelAndNearerOccludersWin() {
        DepthBuffer d = new DepthBuffer(64, 32);
        Mat4f proj = Mat4f.perspective(FOVY, ASPECT, NEAR, 1000f, true);
        d.begin(proj, NEAR);
        // a big wall tilted in depth: z goes from -10 (left) to -30 (right)
        d.addPolygon(new float[] {-40f, -20f, -10f, 40f, -20f, -30f, 40f, 20f, -30f, -40f, 20f, -10f}, 4);
        int mid = d.width() / 2, row = d.height() / 2;
        float far = d.invDepth(mid, row);
        assertTrue(far > 0f);
        double th = Math.tan(FOVY * 0.5);
        // the true 1/w range across pixel `mid` on the wall: solve the wall plane z(x) along the pixel's rays
        double xa = ((double) mid / d.width() * 2 - 1) * th * ASPECT, xb = ((double) (mid + 1) / d.width() * 2 - 1) * th * ASPECT;
        // wall: z = -20 - 0.25 * xw (x from -40..40 maps to z -10..-30); ray x_w = xn * (-z)  =>  xw = xn * w, w = -z
        // w = 20 + 0.25 * xw = 20 + 0.25 * xn * w  =>  w = 20 / (1 - 0.25 * xn)
        double wa = 20.0 / (1 - 0.25 * xa), wb = 20.0 / (1 - 0.25 * xb);
        double lowest = Math.min(1 / wa, 1 / wb);
        assertTrue(far <= lowest * 1.000001, "stored depth must not be nearer than the farthest point of the pixel: " + far + " vs " + lowest);
        assertEquals(lowest, far, lowest * 0.02, "and it should be close to it");
        // a nearer occluder over the same pixels replaces it; a farther one does not
        d.addBox(-1f, -1f, -6f, 1f, 1f, -5f);
        float nearer = d.invDepth(mid, row);
        assertTrue(nearer > far, "the nearer box wins");
        d.addBox(-1f, -1f, -60f, 1f, 1f, -50f);
        assertEquals(nearer, d.invDepth(mid, row), "a farther occluder must not overwrite it");
    }

    @Test
    void nothingIsCoveredBeforeAnOccluderIsAdded() {
        DepthBuffer d = buffer(viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f)));
        assertEquals(0, d.coveredPixels());
        assertFalse(d.isHidden(box(0f, 0f, -20f, 1f, 1f, 1f)));
    }

    // ------------------------------------------------------------ queries

    @Test
    void aWallHidesWhatIsBehindItAndOnlyThat() {
        Mat4f vp = viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f));
        DepthBuffer d = buffer(vp, box(0f, 0f, -20f, 4f, 3f, 0.5f)); // 8 x 6 wall, 20 away
        assertTrue(d.isHidden(box(0f, 0f, -40f, 1f, 1f, 1f)), "directly behind the wall");
        assertTrue(d.isHidden(box(3f, -2f, -45f, 1f, 1f, 1f)), "behind, off centre but inside its shadow");
        assertFalse(d.isHidden(box(0f, 0f, -10f, 1f, 1f, 1f)), "in front of the wall");
        assertFalse(d.isHidden(box(12f, 0f, -40f, 1f, 1f, 1f)), "behind but far to the side");
        assertFalse(d.isHidden(box(8f, 0f, -40f, 2f, 1f, 1f)), "straddles the wall's edge");
        assertFalse(d.isHidden(box(0f, 0f, -20f, 1f, 1f, 5f)), "reaches through the wall");
        assertFalse(d.isHidden(box(0f, 0f, -0.05f, 1f, 1f, 1f)), "reaches the near plane");
        assertFalse(d.isHidden(box(0f, 0f, 30f, 1f, 1f, 1f)), "behind the camera");
    }

    @Test
    void oneOccluderCanBeHiddenByAnotherAndTwoCanCoverWhatOneCannot() {
        Mat4f vp = viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f));
        // two side by side walls that together cover an object neither covers alone
        DepthBuffer d = buffer(vp, box(-3f, 0f, -20f, 3.2f, 3f, 0.5f), box(3f, 0f, -20f, 3.2f, 3f, 0.5f));
        Aabbf across = box(0f, 0f, -40f, 3f, 1f, 1f);
        assertTrue(d.isHidden(across), "the pair covers it");
        DepthBuffer one = buffer(vp, box(-3f, 0f, -20f, 3.2f, 3f, 0.5f));
        assertFalse(one.isHidden(across), "one wall alone does not");
    }

    @Test
    void degenerateInputsAreHandled() {
        Mat4f vp = viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f));
        DepthBuffer d = new DepthBuffer(64, 32);
        assertFalse(d.isHidden(box(0f, 0f, -20f, 1f, 1f, 1f)), "before begin there is nothing to hide behind");
        assertThrows(IllegalStateException.class, () -> d.addBox(box(0f, 0f, -20f, 1f, 1f, 1f)));
        assertThrows(IllegalArgumentException.class, () -> d.begin(vp, 0f));
        assertThrows(IllegalArgumentException.class, () -> new DepthBuffer(0, 4));
        d.begin(vp, NEAR);
        d.addTriangle(0f, 0f, -10f, 0f, 0f, -10f, 0f, 0f, -10f);       // a point
        d.addTriangle(-5f, 0f, -10f, 5f, 0f, -10f, 0f, 0f, -10f);      // a line
        d.addTriangle(Float.NaN, 0f, -10f, 5f, 0f, -10f, 0f, 5f, -10f); // NaN vertex
        d.addTriangle(0f, 0f, 5f, 1f, 0f, 5f, 0f, 1f, 5f);              // entirely behind the camera
        assertEquals(0, d.coveredPixels());
        d.addBox(box(0f, 0f, 0f, 3f, 3f, 3f)); // the camera is inside this box: near-clipped, must not throw
        assertFalse(d.isHidden(Float.NaN, 0f, -20f, 1f, 1f, -19f));
        assertFalse(d.isHidden(0f, 0f, -20f, Float.POSITIVE_INFINITY, 1f, -19f));
        // a huge box: must be finite-safe
        d.begin(vp, NEAR);
        d.addBox(box(0f, 0f, -20f, 1e6f, 1e6f, 1f));
        assertTrue(d.coveredPixels() > 0);
    }

    @Test
    void theBufferCanBeReusedAcrossFrames() {
        DepthBuffer d = new DepthBuffer(64, 32);
        Mat4f vp = viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f));
        d.begin(vp, NEAR);
        d.addBox(box(0f, 0f, -20f, 6f, 6f, 0.5f));
        assertTrue(d.isHidden(box(0f, 0f, -40f, 1f, 1f, 1f)));
        d.begin(vp, NEAR); // a new frame with no occluders
        assertEquals(0, d.coveredPixels());
        assertFalse(d.isHidden(box(0f, 0f, -40f, 1f, 1f, 1f)));
    }

    // ------------------------------------------------------------ pyramid

    @Test
    void pyramidLevelsAreTheMinimumOfTheLevelBelow() {
        DepthBuffer d = new DepthBuffer(37, 21); // odd sizes on purpose
        Mat4f vp = viewProjection(new Vec3f(1f, 2f, 3f), new Vec3f(0f, 0f, -20f));
        d.begin(vp, NEAR);
        for (int i = 0; i < 6; i++) {
            d.addBox(box((float) rnd.range(-6, 6), (float) rnd.range(-4, 4), (float) rnd.range(-30, -10), (float) rnd.range(1, 5),
                    (float) rnd.range(1, 4), (float) rnd.range(0.5, 3)));
        }
        d.finish();
        for (int l = 1; l < d.levels(); l++) {
            int sw = HiZ.mipSize(37, l - 1), sh = HiZ.mipSize(21, l - 1), dw = HiZ.mipSize(37, l), dh = HiZ.mipSize(21, l);
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    float expected = Float.POSITIVE_INFINITY;
                    for (int j = 0; j < 2; j++) {
                        for (int i = 0; i < 2; i++) {
                            int sx = x * 2 + i, sy = y * 2 + j;
                            if (sx < sw && sy < sh) {
                                expected = Math.min(expected, d.invDepth(sx, sy, l - 1));
                            }
                        }
                    }
                    assertEquals(expected, d.invDepth(x, y, l), "level " + l + " texel " + x + "," + y);
                }
            }
        }
        assertEquals(1, HiZ.mipSize(37, d.levels() - 1));
    }

    // ------------------------------------------------------------ the conservative property

    /** True if the segment from the eye to {@code p} passes through the solid box before reaching {@code p}. */
    private static boolean blocked(double ex, double ey, double ez, double px, double py, double pz, Aabbf b) {
        double[] o = {ex, ey, ez}, dir = {px - ex, py - ey, pz - ez};
        double[] lo = {b.minX(), b.minY(), b.minZ()}, hi = {b.maxX(), b.maxY(), b.maxZ()};
        double tn = 0, tf = 1 - 1e-5; // only what happens before the point, with a hair of margin
        for (int k = 0; k < 3; k++) {
            if (Math.abs(dir[k]) < 1e-12) {
                if (o[k] < lo[k] || o[k] > hi[k]) {
                    return false;
                }
                continue;
            }
            double t1 = (lo[k] - o[k]) / dir[k], t2 = (hi[k] - o[k]) / dir[k];
            tn = Math.max(tn, Math.min(t1, t2));
            tf = Math.min(tf, Math.max(t1, t2));
        }
        return tn < tf;
    }

    private static boolean contains(Aabbf b, Vec3f p, float margin) {
        return p.x() > b.minX() - margin && p.x() < b.maxX() + margin && p.y() > b.minY() - margin && p.y() < b.maxY() + margin
                && p.z() > b.minZ() - margin && p.z() < b.maxZ() + margin;
    }

    @Test
    void neverHidesAnObjectWithAPointThatCanBeSeen() {
        int hidden = 0, tested = 0;
        for (int trial = 0; trial < 120; trial++) {
            Vec3f eye = rnd.nextVec3f().mul(20f);
            Vec3f forward = rnd.nextVec3f().normalize();
            Vec3f target = eye.add(forward.mul(10f));
            Mat4f vp = viewProjection(eye, target);
            List<Aabbf> occluders = new ArrayList<>();
            int count = 1 + (int) rnd.range(0, 5);
            boolean eyeInside = false;
            for (int i = 0; i < count; i++) {
                // occluders mostly in front of the camera, so that they cover a good part of the screen
                Vec3f centre = i % 3 == 2 ? eye.add(rnd.nextVec3f().mul(35f))
                        : eye.add(forward.mul((float) rnd.range(6, 25))).add(rnd.nextVec3f().mul(6f));
                Aabbf o = Aabbf.fromCenterHalfExtent(centre,
                        new Vec3f((float) rnd.range(1, 8), (float) rnd.range(1, 8), (float) rnd.range(0.3, 8)));
                occluders.add(o);
                eyeInside |= contains(o, eye, 0.5f);
            }
            if (eyeInside) {
                continue;
            }
            DepthBuffer d = new DepthBuffer(128, 64);
            d.begin(vp, NEAR);
            for (Aabbf o : occluders) {
                d.addBox(o);
            }
            d.finish();
            for (int k = 0; k < 250; k++) {
                Vec3f where;
                if (k % 5 != 0) { // most candidates sit behind some occluder, as seen from the eye
                    Aabbf o = occluders.get((int) rnd.range(0, occluders.size()));
                    Vec3f away = o.center().sub(eye).normalize();
                    where = o.center().add(away.mul((float) rnd.range(2, 40))).add(rnd.nextVec3f().mul(o.halfSize().length() * 0.3f));
                } else {
                    where = eye.add(rnd.nextVec3f().mul(60f));
                }
                Aabbf c = Aabbf.fromCenterHalfExtent(where,
                        new Vec3f((float) rnd.range(0.1, 1.2), (float) rnd.range(0.1, 1.2), (float) rnd.range(0.1, 1.2)));
                tested++;
                if (!d.isHidden(c)) {
                    continue;
                }
                hidden++;
                // every sampled point that lands on screen must be blocked by some occluder
                for (int s = 0; s < 48; s++) {
                    Vec3f p = s < 8 ? new Vec3f((s & 1) == 0 ? c.minX() : c.maxX(), (s & 2) == 0 ? c.minY() : c.maxY(),
                            (s & 4) == 0 ? c.minZ() : c.maxZ())
                            : new Vec3f((float) rnd.range(c.minX(), c.maxX()), (float) rnd.range(c.minY(), c.maxY()),
                                    (float) rnd.range(c.minZ(), c.maxZ()));
                    var clip = vp.transform(new vmath.core.Vec4f(p.x(), p.y(), p.z(), 1f));
                    if (!(clip.w() > NEAR)) {
                        continue;
                    }
                    double sx = (clip.x() / clip.w() * 0.5 + 0.5) * 128, sy = (clip.y() / clip.w() * 0.5 + 0.5) * 64;
                    if (sx <= 0 || sx >= 128 || sy <= 0 || sy >= 64) {
                        continue; // off screen: the buffer says nothing about it
                    }
                    boolean isBlocked = false;
                    for (Aabbf o : occluders) {
                        if (blocked(eye.x(), eye.y(), eye.z(), p.x(), p.y(), p.z(), o)) {
                            isBlocked = true;
                            break;
                        }
                    }
                    assertTrue(isBlocked, "the buffer hid a box but point " + p + " is visible (trial " + trial + ", box " + c + ")");
                }
            }
        }
        assertTrue(hidden > 100, "the test must hide something to mean anything, hid " + hidden + " of " + tested);
    }

    // ------------------------------------------------------------ the stage

    @Test
    void theStageClearsHiddenObjectsAndLeavesTheRest() {
        Mat4f vp = viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f));
        DepthBuffer d = buffer(vp, box(0f, 0f, -20f, 5f, 4f, 0.5f));
        BoundsArray b = new BoundsArray(4);
        b.add(box(0f, 0f, -40f, 1f, 1f, 1f));   // hidden
        b.add(box(0f, 0f, -10f, 1f, 1f, 1f));   // in front
        b.add(box(15f, 0f, -40f, 1f, 1f, 1f));  // to the side
        b.add(box(1f, 1f, -60f, 2f, 2f, 2f));   // hidden
        VisibilitySet vis = new VisibilitySet(4);
        vis.set(0);
        vis.set(1);
        vis.set(2); // object 3 is already invisible and must stay so
        new OcclusionStage(d).cull(null, b, vis);
        assertFalse(vis.get(0));
        assertTrue(vis.get(1));
        assertTrue(vis.get(2));
        assertFalse(vis.get(3));
        // and inside a pipeline, after the other stages
        VisibilitySet out = new VisibilitySet(4);
        int left = CullPipeline.of(new OcclusionStage(d)).run(null, b, out);
        assertEquals(2, left);
        assertTrue(out.get(1) && out.get(2) && !out.get(0) && !out.get(3));
    }

    // ------------------------------------------------------------ orthographic views

    private static final float ORTHO_NEAR = 0.1f;
    private static final float ORTHO_FAR = 100f;

    /** The view-projection of an orthographic camera, 20 wide and 10 high, in one of the three depth conventions. */
    private static Mat4f orthographic(Vec3f eye, Vec3f target, DepthRange depth) {
        Vec3f dir = target.sub(eye).normalize();
        Vec3f up = Math.abs(dir.y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y;
        Mat4f view = Mat4f.lookAt(eye, target, up);
        Mat4f projection = switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> Mat4f.ortho(-10f, 10f, -5f, 5f, ORTHO_NEAR, ORTHO_FAR, ClipSpace.OPENGL);
            case ZERO_TO_ONE -> Mat4f.ortho(-10f, 10f, -5f, 5f, ORTHO_NEAR, ORTHO_FAR, ClipSpace.D3D);
            case REVERSED_ZERO_TO_ONE -> Mat4f.orthoReversedZ(-10f, 10f, -5f, 5f, ORTHO_NEAR, ORTHO_FAR, ClipSpace.D3D);
        };
        return projection.mul(view);
    }

    private static DepthBuffer orthographicBuffer(Mat4f vp, DepthRange depth, Aabbf... occluders) {
        DepthBuffer d = new DepthBuffer(128, 64);
        d.beginOrthographic(vp, depth);
        for (Aabbf o : occluders) {
            d.addBox(o);
        }
        d.finish();
        return d;
    }

    @Test
    void anOrthographicWallHidesWhatIsBehindItInEveryDepthConvention() {
        for (DepthRange depth : DepthRange.values()) {
            Mat4f vp = orthographic(Vec3f.ZERO, new Vec3f(0f, 0f, -1f), depth);
            DepthBuffer d = orthographicBuffer(vp, depth, box(0f, 0f, -20f, 4f, 4f, 0.5f));
            assertTrue(d.isHidden(box(0f, 0f, -40f, 1f, 1f, 1f)), depth + ": directly behind the wall");
            assertTrue(d.isHidden(box(3f, -2f, -45f, 0.5f, 0.5f, 1f)), depth + ": behind, inside its shadow");
            assertFalse(d.isHidden(box(0f, 0f, -10f, 1f, 1f, 1f)), depth + ": in front of the wall");
            assertFalse(d.isHidden(box(6f, 0f, -40f, 1f, 1f, 1f)), depth + ": behind but beside it (the wall is 8 wide)");
            assertFalse(d.isHidden(box(3.5f, 0f, -40f, 1f, 1f, 1f)), depth + ": straddles the edge of the wall");
            assertFalse(d.isHidden(box(0f, 0f, -20f, 1f, 1f, 5f)), depth + ": reaches through the wall");
            assertFalse(d.isHidden(box(0f, 0f, -0.05f, 1f, 1f, 1f)), depth + ": reaches the near plane");
        }
    }

    @Test
    void anOrthographicBufferStoresTheNearnessAndClipsAtTheNearPlane() {
        for (DepthRange depth : DepthRange.values()) {
            Mat4f vp = orthographic(Vec3f.ZERO, new Vec3f(0f, 0f, -1f), depth);
            DepthBuffer d = new DepthBuffer(64, 32);
            d.beginOrthographic(vp, depth);
            d.addPolygon(new float[] {-4f, -2f, -10f, 4f, -2f, -10f, 4f, 2f, -10f, -4f, 2f, -10f}, 4);
            float expected = 1f - (10f - ORTHO_NEAR) / (ORTHO_FAR - ORTHO_NEAR);
            assertEquals(expected, d.invDepth(32, 16), expected * 1e-4f, depth + ": a plane at distance 10 stores one minus the fraction of the way to the far plane");
            assertTrue(d.coveredPixels() > 100, depth.toString());
            // a plane behind the far plane covers nothing; one crossing the near plane keeps the part in front of it
            d.beginOrthographic(vp, depth);
            d.addPolygon(new float[] {-4f, -2f, -200f, 4f, -2f, -200f, 4f, 2f, -200f, -4f, 2f, -200f}, 4);
            assertEquals(0, d.coveredPixels(), depth + ": beyond the far plane");
            d.beginOrthographic(vp, depth);
            d.addPolygon(new float[] {-4f, -2f, 5f, 4f, -2f, 5f, 4f, 2f, -50f, -4f, 2f, -50f}, 4);
            assertTrue(d.coveredPixels() > 0, depth + ": the part in front of the near plane is kept");
            assertEquals(0f, d.invDepth(32, 0), depth + ": and the part behind the camera is not");
        }
    }

    @Test
    void beginOrthographicRejectsAPerspectiveMatrix() {
        DepthBuffer d = new DepthBuffer(16, 8);
        Mat4f perspective = Mat4f.perspective(FOVY, ASPECT, NEAR, 100f, ClipSpace.OPENGL);
        assertThrows(IllegalArgumentException.class, () -> d.beginOrthographic(perspective, DepthRange.NEGATIVE_ONE_TO_ONE));
        // and begin() after an orthographic frame goes back to perspective
        Mat4f vp = orthographic(Vec3f.ZERO, new Vec3f(0f, 0f, -1f), DepthRange.ZERO_TO_ONE);
        d.beginOrthographic(vp, DepthRange.ZERO_TO_ONE);
        d.begin(viewProjection(Vec3f.ZERO, new Vec3f(0f, 0f, -1f)), NEAR);
        d.addBox(box(0f, 0f, -20f, 6f, 6f, 0.5f));
        assertTrue(d.isHidden(box(0f, 0f, -40f, 1f, 1f, 1f)));
    }

    @Test
    void anOrthographicBufferNeverHidesAnObjectWithAPointThatCanBeSeen() {
        int hidden = 0, tested = 0;
        for (DepthRange depth : DepthRange.values()) {
            for (int trial = 0; trial < 60; trial++) {
                Vec3f eye = rnd.nextVec3f().mul(5f);
                Vec3f forward = rnd.nextVec3f().normalize();
                Mat4f vp = orthographic(eye, eye.add(forward), depth);
                List<Aabbf> occluders = new ArrayList<>();
                int count = 1 + (int) rnd.range(0, 5);
                for (int i = 0; i < count; i++) {
                    Vec3f centre = eye.add(forward.mul((float) rnd.range(5, 40))).add(rnd.nextVec3f().mul(1.2f));
                    occluders.add(Aabbf.fromCenterHalfExtent(centre, new Vec3f((float) rnd.range(1, 6), (float) rnd.range(1, 6), (float) rnd.range(0.3, 4))));
                }
                DepthBuffer d = new DepthBuffer(128, 64);
                d.beginOrthographic(vp, depth);
                for (Aabbf o : occluders) {
                    d.addBox(o);
                }
                d.finish();
                for (int k = 0; k < 200; k++) {
                    Aabbf o = occluders.get((int) rnd.range(0, occluders.size()));
                    Vec3f where = o.center().add(forward.mul((float) rnd.range(2, 40))).add(rnd.nextVec3f().mul(o.halfSize().length() * 0.25f));
                    Aabbf c = Aabbf.fromCenterHalfExtent(where, new Vec3f((float) rnd.range(0.1, 1.2), (float) rnd.range(0.1, 1.2), (float) rnd.range(0.1, 1.2)));
                    tested++;
                    if (!d.isHidden(c)) {
                        continue;
                    }
                    hidden++;
                    for (int s = 0; s < 40; s++) {
                        Vec3f p = s < 8 ? new Vec3f((s & 1) == 0 ? c.minX() : c.maxX(), (s & 2) == 0 ? c.minY() : c.maxY(), (s & 4) == 0 ? c.minZ() : c.maxZ())
                                : new Vec3f((float) rnd.range(c.minX(), c.maxX()), (float) rnd.range(c.minY(), c.maxY()), (float) rnd.range(c.minZ(), c.maxZ()));
                        var clip = vp.transform(new vmath.core.Vec4f(p.x(), p.y(), p.z(), 1f));
                        double sx = (clip.x() / clip.w() * 0.5 + 0.5) * 128, sy = (clip.y() / clip.w() * 0.5 + 0.5) * 64;
                        if (sx <= 0 || sx >= 128 || sy <= 0 || sy >= 64) {
                            continue;
                        }
                        float inFront = p.sub(eye).dot(forward) - ORTHO_NEAR; // the distance from p back to the near plane
                        if (!(inFront > 0f)) {
                            continue;
                        }
                        Vec3f start = p.sub(forward.mul(inFront)); // the viewer end of the ray: parallel rays, no single eye
                        boolean isBlocked = false;
                        for (Aabbf occluder : occluders) {
                            if (blocked(start.x(), start.y(), start.z(), p.x(), p.y(), p.z(), occluder)) {
                                isBlocked = true;
                                break;
                            }
                        }
                        assertTrue(isBlocked, depth + ": the buffer hid a box but point " + p + " is visible (trial " + trial + ", box " + c + ")");
                    }
                }
            }
        }
        assertTrue(hidden > 100, "the test must hide something to mean anything, hid " + hidden + " of " + tested);
    }
}
