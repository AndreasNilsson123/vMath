package vmath.physics;

/**
 * The contact between two bodies as a physics engine wants it: a single <b>normal</b> and up to
 * four <b>contact points</b>, each with the penetration depth, the points on the two surfaces and
 * the impulses accumulated by the solver in earlier frames (for warm starting).
 *
 * <p>A face resting on a face makes four points, a box on an edge makes two, a corner or two
 * crossing edges one; four points are enough to hold a body steadily without the jitter that a
 * single point gives.
 *
 * <p>The normal is a unit vector pointing <b>from body A to body B</b>: moving B along it (or A
 * against it) by {@code depth} separates the two. {@link #depth(int)} is positive when the bodies
 * penetrate; it may be negative (the shapes are apart by that distance) for contacts made with a
 * speculative margin ({@link ManifoldBuilder}). The point on A and the point on B of a contact
 * coincide when the depth is zero and are {@code depth} apart along the normal otherwise.
 *
 * <p>Fill one with {@link ManifoldBuilder}, solve it with {@link ContactSolver}, and carry the
 * impulses from one frame to the next with {@link #warmStartFrom}. Mutable, not thread-safe.
 *
 * <p><b>Thread safety.</b> Mutable and not thread-safe: use one manifold per body pair, and do not
 * share it between threads while the builder or the solver writes to it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ContactManifold manifold = new ContactManifold();
 * manifold.setNormal(0.0, 1.0, 0.0);                                      // from body A to body B
 * manifold.add(0.0, 0.0, 0.0, 0.0, -0.01, 0.0, 0.01, 1);                  // a point on each body, the depth and a feature id
 * int count = manifold.count();
 * manifold.setNormalImpulse(0, 2.5);                                      // the solver keeps impulses for the next frame
 * }</pre>
 */
public final class ContactManifold {

    /**
     * The most contact points a manifold holds.
     */
    public static final int MAX_POINTS = 4;

    /**
     * The contact normal, from body A to body B (unit length when {@link #count()} is positive).
     */
    public double nx, ny, nz;

    private int count;
    private final double[] pointA = new double[3 * MAX_POINTS], pointB = new double[3 * MAX_POINTS];
    private final double[] depth = new double[MAX_POINTS];
    private final int[] id = new int[MAX_POINTS];
    private final double[] normalImpulse = new double[MAX_POINTS];
    private final double[] tangentImpulse1 = new double[MAX_POINTS], tangentImpulse2 = new double[MAX_POINTS];
    /**
     * Solver state of the current step, per point: the velocity targets of the normal constraint,
     * the bias (split impulse) speeds and their accumulated impulses.
     */
    final double[] target = new double[MAX_POINTS], bias = new double[MAX_POINTS], biasImpulse = new double[MAX_POINTS];

    /**
     * Creates an empty manifold.
     */
    public ContactManifold() {
    }

    /**
     * Counts the contact points of the manifold, which holds at most a fixed number.
     *
     * @return the number of contact points: 0 to {@link #MAX_POINTS}
     */
    public int count() {
        return count;
    }

    /**
     * Removes all points (and their impulses).
     */
    public void clear() {
        count = 0;
        java.util.Arrays.fill(normalImpulse, 0);
        java.util.Arrays.fill(tangentImpulse1, 0);
        java.util.Arrays.fill(tangentImpulse2, 0);
    }

    /**
     * Reads the penetration depth of a contact; a negative depth marks a speculative contact
     * between bodies that do not yet touch.
     *
     * @param i the index
     * @return the penetration depth of point {@code i}: positive when the shapes overlap, negative
     *     when they are apart by that distance (speculative contacts)
     */
    public double depth(int i) {
        return depth[i];
    }

    /**
     * Reads one coordinate of the contact point on body A.
     *
     * @param i the index
     * @param axis the axis
     * @return the coordinate {@code axis} of the point of contact {@code i} on the surface of body
     *     A
     */
    public double pointA(int i, int axis) {
        return pointA[3 * i + axis];
    }

    /**
     * Reads one coordinate of the contact point on body B.
     *
     * @param i the index
     * @param axis the axis
     * @return the coordinate {@code axis} of the point of contact {@code i} on the surface of body
     *     B
     */
    public double pointB(int i, int axis) {
        return pointB[3 * i + axis];
    }

    /**
     * Reads one coordinate of the midpoint between the two contact points, which is where the
     * solver applies the impulse.
     *
     * @param i the index
     * @param axis the axis
     * @return the coordinate {@code axis} of the middle of the points of contact {@code i} on the
     *     two surfaces: the point at which the solver applies the impulse
     */
    public double point(int i, int axis) {
        return 0.5 * (pointA[3 * i + axis] + pointB[3 * i + axis]);
    }

    /**
     * Reads the feature identifier of a contact, which stays the same across frames while the
     * contact keeps its geometric features and so lets warm starting match old and new contacts.
     *
     * @param i the index
     * @return the identifier of the geometric feature that made point {@code i} (an incident vertex
     *     or an edge crossing): equal across frames while the contact keeps its features
     */
    public int id(int i) {
        return id[i];
    }

    /**
     * Reads the accumulated normal impulse of a contact, which warm starting carries into the next
     * frame.
     *
     * @param i the index
     * @return the normal impulse accumulated for point {@code i} (by {@link ContactSolver}),
     *     carried to the next frame by {@link #warmStartFrom}
     */
    public double normalImpulse(int i) {
        return normalImpulse[i];
    }

    /**
     * Sets the normal impulse of point {@code i}.
     *
     * @param i the index
     * @param impulse the impulse
     */
    public void setNormalImpulse(int i, double impulse) {
        normalImpulse[i] = impulse;
    }

    /**
     * Reads the accumulated friction impulse of a contact along the first tangent direction.
     *
     * @param i the index
     * @return the friction impulse of point {@code i} along the first tangent
     *     ({@link ContactSolver#tangentBasis})
     */
    public double tangentImpulse1(int i) {
        return tangentImpulse1[i];
    }

    /**
     * Reads the accumulated friction impulse of a contact along the second tangent direction.
     *
     * @param i the index
     * @return the friction impulse of point {@code i} along the second tangent
     */
    public double tangentImpulse2(int i) {
        return tangentImpulse2[i];
    }

    /**
     * Sets the friction impulses of point {@code i}.
     *
     * @param i the index
     * @param first the first
     * @param second the second
     */
    public void setTangentImpulses(int i, double first, double second) {
        tangentImpulse1[i] = first;
        tangentImpulse2[i] = second;
    }

    /**
     * Sets the normal, which is normalised.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public void setNormal(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        nx = x / l;
        ny = y / l;
        nz = z / l;
    }

    /**
     * Appends a contact point (ignored when the manifold is full) with zero impulses.
     *
     * @param ax the x coordinate of the contact point on body A
     * @param ay the y coordinate of the contact point on body A
     * @param az the z coordinate of the contact point on body A
     * @param bx the x coordinate of the contact point on body B
     * @param by the y coordinate of the contact point on body B
     * @param bz the z coordinate of the contact point on body B
     * @param depth the penetration depth, negative if the points are apart
     * @param id the feature id that identifies this contact from one frame to the next
     */
    public void add(double ax, double ay, double az, double bx, double by, double bz, double depth, int id) {
        if (count == MAX_POINTS) {
            return;
        }
        int o = 3 * count;
        pointA[o] = ax;
        pointA[o + 1] = ay;
        pointA[o + 2] = az;
        pointB[o] = bx;
        pointB[o + 1] = by;
        pointB[o + 2] = bz;
        this.depth[count] = depth;
        this.id[count] = id;
        normalImpulse[count] = 0;
        tangentImpulse1[count] = 0;
        tangentImpulse2[count] = 0;
        count++;
    }

    /**
     * Copies the accumulated impulses of the contact points of {@code previous} (the manifold of
     * the same pair of bodies in the last frame) to the points here that continue them: a point
     * continues one with the same {@link #id(int)}, or, failing that, the nearest one within
     * {@code distance} (measured between the points on A).
     *
     * <p>Points that match nothing keep zero impulses. Each point of {@code previous} is used at
     * most once. Warm starting makes the solver converge in a few iterations for resting contacts.
     *
     * @param previous the previous; must not be {@code null}
     * @param distance the distance
     */
    public void warmStartFrom(ContactManifold previous, double distance) {
        int used = 0;
        double limit = distance * distance;
        for (int i = 0; i < count; i++) {
            int match = -1;
            for (int j = 0; j < previous.count && match < 0; j++) {
                if ((used & (1 << j)) == 0 && previous.id[j] == id[i]) {
                    match = j;
                }
            }
            if (match < 0) {
                double best = limit;
                for (int j = 0; j < previous.count; j++) {
                    if ((used & (1 << j)) != 0) {
                        continue;
                    }
                    double dx = previous.pointA[3 * j] - pointA[3 * i], dy = previous.pointA[3 * j + 1] - pointA[3 * i + 1], dz = previous.pointA[3 * j + 2] - pointA[3 * i + 2];
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= best) {
                        best = d2;
                        match = j;
                    }
                }
            }
            if (match >= 0) {
                used |= 1 << match;
                normalImpulse[i] = previous.normalImpulse[match];
                tangentImpulse1[i] = previous.tangentImpulse1[match];
                tangentImpulse2[i] = previous.tangentImpulse2[match];
            }
        }
    }

    /**
     * Copies the whole manifold (normal, points and impulses) from {@code other}.
     *
     * @param other the other contact manifold; must not be {@code null}
     */
    public void copyFrom(ContactManifold other) {
        nx = other.nx;
        ny = other.ny;
        nz = other.nz;
        count = other.count;
        System.arraycopy(other.pointA, 0, pointA, 0, pointA.length);
        System.arraycopy(other.pointB, 0, pointB, 0, pointB.length);
        System.arraycopy(other.depth, 0, depth, 0, depth.length);
        System.arraycopy(other.id, 0, id, 0, id.length);
        System.arraycopy(other.normalImpulse, 0, normalImpulse, 0, normalImpulse.length);
        System.arraycopy(other.tangentImpulse1, 0, tangentImpulse1, 0, tangentImpulse1.length);
        System.arraycopy(other.tangentImpulse2, 0, tangentImpulse2, 0, tangentImpulse2.length);
    }
}
