package vmath.samples.demos.physics;

import vmath.bulk.IntList;
import vmath.geo.Aabbf;
import vmath.geo.ConvexShape;
import vmath.physics.ContactManifold;
import vmath.physics.ContactSolver;
import vmath.physics.ManifoldBuilder;
import vmath.physics.MassProperties;
import vmath.physics.OrientedBox;
import vmath.physics.RigidBody;
import vmath.spatial.DynamicAabbTree;
import vmath.util.Rng;

/**
 * A pit with a pile of boxes and spheres dropped into it, simulated with the library's rigid-body
 * building blocks: {@link RigidBody} for the state and the integration, a {@link DynamicAabbTree}
 * for the broad phase, {@link ManifoldBuilder} for the contacts between boxes (the whole contact
 * patch, up to four points) and between a sphere and anything (one point, from GJK), and
 * {@link ContactSolver} for the impulses, friction and position correction.
 *
 * <p>It is a toy engine on purpose: there are no islands and no sleeping, and the pile never comes
 * fully to rest. Warm starting is on by default ({@link #warmStart}): the manifold of a pair in the
 * last step gives its impulses to the matching points of this step. Its job is to show what the pieces do together and what they
 * cost. A step is gravity, the broad phase (moving the boxes of the tree and asking it for the
 * overlaps of every body), the narrow phase (one manifold per overlapping pair that touches), the
 * solver (ten sweeps over all manifolds), and the integration, each timed by the caller through
 * the {@code *Ns} getters.
 *
 * <p>The world is a pit of {@value #HALF} by {@value #HALF} metres (half extents) with a floor and
 * four walls, all static boxes. The bodies are added with {@link #spawn}, in a seeded random
 * order, so a run is the same every time.
 *
 * <p>The class does not use OpenGL.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is stepped and read by the render thread.
 */
final class PileSimulation {

    static final float HALF = 16f;
    static final float WALL_HEIGHT = 8f;
    private static final double WALL = 2.0;
    private static final double GRAVITY = 9.81;
    private static final double DT = 1.0 / 60.0;
    private static final double MAX_SPEED = 25.0;
    private static final float TREE_MARGIN = 0.1f;
    private static final double CONTACT_MARGIN = 0.02;
    private static final double WARM_START_DISTANCE = 0.05;

    private final int capacity;
    private final int staticCount;
    private final RigidBody[] bodies;
    private final boolean[] sphere;
    private final double[] hx;
    private final double[] hy;
    private final double[] hz;
    private final OrientedBox[] orient;
    private final ConvexShape[] shapes;
    private final int[] handles;
    private final DynamicAabbTree tree = new DynamicAabbTree(TREE_MARGIN, 1024);
    private final DynamicAabbTree.Query query = tree.newQuery();
    private final IntList candidates = new IntList(64);
    private final ManifoldBuilder builder = new ManifoldBuilder();
    private final ContactSolver.Params params = new ContactSolver.Params();
    private final Rng rng = new Rng(21);
    private ContactManifold[] manifolds = new ContactManifold[1024];
    private ContactManifold[] previous = new ContactManifold[1024];
    private long[] pairKeys = new long[1024];
    private long[] previousKeys = new long[1024];
    private int previousCount;
    private int[] table = new int[4096];
    private boolean warmStart = true;
    private RigidBody[] sideA = new RigidBody[1024];
    private RigidBody[] sideB = new RigidBody[1024];
    private int count;
    private int pairs;
    private int contacts;
    private long broadNs;
    private long narrowNs;
    private long solveNs;
    private long integrateNs;

    /**
     * Creates the pit with its static floor and walls and room for a number of bodies.
     *
     * @param capacity the most bodies that {@link #spawn} may add; at least 1
     */
    PileSimulation(int capacity) {
        this.capacity = capacity;
        int total = capacity + 5;
        bodies = new RigidBody[total];
        sphere = new boolean[total];
        hx = new double[total];
        hy = new double[total];
        hz = new double[total];
        orient = new OrientedBox[total];
        shapes = new ConvexShape[total];
        handles = new int[total];
        params.friction = 0.5;
        params.restitution = 0.1;
        params.iterations = 10;
        addStatic(0, -1, 0, 3 * HALF, 1, 3 * HALF);
        addStatic(HALF + WALL, WALL_HEIGHT * 0.5, 0, WALL, WALL_HEIGHT * 0.5, HALF + 2 * WALL);
        addStatic(-HALF - WALL, WALL_HEIGHT * 0.5, 0, WALL, WALL_HEIGHT * 0.5, HALF + 2 * WALL);
        addStatic(0, WALL_HEIGHT * 0.5, HALF + WALL, HALF + 2 * WALL, WALL_HEIGHT * 0.5, WALL);
        addStatic(0, WALL_HEIGHT * 0.5, -HALF - WALL, HALF + 2 * WALL, WALL_HEIGHT * 0.5, WALL);
        staticCount = count;
    }

    private void addStatic(double x, double y, double z, double ex, double ey, double ez) {
        RigidBody b = new RigidBody();
        b.setPose(x, y, z, 0, 0, 0, 1);
        b.makeStatic();
        int i = count++;
        bodies[i] = b;
        hx[i] = ex;
        hy[i] = ey;
        hz[i] = ez;
        orient[i] = new OrientedBox().set(x, y, z, 0, 0, 0, 1, ex, ey, ez);
        handles[i] = tree.insert((float) (x - ex), (float) (y - ey), (float) (z - ez), (float) (x + ex), (float) (y + ey), (float) (z + ez), i);
    }

    int staticCount() {
        return staticCount;
    }

    int bodyCount() {
        return count;
    }

    int dynamicCount() {
        return count - staticCount;
    }

    int capacity() {
        return capacity;
    }

    RigidBody body(int i) {
        return bodies[i];
    }

    boolean isSphere(int i) {
        return sphere[i];
    }

    double halfX(int i) {
        return hx[i];
    }

    double halfY(int i) {
        return hy[i];
    }

    double halfZ(int i) {
        return hz[i];
    }

    int pairs() {
        return pairs;
    }

    int contacts() {
        return contacts;
    }

    ContactManifold manifold(int k) {
        return manifolds[k];
    }

    long broadNs() {
        return broadNs;
    }

    long narrowNs() {
        return narrowNs;
    }

    long solveNs() {
        return solveNs;
    }

    long integrateNs() {
        return integrateNs;
    }

    /**
     * Adds a box or a sphere above the pit, at a random place, size and orientation.
     *
     * @return {@code true} if there was room for another body
     */
    boolean spawn() {
        if (count - staticCount >= capacity) {
            return false;
        }
        boolean ball = rng.nextInt(3) == 0;
        double x = rng.nextDouble(-HALF + 2, HALF - 2), z = rng.nextDouble(-HALF + 2, HALF - 2), y = rng.nextDouble(8.0, 22.0);
        RigidBody b;
        int i = count++;
        if (ball) {
            double r = rng.nextDouble(0.35, 0.7);
            hx[i] = hy[i] = hz[i] = r;
            sphere[i] = true;
            b = new RigidBody(MassProperties.sphere(r, 4.0 / 3.0 * Math.PI * r * r * r));
            final RigidBody body = b;
            final double radius = r;
            shapes[i] = (dx, dy, dz, out) -> {
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double s = len > 0 ? radius / len : 0.0;
                out[0] = body.px + dx * s;
                out[1] = body.py + dy * s;
                out[2] = body.pz + dz * s;
            };
        } else {
            hx[i] = rng.nextDouble(0.3, 0.7);
            hy[i] = rng.nextDouble(0.3, 0.7);
            hz[i] = rng.nextDouble(0.3, 0.7);
            b = new RigidBody(MassProperties.box(hx[i], hy[i], hz[i], 8 * hx[i] * hy[i] * hz[i]));
            orient[i] = new OrientedBox();
        }
        double ax = rng.nextGaussian(), ay = rng.nextGaussian(), az = rng.nextGaussian(), aw = rng.nextGaussian();
        double n = Math.sqrt(ax * ax + ay * ay + az * az + aw * aw);
        b.setPose(x, y, z, ax / n, ay / n, az / n, aw / n);
        b.wx = rng.nextDouble(-2, 2);
        b.wy = rng.nextDouble(-2, 2);
        b.wz = rng.nextDouble(-2, 2);
        b.linearDamping = 0.02;
        b.angularDamping = 0.05;
        bodies[i] = b;
        float[] box = new float[6];
        aabb(i, box);
        handles[i] = tree.insert(box[0], box[1], box[2], box[3], box[4], box[5], i);
        return true;
    }

    /**
     * Writes the world box of a dynamic body: the box of its centre and extent, which for a box
     * body is the extent of the rotated box.
     */
    private void aabb(int i, float[] out) {
        RigidBody b = bodies[i];
        double ex, ey, ez;
        if (sphere[i]) {
            ex = ey = ez = hx[i];
        } else {
            double x = b.qx, y = b.qy, z = b.qz, w = b.qw;
            double r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - z * w), r02 = 2 * (x * z + y * w);
            double r10 = 2 * (x * y + z * w), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - x * w);
            double r20 = 2 * (x * z - y * w), r21 = 2 * (y * z + x * w), r22 = 1 - 2 * (x * x + y * y);
            ex = Math.abs(r00) * hx[i] + Math.abs(r01) * hy[i] + Math.abs(r02) * hz[i];
            ey = Math.abs(r10) * hx[i] + Math.abs(r11) * hy[i] + Math.abs(r12) * hz[i];
            ez = Math.abs(r20) * hx[i] + Math.abs(r21) * hy[i] + Math.abs(r22) * hz[i];
        }
        out[0] = (float) (b.px - ex);
        out[1] = (float) (b.py - ey);
        out[2] = (float) (b.pz - ez);
        out[3] = (float) (b.px + ex);
        out[4] = (float) (b.py + ey);
        out[5] = (float) (b.pz + ez);
    }

    private final float[] box = new float[6];

    /**
     * Advances the world by one step of 1/60 s.
     */
    void step() {
        for (int i = staticCount; i < count; i++) {
            RigidBody b = bodies[i];
            b.applyForce(0.0, -GRAVITY * b.mass(), 0.0);
        }

        long t0 = System.nanoTime();
        for (int i = staticCount; i < count; i++) {
            aabb(i, box);
            tree.move(handles[i], box[0], box[1], box[2], box[3], box[4], box[5], (float) (bodies[i].vx * DT), (float) (bodies[i].vy * DT), (float) (bodies[i].vz * DT));
        }
        long t1 = System.nanoTime();
        for (int i = staticCount; i < count; i++) {
            if (!sphere[i]) {
                orient[i].set(bodies[i], hx[i], hy[i], hz[i]);
            }
        }
        // the manifolds of the last step are the previous ones now, found again by the pair of bodies
        ContactManifold[] swap = manifolds;
        manifolds = previous;
        previous = swap;
        long[] swapKeys = pairKeys;
        pairKeys = previousKeys;
        previousKeys = swapKeys;
        previousCount = manifoldCount;
        indexPrevious();
        pairs = 0;
        contacts = 0;
        int manifoldCount = 0;
        long overlapNs = 0;
        for (int i = staticCount; i < count; i++) {
            long q0 = System.nanoTime();
            aabb(i, box);
            candidates.clear();
            query.overlapAabb(new Aabbf(box[0], box[1], box[2], box[3], box[4], box[5]), candidates);
            overlapNs += System.nanoTime() - q0;
            for (int k = 0; k < candidates.size(); k++) {
                int j = candidates.get(k);
                if (j == i || (j >= staticCount && j < i)) {
                    continue;
                }
                pairs++;
                if (manifoldCount == manifolds.length) {
                    grow();
                }
                if (manifolds[manifoldCount] == null) {
                    manifolds[manifoldCount] = new ContactManifold();
                }
                ContactManifold m = manifolds[manifoldCount];
                // the static body is always the first of a pair, so the normal points from the world into the body
                int a = j < staticCount ? j : i, b = j < staticCount ? i : j;
                boolean touching;
                if (sphere[a] || sphere[b]) {
                    touching = builder.shapes(shape(a), shape(b), m);
                } else {
                    touching = builder.boxes(orient[a], orient[b], CONTACT_MARGIN, m);
                }
                if (touching && m.count() > 0) {
                    long key = (long) a * (capacity + 5) + b;
                    pairKeys[manifoldCount] = key;
                    if (warmStart) {
                        int before = findPrevious(key);
                        if (before >= 0) {
                            m.warmStartFrom(previous[before], WARM_START_DISTANCE);
                        }
                    }
                    sideA[manifoldCount] = bodies[a];
                    sideB[manifoldCount] = bodies[b];
                    contacts += m.count();
                    manifoldCount++;
                }
            }
        }
        long t2 = System.nanoTime();
        broadNs = (t1 - t0) + overlapNs;
        narrowNs = (t2 - t1) - overlapNs;
        ContactSolver.solveAll(sideA, sideB, manifolds, manifoldCount, params, DT);
        long t3 = System.nanoTime();
        solveNs = t3 - t2;
        for (int i = staticCount; i < count; i++) {
            RigidBody b = bodies[i];
            b.integrate(DT);
            double speed2 = b.vx * b.vx + b.vy * b.vy + b.vz * b.vz;
            if (speed2 > MAX_SPEED * MAX_SPEED) {
                double k = MAX_SPEED / Math.sqrt(speed2);
                b.vx *= k;
                b.vy *= k;
                b.vz *= k;
            }
        }
        integrateNs = System.nanoTime() - t3;
        this.manifoldCount = manifoldCount;
    }

    private int manifoldCount;

    int manifoldCount() {
        return manifoldCount;
    }

    private ConvexShape shape(int i) {
        return sphere[i] ? shapes[i] : orient[i];
    }

    /**
     * Turns warm starting on or off. When on (the default), the impulses that the solver found for a
     * pair in the last step are the first guess in this one, matched point by point.
     *
     * @param enabled whether to carry the impulses from step to step
     */
    void warmStart(boolean enabled) {
        this.warmStart = enabled;
    }

    // an open-addressing table from the key of a pair to its index among the previous manifolds, plus one (0 is empty); at most half full
    private void indexPrevious() {
        int size = Integer.highestOneBit(Math.max(4, 4 * previousCount - 1)) << 1;
        if (table.length < size) {
            table = new int[size];
        } else {
            java.util.Arrays.fill(table, 0, size, 0);
        }
        int mask = size - 1;
        for (int k = 0; k < previousCount; k++) {
            int h = hash(previousKeys[k]) & mask;
            while (table[h] != 0) {
                h = (h + 1) & mask;
            }
            table[h] = k + 1;
        }
        tableMask = mask;
    }

    private int tableMask;

    private int findPrevious(long key) {
        if (previousCount == 0) {
            return -1;
        }
        int h = hash(key) & tableMask;
        while (table[h] != 0) {
            if (previousKeys[table[h] - 1] == key) {
                return table[h] - 1;
            }
            h = (h + 1) & tableMask;
        }
        return -1;
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32);
    }

    private void grow() {
        int n = manifolds.length * 2;
        manifolds = java.util.Arrays.copyOf(manifolds, n);
        previous = java.util.Arrays.copyOf(previous, n);
        pairKeys = java.util.Arrays.copyOf(pairKeys, n);
        previousKeys = java.util.Arrays.copyOf(previousKeys, n);
        sideA = java.util.Arrays.copyOf(sideA, n);
        sideB = java.util.Arrays.copyOf(sideB, n);
    }

    /**
     * Counts the dynamic bodies that have left the pit: below the floor or outside the walls.
     *
     * @return the number of bodies that are somewhere they cannot be, which is zero when the
     *     collision handling works; a body that is not a finite number counts as lost
     */
    int lost() {
        int lost = 0;
        for (int i = staticCount; i < count; i++) {
            RigidBody b = bodies[i];
            boolean ok = Double.isFinite(b.px + b.py + b.pz) && b.py > -0.5 && Math.abs(b.px) < HALF + 0.5 && Math.abs(b.pz) < HALF + 0.5;
            if (!ok) {
                lost++;
            }
        }
        return lost;
    }

    /**
     * Describes the first body that has left the pit, for the message of a failed check.
     *
     * @return the index, kind, position and velocity of the body, or an empty string if none
     */
    String describeLost() {
        for (int i = staticCount; i < count; i++) {
            RigidBody b = bodies[i];
            boolean ok = Double.isFinite(b.px + b.py + b.pz) && b.py > -0.5 && Math.abs(b.px) < HALF + 0.5 && Math.abs(b.pz) < HALF + 0.5;
            if (!ok) {
                return String.format("body %d (%s, half %.2f %.2f %.2f) at %.2f %.2f %.2f moving %.2f %.2f %.2f", i, sphere[i] ? "sphere" : "box", hx[i], hy[i], hz[i], b.px, b.py, b.pz, b.vx, b.vy, b.vz);
            }
        }
        return "";
    }

    /**
     * Sums the kinetic energy of the dynamic bodies.
     *
     * @return the energy in joules
     */
    double kineticEnergy() {
        double e = 0;
        for (int i = staticCount; i < count; i++) {
            e += bodies[i].kineticEnergy();
        }
        return e;
    }
}
