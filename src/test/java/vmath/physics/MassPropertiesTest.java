package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Mat3d;
import vmath.core.Quatd;
import vmath.core.Rnd;
import vmath.core.Vec3d;
import vmath.geo.Aabbf;
import vmath.geo.ConvexPolytope;
import vmath.physics.MassProperties.Axis;
import vmath.util.Sequences;

class MassPropertiesTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    /** A solid by its inside test and a box around it. */
    private interface Solid {
        boolean contains(double x, double y, double z);
    }

    /**
     * The mass properties of a uniform solid by numerical integration: points of a low-discrepancy sequence in the box, those inside the solid carry equal shares of the mass. The centre of
     * mass and the tensor about it come from sums, an oracle that shares nothing with the closed forms.
     */
    private static double[] integrate(Solid solid, double[] lo, double[] hi, double mass, int samples) {
        double sx = 0, sy = 0, sz = 0;
        int inside = 0;
        double[] px = new double[samples], py = new double[samples], pz = new double[samples];
        for (int i = 0; i < samples; i++) {
            double x = lo[0] + (hi[0] - lo[0]) * Sequences.halton(i + 1, 0);
            double y = lo[1] + (hi[1] - lo[1]) * Sequences.halton(i + 1, 1);
            double z = lo[2] + (hi[2] - lo[2]) * Sequences.halton(i + 1, 2);
            if (solid.contains(x, y, z)) {
                px[inside] = x;
                py[inside] = y;
                pz[inside] = z;
                sx += x;
                sy += y;
                sz += z;
                inside++;
            }
        }
        double cx = sx / inside, cy = sy / inside, cz = sz / inside, m = mass / inside;
        double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
        for (int i = 0; i < inside; i++) {
            double x = px[i] - cx, y = py[i] - cy, z = pz[i] - cz;
            xx += m * x * x;
            yy += m * y * y;
            zz += m * z * z;
            xy += m * x * y;
            xz += m * x * z;
            yz += m * y * z;
        }
        return new double[] {cx, cy, cz, yy + zz, xx + zz, xx + yy, -xy, -xz, -yz};
    }

    private static void assertMatches(MassProperties p, double[] ref, double size, double relative, String what) {
        double scale = Math.max(p.ixx(), Math.max(p.iyy(), p.izz()));
        assertEquals(ref[0], p.centerOfMass().x(), 0.01 * size, what + " com x");
        assertEquals(ref[1], p.centerOfMass().y(), 0.01 * size, what + " com y");
        assertEquals(ref[2], p.centerOfMass().z(), 0.01 * size, what + " com z");
        assertEquals(ref[3], p.ixx(), relative * scale, what + " Ixx");
        assertEquals(ref[4], p.iyy(), relative * scale, what + " Iyy");
        assertEquals(ref[5], p.izz(), relative * scale, what + " Izz");
        Mat3d m = p.inertia();
        assertEquals(ref[6], m.m10(), relative * scale, what + " Ixy");
        assertEquals(ref[7], m.m20(), relative * scale, what + " Ixz");
        assertEquals(ref[8], m.m21(), relative * scale, what + " Iyz");
    }

    @Test
    void thePrimitivesMatchNumericalIntegration() {
        int n = 1_500_000;
        double mass = 2.5;
        // sphere
        double r = 1.3;
        assertMatches(MassProperties.sphere(r, mass), integrate((x, y, z) -> x * x + y * y + z * z <= r * r, new double[] {-r, -r, -r}, new double[] {r, r, r}, mass, n), r, 0.01, "sphere");
        // box
        assertMatches(MassProperties.box(0.5, 1.0, 2.0, mass), integrate((x, y, z) -> true, new double[] {-0.5, -1, -2}, new double[] {0.5, 1, 2}, mass, 200_000), 1, 0.01, "box");
        // ellipsoid
        assertMatches(MassProperties.ellipsoid(1, 1.5, 0.7, mass), integrate((x, y, z) -> x * x + y * y / 2.25 + z * z / 0.49 <= 1, new double[] {-1, -1.5, -0.7}, new double[] {1, 1.5, 0.7}, mass, n), 1, 0.01, "ellipsoid");
        // cylinder, capsule and cone along each axis
        double cr = 0.8, ch = 1.1;
        for (Axis axis : Axis.values()) {
            final int a = axis.ordinal(), b = (a + 1) % 3, c = (a + 2) % 3;
            double[] lo = new double[3], hi = new double[3];
            lo[a] = -ch - cr;
            hi[a] = ch + cr;
            lo[b] = lo[c] = -cr;
            hi[b] = hi[c] = cr;
            assertMatches(MassProperties.cylinder(cr, ch, axis, mass), integrate((x, y, z) -> {
                double[] p = {x, y, z};
                return Math.abs(p[a]) <= ch && p[b] * p[b] + p[c] * p[c] <= cr * cr;
            }, lo, hi, mass, n), 1, 0.01, "cylinder " + axis);
            assertMatches(MassProperties.capsule(cr, ch, axis, mass), integrate((x, y, z) -> {
                double[] p = {x, y, z};
                double along = Math.max(Math.abs(p[a]) - ch, 0);
                return along * along + p[b] * p[b] + p[c] * p[c] <= cr * cr;
            }, lo, hi, mass, n), 1, 0.01, "capsule " + axis);
            double height = 2 * ch;
            assertMatches(MassProperties.cone(cr, height, axis, mass), integrate((x, y, z) -> {
                double[] p = {x, y, z};
                // the apex is at +height / 2 along the axis, the base at -height / 2
                double radius = cr * (0.5 - p[a] / height);
                return Math.abs(p[a]) <= height / 2 && p[b] * p[b] + p[c] * p[c] <= radius * radius;
            }, lo, hi, mass, n), 1, 0.012, "cone " + axis);
        }
    }

    @Test
    void closedFormsOfTheTextbook() {
        double m = 3, r = 0.7;
        assertEquals(0.4 * m * r * r, MassProperties.sphere(r, m).ixx(), 1e-12);
        assertEquals(2.0 / 3 * m * r * r, MassProperties.hollowSphere(r, m).iyy(), 1e-12);
        MassProperties box = MassProperties.box(1, 2, 3, 12);
        assertEquals(12.0 / 3 * (4 + 9), box.ixx(), 1e-12);
        assertEquals(12.0 / 3 * (1 + 9), box.iyy(), 1e-12);
        assertEquals(12.0 / 3 * (1 + 4), box.izz(), 1e-12);
        assertEquals(m * r * r / 2, MassProperties.cylinder(r, 2, Axis.Y, m).iyy(), 1e-12);
        assertEquals(m * (r * r / 4 + 4.0 / 3), MassProperties.cylinder(r, 2, Axis.Y, m).ixx(), 1e-12);
        // a cone: centre of mass a quarter of the height from the base, 3/10 m r^2 about the axis
        MassProperties cone = MassProperties.cone(r, 2, Axis.Z, m);
        assertEquals(-0.5, cone.centerOfMass().z(), 1e-12);
        assertEquals(0.3 * m * r * r, cone.izz(), 1e-12);
        // a capsule with no cylinder is a sphere
        MassProperties sphereLike = MassProperties.capsule(r, 0, Axis.X, m);
        assertEquals(0.4 * m * r * r, sphereLike.ixx(), 1e-12);
        assertEquals(0.4 * m * r * r, sphereLike.iyy(), 1e-9);
        // the mass is inherited as given and withMass scales the tensor with it
        MassProperties twice = box.withMass(24);
        assertEquals(2 * box.ixx(), twice.ixx(), 1e-12);
        assertEquals(24.0, twice.mass(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> MassProperties.sphere(0, 1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.sphere(1, 0));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.sphere(1, -1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.box(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.capsule(1, -1, Axis.X, 1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.cone(1, 0, Axis.X, 1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ellipsoid(1, 1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.cylinder(1, 1, Axis.X, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.hollowSphere(-1, 1));
    }

    @Test
    void aMeshOfABoxIsExact() {
        ConvexPolytope poly = ConvexPolytope.of(new Aabbf(-1, -2, -3, 1, 2, 3));
        MassProperties mesh = MassProperties.ofMesh(poly.vertices(), poly.triangles(), poly.triangles().length / 3, 0.5);
        double volume = 2 * 4 * 6;
        MassProperties box = MassProperties.box(1, 2, 3, 0.5 * volume);
        assertEquals(0.5 * volume, mesh.mass(), 1e-9);
        assertEquals(0.0, mesh.centerOfMass().x(), 1e-9);
        assertEquals(box.ixx(), mesh.ixx(), 1e-9 * box.ixx());
        assertEquals(box.iyy(), mesh.iyy(), 1e-9 * box.iyy());
        assertEquals(box.izz(), mesh.izz(), 1e-9 * box.izz());
        assertEquals(0.0, mesh.inertia().m10(), 1e-9);
        // moved away from the origin: the centre of mass follows and the tensor about it is unchanged (the offset is exact in float)
        float[] v = poly.vertices();
        for (int i = 0; i < v.length / 3; i++) {
            v[3 * i] += 4f;
            v[3 * i + 1] -= 8f;
            v[3 * i + 2] += 16f;
        }
        MassProperties moved = MassProperties.ofMesh(v, poly.triangles(), poly.triangles().length / 3, 0.5);
        assertEquals(4.0, moved.centerOfMass().x(), 1e-9);
        assertEquals(-8.0, moved.centerOfMass().y(), 1e-9);
        assertEquals(16.0, moved.centerOfMass().z(), 1e-9);
        assertEquals(mesh.ixx(), moved.ixx(), 1e-6 * mesh.ixx());
        assertEquals(mesh.izz(), moved.izz(), 1e-6 * mesh.izz());
        assertEquals(0.0, moved.inertia().m10(), 1e-6);
    }

    @Test
    void aMeshOfARandomHullMatchesNumericalIntegration() {
        for (int t = 0; t < 4; t++) {
            int n = 10 + rng.nextInt(20);
            float[] pts = new float[3 * n];
            for (int i = 0; i < pts.length; i++) {
                pts[i] = (float) (rng.nextDouble() * 2 - 1 + (i % 3 == 0 ? 0.5 : 0));
            }
            ConvexPolytope hull = ConvexPolytope.of(pts, n);
            MassProperties mesh = MassProperties.ofMesh(hull.vertices(), hull.triangles(), hull.triangles().length / 3, 1.0);
            assertEquals(hull.volume(), mesh.mass(), 1e-5 * hull.volume());
            double[] lo = {-2, -2, -2}, hi = {3, 2, 2};
            double[] ref = integrate((x, y, z) -> hull.contains((float) x, (float) y, (float) z), lo, hi, mesh.mass(), 1_500_000);
            assertMatches(mesh, ref, 1, 0.01, "hull " + t);
        }
    }

    @Test
    void aTessellatedSphereApproachesTheSphere() {
        double[] ratios = new double[3];
        for (int level = 1; level <= 3; level++) {
            float[] pos = icosphere(level);
            int[] idx = icosphereIndices(level);
            MassProperties mp = MassProperties.ofMesh(pos, idx, idx.length / 3, 1.0);
            // I = 2/5 m r^2 for a sphere of radius r: the effective radius squared from the mesh is 5/2 I / m, close to 1 as the mesh gets finer
            ratios[level - 1] = 2.5 * mp.ixx() / mp.mass();
            assertEquals(mp.ixx(), mp.iyy(), 0.02 * mp.ixx());
            assertEquals(0.0, mp.centerOfMass().x(), 1e-6);
        }
        assertTrue(ratios[0] < ratios[1] && ratios[1] < ratios[2] && ratios[2] < 1.0, java.util.Arrays.toString(ratios));
        assertEquals(1.0, ratios[2], 0.015);
    }

    private static Object[] ico(int level) {
        double t = (1 + Math.sqrt(5)) / 2;
        java.util.List<float[]> v = new java.util.ArrayList<>();
        double[][] base = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        for (double[] b : base) {
            double l = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            v.add(new float[] {(float) (b[0] / l), (float) (b[1] / l), (float) (b[2] / l)});
        }
        int[][] faces = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        java.util.List<int[]> f = new java.util.ArrayList<>(java.util.Arrays.asList(faces));
        for (int l = 0; l < level; l++) {
            java.util.List<int[]> next = new java.util.ArrayList<>();
            java.util.Map<Long, Integer> mid = new java.util.HashMap<>();
            for (int[] tri : f) {
                int[] m = new int[3];
                for (int k = 0; k < 3; k++) {
                    int a = tri[k], b = tri[(k + 1) % 3];
                    long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                    Integer id = mid.get(key);
                    if (id == null) {
                        float[] pa = v.get(a), pb = v.get(b);
                        double x = pa[0] + pb[0], y = pa[1] + pb[1], z = pa[2] + pb[2], len = Math.sqrt(x * x + y * y + z * z);
                        v.add(new float[] {(float) (x / len), (float) (y / len), (float) (z / len)});
                        id = v.size() - 1;
                        mid.put(key, id);
                    }
                    m[k] = id;
                }
                next.add(new int[] {tri[0], m[0], m[2]});
                next.add(new int[] {tri[1], m[1], m[0]});
                next.add(new int[] {tri[2], m[2], m[1]});
                next.add(new int[] {m[0], m[1], m[2]});
            }
            f = next;
        }
        float[] pos = new float[3 * v.size()];
        for (int i = 0; i < v.size(); i++) {
            System.arraycopy(v.get(i), 0, pos, 3 * i, 3);
        }
        int[] idx = new int[3 * f.size()];
        for (int i = 0; i < f.size(); i++) {
            System.arraycopy(f.get(i), 0, idx, 3 * i, 3);
        }
        return new Object[] {pos, idx};
    }

    private static float[] icosphere(int level) {
        return (float[]) ico(level)[0];
    }

    private static int[] icosphereIndices(int level) {
        return (int[]) ico(level)[1];
    }

    @Test
    void badMeshesAreRejected() {
        ConvexPolytope poly = ConvexPolytope.of(new Aabbf(-1, -1, -1, 1, 1, 1));
        int[] t = poly.triangles();
        int[] inside = new int[t.length];
        for (int i = 0; i < t.length; i += 3) {
            inside[i] = t[i];
            inside[i + 1] = t[i + 2];
            inside[i + 2] = t[i + 1];
        }
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofMesh(poly.vertices(), inside, inside.length / 3, 1.0), "inside out");
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofMesh(poly.vertices(), t, 3, 1.0), "too few triangles");
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofMesh(poly.vertices(), t, t.length / 3, 0.0));
        float[] flat = {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0};
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofMesh(flat, new int[] {0, 1, 2, 1, 3, 2, 0, 2, 1, 1, 2, 3}, 4, 1.0), "no volume");
    }

    @Test
    void pointMassesAndTheParallelAxisTheorem() {
        // a dumbbell: two unit masses at x = -1 and x = 1
        MassProperties dumbbell = MassProperties.ofPoints(new float[] {-1, 0, 0, 1, 0, 0}, new double[] {1, 1}, 2);
        assertEquals(2.0, dumbbell.mass(), 0.0);
        assertEquals(0.0, dumbbell.ixx(), 0.0);
        assertEquals(2.0, dumbbell.iyy(), 1e-12);
        assertEquals(2.0, dumbbell.izz(), 1e-12);
        // eight equal masses at the corners of a cube
        float[] corners = new float[24];
        for (int i = 0; i < 8; i++) {
            corners[3 * i] = (i & 1) == 0 ? -1 : 1;
            corners[3 * i + 1] = (i & 2) == 0 ? -1 : 1;
            corners[3 * i + 2] = (i & 4) == 0 ? -1 : 1;
        }
        double[] masses = new double[8];
        java.util.Arrays.fill(masses, 0.5);
        MassProperties cube = MassProperties.ofPoints(corners, masses, 8);
        assertEquals(8.0, cube.ixx(), 1e-12);
        assertEquals(0.0, cube.inertia().m10(), 1e-12);
        // the tensor about another point against the direct sum over the points
        for (int t = 0; t < 20; t++) {
            int n = 3 + rng.nextInt(10);
            float[] pts = new float[3 * n];
            double[] ms = new double[n];
            for (int i = 0; i < n; i++) {
                ms[i] = 0.5 + rng.nextDouble();
                for (int k = 0; k < 3; k++) {
                    pts[3 * i + k] = (float) (rng.nextDouble() * 4 - 2);
                }
            }
            MassProperties p = MassProperties.ofPoints(pts, ms, n);
            Vec3d d = new Vec3d(rng.nextDouble() * 2 - 1, rng.nextDouble() * 2 - 1, rng.nextDouble() * 2 - 1);
            Mat3d moved = p.translatedInertia(d);
            double px = p.centerOfMass().x() + d.x(), py = p.centerOfMass().y() + d.y(), pz = p.centerOfMass().z() + d.z();
            double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
            for (int i = 0; i < n; i++) {
                double x = pts[3 * i] - px, y = pts[3 * i + 1] - py, z = pts[3 * i + 2] - pz;
                xx += ms[i] * (y * y + z * z);
                yy += ms[i] * (x * x + z * z);
                zz += ms[i] * (x * x + y * y);
                xy -= ms[i] * x * y;
                xz -= ms[i] * x * z;
                yz -= ms[i] * y * z;
            }
            assertEquals(xx, moved.m00(), 1e-9 * (1 + xx));
            assertEquals(yy, moved.m11(), 1e-9 * (1 + yy));
            assertEquals(zz, moved.m22(), 1e-9 * (1 + zz));
            assertEquals(xy, moved.m10(), 1e-9 * (1 + Math.abs(xy)));
            assertEquals(xz, moved.m20(), 1e-9 * (1 + Math.abs(xz)));
            assertEquals(yz, moved.m21(), 1e-9 * (1 + Math.abs(yz)));
        }
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofPoints(new float[3], new double[] {1}, 2));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofPoints(new float[3], new double[] {-1}, 1));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.ofPoints(new float[0], new double[0], 0));
    }

    @Test
    void movingAndCombiningBodies() {
        // two halves of a box make the box
        MassProperties half = MassProperties.box(1, 2, 3, 6);
        MassProperties whole = MassProperties.combine(new MassProperties[] {half, half}, new Quatd[] {Quatd.IDENTITY, Quatd.IDENTITY}, new Vec3d[] {new Vec3d(-1, 0, 0), new Vec3d(1, 0, 0)});
        MassProperties box = MassProperties.box(2, 2, 3, 12);
        assertEquals(12.0, whole.mass(), 1e-12);
        assertEquals(0.0, whole.centerOfMass().x(), 1e-12);
        assertEquals(box.ixx(), whole.ixx(), 1e-12 * box.ixx());
        assertEquals(box.iyy(), whole.iyy(), 1e-12 * box.iyy());
        assertEquals(box.izz(), whole.izz(), 1e-12 * box.izz());
        assertEquals(0.0, whole.inertia().m10(), 1e-12);
        // parts that are not the same: the centre of mass is the mass-weighted mean
        MassProperties heavy = MassProperties.sphere(1, 3), light = MassProperties.sphere(1, 1);
        MassProperties pair = MassProperties.combine(new MassProperties[] {heavy, light}, new Quatd[] {Quatd.IDENTITY, Quatd.IDENTITY}, new Vec3d[] {new Vec3d(0, 0, 0), new Vec3d(4, 0, 0)});
        assertEquals(1.0, pair.centerOfMass().x(), 1e-12);
        assertEquals(4.0, pair.mass(), 1e-12);
        // Iyy = sum of the parts about their own centres plus m d^2: 3 (0.4) + 3 * 1 + 0.4 + 1 * 9
        assertEquals(1.2 + 3 + 0.4 + 9, pair.iyy(), 1e-12);
        assertEquals(0.4 * 3 + 0.4, pair.ixx(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> MassProperties.combine(new MassProperties[] {heavy}, new Quatd[0], new Vec3d[0]));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.combine(new MassProperties[0], new Quatd[0], new Vec3d[0]));
        // rotating a box a quarter turn about z exchanges Ixx and Iyy
        Quatd quarter = Quatd.fromAxisAngle(Math.PI / 2, new Vec3d(0, 0, 1));
        MassProperties turned = MassProperties.box(1, 2, 3, 12).transformed(quarter, new Vec3d(5, 6, 7));
        assertEquals(MassProperties.box(1, 2, 3, 12).iyy(), turned.ixx(), 1e-12);
        assertEquals(MassProperties.box(1, 2, 3, 12).ixx(), turned.iyy(), 1e-12);
        assertEquals(5.0, turned.centerOfMass().x(), 1e-12);
        // the moment about an axis: the diagonal entries on the axes, and u . I u in general
        MassProperties box2 = MassProperties.box(1, 2, 3, 12);
        assertEquals(box2.ixx(), box2.momentAbout(1, 0, 0), 1e-12);
        assertEquals(box2.izz(), box2.momentAbout(0, 0, -5), 1e-12);
        assertEquals((box2.ixx() + box2.iyy()) / 2, box2.momentAbout(1, 1, 0), 1e-12);
    }

    @Test
    void principalAxesDiagonaliseTheTensor() {
        for (int t = 0; t < 50; t++) {
            double hx = 0.5 + rng.nextDouble(), hy = 2 + rng.nextDouble(), hz = 4 + rng.nextDouble();
            MassProperties box = MassProperties.box(hx, hy, hz, 3);
            double ax = rng.nextGaussian(), ay = rng.nextGaussian(), az = rng.nextGaussian(), l = Math.sqrt(ax * ax + ay * ay + az * az);
            Quatd q = Quatd.fromAxisAngle(rng.nextDouble() * 6, new Vec3d(ax / l, ay / l, az / l));
            MassProperties turned = box.transformed(q, new Vec3d(0, 0, 0));
            MassProperties.Principal p = turned.principalAxes();
            double[] expected = {box.ixx(), box.iyy(), box.izz()};
            java.util.Arrays.sort(expected);
            assertEquals(expected[0], p.moments().x(), 1e-9 * expected[2]);
            assertEquals(expected[1], p.moments().y(), 1e-9 * expected[2]);
            assertEquals(expected[2], p.moments().z(), 1e-9 * expected[2]);
            // R^T I R is diagonal with the moments
            Mat3d r = Mat3d.rotation(p.rotation());
            Mat3d diag = r.transpose().mul(turned.inertia()).mul(r);
            assertEquals(p.moments().x(), diag.m00(), 1e-9 * expected[2]);
            assertEquals(p.moments().y(), diag.m11(), 1e-9 * expected[2]);
            assertEquals(p.moments().z(), diag.m22(), 1e-9 * expected[2]);
            assertEquals(0.0, diag.m10(), 1e-9 * expected[2]);
            assertEquals(0.0, diag.m20(), 1e-9 * expected[2]);
            assertEquals(0.0, diag.m21(), 1e-9 * expected[2]);
            assertEquals(1.0, r.determinant(), 1e-9);
            assertEquals(1.0, p.rotation().length(), 1e-12);
        }
    }

    @Test
    void theInverseAndTheValidation() {
        MassProperties p = MassProperties.box(1, 2, 3, 5).transformed(Quatd.fromAxisAngle(0.8, new Vec3d(1, 2, 3).normalize()), new Vec3d(0, 0, 0));
        Mat3d id = p.inertia().mul(p.inverseInertia());
        assertEquals(1.0, id.m00(), 1e-12);
        assertEquals(1.0, id.m11(), 1e-12);
        assertEquals(1.0, id.m22(), 1e-12);
        assertEquals(0.0, id.m10(), 1e-12);
        assertEquals(0.0, id.m21(), 1e-12);
        // the constructor checks that the numbers are the tensor of a body
        MassProperties ok = MassProperties.of(1, new Vec3d(1, 2, 3), 2, 3, 4, 0.1, 0, 0);
        assertEquals(1.0, ok.centerOfMass().x());
        assertThrows(IllegalArgumentException.class, () -> MassProperties.of(0, Vec3d.ZERO, 1, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.of(1, Vec3d.ZERO, 1, 1, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> MassProperties.of(1, Vec3d.ZERO, 1, 1, 5, 0, 0, 0), "5 > 1 + 1 violates the triangle inequality of the principal moments");
        assertThrows(IllegalArgumentException.class, () -> MassProperties.of(Double.POSITIVE_INFINITY, Vec3d.ZERO, 1, 1, 1, 0, 0, 0));
    }
}
