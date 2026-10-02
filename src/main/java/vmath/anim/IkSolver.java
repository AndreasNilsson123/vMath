package vmath.anim;

import vmath.annotations.Experimental;

/**
 * Inverse kinematics for a chain of joints of a {@link Skeleton}: given where the tip of the chain should be, rotate the joints of a {@link Pose} so that it gets there.
 * Three solvers and a look-at constraint:
 *
 * <ul>
 *   <li>{@link #twoBone}: the analytic solution for a three-joint limb (shoulder, elbow, wrist; hip, knee, ankle): exact and instantaneous, with a <em>pole vector</em> that says
 *       which way the middle joint bends.</li>
 *   <li>{@link #fabrik}: FABRIK (forward and backward reaching) for a chain of any length: iterates over positions, then converts them to rotations; no singularities and an
 *       even bend along the chain; it is not guaranteed to converge (it can stall in a folded configuration), so check the returned distance.</li>
 *   <li>{@link #ccd}: cyclic coordinate descent for a chain of any length: rotates one joint at a time to point the tip at the target; cheap per step, and it concentrates the
 *       rotation in the joints near the tip.</li>
 *   <li>{@link #lookAt}: turns one joint so that an axis of it points at a target (a head or an eye following a point), optionally keeping a second axis towards "up", and
 *       blended in by a weight.</li>
 * </ul>
 *
 * <p>A <b>chain</b> is a list of joint indices in which every joint is the child of the one before it ({@code skeleton.parent(chain[k + 1]) == chain[k]}); the first joint
 * stays where it is, the last is the tip, and the joints rotate about their own origins (the rotation of the tip joint itself does not change anything the solvers care about, so
 * it is left alone). Targets and pole vectors are in the space of the skeleton's world matrices ({@link Skinning#worldMatrices}), that is model space for a root joint with
 * no parent above it.
 *
 * <p>Only rotations change; the bone lengths (the translations of the joints) are never touched, so a target beyond reach leaves the chain fully stretched towards it and the
 * solver returns the distance that is left. Each method returns the distance between the tip and the target (or, for {@code lookAt}, the angle between the axis and the
 * target direction in radians) after the solve. The solvers assume the chain joints and their ancestors have a <b>uniform scale</b> (the scale of a joint is read from the length of
 * its world x axis); with non-uniform scale the positions are only approximate.
 *
 * <p>An instance owns its scratch arrays, so the solves <b>allocate nothing</b> after construction. Not thread-safe: use one per thread (and per skeleton).
 *
 * <p><b>Thread safety.</b> Not thread-safe (it holds scratch memory): one instance per thread. The skeleton may be shared.
 */
@Experimental("constraints (joint limits, twist) and a full-body solver are the next steps; the signatures are expected to stay")
public final class IkSolver {

    private final Skeleton skeleton;
    private final int joints;
    private final float[] world;
    private final int[] ancestors;

    // chain state, indexed by position in the chain
    private final double[] px, py, pz;      // world position of each chain joint
    private final double[] rw;              // world rotation (x, y, z, w) of each chain joint
    private final double[] lq;              // local rotation of each chain joint
    private final double[] lt;              // local translation of each chain joint (relative to its parent)
    private final double[] scale;           // uniform world scale at each chain joint
    private final double[] length;          // distance from chain joint k to chain joint k + 1
    private final double[] fabrikX, fabrikY, fabrikZ; // FABRIK working positions
    private final double[] parentRotation = new double[4]; // world rotation of the parent of the first chain joint
    private int count;
    private int rotated;
    private int[] chainJoints = new int[0];
    private final int[] twoBoneChain = new int[3];

    /** A solver for {@code skeleton}. */
    public IkSolver(Skeleton skeleton) {
        this.skeleton = skeleton;
        this.joints = skeleton.jointCount();
        world = new float[16 * joints];
        ancestors = new int[joints];
        px = new double[joints];
        py = new double[joints];
        pz = new double[joints];
        rw = new double[4 * joints];
        lq = new double[4 * joints];
        lt = new double[3 * joints];
        scale = new double[joints];
        length = new double[joints];
        fabrikX = new double[joints];
        fabrikY = new double[joints];
        fabrikZ = new double[joints];
    }

    // ---------------------------------------------------------------- the solvers

    /**
     * Solves a three-joint limb exactly: rotates {@code root} and {@code mid} so that the {@code end} joint reaches {@code (tx, ty, tz)}, or points straight at it when it is out
     * of reach (or folded to the minimum when it is too close). {@code mid} must be a child of {@code root} and {@code end} a child of {@code mid}. The middle joint bends
     * towards the pole point {@code (poleX, poleY, poleZ)} (the knee towards a point in front of the leg): the plane of the limb contains the root, the target and the pole.
     * Returns the distance from the end joint to the target after the solve.
     */
    public float twoBone(Pose pose, int root, int mid, int end, float tx, float ty, float tz, float poleX, float poleY, float poleZ) {
        return twoBone(pose, root, mid, end, tx, ty, tz, poleX, poleY, poleZ, true);
    }

    /** {@link #twoBone} that keeps the current bend direction of the limb instead of taking a pole point. */
    public float twoBone(Pose pose, int root, int mid, int end, float tx, float ty, float tz) {
        return twoBone(pose, root, mid, end, tx, ty, tz, 0f, 0f, 0f, false);
    }

    private float twoBone(Pose pose, int root, int mid, int end, float tx, float ty, float tz, float poleX, float poleY, float poleZ, boolean usePole) {
        twoBoneChain[0] = root;
        twoBoneChain[1] = mid;
        twoBoneChain[2] = end;
        load(pose, twoBoneChain, 3);
        double a = length[0], b = length[1];
        double tvx = tx - px[0], tvy = ty - py[0], tvz = tz - pz[0];
        double dist = Math.sqrt(tvx * tvx + tvy * tvy + tvz * tvz);
        double ux, uy, uz;
        if (dist > 1e-12) {
            ux = tvx / dist;
            uy = tvy / dist;
            uz = tvz / dist;
        } else { // the target is at the root: keep the current direction of the end
            ux = px[2] - px[0];
            uy = py[2] - py[0];
            uz = pz[2] - pz[0];
            double l = Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux /= l;
            uy /= l;
            uz /= l;
        }
        double reachMin = Math.abs(a - b) + 1e-9 * (a + b), reachMax = a + b - 1e-9 * (a + b);
        double d = Math.max(reachMin, Math.min(reachMax, dist));
        // the middle joint lies on the circle where the spheres of radius a around the root and b around the end meet
        double along = (a * a - b * b + d * d) / (2 * d);
        double height = Math.sqrt(Math.max(a * a - along * along, 0));
        // the bend direction: the pole's offset from the root-target axis, else the limb's own current bend
        double bx, by, bz;
        if (usePole) {
            bx = poleX - px[0];
            by = poleY - py[0];
            bz = poleZ - pz[0];
        } else {
            bx = px[1] - px[0];
            by = py[1] - py[0];
            bz = pz[1] - pz[0];
        }
        double bu = bx * ux + by * uy + bz * uz;
        bx -= ux * bu;
        by -= uy * bu;
        bz -= uz * bu;
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1e-9 * (a + b)) { // no usable bend direction: any direction perpendicular to the axis
            double ax = Math.abs(ux), ay = Math.abs(uy), az = Math.abs(uz);
            double cx = ax <= ay && ax <= az ? 1 : 0, cy = ay < ax && ay <= az ? 1 : 0, cz = az < ax && az < ay ? 1 : 0;
            bx = uy * cz - uz * cy;
            by = uz * cx - ux * cz;
            bz = ux * cy - uy * cx;
            bl = Math.sqrt(bx * bx + by * by + bz * bz);
        }
        bx /= bl;
        by /= bl;
        bz /= bl;
        double mx = px[0] + ux * along + bx * height, my = py[0] + uy * along + by * height, mz = pz[0] + uz * along + bz * height;
        double ex = px[0] + ux * d, ey = py[0] + uy * d, ez = pz[0] + uz * d;
        aim(0, mx - px[0], my - py[0], mz - pz[0]);
        aim(1, ex - px[1], ey - py[1], ez - pz[1]);
        store(pose);
        return (float) distanceToTip(tx, ty, tz);
    }

    /**
     * Solves a chain of any length with FABRIK: alternately drag the tip to the target and the root back to its place, each joint keeping its distance to the next, until the
     * tip is within {@code tolerance} of the target or {@code maxIterations} rounds have passed. A target out of reach leaves the chain straight towards it. Returns the
     * remaining distance from the tip to the target.
     */
    public float fabrik(Pose pose, int[] chain, int chainLength, float tx, float ty, float tz, int maxIterations, float tolerance) {
        load(pose, chain, chainLength);
        int n = count;
        double total = 0;
        for (int k = 0; k < n - 1; k++) {
            total += length[k];
        }
        double rx = px[0], ry = py[0], rz = pz[0];
        double dx = tx - rx, dy = ty - ry, dz = tz - rz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // work on copies of the positions: the pose is only touched when the new positions are turned into rotations
        double[] qx = fabrikX, qy = fabrikY, qz = fabrikZ;
        System.arraycopy(px, 0, qx, 0, n);
        System.arraycopy(py, 0, qy, 0, n);
        System.arraycopy(pz, 0, qz, 0, n);
        if (dist >= total) { // out of reach: straight towards the target
            for (int k = 0; k < n - 1; k++) {
                double s = length[k] / dist;
                qx[k + 1] = qx[k] + dx * s;
                qy[k + 1] = qy[k] + dy * s;
                qz[k + 1] = qz[k] + dz * s;
            }
        } else {
            for (int iter = 0; iter < maxIterations; iter++) {
                double ex = qx[n - 1] - tx, ey = qy[n - 1] - ty, ez = qz[n - 1] - tz;
                if (ex * ex + ey * ey + ez * ez <= (double) tolerance * tolerance) {
                    break;
                }
                // backward: the tip to the target, each joint pulled to its bone length from the next
                qx[n - 1] = tx;
                qy[n - 1] = ty;
                qz[n - 1] = tz;
                for (int k = n - 2; k >= 0; k--) {
                    double vx = qx[k] - qx[k + 1], vy = qy[k] - qy[k + 1], vz = qz[k] - qz[k + 1];
                    double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    double s = l > 1e-15 ? length[k] / l : 0;
                    if (l <= 1e-15) {
                        vx = 1;
                        vy = 0;
                        vz = 0;
                        s = length[k];
                    }
                    qx[k] = qx[k + 1] + vx * s;
                    qy[k] = qy[k + 1] + vy * s;
                    qz[k] = qz[k + 1] + vz * s;
                }
                // forward: the root back to its place
                qx[0] = rx;
                qy[0] = ry;
                qz[0] = rz;
                for (int k = 0; k < n - 1; k++) {
                    double vx = qx[k + 1] - qx[k], vy = qy[k + 1] - qy[k], vz = qz[k + 1] - qz[k];
                    double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    double s = l > 1e-15 ? length[k] / l : 0;
                    if (l <= 1e-15) {
                        vx = 1;
                        vy = 0;
                        vz = 0;
                        s = length[k];
                    }
                    qx[k + 1] = qx[k] + vx * s;
                    qy[k + 1] = qy[k] + vy * s;
                    qz[k + 1] = qz[k] + vz * s;
                }
            }
        }
        // positions to rotations, root first: each joint turns to point at the next of the solved positions
        for (int k = 0; k < n - 1; k++) {
            aim(k, qx[k + 1] - px[k], qy[k + 1] - py[k], qz[k + 1] - pz[k]);
        }
        store(pose);
        return (float) distanceToTip(tx, ty, tz);
    }

    /**
     * Solves a chain of any length by cyclic coordinate descent: from the joint before the tip back to the root, rotate each joint about its origin to point the tip at the
     * target, and repeat until the tip is within {@code tolerance} of the target or {@code maxIterations} sweeps have passed. Returns the remaining distance.
     */
    public float ccd(Pose pose, int[] chain, int chainLength, float tx, float ty, float tz, int maxIterations, float tolerance) {
        load(pose, chain, chainLength);
        int n = count;
        for (int iter = 0; iter < maxIterations; iter++) {
            if (distanceToTip(tx, ty, tz) <= tolerance) {
                break;
            }
            for (int k = n - 2; k >= 0; k--) {
                double ex = px[n - 1] - px[k], ey = py[n - 1] - py[k], ez = pz[n - 1] - pz[k];
                double gx = tx - px[k], gy = ty - py[k], gz = tz - pz[k];
                if (ex * ex + ey * ey + ez * ez < 1e-24 || gx * gx + gy * gy + gz * gz < 1e-24) {
                    continue;
                }
                rotateBy(k, ex, ey, ez, gx, gy, gz);
            }
        }
        store(pose);
        return (float) distanceToTip(tx, ty, tz);
    }

    /**
     * Turns {@code joint} so that its local axis {@code (fx, fy, fz)} points at {@code (tx, ty, tz)} by the shortest rotation, blended in by {@code weight} (0 leaves the pose
     * alone, 1 aims fully). The rotation of the joint's parent is respected; the rotation about the aimed axis (the roll) is whatever the shortest rotation gives, see the overload
     * with an up axis to control it. Returns the angle in radians between the axis and the direction to the target after the solve (0 at weight 1).
     */
    public float lookAt(Pose pose, int joint, float fx, float fy, float fz, float tx, float ty, float tz, float weight) {
        return lookAtImpl(pose, joint, fx, fy, fz, tx, ty, tz, weight, false, 0, 0, 0, 0, 0, 0);
    }

    /**
     * {@link #lookAt} that also keeps the joint's local axis {@code (ux, uy, uz)} as close as possible to the world direction {@code (wx, wy, wz)} while the forward axis points at
     * the target: the roll is chosen so that the up axis leans towards "up". The up axis should be perpendicular to the forward axis. When the forward direction is parallel to
     * the world up the roll is left as the shortest rotation gives it.
     */
    public float lookAt(Pose pose, int joint, float fx, float fy, float fz, float ux, float uy, float uz, float tx, float ty, float tz, float wx, float wy, float wz, float weight) {
        return lookAtImpl(pose, joint, fx, fy, fz, tx, ty, tz, weight, true, ux, uy, uz, wx, wy, wz);
    }

    private float lookAtImpl(Pose pose, int joint, double fx, double fy, double fz, double tx, double ty, double tz, double weight, boolean up, double ux, double uy, double uz,
                             double wx, double wy, double wz) {
        if (joint < 0 || joint >= joints) {
            throw new IllegalArgumentException("joint " + joint + " is outside [0, " + joints + ")");
        }
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(fl > 0)) {
            throw new IllegalArgumentException("the forward axis must not be zero");
        }
        chainOf1(joint);
        load(pose, chainJoint1, 1);
        double[] q = lookAtQ;
        // the world rotation after turning the forward axis to the target
        double cx = rw[0], cy = rw[1], cz = rw[2], cw = rw[3];
        rotateVector(cx, cy, cz, cw, fx / fl, fy / fl, fz / fl, tmp);
        double dx = tx - px[0], dy = ty - py[0], dz = tz - pz[0];
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double oldLq0 = lq[0], oldLq1 = lq[1], oldLq2 = lq[2], oldLq3 = lq[3];
        if (dl > 1e-12) {
            dx /= dl;
            dy /= dl;
            dz /= dl;
            arc(tmp[0], tmp[1], tmp[2], dx, dy, dz, q);
            multiply(q[0], q[1], q[2], q[3], cx, cy, cz, cw, tmp4);
            double nx = tmp4[0], ny = tmp4[1], nz = tmp4[2], nw = tmp4[3];
            if (up) {
                double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
                rotateVector(nx, ny, nz, nw, ux / ul, uy / ul, uz / ul, tmp);
                double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
                double ax = wx / wl, ay = wy / wl, az = wz / wl;
                double wd = ax * dx + ay * dy + az * dz;
                double qx = ax - dx * wd, qy = ay - dy * wd, qz = az - dz * wd; // the world up, perpendicular to the forward direction
                double ql = Math.sqrt(qx * qx + qy * qy + qz * qz);
                if (ql > 1e-9) {
                    qx /= ql;
                    qy /= ql;
                    qz /= ql;
                    // the angle about the forward axis from the current up to the wanted up
                    double sx = tmp[1] * qz - tmp[2] * qy, sy = tmp[2] * qx - tmp[0] * qz, sz = tmp[0] * qy - tmp[1] * qx;
                    double angle = Math.atan2(sx * dx + sy * dy + sz * dz, tmp[0] * qx + tmp[1] * qy + tmp[2] * qz);
                    double s = Math.sin(angle / 2);
                    multiply(dx * s, dy * s, dz * s, Math.cos(angle / 2), nx, ny, nz, nw, tmp4);
                    nx = tmp4[0];
                    ny = tmp4[1];
                    nz = tmp4[2];
                    nw = tmp4[3];
                }
            }
            // the local rotation that gives that world rotation, blended in by the weight along the shortest arc
            double[] pr = parentRotation;
            multiply(-pr[0], -pr[1], -pr[2], pr[3], nx, ny, nz, nw, tmp4);
            double tx4 = tmp4[0], ty4 = tmp4[1], tz4 = tmp4[2], tw4 = tmp4[3];
            if (oldLq0 * tx4 + oldLq1 * ty4 + oldLq2 * tz4 + oldLq3 * tw4 < 0) {
                tx4 = -tx4;
                ty4 = -ty4;
                tz4 = -tz4;
                tw4 = -tw4;
            }
            double wgt = Math.max(0, Math.min(1, weight));
            double bx = oldLq0 + (tx4 - oldLq0) * wgt, by = oldLq1 + (ty4 - oldLq1) * wgt, bz = oldLq2 + (tz4 - oldLq2) * wgt, bw = oldLq3 + (tw4 - oldLq3) * wgt;
            double bl = Math.sqrt(bx * bx + by * by + bz * bz + bw * bw);
            lq[0] = bx / bl;
            lq[1] = by / bl;
            lq[2] = bz / bl;
            lq[3] = bw / bl;
            fk(0);
        }
        store(pose);
        // the angle that is left
        rotateVector(rw[0], rw[1], rw[2], rw[3], fx / fl, fy / fl, fz / fl, tmp);
        double dot = tmp[0] * dx + tmp[1] * dy + tmp[2] * dz;
        return (float) Math.acos(Math.max(-1, Math.min(1, dot)));
    }

    private final int[] chainJoint1 = new int[1];
    private final double[] lookAtQ = new double[4];
    private final double[] tmp = new double[3];
    private final double[] tmp4 = new double[4];

    private void chainOf1(int joint) {
        chainJoint1[0] = joint;
    }

    // ---------------------------------------------------------------- the chain state

    /** Reads the chain from the pose: positions, rotations, local transforms and bone lengths. */
    private void load(Pose pose, int[] chain, int n) {
        if (pose.jointCount() != joints) {
            throw new IllegalArgumentException("the pose has " + pose.jointCount() + " joints, the skeleton " + joints);
        }
        if (n < 1 || n > chain.length) {
            throw new IllegalArgumentException("the chain length " + n + " does not fit in an array of " + chain.length);
        }
        for (int k = 0; k < n; k++) {
            if (chain[k] < 0 || chain[k] >= joints) {
                throw new IllegalArgumentException("chain joint " + chain[k] + " is outside [0, " + joints + ")");
            }
            if (k > 0 && skeleton.parent(chain[k]) != chain[k - 1]) {
                throw new IllegalArgumentException("chain joint " + chain[k] + " is not a child of " + chain[k - 1]);
            }
        }
        if (n < 2 && chain != chainJoint1) {
            throw new IllegalArgumentException("a chain needs at least two joints");
        }
        chainJoints = chain;
        count = n;
        rotated = n == 1 ? 1 : n - 1; // the tip of a chain keeps its rotation; a single joint (look-at) is the one that turns
        Skinning.worldMatrices(skeleton, pose, world);
        float[] data = pose.data();
        for (int k = 0; k < n; k++) {
            int j = chain[k];
            int o = j * TransformMath.TRS;
            lt[3 * k] = data[o];
            lt[3 * k + 1] = data[o + 1];
            lt[3 * k + 2] = data[o + 2];
            lq[4 * k] = data[o + 3];
            lq[4 * k + 1] = data[o + 4];
            lq[4 * k + 2] = data[o + 5];
            lq[4 * k + 3] = data[o + 6];
            int m = j * 16;
            px[k] = world[m + 12];
            py[k] = world[m + 13];
            pz[k] = world[m + 14];
            double c0x = world[m], c0y = world[m + 1], c0z = world[m + 2];
            scale[k] = Math.sqrt(c0x * c0x + c0y * c0y + c0z * c0z);
        }
        // the world rotation of the parent of the first joint: the product of the local rotations of its ancestors, root first
        int depth = 0;
        for (int a = skeleton.parent(chain[0]); a >= 0; a = skeleton.parent(a)) {
            ancestors[depth++] = a;
        }
        double[] pr = parentRotation;
        pr[0] = 0;
        pr[1] = 0;
        pr[2] = 0;
        pr[3] = 1;
        for (int i = depth - 1; i >= 0; i--) {
            int o = ancestors[i] * TransformMath.TRS + 3;
            multiply(pr[0], pr[1], pr[2], pr[3], data[o], data[o + 1], data[o + 2], data[o + 3], tmp4);
            System.arraycopy(tmp4, 0, pr, 0, 4);
        }
        fk(0);
        for (int k = 0; k < n - 1; k++) {
            double dx = px[k + 1] - px[k], dy = py[k + 1] - py[k], dz = pz[k + 1] - pz[k];
            length[k] = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Recomputes the world rotations of the chain joints from {@code from} on (and the positions of the joints after {@code from}) from the local rotations. */
    private void fk(int from) {
        for (int k = from; k < count; k++) {
            double qx, qy, qz, qw;
            if (k == 0) {
                qx = parentRotation[0];
                qy = parentRotation[1];
                qz = parentRotation[2];
                qw = parentRotation[3];
            } else {
                qx = rw[4 * (k - 1)];
                qy = rw[4 * (k - 1) + 1];
                qz = rw[4 * (k - 1) + 2];
                qw = rw[4 * (k - 1) + 3];
                // the position of joint k: its parent's position plus its local translation, scaled and rotated by the parent's world transform
                rotateVector(qx, qy, qz, qw, lt[3 * k], lt[3 * k + 1], lt[3 * k + 2], tmp);
                px[k] = px[k - 1] + scale[k - 1] * tmp[0];
                py[k] = py[k - 1] + scale[k - 1] * tmp[1];
                pz[k] = pz[k - 1] + scale[k - 1] * tmp[2];
            }
            multiply(qx, qy, qz, qw, lq[4 * k], lq[4 * k + 1], lq[4 * k + 2], lq[4 * k + 3], tmp4);
            rw[4 * k] = tmp4[0];
            rw[4 * k + 1] = tmp4[1];
            rw[4 * k + 2] = tmp4[2];
            rw[4 * k + 3] = tmp4[3];
        }
    }

    /** Writes the local rotations of the chain joints back into the pose. */
    private void store(Pose pose) {
        float[] data = pose.data();
        for (int k = 0; k < rotated; k++) {
            int o = chainJoints[k] * TransformMath.TRS + 3;
            double l = Math.sqrt(lq[4 * k] * lq[4 * k] + lq[4 * k + 1] * lq[4 * k + 1] + lq[4 * k + 2] * lq[4 * k + 2] + lq[4 * k + 3] * lq[4 * k + 3]);
            data[o] = (float) (lq[4 * k] / l);
            data[o + 1] = (float) (lq[4 * k + 1] / l);
            data[o + 2] = (float) (lq[4 * k + 2] / l);
            data[o + 3] = (float) (lq[4 * k + 3] / l);
        }
    }

    private double distanceToTip(double tx, double ty, double tz) {
        int t = count - 1;
        double dx = px[t] - tx, dy = py[t] - ty, dz = pz[t] - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- turning joints

    /** Turns joint {@code k} so that the direction to the next chain joint becomes {@code (dx, dy, dz)}. */
    private void aim(int k, double dx, double dy, double dz) {
        double cx = px[k + 1] - px[k], cy = py[k + 1] - py[k], cz = pz[k + 1] - pz[k];
        rotateBy(k, cx, cy, cz, dx, dy, dz);
    }

    /** Rotates joint {@code k} about its origin by the shortest rotation that takes direction c to direction d, and updates the chain below it. */
    private void rotateBy(int k, double cx, double cy, double cz, double dx, double dy, double dz) {
        double[] q = arcQ;
        arc(cx, cy, cz, dx, dy, dz, q);
        // new world rotation: the arc applied after the old one; the new local rotation is parentWorld^-1 * newWorld
        multiply(q[0], q[1], q[2], q[3], rw[4 * k], rw[4 * k + 1], rw[4 * k + 2], rw[4 * k + 3], tmp4);
        double nx = tmp4[0], ny = tmp4[1], nz = tmp4[2], nw = tmp4[3];
        double ax, ay, az, aw;
        if (k == 0) {
            ax = parentRotation[0];
            ay = parentRotation[1];
            az = parentRotation[2];
            aw = parentRotation[3];
        } else {
            ax = rw[4 * (k - 1)];
            ay = rw[4 * (k - 1) + 1];
            az = rw[4 * (k - 1) + 2];
            aw = rw[4 * (k - 1) + 3];
        }
        multiply(-ax, -ay, -az, aw, nx, ny, nz, nw, tmp4);
        double l = Math.sqrt(tmp4[0] * tmp4[0] + tmp4[1] * tmp4[1] + tmp4[2] * tmp4[2] + tmp4[3] * tmp4[3]); // keep it a unit quaternion however many sweeps accumulate
        lq[4 * k] = tmp4[0] / l;
        lq[4 * k + 1] = tmp4[1] / l;
        lq[4 * k + 2] = tmp4[2] / l;
        lq[4 * k + 3] = tmp4[3] / l;
        fk(k);
    }

    private final double[] arcQ = new double[4];

    // ---------------------------------------------------------------- quaternion helpers on doubles (x, y, z, w)

    /** {@code out = a * b}. */
    private static void multiply(double ax, double ay, double az, double aw, double bx, double by, double bz, double bw, double[] out) {
        out[0] = aw * bx + ax * bw + ay * bz - az * by;
        out[1] = aw * by - ax * bz + ay * bw + az * bx;
        out[2] = aw * bz + ax * by - ay * bx + az * bw;
        out[3] = aw * bw - ax * bx - ay * by - az * bz;
    }

    /** Rotates the vector by the unit quaternion into {@code out[0..2]}. */
    private static void rotateVector(double qx, double qy, double qz, double qw, double vx, double vy, double vz, double[] out) {
        double tx = 2 * (qy * vz - qz * vy), ty = 2 * (qz * vx - qx * vz), tz = 2 * (qx * vy - qy * vx);
        out[0] = vx + qw * tx + (qy * tz - qz * ty);
        out[1] = vy + qw * ty + (qz * tx - qx * tz);
        out[2] = vz + qw * tz + (qx * ty - qy * tx);
    }

    /** The shortest-arc rotation (a unit quaternion) that takes direction a to direction b; the identity when either is zero, a half turn about a perpendicular axis when they oppose. */
    private static void arc(double ax, double ay, double az, double bx, double by, double bz, double[] out) {
        double al = Math.sqrt(ax * ax + ay * ay + az * az), bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (!(al > 0) || !(bl > 0)) {
            out[0] = out[1] = out[2] = 0;
            out[3] = 1;
            return;
        }
        ax /= al;
        ay /= al;
        az /= al;
        bx /= bl;
        by /= bl;
        bz /= bl;
        double dot = ax * bx + ay * by + az * bz;
        if (dot < -1 + 1e-12) {
            // opposite: turn half a circle about any axis perpendicular to a
            double px = Math.abs(ax) < 0.9 ? 1 : 0, py = Math.abs(ax) < 0.9 ? 0 : 1;
            double cx = ay * 0 - az * py, cy = az * px - ax * 0, cz = ax * py - ay * px;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            out[0] = cx / cl;
            out[1] = cy / cl;
            out[2] = cz / cl;
            out[3] = 0;
            return;
        }
        double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
        double w = 1 + dot;
        double l = Math.sqrt(cx * cx + cy * cy + cz * cz + w * w);
        out[0] = cx / l;
        out[1] = cy / l;
        out[2] = cz / l;
        out[3] = w / l;
    }
}
