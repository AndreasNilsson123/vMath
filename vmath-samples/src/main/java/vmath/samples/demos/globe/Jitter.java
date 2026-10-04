package vmath.samples.demos.globe;

import java.util.Random;
import vmath.core.FloatingOrigin;
import vmath.core.Geodetic;
import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Measures what camera-relative rendering buys on a globe: the error, in metres, of the position
 * that the vertex shader computes for a vertex near the camera, with and without a floating
 * origin.
 *
 * <p>The shader forms {@code position + (tileOffset - cameraLocal)} in {@code float}, where
 * {@code position} is the vertex relative to its tile's reference point. Both pipelines are
 * simulated here with the same {@code float} arithmetic and compared with the exact difference
 * computed in {@code double}:
 *
 * <ul>
 *   <li><b>naive</b>: the tile offset and the camera are the ECEF positions themselves narrowed to
 *       {@code float}, which are millions of metres and have a resolution of 0.5 m;
 *   <li><b>rebased</b>: the tile offset and the camera are taken relative to a {@code FloatingOrigin}
 *       in {@code double} first ({@code Rebase}), so they are small near the camera and the float
 *       arithmetic works on small numbers.
 * </ul>
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 */
final class Jitter {

    private Jitter() {
    }

    /**
     * Gives the largest error of the position of a vertex on the ground under a camera.
     *
     * <p>The camera is at a height above a fixed place, and 400 vertices lie on the ground (height
     * 0) within a radius of three times the height, at most 3 km, around the point below the camera;
     * their tile's reference point is that point.
     *
     * @param altitude the height of the camera above the ground in metres
     * @return two values in metres: the largest error of the naive pipeline and that of the rebased
     *     one
     */
    static double[] maxError(double altitude) {
        Geodetic place = Geodetic.ofDegrees(46.55, 10.15, 0.0);
        Vec3d ref = Wgs84.toEcef(place);
        Vec3d camera = Wgs84.toEcef(place.latitude(), place.longitude(), altitude);
        FloatingOrigin floating = new FloatingOrigin(1024.0, 4096.0);
        floating.update(camera);
        Vec3d origin = floating.origin();
        double radius = Math.min(3000.0, 3.0 * altitude);
        Random random = new Random(1);
        double naive = 0.0;
        double rebased = 0.0;
        for (int i = 0; i < 400; i++) {
            double e = (random.nextDouble() * 2 - 1) * radius, n = (random.nextDouble() * 2 - 1) * radius;
            Vec3d p = Wgs84.enuToEcef(place, new Vec3d(e, n, 0.0));
            Vec3d exact = p.sub(camera);
            float px = (float) (p.x() - ref.x()), py = (float) (p.y() - ref.y()), pz = (float) (p.z() - ref.z());
            naive = Math.max(naive, error(px, py, pz, (float) ref.x(), (float) ref.y(), (float) ref.z(), (float) camera.x(), (float) camera.y(), (float) camera.z(), exact));
            rebased = Math.max(rebased, error(px, py, pz, (float) (ref.x() - origin.x()), (float) (ref.y() - origin.y()), (float) (ref.z() - origin.z()),
                    (float) (camera.x() - origin.x()), (float) (camera.y() - origin.y()), (float) (camera.z() - origin.z()), exact));
        }
        return new double[] {naive, rebased};
    }

    private static double error(float px, float py, float pz, float ox, float oy, float oz, float cx, float cy, float cz, Vec3d exact) {
        float rx = px + (ox - cx), ry = py + (oy - cy), rz = pz + (oz - cz);
        double dx = rx - exact.x(), dy = ry - exact.y(), dz = rz - exact.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
