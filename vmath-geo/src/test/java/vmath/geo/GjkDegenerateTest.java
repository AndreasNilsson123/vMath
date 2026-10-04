package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

/**
 * {@link Gjk#penetration} on shapes that have almost no volume.
 *
 * <p>The expanding polytope must end in a finite answer, never in an exception, however flat the
 * Minkowski difference is; the result may be a touching contact with a default normal.
 */
class GjkDegenerateTest {

    private final SplittableRandom r = new SplittableRandom(Rnd.SEED);
    private final Gjk gjk = new Gjk();
    private final Gjk.Result res = new Gjk.Result();

    private ConvexShape flatPoints(double thickness, double ox, double oy, double oz) {
        int n = 12;
        float[] p = new float[3 * n];
        for (int i = 0; i < n; i++) {
            p[3 * i] = (float) (ox + r.nextDouble(-1, 1));
            p[3 * i + 1] = (float) (oy + r.nextDouble(-1, 1));
            p[3 * i + 2] = (float) (oz + thickness * r.nextDouble(-1, 1));
        }
        return ConvexShapes.points(p, n);
    }

    @Test
    void penetrationOfNearlyFlatShapesDoesNotThrowAndIsFinite() {
        double[] thicknesses = {0, 1e-12, 1e-9, 1e-6, 1e-3};
        for (double t : thicknesses) {
            for (int rep = 0; rep < 300; rep++) {
                ConvexShape a = flatPoints(t, 0, 0, 0);
                ConvexShape b = flatPoints(t, r.nextDouble(-0.5, 0.5), r.nextDouble(-0.5, 0.5), t * r.nextDouble(-1, 1));
                if (gjk.penetration(a, b, res)) {
                    String what = "thickness " + t;
                    assertTrue(Double.isFinite(res.depth) && res.depth >= 0, what);
                    for (int k = 0; k < 3; k++) {
                        assertTrue(Double.isFinite(res.normal[k]) && Double.isFinite(res.pointA[k]) && Double.isFinite(res.pointB[k]), what);
                    }
                }
            }
        }
    }
}
