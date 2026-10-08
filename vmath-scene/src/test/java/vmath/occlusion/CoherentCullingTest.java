package vmath.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.spatial.StaticBvh;

/**
 * {@link CoherentCulling} and {@link VisibilityHistory} against a software renderer that is exact:
 * boxes seen along z on a 64 by 64 pixel grid, each covering whole pixels at a distinct depth. After
 * every frame the depth image of the objects the culling drew must be the depth image of all of them
 * (nothing that shows was left out), every object that shows in the full image must have been drawn,
 * and the queries are counted against a query for every node.
 */
class CoherentCullingTest {

    private static final int SIZE = 64;

    /** The scene: boxes with integer corners in x and y, a distinct front depth each. */
    private static final class Scene {
        final int n;
        final int[] x0, y0, x1, y1;
        final float[] z;
        final BoundsArray bounds;

        Scene(int n, long seed, int extent) {
            this.n = n;
            Random rnd = new Random(seed);
            x0 = new int[n];
            y0 = new int[n];
            x1 = new int[n];
            y1 = new int[n];
            z = new float[n];
            bounds = new BoundsArray(n);
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = perm[i];
                perm[i] = perm[j];
                perm[j] = t;
            }
            for (int i = 0; i < n; i++) {
                x0[i] = rnd.nextInt(extent);
                y0[i] = rnd.nextInt(SIZE);
                x1[i] = x0[i] + 2 + rnd.nextInt(9);
                y1[i] = y0[i] + 2 + rnd.nextInt(9);
                z[i] = 1f + perm[i] * 0.5f;
                bounds.add(x0[i], y0[i], z[i], x1[i], y1[i], z[i] + 1f);
            }
        }
    }

    /** A depth buffer and the occlusion queries of a GPU that works on it, with a latency in polls. */
    private static final class Raster implements OcclusionQueries {
        final float[] depth = new float[SIZE * SIZE];
        final int[] id = new int[SIZE * SIZE];
        int window;
        final int latency;
        int[] pollsLeft = new int[1024];
        int[] results = new int[1024];
        int queries;
        int next;

        Raster(int window, int latency) {
            this.window = window;
            this.latency = latency;
            Arrays.fill(depth, Float.MAX_VALUE);
            Arrays.fill(id, -1);
        }

        void draw(Scene s, int object) {
            for (int y = Math.max(0, s.y0[object]); y < Math.min(SIZE, s.y1[object]); y++) {
                for (int x = Math.max(0, s.x0[object] - window); x < Math.min(SIZE, s.x1[object] - window); x++) {
                    if (s.z[object] < depth[y * SIZE + x]) {
                        depth[y * SIZE + x] = s.z[object];
                        id[y * SIZE + x] = object;
                    }
                }
            }
        }

        @Override
        public int issue(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            int count = 0;
            for (int y = Math.max(0, (int) Math.floor(minY)); y < Math.min(SIZE, (int) Math.ceil(maxY)); y++) {
                for (int x = Math.max(0, (int) Math.floor(minX) - window); x < Math.min(SIZE, (int) Math.ceil(maxX) - window); x++) {
                    if (minZ < depth[y * SIZE + x]) {
                        count++;
                    }
                }
            }
            if (next == results.length) {
                results = Arrays.copyOf(results, next * 2);
                pollsLeft = Arrays.copyOf(pollsLeft, next * 2);
            }
            results[next] = count;
            pollsLeft[next] = latency;
            queries++;
            return next++;
        }

        @Override
        public boolean isReady(int query) {
            return pollsLeft[query]-- <= 0;
        }

        @Override
        public int visibleSamples(int query) {
            return results[query];
        }
    }

    /** The planes of the window {@code [window, window + SIZE)} in x and the whole of y, looking along z. */
    private static float[] planes(int window) {
        return new float[] {1, 0, 0, -window, -1, 0, 0, window + SIZE, 0, 1, 0, 0, 0, -1, 0, SIZE, 0, 0, 1, 0, 0, 0, -1, 5000};
    }

    private static Raster reference(Scene s, int window) {
        Raster r = new Raster(window, 0);
        for (int i = 0; i < s.n; i++) {
            r.draw(s, i);
        }
        return r;
    }

    private static void assertSameImage(Raster expected, Raster actual, String message) {
        assertTrue(Arrays.equals(expected.depth, actual.depth), message + ": the depth images differ");
        assertTrue(Arrays.equals(expected.id, actual.id), message + ": the object images differ");
    }

    private static Set<Integer> shown(Raster r) {
        Set<Integer> out = new HashSet<>();
        for (int v : r.id) {
            if (v >= 0) {
                out.add(v);
            }
        }
        return out;
    }

    @Test
    void whateverTheLatencyTheImageIsTheImageOfAllTheObjects() {
        Scene scene = new Scene(1500, 3, 220);
        for (int latency : new int[] {0, 3, 40}) {
            StaticBvh bvh = StaticBvh.build(scene.bounds, 4);
            CoherentCulling culling = new CoherentCulling(bvh, new VisibilityHistory(scene.n));
            for (int frame = 0; frame < 60; frame++) {
                int window = 2 * frame;                                           // the camera pans
                Raster raster = new Raster(window, latency);
                culling.cull(planes(window), SIZE / 2f + window, SIZE / 2f, -2000f, raster, o -> raster.draw(scene, o));
                Raster brute = reference(scene, window);
                assertSameImage(brute, raster, "latency " + latency + ", frame " + frame);
            }
        }
    }

    @Test
    void everyObjectThatShowsIsDrawnAndSteadyStateAsksFewerQuestions() {
        Scene scene = new Scene(3000, 7, 70);
        StaticBvh bvh = StaticBvh.build(scene.bounds, 4);
        VisibilityHistory history = new VisibilityHistory(scene.n);
        CoherentCulling culling = new CoherentCulling(bvh, history);
        int first = -1, last = -1;
        int nodes = bvh.nodeCount();
        Set<Integer> expectedShown = shown(reference(scene, 0));
        for (int frame = 0; frame < 30; frame++) {
            Raster raster = new Raster(0, 2);
            Set<Integer> drawn = new HashSet<>();
            CoherentCulling.Stats stats = culling.cull(planes(0), SIZE / 2f, SIZE / 2f, -2000f, raster, o -> {
                drawn.add(o);
                raster.draw(scene, o);
            });
            assertTrue(drawn.containsAll(expectedShown), "an object that shows was not drawn in frame " + frame);
            assertEquals(drawn.size(), stats.drawnObjects());
            assertEquals(raster.queries, stats.queries());
            assertEquals(stats.queries(), stats.visibleResults() + stats.hiddenResults());
            if (frame == 0) {
                first = stats.queries();
            }
            last = stats.queries();
            assertTrue(stats.queries() < nodes, "fewer queries than nodes");
            // what the history says is what was drawn
            for (int i = 0; i < scene.n; i++) {
                assertEquals(drawn.contains(i), history.wasVisible(i), "object " + i + " in frame " + frame);
            }
        }
        assertTrue(last < first, "steady state: " + last + " queries against " + first + " in the first frame");
        assertTrue(last < nodes / 3, "steady state asks about few nodes: " + last + " of " + nodes);
        System.out.printf("coherent culling: %d objects, %d nodes: %d queries in the first frame, %d in steady state%n", scene.n, nodes, first, last);
    }

    @Test
    void aVisibleLeafIsDrawnWithoutWaitingForAnyQuery() {
        Scene scene = new Scene(400, 11, 70);
        StaticBvh bvh = StaticBvh.build(scene.bounds, 4);
        CoherentCulling culling = new CoherentCulling(bvh, null);
        culling.setQueryInterval(1000);
        for (int frame = 0; frame < 6; frame++) {
            Raster raster = new Raster(0, 1000);          // results that are never ready until forced
            Set<Integer> drawn = new HashSet<>();
            culling.cull(planes(0), SIZE / 2f, SIZE / 2f, -2000f, raster, o -> {
                drawn.add(o);
                raster.draw(scene, o);
            });
            assertTrue(drawn.containsAll(shown(reference(scene, 0))), "frame " + frame);
        }
        assertEquals(6, culling.frame());
    }

    @Test
    void anObjectThatBecomesVisibleIsFoundWhateverTheHistorySays() {
        // an occluder in front, then removed: what it hid must appear in the next frame
        BoundsArray b = new BoundsArray(3);
        b.add(0, 0, 1, 40, 40, 2);              // the occluder, in front
        b.add(5, 5, 10, 15, 15, 11);            // hidden behind it
        b.add(50, 50, 5, 60, 60, 6);            // always visible
        StaticBvh bvh = StaticBvh.build(b, 1);
        CoherentCulling culling = new CoherentCulling(bvh, null);
        Raster r1 = new Raster(0, 0);
        Set<Integer> drawn1 = new HashSet<>();
        for (int f = 0; f < 3; f++) {
            r1 = new Raster(0, 0);
            drawn1.clear();
            Raster rr = r1;
            culling.cull(planes(0), 32f, 32f, -2000f, rr, o -> {
                drawn1.add(o);
                rr.draw(scene3(), o);
            });
        }
        assertTrue(drawn1.contains(0) && drawn1.contains(2) && !drawn1.contains(1), "the hidden object is not drawn: " + drawn1);
        // now the occluder is far to the right of the window: the same culler, the same tree shape, the window moved
        Raster r2 = new Raster(45, 0);
        Set<Integer> drawn2 = new HashSet<>();
        culling.cull(planes(45), 32f + 45, 32f, -2000f, r2, o -> {
            drawn2.add(o);
            r2.draw(scene3(), o);
        });
        assertFalse(drawn2.contains(1), "it is outside the window of the second frame");
        Raster r3 = new Raster(0, 0);
        Set<Integer> drawn3 = new HashSet<>();
        BoundsArray moved = new BoundsArray(3);
        moved.add(100, 0, 1, 140, 40, 2);       // the occluder has moved away
        moved.add(5, 5, 10, 15, 15, 11);
        moved.add(50, 50, 5, 60, 60, 6);
        bvh.refit(moved);
        Scene s = scene3();
        s.x0[0] = 100;
        s.x1[0] = 140;
        culling.cull(planes(0), 32f, 32f, -2000f, r3, o -> {
            drawn3.add(o);
            r3.draw(s, o);
        });
        assertTrue(drawn3.contains(1), "it is no longer hidden: " + drawn3);
    }

    private static Scene scene3() {
        Scene s = new Scene(3, 1, 10);
        int[][] boxes = {{0, 0, 40, 40}, {5, 5, 15, 15}, {50, 50, 60, 60}};
        float[] zs = {1f, 10f, 5f};
        for (int i = 0; i < 3; i++) {
            s.x0[i] = boxes[i][0];
            s.y0[i] = boxes[i][1];
            s.x1[i] = boxes[i][2];
            s.y1[i] = boxes[i][3];
            s.z[i] = zs[i];
        }
        return s;
    }

    @Test
    void theFrustumAndTheSettingsAreChecked() {
        BoundsArray b = new BoundsArray(1);
        b.add(0, 0, 0, 1, 1, 1);
        CoherentCulling c = new CoherentCulling(StaticBvh.build(b, 1), null);
        assertThrows(IllegalArgumentException.class, () -> c.setQueryInterval(0));
        assertThrows(IllegalArgumentException.class, () -> c.setMinimumSamples(0));
        assertThrows(IllegalArgumentException.class, () -> c.cull(new float[23], 0f, 0f, 0f, new Raster(0, 0), o -> {
        }));
        c.setQueryInterval(5);
        assertEquals(5, c.queryInterval());
        // a frustum that excludes everything draws nothing and asks nothing
        float[] none = {1, 0, 0, -100, -1, 0, 0, 200, 0, 1, 0, 0, 0, -1, 0, 64, 0, 0, 1, 0, 0, 0, -1, 5000};
        CoherentCulling.Stats stats = c.cull(none, 0f, 0f, -10f, new Raster(0, 0), o -> {
            throw new AssertionError("nothing is in the frustum");
        });
        assertEquals(0, stats.queries());
        assertEquals(1, stats.frustumCulled());
    }

    @Test
    void theHistoryCountsStreaksAndRefusesMisuse() {
        VisibilityHistory h = new VisibilityHistory(4);
        assertThrows(IllegalStateException.class, () -> h.markVisible(0));
        assertThrows(IllegalStateException.class, h::endFrame);
        h.beginFrame();
        assertThrows(IllegalStateException.class, h::beginFrame);
        h.markVisible(1);
        h.markVisible(1);
        h.endFrame();
        assertTrue(h.wasVisible(1));
        assertFalse(h.wasVisible(0));
        assertEquals(1, h.visibleStreak(1));
        assertEquals(1, h.hiddenStreak(0));
        h.beginFrame();
        h.markVisible(1);
        h.markVisible(2);
        h.endFrame();
        assertEquals(2, h.visibleStreak(1));
        assertEquals(1, h.visibleStreak(2));
        assertEquals(2, h.hiddenStreak(0));
        h.beginFrame();
        h.endFrame();
        assertEquals(0, h.visibleStreak(1));
        assertEquals(1, h.hiddenStreak(1));
        assertEquals(1, h.lastVisibleFrame(1));
        assertEquals(1, h.frame() - 1);
        int[] out = new int[4];
        assertEquals(0, h.listVisible(out));
        h.beginFrame();
        h.markVisible(3);
        h.markVisible(0);
        h.endFrame();
        assertEquals(2, h.listVisible(out));
        assertEquals(0, out[0]);
        assertEquals(3, out[1]);
        h.ensureCapacity(10);
        assertEquals(10, h.capacity());
        assertFalse(h.wasVisible(9));
        h.clear();
        assertEquals(-1, h.frame());
        assertFalse(h.wasVisible(0));
        assertThrows(IllegalArgumentException.class, () -> new VisibilityHistory(0));
    }
}
