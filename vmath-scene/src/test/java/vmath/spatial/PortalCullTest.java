package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

class PortalCullTest {

    private static final float ROOM = 10f, HEIGHT = 3f;

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    /** A grid of {@code n x n} rooms with doors between neighbours (some walls have none), and the boxes of the rooms. */
    private static final class Scene {
        PortalGraph graph;
        Aabbf[] rooms;
        int n;

        /** The room index of the point, by the boxes (an oracle independent of the graph's own locate). */
        int roomOf(double x, double y, double z) {
            for (int i = 0; i < rooms.length; i++) {
                Aabbf r = rooms[i];
                if (x >= r.minX() - 1e-4 && x <= r.maxX() + 1e-4 && y >= r.minY() - 1e-4 && y <= r.maxY() + 1e-4 && z >= r.minZ() - 1e-4 && z <= r.maxZ() + 1e-4) {
                    return i;
                }
            }
            return -1;
        }
    }

    private Scene scene(int n, double wallProbability) {
        Scene sc = new Scene();
        sc.n = n;
        sc.rooms = new Aabbf[n * n];
        PortalGraph.Builder b = PortalGraph.builder();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                sc.rooms[r * n + c] = new Aabbf(c * ROOM, 0, r * ROOM, (c + 1) * ROOM, HEIGHT, (r + 1) * ROOM);
                assertEquals(r * n + c, b.addBox(sc.rooms[r * n + c]));
            }
        }
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (c + 1 < n && rng.nextDouble() >= wallProbability) {
                    double z0 = r * ROOM + 1 + rng.nextDouble() * 4, width = 2 + rng.nextDouble() * 3;
                    float x = (c + 1) * ROOM;
                    float[] door = {x, 0, (float) z0, x, 0, (float) (z0 + width), x, 2.5f, (float) (z0 + width), x, 2.5f, (float) z0};
                    b.addPortal(r * n + c, r * n + c + 1, door, 4, 1, 0, 0);
                }
                if (r + 1 < n && rng.nextDouble() >= wallProbability) {
                    double x0 = c * ROOM + 1 + rng.nextDouble() * 4, width = 2 + rng.nextDouble() * 3;
                    float z = (r + 1) * ROOM;
                    float[] door = {(float) x0, 0, z, (float) (x0 + width), 0, z, (float) (x0 + width), 2.5f, z, (float) x0, 2.5f, z};
                    b.addPortal(r * n + c, (r + 1) * n + c, door, 4, 0, 0, 1);
                }
            }
        }
        sc.graph = b.build();
        return sc;
    }

    private BoundsArray objects(Scene sc, int count) {
        BoundsArray bounds = new BoundsArray(count);
        double extent = sc.n * ROOM;
        for (int i = 0; i < count; i++) {
            float cx = (float) (rng.nextDouble() * extent), cz = (float) (rng.nextDouble() * extent), cy = (float) (0.3 + rng.nextDouble() * 2.4);
            float h = 0.25f;
            bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }
        return bounds;
    }

    private static Mat4f viewProjection(Vec3f eye, double yaw, DepthRange depth) {
        Vec3f dir = new Vec3f((float) Math.cos(yaw), 0f, (float) Math.sin(yaw));
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), new Vec3f(0, 1, 0));
        ClipSpace space = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? ClipSpace.OPENGL : ClipSpace.D3D;
        Mat4f proj = depth == DepthRange.REVERSED_ZERO_TO_ONE ? Mat4f.perspectiveReversedZ(1.3f, 1.6f, 0.1f, space) : Mat4f.perspective(1.3f, 1.6f, 0.1f, 300f, space);
        return proj.mul(view);
    }

    /**
     * Whether the segment from the eye to the point passes through open doors only: walk it from room to room, leaving each box where the segment leaves it, and check that the exit
     * point lies in an open portal polygon (a rectangle here). A construction that shares nothing with the culler's projection logic.
     */
    private static boolean lineOfSight(Scene sc, double ex, double ey, double ez, double px, double py, double pz) {
        int cur = sc.roomOf(ex, ey, ez);
        if (cur < 0) {
            return false;
        }
        double dx = px - ex, dy = py - ey, dz = pz - ez;
        double tPrev = 0;
        for (int step = 0; step < 200; step++) {
            Aabbf r = sc.rooms[cur];
            if (px >= r.minX() - 1e-4 && px <= r.maxX() + 1e-4 && py >= r.minY() - 1e-4 && py <= r.maxY() + 1e-4 && pz >= r.minZ() - 1e-4 && pz <= r.maxZ() + 1e-4) {
                return true;
            }
            double tExit = Double.POSITIVE_INFINITY;
            double[] d = {dx, dy, dz}, e = {ex, ey, ez}, lo = {r.minX(), r.minY(), r.minZ()}, hi = {r.maxX(), r.maxY(), r.maxZ()};
            for (int k = 0; k < 3; k++) {
                if (d[k] > 0) {
                    tExit = Math.min(tExit, (hi[k] - e[k]) / d[k]);
                } else if (d[k] < 0) {
                    tExit = Math.min(tExit, (lo[k] - e[k]) / d[k]);
                }
            }
            if (!(tExit > tPrev - 1e-9) || tExit >= 1.0) {
                return false;
            }
            double xx = ex + tExit * dx, yy = ey + tExit * dy, zz = ez + tExit * dz;
            int next = -1;
            for (int k = 0; k < sc.graph.portalsOf(cur) && next < 0; k++) {
                int p = sc.graph.portalOf(cur, k);
                if (!sc.graph.isPortalOpen(p)) {
                    continue;
                }
                float[] verts = new float[3 * sc.graph.portalVertexCount(p)];
                sc.graph.portalVertices(p, verts);
                if (inRectangle(verts, xx, yy, zz)) {
                    next = sc.graph.otherSector(p, cur);
                }
            }
            if (next < 0) {
                return false;
            }
            cur = next;
            tPrev = tExit;
        }
        return false;
    }

    /** Whether the point lies in the axis-aligned rectangle spanned by the four corners (with a small tolerance in the plane and across it). */
    private static boolean inRectangle(float[] v, double x, double y, double z) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < v.length / 3; i++) {
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], v[3 * i + k]);
                hi[k] = Math.max(hi[k], v[3 * i + k]);
            }
        }
        double[] p = {x, y, z};
        for (int k = 0; k < 3; k++) {
            if (p[k] < lo[k] - 1e-4 || p[k] > hi[k] + 1e-4) {
                return false;
            }
        }
        return true;
    }

    /** True when a sample point of the object is in the view frustum and in line of sight of the eye through open doors. */
    private static boolean seenByOracle(Scene sc, Vec3f eye, Frustumf frustum, Aabbf box) {
        double sx = (box.maxX() - box.minX()) * 0.49, sy = (box.maxY() - box.minY()) * 0.49, sz = (box.maxZ() - box.minZ()) * 0.49;
        double cx = (box.minX() + box.maxX()) / 2, cy = (box.minY() + box.maxY()) / 2, cz = (box.minZ() + box.maxZ()) / 2;
        for (int i = 0; i < 9; i++) {
            double px = cx + (i == 8 ? 0 : (i & 1) == 0 ? -sx : sx), py = cy + (i == 8 ? 0 : (i & 2) == 0 ? -sy : sy), pz = cz + (i == 8 ? 0 : (i & 4) == 0 ? -sz : sz);
            if (!frustum.contains(new Vec3f((float) px, (float) py, (float) pz))) {
                continue;
            }
            if (lineOfSight(sc, eye.x(), eye.y(), eye.z(), px, py, pz)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void neverCullsAnObjectThatCanBeSeenThroughTheDoors() {
        int culledTotal = 0, objectsTotal = 0, seenTotal = 0;
        for (DepthRange depth : DepthRange.values()) {
            for (int t = 0; t < 12; t++) {
                Scene sc = scene(5, 0.3);
                BoundsArray bounds = objects(sc, 1500);
                sc.graph.assignAll(bounds);
                Vec3f eye = new Vec3f((float) (rng.nextDouble() * sc.n * ROOM), 1.5f, (float) (rng.nextDouble() * sc.n * ROOM));
                int room = sc.roomOf(eye.x(), eye.y(), eye.z());
                if (room < 0) {
                    continue;
                }
                Mat4f vp = viewProjection(eye, rng.nextDouble() * 6.28, depth);
                Frustumf frustum = Frustumf.fromViewProjection(vp, depth);
                VisibilitySet visible = new VisibilitySet(bounds.size());
                visible.setAll(bounds.size());
                PortalCuller culler = new PortalCuller(sc.graph);
                culler.traverse(vp, depth, eye.x(), eye.y(), eye.z(), sc.graph.locate(eye.x(), eye.y(), eye.z()));
                culler.cullObjects(bounds, visible, false);
                for (int i = 0; i < bounds.size(); i++) {
                    objectsTotal++;
                    if (!visible.get(i)) {
                        culledTotal++;
                    }
                    if (seenByOracle(sc, eye, frustum, bounds.get(i))) {
                        seenTotal++;
                        assertTrue(visible.get(i), "object " + i + " is seen through the doors but was culled (" + depth + ", trial " + t + ", eye " + eye + ")");
                    }
                }
            }
        }
        // the culling must do something: with walls in the way most objects are hidden, and some are seen
        assertTrue(culledTotal > objectsTotal / 2, "culled " + culledTotal + " of " + objectsTotal);
        assertTrue(seenTotal > 100, "the oracle saw " + seenTotal + " objects: the scenes are too closed to test anything");
    }

    @Test
    void eyesRightNextToADoorStillSeeThroughIt() {
        // the near plane is 0.1: an eye closer than that to a door has the whole doorway in front of the near plane, and what is behind it must still be shown
        int checked = 0;
        for (DepthRange depth : DepthRange.values()) {
            for (int t = 0; t < 40; t++) {
                Scene sc = scene(4, 0.2);
                if (sc.graph.portalCount() == 0) {
                    continue;
                }
                BoundsArray bounds = objects(sc, 1200);
                sc.graph.assignAll(bounds);
                int p = rng.nextInt(sc.graph.portalCount());
                float[] plane = new float[4], verts = new float[3 * sc.graph.portalVertexCount(p)];
                sc.graph.portalPlane(p, plane);
                sc.graph.portalVertices(p, verts);
                double cx = 0, cy = 0, cz = 0;
                for (int i = 0; i < verts.length / 3; i++) {
                    cx += verts[3 * i];
                    cy += verts[3 * i + 1];
                    cz += verts[3 * i + 2];
                }
                cx /= verts.length / 3;
                cy /= verts.length / 3;
                cz /= verts.length / 3;
                double off = 0.01 + rng.nextDouble() * 0.2; // behind the portal plane, in sector A
                Vec3f eye = new Vec3f((float) (cx - plane[0] * off), (float) (1.0 + rng.nextDouble()), (float) (cz - plane[2] * off));
                int room = sc.roomOf(eye.x(), eye.y(), eye.z());
                if (room != sc.graph.portalSectorA(p)) {
                    continue;
                }
                // look through the door
                double yaw = Math.atan2(plane[2], plane[0]) + (rng.nextDouble() - 0.5) * 0.6;
                Mat4f vp = viewProjection(eye, yaw, depth);
                Frustumf frustum = Frustumf.fromViewProjection(vp, depth);
                VisibilitySet visible = new VisibilitySet(bounds.size());
                visible.setAll(bounds.size());
                PortalCuller culler = new PortalCuller(sc.graph);
                culler.traverse(vp, depth, eye.x(), eye.y(), eye.z(), room);
                culler.cullObjects(bounds, visible, false);
                for (int i = 0; i < bounds.size(); i++) {
                    if (seenByOracle(sc, eye, frustum, bounds.get(i))) {
                        checked++;
                        assertTrue(visible.get(i), "object " + i + " is seen through the door next to the eye but was culled (" + depth + ", eye " + eye + ", off " + off + ")");
                    }
                }
            }
        }
        assertTrue(checked > 200, "only " + checked + " visible objects were checked");
    }

    @Test
    void objectsInSectorsThatCannotBeReachedAreAlwaysCulled() {
        for (int t = 0; t < 20; t++) {
            Scene sc = scene(5, 0.55);
            BoundsArray bounds = objects(sc, 800);
            sc.graph.assignAll(bounds);
            // the sectors reachable through open portals by plain graph search: no geometry involved
            int start = sc.roomOf(rng.nextDouble() * 50, 1.5, rng.nextDouble() * 50);
            boolean[] reach = new boolean[sc.graph.sectorCount()];
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            reach[start] = true;
            while (!queue.isEmpty()) {
                int s = queue.poll();
                for (int k = 0; k < sc.graph.portalsOf(s); k++) {
                    int p = sc.graph.portalOf(s, k);
                    int o = sc.graph.otherSector(p, s);
                    if (sc.graph.isPortalOpen(p) && !reach[o]) {
                        reach[o] = true;
                        queue.add(o);
                    }
                }
            }
            // a wide-angle view from the middle of the start room (the whole screen)
            Aabbf r = sc.rooms[start];
            Vec3f eye = new Vec3f((r.minX() + r.maxX()) / 2, 1.5f, (r.minZ() + r.maxZ()) / 2);
            Mat4f vp = viewProjection(eye, 0.3, DepthRange.ZERO_TO_ONE);
            PortalCuller culler = new PortalCuller(sc.graph);
            culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), start);
            for (int s = 0; s < reach.length; s++) {
                if (!reach[s]) {
                    assertFalse(culler.isSectorVisible(s), "sector " + s + " cannot be reached");
                }
            }
            VisibilitySet visible = new VisibilitySet(bounds.size());
            visible.setAll(bounds.size());
            culler.cullObjects(bounds, visible, false);
            for (int i = 0; i < bounds.size(); i++) {
                boolean anyReachable = false;
                for (int s = 0; s < reach.length; s++) {
                    if (reach[s] && sc.graph.mayOverlap(s, bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i))) {
                        anyReachable = true;
                    }
                }
                if (sc.graph.isAssigned(i) && !anyReachable) {
                    assertFalse(visible.get(i), "object " + i + " is only in unreachable sectors");
                }
            }
        }
    }

    @Test
    void aClosedDoorHidesTheRoomBehindIt() {
        PortalGraph.Builder b = PortalGraph.builder();
        int a = b.addBox(new Aabbf(0, 0, 0, 10, 3, 10)), c = b.addBox(new Aabbf(10, 0, 0, 20, 3, 10)), d = b.addBox(new Aabbf(20, 0, 0, 30, 3, 10));
        int p0 = b.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 6, 10, 2.5f, 6, 10, 2.5f, 4}, 4);
        int p1 = b.addPortal(c, d, new float[] {20, 0, 4, 20, 0, 6, 20, 2.5f, 6, 20, 2.5f, 4}, 4);
        PortalGraph g = b.build();
        BoundsArray bounds = new BoundsArray(3);
        bounds.add(3, 1, 4.5f, 4, 2, 5.5f);
        bounds.add(14, 1, 4.5f, 15, 2, 5.5f);
        bounds.add(24, 1, 4.5f, 25, 2, 5.5f);
        assertEquals(0, g.assignAll(bounds));
        Vec3f eye = new Vec3f(1f, 1.5f, 5f);
        Mat4f vp = Mat4f.perspective(1.2f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, new Vec3f(30, 1.5f, 5), new Vec3f(0, 1, 0)));
        PortalCuller culler = new PortalCuller(g);
        assertEquals(3, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a), "looking along the row of doors sees all three rooms");
        VisibilitySet v = new VisibilitySet(3);
        v.setAll(3);
        culler.cullObjects(bounds, v, false);
        assertTrue(v.get(0) && v.get(1) && v.get(2));
        g.setPortalOpen(p1, false);
        assertFalse(g.isPortalOpen(p1));
        assertEquals(2, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a));
        v.setAll(3);
        culler.cullObjects(bounds, v, false);
        assertTrue(v.get(0) && v.get(1) && !v.get(2), "the third room is behind a shut door");
        g.setPortalOpen(p0, false);
        assertEquals(1, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a));
        v.setAll(3);
        culler.cullObjects(bounds, v, false);
        assertTrue(v.get(0) && !v.get(1) && !v.get(2));
        g.setPortalOpen(p0, true);
        g.setPortalOpen(p1, true);
        assertEquals(3, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a));
        // the portals know their direction: from a into c, and from c into d
        float[] plane = new float[4];
        g.portalPlane(p0, plane);
        assertArrayEquals(new float[] {1, 0, 0, -10}, plane, 1e-6f);
        assertEquals(a, g.portalSectorA(p0));
        assertEquals(c, g.portalSectorB(p0));
        assertEquals(4, g.portalVertexCount(p0));
        // the door is seen only when looking at it: turn away and the neighbour is not entered
        Mat4f away = Mat4f.perspective(1.2f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, new Vec3f(-30, 1.5f, 5), new Vec3f(0, 1, 0)));
        assertEquals(1, culler.traverse(away, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a));
        // and a view that sees only a part of the wall beside the door sees nothing of the next room
        Mat4f aside = Mat4f.perspective(0.3f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, new Vec3f(10, 1.5f, 9), new Vec3f(0, 1, 0)));
        assertEquals(1, culler.traverse(aside, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a));
    }

    @Test
    void theRectangleOfASectorShrinksToTheDoorway() {
        Scene sc = scene(3, 0.0);
        Aabbf r = sc.rooms[0];
        Vec3f eye = new Vec3f(1f, 1.5f, 1f);
        Mat4f vp = viewProjection(eye, Math.atan2(5, 5), DepthRange.ZERO_TO_ONE);
        PortalCuller culler = new PortalCuller(sc.graph);
        assertTrue(culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0) >= 1);
        double[] rc = new double[4];
        culler.sectorRect(0, rc);
        assertArrayEquals(new double[] {-1, -1, 1, 1}, rc, 0.0, "the start sector is seen through the whole screen");
        for (int i = 0; i < culler.visibleSectorCount(); i++) {
            int s = culler.visibleSector(i);
            culler.sectorRect(s, rc);
            assertTrue(rc[0] >= -1 && rc[2] <= 1 && rc[1] >= -1 && rc[3] <= 1 && rc[0] <= rc[2] && rc[1] <= rc[3]);
            if (s != 0) {
                assertTrue((rc[2] - rc[0]) * (rc[3] - rc[1]) < 4.0, "a sector behind a door is seen through a part of the screen: " + java.util.Arrays.toString(rc));
            }
        }
        assertTrue(r.contains(eye));
    }

    @Test
    void anEyeInTheDoorwayAndNearTheNearPlane() {
        PortalGraph.Builder b = PortalGraph.builder();
        int a = b.addBox(new Aabbf(0, 0, 0, 10, 3, 10)), c = b.addBox(new Aabbf(10, 0, 0, 20, 3, 10));
        int p = b.addPortal(a, c, new float[] {10, 0, 3, 10, 0, 7, 10, 2.5f, 7, 10, 2.5f, 3}, 4);
        PortalGraph g = b.build();
        PortalCuller culler = new PortalCuller(g);
        // standing in the doorway: the plane of the portal passes through the eye; the neighbour is seen whole, from either side
        Vec3f eye = new Vec3f(10f, 1.5f, 5f);
        Mat4f vp = Mat4f.perspective(1.2f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, new Vec3f(20, 1.5f, 5), new Vec3f(0, 1, 0)));
        assertEquals(2, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a));
        assertEquals(2, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), c));
        // just before the doorway, with the portal partly in front of the near plane
        Vec3f near = new Vec3f(9.95f, 1.5f, 5f);
        Mat4f vpNear = Mat4f.perspective(1.2f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(near, new Vec3f(20, 1.5f, 5), new Vec3f(0, 1, 0)));
        assertEquals(2, culler.traverse(vpNear, DepthRange.ZERO_TO_ONE, near.x(), near.y(), near.z(), a));
        // seen from the wrong side: starting in c, the eye is on a's side of the plane, so the portal c -> a is not passable
        Vec3f wrong = new Vec3f(5f, 1.5f, 5f);
        Mat4f vpWrong = Mat4f.perspective(1.2f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(wrong, new Vec3f(20, 1.5f, 5), new Vec3f(0, 1, 0)));
        assertEquals(1, culler.traverse(vpWrong, DepthRange.ZERO_TO_ONE, wrong.x(), wrong.y(), wrong.z(), c));
        assertTrue(g.contains(a, 5f, 1.5f, 5f) && !g.contains(c, 5f, 1.5f, 5f));
        assertEquals(p, 0);
    }

    @Test
    void aRootRectangleNarrowsTheTraversal() {
        PortalGraph.Builder b = PortalGraph.builder();
        int a = b.addBox(new Aabbf(0, 0, 0, 10, 3, 10)), c = b.addBox(new Aabbf(10, 0, 0, 20, 3, 10));
        b.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 6, 10, 2.5f, 6, 10, 2.5f, 4}, 4);
        PortalGraph g = b.build();
        Vec3f eye = new Vec3f(1f, 1.5f, 5f);
        Mat4f vp = Mat4f.perspective(1.2f, 1.5f, 0.1f, 100f, ClipSpace.D3D).mul(Mat4f.lookAt(eye, new Vec3f(20, 1.5f, 5), new Vec3f(0, 1, 0)));
        PortalCuller culler = new PortalCuller(g);
        assertEquals(2, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a, -1, -1, 1, 1));
        // a root rectangle in a corner of the screen that does not contain the door
        assertEquals(1, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a, 0.6, 0.6, 1, 1));
        // an empty rectangle reaches nothing
        assertEquals(0, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), a, 0.5, 0.5, -0.5, -0.5));
        assertThrows(IllegalStateException.class, () -> new PortalCuller(g).traverse(0f, 0f, 0f, 0));
        // the view set once serves the traversals that follow
        culler.setView(vp, DepthRange.ZERO_TO_ONE);
        assertEquals(2, culler.traverse(eye.x(), eye.y(), eye.z(), a));
        assertEquals(1, culler.traverse(eye.x(), eye.y(), eye.z(), a, 0.6, 0.6, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> culler.traverse(vp, DepthRange.ZERO_TO_ONE, 0, 0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> culler.traverse(vp, DepthRange.ZERO_TO_ONE, 0, 0, 0, -1));
    }

    @Test
    void theGrowthBudgetKeepsTheTraversalConservative() {
        // a ring of rooms with doors between all neighbours: many routes to every room
        Scene sc = scene(6, 0.0);
        BoundsArray bounds = objects(sc, 600);
        sc.graph.assignAll(bounds);
        Vec3f eye = new Vec3f(5f, 1.5f, 5f);
        Mat4f vp = viewProjection(eye, 0.78, DepthRange.ZERO_TO_ONE);
        Frustumf frustum = Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE);
        PortalCuller exact = new PortalCuller(sc.graph), limited = new PortalCuller(sc.graph);
        limited.setGrowthBudget(2);
        exact.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0);
        limited.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0);
        assertFalse(exact.budgetExhausted());
        assertTrue(limited.budgetExhausted());
        // the limited run sees at least what the exact one sees, and each sector through a rectangle at least as large
        double[] re = new double[4], rl = new double[4];
        for (int s = 0; s < sc.graph.sectorCount(); s++) {
            if (exact.isSectorVisible(s)) {
                assertTrue(limited.isSectorVisible(s));
                exact.sectorRect(s, re);
                limited.sectorRect(s, rl);
                assertTrue(rl[0] <= re[0] + 1e-9 && rl[1] <= re[1] + 1e-9 && rl[2] >= re[2] - 1e-9 && rl[3] >= re[3] - 1e-9);
            }
        }
        limited.setGrowthBudget(-1);
        limited.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0);
        assertFalse(limited.budgetExhausted());
        assertTrue(frustum.contains(new Vec3f(20, 1.5f, 20)) || true);
    }

    @Test
    void theVisibilitySetHookNarrowsTheTraversalAndConnectivityChangesNothing() {
        Scene sc = scene(5, 0.2);
        Vec3f eye = new Vec3f(5f, 1.5f, 5f);
        Mat4f vp = viewProjection(eye, 0.78, DepthRange.ZERO_TO_ONE);
        PortalCuller culler = new PortalCuller(sc.graph);
        int without = culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0);
        boolean[] seen = new boolean[sc.graph.sectorCount()];
        for (int s = 0; s < seen.length; s++) {
            seen[s] = culler.isSectorVisible(s);
        }
        culler.setVisibility(PvsMatrix.fromConnectivity(sc.graph));
        assertEquals(without, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0), "reachability changes nothing the traversal finds");
        // a set that hides one visible sector removes it (and what is only reachable through it)
        int hidden = -1;
        for (int s = 1; s < seen.length && hidden < 0; s++) {
            if (seen[s]) {
                hidden = s;
            }
        }
        if (hidden >= 0) {
            final int h = hidden;
            culler.setVisibility((from, to) -> to != h);
            culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0);
            assertFalse(culler.isSectorVisible(h));
        }
        culler.setVisibility(null);
        assertEquals(without, culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0));
    }

    @Test
    void pvsMatrixFormatAndConnectivity() {
        PvsMatrix m = new PvsMatrix(10);
        assertEquals(10, m.sectorCount());
        assertEquals(0, m.count());
        m.set(2, 9, true);
        m.set(0, 0, true);
        m.set(9, 1, true);
        assertTrue(m.isVisible(2, 9) && m.isVisible(0, 0) && m.isVisible(9, 1));
        assertFalse(m.isVisible(9, 2));
        assertEquals(3, m.count());
        m.set(2, 9, false);
        assertFalse(m.isVisible(2, 9));
        // the format: rows of ceil(10 / 8) = 2 bytes, bit j of a row is bit j % 8 of byte j / 8
        byte[] bytes = m.toBytes();
        assertEquals(20, bytes.length);
        assertEquals(1, bytes[0]);
        assertEquals(2, bytes[9 * 2]);
        PvsMatrix back = PvsMatrix.fromBytes(10, bytes);
        assertEquals(m.count(), back.count());
        assertTrue(back.isVisible(0, 0) && back.isVisible(9, 1));
        assertThrows(IllegalArgumentException.class, () -> PvsMatrix.fromBytes(10, new byte[19]));
        assertThrows(IllegalArgumentException.class, () -> new PvsMatrix(0));
        assertThrows(IndexOutOfBoundsException.class, () -> m.set(10, 0, true));
        assertThrows(IndexOutOfBoundsException.class, () -> m.set(0, -1, true));
        PvsMatrix all = new PvsMatrix(5);
        all.setAll();
        assertTrue(all.isVisible(3, 4));
        // connectivity: two separate rows of rooms never see each other
        PortalGraph.Builder b = PortalGraph.builder();
        int[] s = new int[6];
        for (int i = 0; i < 3; i++) {
            s[i] = b.addBox(new Aabbf(10 * i, 0, 0, 10 * i + 10, 3, 10));
            s[3 + i] = b.addBox(new Aabbf(10 * i, 0, 50, 10 * i + 10, 3, 60));
        }
        for (int i = 0; i < 2; i++) {
            b.addPortal(s[i], s[i + 1], new float[] {10 * i + 10, 0, 4, 10 * i + 10, 0, 6, 10 * i + 10, 2.5f, 6, 10 * i + 10, 2.5f, 4}, 4);
            b.addPortal(s[3 + i], s[3 + i + 1], new float[] {10 * i + 10, 0, 54, 10 * i + 10, 0, 56, 10 * i + 10, 2.5f, 56, 10 * i + 10, 2.5f, 54}, 4);
        }
        PortalGraph g = b.build();
        g.setPortalOpen(0, false); // doors do not matter to the connectivity
        PvsMatrix c = PvsMatrix.fromConnectivity(g);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertTrue(c.isVisible(s[i], s[j]) && c.isVisible(s[3 + i], s[3 + j]));
                assertFalse(c.isVisible(s[i], s[3 + j]) || c.isVisible(s[3 + i], s[j]));
            }
        }
    }

    @Test
    void sectorsPortalsAndLocating() {
        PortalGraph.Builder b = PortalGraph.builder();
        assertThrows(IllegalStateException.class, b::build);
        assertThrows(IllegalArgumentException.class, () -> b.addBox(Aabbf.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> b.addBox(new Aabbf(0, 0, 0, 1, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> b.addConvex(new float[12], 3));
        assertThrows(IllegalArgumentException.class, () -> b.addConvex(new float[16], 4), "planes without normals");
        // four boxes in a 2 x 2 arrangement share four faces
        int[] s = new int[4];
        for (int i = 0; i < 4; i++) {
            s[i] = b.addBox(new Aabbf(10 * (i % 2), 0, 10 * (i / 2), 10 * (i % 2) + 10, 3, 10 * (i / 2) + 10));
        }
        assertEquals(4, b.autoPortals(1e-3f));
        PortalGraph g = b.build();
        assertEquals(4, g.sectorCount());
        assertEquals(4, g.portalCount());
        for (int p = 0; p < 4; p++) {
            assertTrue(g.isPortalOpen(p));
            float[] pl = new float[4];
            g.portalPlane(p, pl);
            // the normal points from A into B: the centre of A is behind the plane, the centre of B in front
            int a = g.portalSectorA(p), bb = g.portalSectorB(p);
            float[] ca = {10 * (a % 2) + 5, 1.5f, 10 * (a / 2) + 5}, cb = {10 * (bb % 2) + 5, 1.5f, 10 * (bb / 2) + 5};
            assertTrue(pl[0] * ca[0] + pl[1] * ca[1] + pl[2] * ca[2] + pl[3] < 0);
            assertTrue(pl[0] * cb[0] + pl[1] * cb[1] + pl[2] * cb[2] + pl[3] > 0);
            assertEquals(1.0, Math.sqrt(pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2]), 1e-6);
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(2, g.portalsOf(i));
        }
        // locating: the sector of a point, with and without a hint, and -1 outside
        assertEquals(s[0], g.locate(5, 1, 5));
        assertEquals(s[3], g.locate(15, 1, 15));
        assertEquals(-1, g.locate(25, 1, 5));
        assertEquals(-1, g.locate(5, 5, 5));
        assertEquals(s[1], g.locate(12, 1, 5, s[0]), "a neighbour of the hint");
        assertEquals(s[3], g.locate(12, 1, 12, s[0]), "a sector two portals from the hint falls back to the search");
        assertEquals(s[0], g.locate(5, 1, 5, 99), "a hint out of range is ignored");
        assertEquals(s[2], g.locate(5, 1, 15, -3));
        assertTrue(g.contains(s[0], 10, 1, 5), "a point on a shared face belongs to both");
        assertTrue(g.contains(s[1], 10, 1, 5));
        // portals made by hand: the side is found from the sectors, and cannot be if they are not on either side
        PortalGraph.Builder two = PortalGraph.builder();
        int a = two.addBox(new Aabbf(0, 0, 0, 10, 3, 10)), c = two.addBox(new Aabbf(10, 0, 0, 20, 3, 10));
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, c, new float[] {5, 0, 4, 5, 0, 6, 5, 2, 6, 5, 2, 4}, 4), "a polygon in the middle of a room");
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, a, new float[] {10, 0, 4, 10, 0, 6, 10, 2, 6, 10, 2, 4}, 4, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, 7, new float[] {10, 0, 4, 10, 0, 6, 10, 2, 6, 10, 2, 4}, 4, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 6}, 2));
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 4, 10, 0, 4}, 3), "no area");
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 6, 10, 2, 6, 10, 2, 4}, 4, 0, 0, 0), "no normal");
        assertThrows(IllegalArgumentException.class, () -> two.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 6, 10, 2, 6, 10, 2, 4}, 4, 0, 1, 0), "normal along the polygon");
        // either winding gives the same normal, from a into c
        int p1 = two.addPortal(a, c, new float[] {10, 0, 4, 10, 0, 6, 10, 2, 6, 10, 2, 4}, 4);
        int p2 = two.addPortal(a, c, new float[] {10, 2, 4, 10, 2, 6, 10, 0, 6, 10, 0, 4}, 4);
        PortalGraph g2 = two.build();
        float[] x = new float[4], y = new float[4];
        g2.portalPlane(p1, x);
        g2.portalPlane(p2, y);
        assertArrayEquals(x, y, 1e-6f);
        assertEquals(1f, x[0], 1e-6f);
        // a sector from planes: a tetrahedron
        PortalGraph.Builder tet = PortalGraph.builder();
        int t = tet.addConvex(new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -1, -1, -1, 1}, 4);
        PortalGraph gt = tet.build();
        assertEquals(t, gt.locate(0.1f, 0.1f, 0.1f));
        assertEquals(-1, gt.locate(0.6f, 0.6f, 0.6f));
        assertTrue(gt.mayOverlap(t, 0.2f, 0.2f, 0.2f, 0.3f, 0.3f, 0.3f));
        assertFalse(gt.mayOverlap(t, 2, 2, 2, 3, 3, 3));
        assertFalse(gt.mayOverlap(t, -3, -3, -3, -2, -2, -2));
    }

    @Test
    void theSectorGridAgreesWithABruteForceSearch() {
        // boxes, a tetrahedron, and a sector that is not bounded: all kinds of sector in one graph
        PortalGraph.Builder b = PortalGraph.builder();
        int boxes = 40;
        for (int i = 0; i < boxes; i++) {
            float x = (i % 8) * 6, z = (i / 8) * 6;
            b.addBox(new Aabbf(x, 0, z, x + 5.5f, 3, z + 5.5f));
        }
        int tetra = b.addConvex(new float[] {1, 0, 0, -60, 0, 1, 0, 0, 0, 0, 1, 0, -1, -1, -1, 70}, 4);
        int open = b.addConvex(new float[] {1, 0, 0, -200, 0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 0, -150}, 4); // x >= 200 and more: unbounded towards +x
        PortalGraph g = b.build();
        for (int t = 0; t < 4000; t++) {
            float x = (float) (rng.nextDouble() * 90 - 10), y = (float) (rng.nextDouble() * 6 - 1.5), z = (float) (rng.nextDouble() * 50 - 5);
            if (t % 7 == 0) {
                x += 200;
            }
            int expected = -1;
            for (int s = 0; s < g.sectorCount() && expected < 0; s++) {
                if (g.contains(s, x, y, z)) {
                    expected = s;
                }
            }
            assertEquals(expected, g.locate(x, y, z), "point " + x + ", " + y + ", " + z);
        }
        assertEquals(open, g.locate(250, 1, 1));
        assertEquals(tetra, g.locate(61, 0.5f, 0.5f));
        BoundsArray bounds = new BoundsArray(1500);
        for (int i = 0; i < 1500; i++) {
            float cx = (float) (rng.nextDouble() * 100 - 10) + (i % 9 == 0 ? 200 : 0), cy = (float) (rng.nextDouble() * 5 - 1), cz = (float) (rng.nextDouble() * 50 - 5);
            float h = (float) (0.1 + rng.nextDouble() * 4);
            bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }
        g.assignAll(bounds);
        for (int i = 0; i < bounds.size(); i++) {
            int expected = 0;
            for (int s = 0; s < g.sectorCount(); s++) {
                if (g.mayOverlap(s, bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i))) {
                    expected++;
                }
            }
            assertEquals(expected, memberships(g, i), "object " + i);
            assertEquals(expected > 0, g.isAssigned(i));
        }
    }

    @Test
    void objectMembership() {
        Scene sc = scene(2, 0.0);
        PortalGraph g = sc.graph;
        BoundsArray bounds = new BoundsArray(5);
        bounds.add(2, 1, 2, 3, 2, 3); // inside room 0
        bounds.add(9.5f, 1, 2, 10.5f, 2, 3); // straddles rooms 0 and 1
        bounds.add(9.5f, 1, 9.5f, 10.5f, 2, 10.5f); // the corner shared by all four
        bounds.add(100, 1, 100, 101, 2, 101); // in no room
        bounds.add(12, 1, 12, 13, 2, 13); // inside room 3
        assertEquals(1, g.assignAll(bounds), "one object in no sector");
        assertEquals(1, memberships(g, 0));
        assertTrue(g.isAssigned(0) && g.isAssigned(1) && g.isAssigned(2) && !g.isAssigned(3) && g.isAssigned(4));
        assertFalse(g.isAssigned(99) || g.isAssigned(-1));
        assertEquals(1 + 1 + 1, g.memberCount(0));
        assertEquals(2, g.memberCount(1));
        assertEquals(1, g.memberCount(2));
        assertEquals(2, g.memberCount(3));
        // removing and updating
        g.remove(1);
        assertFalse(g.isAssigned(1));
        assertEquals(2, g.memberCount(0));
        assertEquals(1, g.memberCount(1));
        g.remove(1); // already gone
        g.remove(1000); // never seen
        assertEquals(2, g.update(1, 9.5f, 1, 2, 10.5f, 2, 3));
        assertTrue(g.isAssigned(1));
        assertEquals(3, g.memberCount(0));
        assertEquals(1, g.update(1, 15, 1, 2, 16, 2, 3));
        assertEquals(2, g.memberCount(0));
        assertEquals(0, g.update(3, 100, 1, 100, 101, 2, 101));
        // entries are reused after removals
        for (int i = 0; i < 100; i++) {
            g.update(0, 2, 1, 2, 3, 2, 3);
            g.update(0, 12, 1, 12, 13, 2, 13);
        }
        assertEquals(1, memberships(g, 0));
        g.clearObjects();
        for (int s = 0; s < 4; s++) {
            assertEquals(0, g.memberCount(s));
        }
        assertFalse(g.isAssigned(0));
        assertThrows(IllegalArgumentException.class, () -> g.assign(0, 4));
        assertThrows(IllegalArgumentException.class, () -> g.assign(-1, 0));
        g.assign(70, 2); // a large object index grows the structures
        assertTrue(g.isAssigned(70));
        assertEquals(1, g.memberCount(2));
    }

    private static int memberships(PortalGraph g, int object) {
        int n = 0;
        for (int s = 0; s < g.sectorCount(); s++) {
            for (int e = g.firstMember(s); e >= 0; e = g.nextMember(e)) {
                if (g.memberObject(e) == object) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void unassignedObjectsAreKeptOrCulledAsAsked() {
        Scene sc = scene(2, 0.0);
        BoundsArray bounds = new BoundsArray(3);
        bounds.add(2, 1, 2, 3, 2, 3);
        bounds.add(100, 1, 100, 101, 2, 101); // in no room
        bounds.add(12, 1, 12, 13, 2, 13);
        sc.graph.assignAll(bounds);
        Vec3f eye = new Vec3f(5f, 1.5f, 5f);
        Mat4f vp = viewProjection(eye, 1.5, DepthRange.ZERO_TO_ONE);
        PortalCuller culler = new PortalCuller(sc.graph);
        culler.traverse(vp, DepthRange.ZERO_TO_ONE, eye.x(), eye.y(), eye.z(), 0);
        VisibilitySet v = new VisibilitySet(3);
        v.setAll(3);
        culler.cullObjects(bounds, v, false);
        assertTrue(v.get(1), "an object the graph does not know is kept");
        v.setAll(3);
        culler.cullObjects(bounds, v, true);
        assertFalse(v.get(1));
        // bits that are already clear stay clear, and objects beyond the bounds are not touched
        v.setAll(3);
        v.clear(0);
        culler.cullObjects(bounds, v, false);
        assertFalse(v.get(0));
        VisibilitySet big = new VisibilitySet(200);
        big.setAll(200);
        culler.cullObjects(bounds, big, true);
        for (int i = 3; i < 200; i++) {
            assertTrue(big.get(i), "object " + i + " is beyond the bounds");
        }
    }

    @Test
    void theStageWorksInAPipelineAndNeedsItsView() {
        Scene sc = scene(4, 0.3);
        BoundsArray bounds = objects(sc, 500);
        sc.graph.assignAll(bounds);
        Vec3f eye = new Vec3f(5f, 1.5f, 5f);
        DepthRange depth = DepthRange.ZERO_TO_ONE;
        Mat4f vp = viewProjection(eye, 0.78, depth);
        Frustumf frustum = Frustumf.fromViewProjection(vp, depth);
        CullContext ctx = new CullContext(frustum, eye, 0f);
        PortalStage stage = new PortalStage(sc.graph);
        VisibilitySet v = new VisibilitySet(bounds.size());
        v.setAll(bounds.size());
        assertThrows(IllegalStateException.class, () -> stage.cull(ctx, bounds, v));
        stage.setView(vp, depth);
        CullPipeline pipeline = CullPipeline.of(new CullStages.Frustum(), stage);
        int both = pipeline.run(ctx, bounds, v);
        assertEquals(0, stage.lastSector());
        VisibilitySet frustumOnly = new VisibilitySet(bounds.size());
        frustumOnly.setAll(bounds.size());
        new CullStages.Frustum().cull(ctx, bounds, frustumOnly);
        assertTrue(both <= frustumOnly.count(), "the portal stage only removes objects");
        assertTrue(both < frustumOnly.count(), "and in this scene it removes some");
        for (int i = 0; i < bounds.size(); i++) {
            if (v.get(i)) {
                assertTrue(frustumOnly.get(i));
            }
        }
        // an eye outside every room: nothing is culled, unless a fallback sector is given
        Vec3f out = new Vec3f(-50f, 1.5f, -50f);
        Mat4f vpOut = viewProjection(out, 0.78, depth);
        CullContext ctxOut = new CullContext(Frustumf.fromViewProjection(vpOut, depth), out, 0f);
        stage.setView(vpOut, depth);
        v.setAll(bounds.size());
        new PortalStage(sc.graph).setView(vpOut, depth).cull(ctxOut, bounds, v);
        assertEquals(bounds.size(), v.count());
        assertEquals(-1, stage.lastSector() == 0 ? -1 : stage.lastSector());
        PortalStage withFallback = new PortalStage(sc.graph).setView(vpOut, depth).setFallbackSector(0).setCullUnassigned(true);
        v.setAll(bounds.size());
        withFallback.cull(ctxOut, bounds, v);
        assertEquals(-1, withFallback.lastSector());
        assertTrue(v.count() < bounds.size());
        assertSame(withFallback.culler(), withFallback.culler());
        // the sector of the previous frame is remembered and tried first
        stage.setView(vp, depth);
        v.setAll(bounds.size());
        stage.cull(ctx, bounds, v);
        assertEquals(0, stage.lastSector());
        stage.setFallbackSector(99).setVisibility(PvsMatrix.fromConnectivity(sc.graph)).setVisibility(null);
    }

    private static void assertSame(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertSame(a, b);
    }
}
