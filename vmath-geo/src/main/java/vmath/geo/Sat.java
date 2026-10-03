package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The separating axis test for two {@link ConvexPolytope}s: two convex polytopes are disjoint exactly when some axis separates their projections, and it suffices to try the
 * face normals of both and the cross products of an edge direction of one with an edge direction of the other. The axes come from {@link ConvexPolytope#faceDirections()} and
 * {@link ConvexPolytope#edgeDirections()}, which list each distinct direction once.
 *
 * <p>{@link #separation} returns the largest <em>signed separation</em> over all those axes: positive when the polytopes are apart along that axis (the gap between the two
 * projections), negative when they overlap along every axis, in which case its magnitude is the exact <b>penetration depth</b> (the smallest distance one polytope must move to
 * free itself) and the axis is the direction to move. When they are apart the value is only a <em>lower bound</em> of the distance between them (the gap along the best axis; the
 * true distance is larger when the closest features are not aligned with any of the axes): use {@link Gjk#distance} for the distance.
 *
 * <p>The cost is O(F + E_a E_b) projections of the vertices (each projection scans the vertices of both polytopes), so it is meant for polytopes of up to a few dozen faces.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time.
 */
@Experimental("the result may become an object with the witness features, as the penetration query of Gjk has")
public final class Sat {

    private Sat() {
    }

    /**
     * The largest signed separation of {@code a} and {@code b} over the separating axes (see the class comment). {@code axisOut} receives the unit axis, oriented from {@code a}
     * towards {@code b}: moving {@code b} along it by {@code -separation} (when negative) just frees the two.
     */
    public static double separation(ConvexPolytope a, ConvexPolytope b, double[] axisOut) {
        float[] va = a.vertices(), vb = b.vertices();
        double best = Double.NEGATIVE_INFINITY;
        double[] fa = a.faceDirections(), fb = b.faceDirections();
        for (int i = 0; i < fa.length; i += 3) {
            best = consider(va, vb, fa[i], fa[i + 1], fa[i + 2], best, axisOut);
        }
        for (int i = 0; i < fb.length; i += 3) {
            best = consider(va, vb, fb[i], fb[i + 1], fb[i + 2], best, axisOut);
        }
        double[] ea = a.edgeDirections(), eb = b.edgeDirections();
        for (int i = 0; i < ea.length; i += 3) {
            for (int j = 0; j < eb.length; j += 3) {
                double cx = ea[i + 1] * eb[j + 2] - ea[i + 2] * eb[j + 1];
                double cy = ea[i + 2] * eb[j] - ea[i] * eb[j + 2];
                double cz = ea[i] * eb[j + 1] - ea[i + 1] * eb[j];
                double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (len < 1e-9) {
                    continue; // parallel edges: the face axes already cover them
                }
                best = consider(va, vb, cx / len, cy / len, cz / len, best, axisOut);
            }
        }
        return best;
    }

    /** Whether the polytopes overlap (share at least a point): the separation is not positive on any axis. Touching counts as overlapping. */
    public static boolean intersects(ConvexPolytope a, ConvexPolytope b) {
        return separation(a, b, new double[3]) <= 0.0;
    }

    private static double consider(float[] va, float[] vb, double nx, double ny, double nz, double best, double[] axisOut) {
        double aMin = Double.POSITIVE_INFINITY, aMax = Double.NEGATIVE_INFINITY, bMin = Double.POSITIVE_INFINITY, bMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < va.length; i += 3) {
            double d = va[i] * nx + va[i + 1] * ny + va[i + 2] * nz;
            aMin = Math.min(aMin, d);
            aMax = Math.max(aMax, d);
        }
        for (int i = 0; i < vb.length; i += 3) {
            double d = vb[i] * nx + vb[i + 1] * ny + vb[i + 2] * nz;
            bMin = Math.min(bMin, d);
            bMax = Math.max(bMax, d);
        }
        // the gap on each side of the axis; the larger is the signed separation (b above a, or b below a)
        double above = bMin - aMax, below = aMin - bMax;
        double s = Math.max(above, below);
        if (s > best) {
            double sign = above >= below ? 1 : -1;
            axisOut[0] = sign * nx;
            axisOut[1] = sign * ny;
            axisOut[2] = sign * nz;
            return s;
        }
        return best;
    }
}
