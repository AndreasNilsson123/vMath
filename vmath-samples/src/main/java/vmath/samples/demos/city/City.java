package vmath.samples.demos.city;

import vmath.bulk.BoundsArray;
import vmath.util.Noise;
import vmath.util.Rng;

/**
 * The scene of {@link CityDemo}: a square grid of boxes whose heights follow fractal
 * noise, so that there are hills of tall towers and flat quarters, with a little variation from
 * box to box.
 *
 * <p>Internal: part of the samples, not of the library. The boxes are the bounds that the culling
 * works on and, drawn as scaled unit cubes, what the screen shows.
 *
 * <p><b>Thread safety.</b> Stateless: the method may be called from any number of threads.
 */
final class City {

    /**
     * The distance between the centres of neighbouring boxes.
     */
    static final float SPACING = 3f;

    /**
     * The edge length of the footprint of a box.
     */
    static final float FOOTPRINT = 2f;

    private City() {
    }

    /**
     * Builds the boxes of a city with the given number of buildings, laid out on a square grid
     * around the origin, standing on the plane {@code y = 0}.
     *
     * @param count the number of boxes; at least 1
     * @return the bounds of the {@code count} boxes, in the order of the grid rows, followed by one
     *     flat box for the ground, so there are {@code count + 1} entries and the last is the
     *     ground
     */
    static BoundsArray build(int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        float half = side * SPACING * 0.5f;
        Rng rng = new Rng(7);
        BoundsArray bounds = new BoundsArray(count + 1);
        for (int i = 0; i < count; i++) {
            int gx = i % side, gz = i / side;
            float x = gx * SPACING - half, z = gz * SPACING - half;
            double hills = Noise.fbm2(Noise.Kind.SIMPLEX, gx * 0.006, gz * 0.006, 11, 4, 2.0, 0.5);
            double ridge = Math.max(0.0, hills);
            float height = 1.5f + (float) (90.0 * ridge * ridge) + (float) rng.nextDouble() * 4f;
            float e = FOOTPRINT * 0.5f;
            bounds.add(x - e, 0f, z - e, x + e, height, z + e);
        }
        bounds.add(-half - SPACING, -1f, -half - SPACING, half + SPACING, 0f, half + SPACING);
        return bounds;
    }
}
