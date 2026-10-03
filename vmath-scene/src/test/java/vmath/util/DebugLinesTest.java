package vmath.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.anim.Pose;
import vmath.anim.Skeleton;
import vmath.anim.Skinning;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.geo.Capsulef;
import vmath.geo.DepthRange;
import vmath.geo.Obbf;
import vmath.geo.Spheref;

class DebugLinesTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final Rnd rnd = Rnd.create();

    /** The end points of line {@code i}. */
    private static float[] line(DebugLines d, int i) {
        float[] p = d.positions();
        return new float[] {p[6 * i], p[6 * i + 1], p[6 * i + 2], p[6 * i + 3], p[6 * i + 4], p[6 * i + 5]};
    }

    private static double length(float[] l) {
        return Math.sqrt(Math.pow(l[3] - l[0], 2) + Math.pow(l[4] - l[1], 2) + Math.pow(l[5] - l[2], 2));
    }

    @Test
    void linesColoursAndGrowth() {
        DebugLines d = new DebugLines(2);
        assertEquals(0, d.lineCount());
        d.setColor(DebugLines.RED).line(0, 0, 0, 1, 2, 3);
        d.setColor(0.5f, 1f, 0f, 2f).line(new Vec3f(1, 1, 1), new Vec3f(2, 2, 2));
        assertEquals(2, d.lineCount());
        assertArrayEquals(new float[] {0, 0, 0, 1, 2, 3}, line(d, 0), 0f);
        assertEquals(DebugLines.RED, d.colors()[0]);
        assertEquals(DebugLines.pack(128, 255, 0, 255), d.colors()[1]); // 0.5 rounds to 128, 2 clamps to 1
        float[] before = d.positions();
        for (int i = 0; i < 50; i++) {
            d.line(i, i, i, i + 1, i + 1, i + 1);
        }
        assertEquals(52, d.lineCount());
        assertTrue(d.positions() != before, "the arrays grew");
        assertArrayEquals(new float[] {0, 0, 0, 1, 2, 3}, line(d, 0), 0f);
        assertEquals(DebugLines.pack(128, 255, 0, 255), d.colors()[51]);
        float[] live = d.positions();
        d.clear();
        assertEquals(0, d.lineCount());
        d.line(7, 7, 7, 8, 8, 8);
        assertSame(live, d.positions(), "clear keeps the memory");
        assertEquals(DebugLines.pack(128, 255, 0, 255), d.colors()[0], "clear keeps the colour");
        assertEquals(0x04030201, DebugLines.pack(1, 2, 3, 4));
        assertEquals(0xFF, DebugLines.pack(255, 0, 0, 0) & 0xFF);
        assertThrows(IllegalArgumentException.class, () -> new DebugLines(0));
        assertEquals(1024 * 6, new DebugLines().positions().length);
    }

    @Test
    void polylinesTrianglesAndMeshes() {
        DebugLines d = new DebugLines();
        float[] pts = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        d.polyline(pts, 0, 4, false);
        assertEquals(3, d.lineCount());
        d.polyline(pts, 0, 4, true);
        assertEquals(3 + 4, d.lineCount());
        assertArrayEquals(new float[] {0, 1, 0, 0, 0, 0}, line(d, 6), 0f);
        d.polyline(pts, 3, 2, true); // two points: one line, no closing
        assertEquals(8, d.lineCount());
        d.polyline(pts, 0, 0, true);
        d.polyline(pts, 0, 1, true);
        assertEquals(8, d.lineCount());
        d.triangle(new Vec3f(0, 0, 0), new Vec3f(1, 0, 0), new Vec3f(0, 1, 0));
        assertEquals(11, d.lineCount());
        d.wireTriangles(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, new int[] {0, 1, 2, 0, 2, 3}, 2);
        assertEquals(17, d.lineCount());
        assertThrows(IllegalArgumentException.class, () -> d.polyline(pts, 3, 4, false));
        assertThrows(IllegalArgumentException.class, () -> d.polyline(pts, -1, 2, false));
        d.clear();
        d.cross(1, 2, 3, 0.5f);
        assertEquals(3, d.lineCount());
        assertArrayEquals(new float[] {0.5f, 2, 3, 1.5f, 2, 3}, line(d, 0), 0f);
    }

    @Test
    void anAxisAlignedBoxHasItsTwelveEdges() {
        DebugLines d = new DebugLines();
        d.box(new Aabbf(-1, -2, -3, 4, 5, 6));
        assertEquals(12, d.lineCount());
        // every edge is along one axis, with the extent of the box; every corner is used by exactly three edges
        double[] extents = {5, 7, 9};
        int[] along = new int[3];
        java.util.Map<String, Integer> corners = new java.util.HashMap<>();
        for (int i = 0; i < 12; i++) {
            float[] l = line(d, i);
            int axis = -1, changed = 0;
            for (int k = 0; k < 3; k++) {
                if (l[k] != l[3 + k]) {
                    axis = k;
                    changed++;
                }
            }
            assertEquals(1, changed);
            assertEquals(extents[axis], length(l), 1e-6);
            along[axis]++;
            corners.merge(l[0] + "," + l[1] + "," + l[2], 1, Integer::sum);
            corners.merge(l[3] + "," + l[4] + "," + l[5], 1, Integer::sum);
        }
        assertArrayEquals(new int[] {4, 4, 4}, along);
        assertEquals(8, corners.size());
        for (int c : corners.values()) {
            assertEquals(3, c);
        }
        DebugLines empty = new DebugLines();
        empty.box(Aabbf.EMPTY);
        assertEquals(0, empty.lineCount());
    }

    @Test
    void anOrientedBoxHasTheCornersOfTheBox() {
        for (int t = 0; t < 50; t++) {
            Obbf b = Obbf.of(rnd.nextVec3f(), new Vec3f((float) rnd.range(0.5, 1.0), (float) rnd.range(1.5, 2.0), (float) rnd.range(2.5, 3.0)), rnd.nextUnitQuatf());
            DebugLines d = new DebugLines();
            d.obb(b);
            assertEquals(12, d.lineCount());
            double[] extents = {2 * b.hx(), 2 * b.hy(), 2 * b.hz()};
            int[] lengthsSeen = new int[3];
            for (int i = 0; i < 12; i++) {
                float[] l = line(d, i);
                for (int k = 0; k < 3; k++) {
                    if (Math.abs(length(l) - extents[k]) < 1e-4) {
                        lengthsSeen[k]++;
                    }
                }
                // both end points are corners of the box: in the local frame each coordinate is +-half extent
                for (int e = 0; e < 2; e++) {
                    Vec3f local = b.toLocal(new Vec3f(l[3 * e], l[3 * e + 1], l[3 * e + 2]));
                    assertEquals(b.hx(), Math.abs(local.x()), 1e-4);
                    assertEquals(b.hy(), Math.abs(local.y()), 1e-4);
                    assertEquals(b.hz(), Math.abs(local.z()), 1e-4);
                }
            }
            assertArrayEquals(new int[] {4, 4, 4}, lengthsSeen, "four edges along each axis of the box");
        }
        // an unrotated one equals the plain box
        DebugLines a = new DebugLines(), c = new DebugLines();
        a.obb(Obbf.fromAabb(new Aabbf(0, 0, 0, 1, 2, 3)));
        c.box(new Aabbf(0, 0, 0, 1, 2, 3));
        double sumA = 0, sumC = 0;
        for (int i = 0; i < 12; i++) {
            sumA += length(line(a, i));
            sumC += length(line(c, i));
        }
        assertEquals(sumC, sumA, 1e-5);
    }

    @Test
    void circlesAreRoundAndFlat() {
        for (int t = 0; t < 50; t++) {
            double z = rng.nextDouble() * 2 - 1, a = rng.nextDouble() * 6.28, r = Math.sqrt(1 - z * z);
            float nx = (float) (r * Math.cos(a)), ny = (float) (r * Math.sin(a)), nz = (float) z;
            float cx = (float) rnd.range(-3, 3), cy = (float) rnd.range(-3, 3), cz = (float) rnd.range(-3, 3), radius = (float) rnd.range(0.5, 4);
            int segments = 3 + rng.nextInt(40);
            DebugLines d = new DebugLines();
            d.circle(cx, cy, cz, nx, ny, nz, radius, segments);
            assertEquals(segments, d.lineCount());
            for (int i = 0; i < segments; i++) {
                float[] l = line(d, i);
                for (int e = 0; e < 2; e++) {
                    double dx = l[3 * e] - cx, dy = l[3 * e + 1] - cy, dz = l[3 * e + 2] - cz;
                    assertEquals(radius, Math.sqrt(dx * dx + dy * dy + dz * dz), 1e-4 * radius, "on the circle");
                    assertEquals(0.0, dx * nx + dy * ny + dz * nz, 1e-4 * radius, "in the plane");
                }
                // closed: the end of one line is the start of the next
                float[] next = line(d, (i + 1) % segments);
                assertEquals(l[3], next[0], 0f);
                assertEquals(l[4], next[1], 0f);
                assertEquals(l[5], next[2], 0f);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new DebugLines().circle(0, 0, 0, 0, 1, 0, 1, 2));
    }

    @Test
    void spheresAreThreeGreatCircles() {
        DebugLines d = new DebugLines();
        d.sphere(1, 2, 3, 2, 24);
        assertEquals(72, d.lineCount());
        for (int i = 0; i < 72; i++) {
            float[] l = line(d, i);
            for (int e = 0; e < 2; e++) {
                assertEquals(2.0, Math.sqrt(Math.pow(l[3 * e] - 1, 2) + Math.pow(l[3 * e + 1] - 2, 2) + Math.pow(l[3 * e + 2] - 3, 2)), 1e-5);
            }
        }
        DebugLines s = new DebugLines();
        s.sphere(new Spheref(1, 2, 3, 2), 24);
        assertEquals(72, s.lineCount());
        assertArrayEquals(d.positions(), s.positions(), 0f);
    }

    @Test
    void capsulesLieOnTheirSurface() {
        for (int t = 0; t < 30; t++) {
            Capsulef c = Capsulef.of(rnd.nextVec3f(), rnd.nextVec3f().mul(2), (float) rnd.range(0.2, 1.5));
            DebugLines d = new DebugLines();
            d.capsule(c, 16);
            // two circles (16 lines each), four side lines, and four half circles of 8 lines in each of two planes
            assertEquals(2 * 16 + 4 + 2 * 2 * 8, d.lineCount());
            for (int i = 0; i < d.lineCount(); i++) {
                float[] l = line(d, i);
                for (int e = 0; e < 2; e++) {
                    Vec3f p = new Vec3f(l[3 * e], l[3 * e + 1], l[3 * e + 2]);
                    Vec3f closest = c.segment().closestPoint(p);
                    assertEquals(c.radius(), p.sub(closest).length(), 2e-4 * c.radius(), "every end point is on the surface of the capsule, line " + i);
                }
            }
        }
        DebugLines point = new DebugLines();
        point.capsule(Capsulef.of(new Vec3f(1, 1, 1), new Vec3f(1, 1, 1), 2f), 12);
        assertEquals(36, point.lineCount(), "a zero-length axis is a sphere");
        assertThrows(IllegalArgumentException.class, () -> new DebugLines().capsule(Capsulef.of(new Vec3f(0, 0, 0), new Vec3f(0, 1, 0), 1f), 15));
        assertThrows(IllegalArgumentException.class, () -> new DebugLines().capsule(Capsulef.of(new Vec3f(0, 0, 0), new Vec3f(0, 1, 0), 1f), 2));
    }

    @Test
    void conesHaveTheRightOpening() {
        DebugLines d = new DebugLines();
        float half = 0.6f;
        d.cone(1, 2, 3, 0, 0, -1, 5f, half, 16);
        assertEquals(16 + 4, d.lineCount());
        double expectedRadius = 5 * Math.tan(half);
        for (int i = 0; i < 16; i++) {
            float[] l = line(d, i);
            assertEquals(-2.0, l[2], 1e-5); // the base circle is at z = 3 - 5
            assertEquals(expectedRadius, Math.hypot(l[0] - 1, l[1] - 2), 1e-4);
        }
        for (int i = 16; i < 20; i++) {
            float[] l = line(d, i);
            assertArrayEquals(new float[] {1, 2, 3}, new float[] {l[0], l[1], l[2]}, 0f);
            double angle = Math.atan2(Math.hypot(l[3] - 1, l[4] - 2), 3 - l[5]);
            assertEquals(half, angle, 1e-5);
        }
    }

    @Test
    void axesGridsAndArrows() {
        DebugLines d = new DebugLines();
        d.setColor(DebugLines.YELLOW);
        d.axes(1, 2, 3, 1, 0, 0, 0, 1, 0, 0, 0, 1, 2f);
        assertEquals(3, d.lineCount());
        assertEquals(DebugLines.RED, d.colors()[0]);
        assertEquals(DebugLines.GREEN, d.colors()[1]);
        assertEquals(DebugLines.BLUE, d.colors()[2]);
        assertArrayEquals(new float[] {1, 2, 3, 3, 2, 3}, line(d, 0), 0f);
        d.line(0, 0, 0, 1, 1, 1);
        assertEquals(DebugLines.YELLOW, d.colors()[3], "the colour is restored");
        // a scaled and rotated matrix gives unit axes
        d.clear();
        Mat4f m = Mat4f.translationRotateScale(new Vec3f(1, 2, 3), Quatf.fromAxisAngle(1.0f, new Vec3f(0, 1, 0)), new Vec3f(2, 3, 4));
        d.axes(m, 5f);
        for (int i = 0; i < 3; i++) {
            assertEquals(5.0, length(line(d, i)), 1e-4);
        }
        d.clear();
        d.grid(1, 0.5f, -1, 10f, 4);
        assertEquals(2 * 5, d.lineCount());
        for (int i = 0; i < d.lineCount(); i++) {
            float[] l = line(d, i);
            assertEquals(0.5f, l[1], 0f);
            assertEquals(0.5f, l[4], 0f);
            assertEquals(10.0, length(l), 1e-5);
        }
        assertThrows(IllegalArgumentException.class, () -> new DebugLines().grid(0, 0, 0, 1, 0));
        d.clear();
        d.arrow(new Vec3f(0, 0, 0), new Vec3f(0, 0, -4), 1f);
        assertEquals(5, d.lineCount());
        for (int i = 1; i < 5; i++) {
            float[] l = line(d, i);
            assertArrayEquals(new float[] {0, 0, -4}, new float[] {l[3], l[4], l[5]}, 0f);
            assertEquals(-3.0, l[2], 1e-5, "the head starts one head length before the tip");
            assertEquals(0.25, Math.hypot(l[0], l[1]), 1e-5);
        }
        d.clear();
        d.arrow(new Vec3f(1, 1, 1), new Vec3f(1, 1, 1), 1f); // zero length: only the shaft
        assertEquals(1, d.lineCount());
        d.clear();
        d.arrow(new Vec3f(0, 0, 0), new Vec3f(0, 0.5f, 0), 2f); // a short arrow: the head is no longer than the shaft
        assertEquals(5, d.lineCount());
    }

    @Test
    void aFrustumHasTheEightCornersOfTheNdcCube() {
        for (ClipSpace space : ClipSpace.values()) {
            for (int variant = 0; variant < 2; variant++) {
                Mat4f view = Mat4f.lookAt(new Vec3f(1, 2, 3), new Vec3f(-2, 1, -5), new Vec3f(0, 1, 0));
                Mat4f proj = variant == 0 ? Mat4f.perspective(0.9f, 1.6f, 0.5f, 80f, space) : Mat4f.ortho(-4, 4, -3, 3, 0.5f, 80f, space);
                Mat4f vp = proj.mul(view);
                DepthRange depth = DepthRange.of(space);
                DebugLines d = new DebugLines();
                d.frustum(vp, depth, 100f);
                assertEquals(12, d.lineCount());
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < 12; i++) {
                    float[] l = line(d, i);
                    for (int e = 0; e < 2; e++) {
                        Vec4f clip = vp.transform(new Vec4f(l[3 * e], l[3 * e + 1], l[3 * e + 2], 1f));
                        float x = clip.x() / clip.w(), y = clip.y() / clip.w(), z = clip.z() / clip.w();
                        assertEquals(1.0, Math.abs(x), 2e-3, space + " x");
                        assertEquals(1.0, Math.abs(y), 2e-3, space + " y");
                        float zNear = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? -1f : 0f;
                        assertTrue(Math.abs(z - zNear) < 2e-3 || Math.abs(z - 1f) < 2e-3, space + " z = " + z);
                        seen.add(Math.round(x) + "," + Math.round(y) + "," + Math.round(z * 1000f / 1000f));
                    }
                }
                assertEquals(8, seen.size(), space + " variant " + variant);
            }
        }
    }

    @Test
    void reversedAndInfiniteFrusta() {
        Mat4f view = Mat4f.lookAt(new Vec3f(0, 0, 5), new Vec3f(0, 0, 0), new Vec3f(0, 1, 0));
        // reversed depth: the near corners are at depth 1
        Mat4f reversed = Mat4f.perspectiveReversedZ(1.0f, 1.0f, 0.5f, ClipSpace.D3D).mul(view);
        DebugLines d = new DebugLines();
        d.frustum(reversed, DepthRange.REVERSED_ZERO_TO_ONE, 50f);
        assertEquals(12, d.lineCount());
        float zMax = -Float.MAX_VALUE, zMin = Float.MAX_VALUE;
        for (int i = 0; i < 12; i++) {
            float[] l = line(d, i);
            zMax = Math.max(zMax, Math.max(l[2], l[5]));
            zMin = Math.min(zMin, Math.min(l[2], l[5]));
        }
        assertEquals(4.5, zMax, 1e-3, "the near plane is 0.5 in front of the eye at z = 5");
        // an infinite far plane: the far corners are placed maxDistance along the edges
        Mat4f infinite = Mat4f.perspectiveInfinite(1.0f, 1.0f, 0.5f, ClipSpace.D3D).mul(view);
        DebugLines inf = new DebugLines();
        inf.frustum(infinite, DepthRange.ZERO_TO_ONE, 40f);
        assertEquals(12, inf.lineCount());
        double longest = 0;
        for (int i = 0; i < 12; i++) {
            longest = Math.max(longest, length(line(inf, i)));
            float[] l = line(inf, i);
            assertTrue(Float.isFinite(l[0] + l[1] + l[2] + l[3] + l[4] + l[5]));
        }
        assertEquals(40.0, longest, 0.5, "the edges from the near to the far corners are maxDistance long");
        DebugLines rev = new DebugLines();
        rev.frustum(Mat4f.perspectiveReversedZ(1.0f, 1.0f, 0.5f, ClipSpace.D3D).mul(view), DepthRange.REVERSED_ZERO_TO_ONE, 40f);
        assertEquals(12, rev.lineCount());
    }

    @Test
    void skeletonsDrawOneLinePerBoneAndMarkJoints() {
        int[] parents = {-1, 0, 1, 1, 0};
        float[] bind = new float[10 * 5];
        for (int j = 0; j < 5; j++) {
            bind[10 * j] = j == 0 ? 0f : 1f;
            bind[10 * j + 1] = j * 0.5f;
            bind[10 * j + 6] = 1f;
            bind[10 * j + 7] = bind[10 * j + 8] = bind[10 * j + 9] = 1f;
        }
        Skeleton skeleton = new Skeleton(parents, bind);
        Pose pose = new Pose(skeleton);
        pose.setToBind(skeleton);
        float[] world = new float[16 * 5];
        Skinning.worldMatrices(skeleton, pose, world);
        DebugLines d = new DebugLines();
        d.skeleton(skeleton, world, 0f);
        assertEquals(4, d.lineCount());
        for (int i = 0; i < 4; i++) {
            float[] l = line(d, i);
            int joint = i + 1;
            int parent = parents[joint];
            assertArrayEquals(new float[] {world[16 * parent + 12], world[16 * parent + 13], world[16 * parent + 14]}, new float[] {l[0], l[1], l[2]}, 0f);
            assertArrayEquals(new float[] {world[16 * joint + 12], world[16 * joint + 13], world[16 * joint + 14]}, new float[] {l[3], l[4], l[5]}, 0f);
        }
        d.clear();
        d.skeleton(skeleton, world, 0.1f);
        assertEquals(4 + 5 * 3, d.lineCount());
        assertThrows(IllegalArgumentException.class, () -> new DebugLines().skeleton(skeleton, new float[16 * 5 - 1], 0f));
    }
}
