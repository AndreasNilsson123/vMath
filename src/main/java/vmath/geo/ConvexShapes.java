package vmath.geo;

import vmath.annotations.Experimental;
import vmath.core.Mat3f;
import vmath.core.Quatf;

/**
 * The convex shapes of the library as {@link ConvexShape}s for {@link Gjk}: spheres, axis-aligned and oriented boxes, capsules, point clouds (the hull of the points, without
 * building it) and polytopes, and two combinators that move and rotate a shape rigidly or inflate it by a margin. All the support functions are allocation-free.
 *
 * <p>A point cloud's support function scans every point, which is O(n); for more than a few dozen points build a {@link ConvexPolytope} (which drops the points inside) first.
 *
 * <p><b>Thread safety.</b> The shapes are immutable except the point cloud, which reads the array it was given: do not change it while it is in use. They can be shared between
 * threads.
 */
@Experimental("the set of shapes follows what the collision code needs (cylinders and cones are the obvious next ones)")
public final class ConvexShapes {

    private ConvexShapes() {
    }

    /** A solid sphere. */
    public static ConvexShape sphere(double cx, double cy, double cz, double radius) {
        if (!(radius >= 0.0)) {
            throw new IllegalArgumentException("the radius must not be negative: " + radius);
        }
        return (dx, dy, dz, out) -> {
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double s = len > 0 ? radius / len : 0;
            out[0] = cx + dx * s;
            out[1] = cy + dy * s;
            out[2] = cz + dz * s;
        };
    }

    /** The shape of a {@link Spheref}. */
    public static ConvexShape of(Spheref s) {
        return sphere(s.cx(), s.cy(), s.cz(), s.radius());
    }

    /** The shape of an {@link Aabbf}. */
    public static ConvexShape of(Aabbf b) {
        double x0 = b.minX(), y0 = b.minY(), z0 = b.minZ(), x1 = b.maxX(), y1 = b.maxY(), z1 = b.maxZ();
        return (dx, dy, dz, out) -> {
            out[0] = dx >= 0 ? x1 : x0;
            out[1] = dy >= 0 ? y1 : y0;
            out[2] = dz >= 0 ? z1 : z0;
        };
    }

    /** The shape of an {@link Obbf}. */
    public static ConvexShape of(Obbf b) {
        Mat3f m = b.axes();
        // the columns of the rotation matrix are the box's axes in world space
        double ux = m.m00(), uy = m.m01(), uz = m.m02();
        double vx = m.m10(), vy = m.m11(), vz = m.m12();
        double wx = m.m20(), wy = m.m21(), wz = m.m22();
        double cx = b.cx(), cy = b.cy(), cz = b.cz(), hx = b.hx(), hy = b.hy(), hz = b.hz();
        return (dx, dy, dz, out) -> {
            double a = (ux * dx + uy * dy + uz * dz) >= 0 ? hx : -hx;
            double c = (vx * dx + vy * dy + vz * dz) >= 0 ? hy : -hy;
            double d = (wx * dx + wy * dy + wz * dz) >= 0 ? hz : -hz;
            out[0] = cx + a * ux + c * vx + d * wx;
            out[1] = cy + a * uy + c * vy + d * wy;
            out[2] = cz + a * uz + c * vz + d * wz;
        };
    }

    /** The shape of a {@link Capsulef}. */
    public static ConvexShape of(Capsulef c) {
        double ax = c.ax(), ay = c.ay(), az = c.az(), bx = c.bx(), by = c.by(), bz = c.bz(), r = c.radius();
        return (dx, dy, dz, out) -> {
            boolean second = (bx - ax) * dx + (by - ay) * dy + (bz - az) * dz > 0;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double s = len > 0 ? r / len : 0;
            out[0] = (second ? bx : ax) + dx * s;
            out[1] = (second ? by : ay) + dy * s;
            out[2] = (second ? bz : az) + dz * s;
        };
    }

    /** The convex hull of {@code count} points ({@code x, y, z} triples), without building it: the support function scans the points. */
    public static ConvexShape points(float[] xyz, int count) {
        if (count < 1 || (long) count * 3 > xyz.length) {
            throw new IllegalArgumentException("need between 1 and " + xyz.length / 3 + " points: " + count);
        }
        return (dx, dy, dz, out) -> {
            double best = Double.NEGATIVE_INFINITY;
            int at = 0;
            for (int i = 0; i < count; i++) {
                double d = xyz[3 * i] * dx + xyz[3 * i + 1] * dy + xyz[3 * i + 2] * dz;
                if (d > best) {
                    best = d;
                    at = i;
                }
            }
            out[0] = xyz[3 * at];
            out[1] = xyz[3 * at + 1];
            out[2] = xyz[3 * at + 2];
        };
    }

    /** {@code shape} rotated by {@code rotation} (a unit quaternion) about the origin and then moved by {@code (tx, ty, tz)}. */
    public static ConvexShape transformed(ConvexShape shape, Quatf rotation, double tx, double ty, double tz) {
        Mat3f m = Mat3f.rotation(rotation);
        // m maps local to world; its transpose maps a world direction to the local one
        double m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m10 = m.m10(), m11 = m.m11(), m12 = m.m12(), m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
        return (dx, dy, dz, out) -> {
            double lx = m00 * dx + m01 * dy + m02 * dz;
            double ly = m10 * dx + m11 * dy + m12 * dz;
            double lz = m20 * dx + m21 * dy + m22 * dz;
            shape.support(lx, ly, lz, out);
            double px = out[0], py = out[1], pz = out[2];
            out[0] = m00 * px + m10 * py + m20 * pz + tx;
            out[1] = m01 * px + m11 * py + m21 * pz + ty;
            out[2] = m02 * px + m12 * py + m22 * pz + tz;
        };
    }

    /** {@code shape} moved by {@code (tx, ty, tz)}. */
    public static ConvexShape translated(ConvexShape shape, double tx, double ty, double tz) {
        return (dx, dy, dz, out) -> {
            shape.support(dx, dy, dz, out);
            out[0] += tx;
            out[1] += ty;
            out[2] += tz;
        };
    }

    /** {@code shape} inflated by {@code margin}: the Minkowski sum with a sphere, which rounds every corner. Handy for swept and padded queries. */
    public static ConvexShape inflated(ConvexShape shape, double margin) {
        if (!(margin >= 0.0)) {
            throw new IllegalArgumentException("the margin must not be negative: " + margin);
        }
        return (dx, dy, dz, out) -> {
            shape.support(dx, dy, dz, out);
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double s = len > 0 ? margin / len : 0;
            out[0] += dx * s;
            out[1] += dy * s;
            out[2] += dz * s;
        };
    }
}
