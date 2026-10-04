package vmath.samples.framework;

import vmath.bulk.BoundsArray;
import vmath.util.Noise;
import vmath.util.Rng;

/**
 * Procedural scenes that several demos share: boxes in a {@link BoundsArray}, built from seeded
 * noise so that every run draws the same thing.
 *
 * <p>Internal: part of the samples, not of the library. The boxes are the bounds that the culling
 * works on and, drawn as scaled unit cubes, what the screen shows.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
public final class Scenes {

    /**
     * The distance between the centres of neighbouring boxes of {@link #city}.
     */
    public static final float CITY_SPACING = 3f;

    /**
     * The edge length of the footprint of a box of {@link #city}.
     */
    public static final float CITY_FOOTPRINT = 2f;

    /**
     * The distance between the centres of neighbouring buildings of {@link #blocks}.
     */
    public static final float BLOCK_PITCH = 24f;

    /**
     * The edge length of the footprint of a building of {@link #blocks}; the rest is street.
     */
    public static final float BLOCK_FOOTPRINT = 18f;

    private Scenes() {
    }

    /**
     * The boxes of {@link #blocks}: the buildings first, then the street props, then one flat
     * box for the ground.
     *
     * @param bounds the boxes, {@code buildings + props + 1} of them; never {@code null}
     * @param buildings the number of buildings, which are the first entries
     * @param props the number of street props, which follow the buildings; the last entry is the
     *     ground
     */
    public record Blocks(BoundsArray bounds, int buildings, int props) {
    }

    /**
     * Builds a city of a given number of boxes, laid out on a square grid around the origin,
     * standing on the plane {@code y = 0}, whose heights follow fractal noise, so that there are
     * hills of tall towers and flat quarters, with a little variation from box to box.
     *
     * @param count the number of boxes; at least 1
     * @return the bounds of the {@code count} boxes, in the order of the grid rows, followed by one
     *     flat box for the ground, so there are {@code count + 1} entries and the last is the
     *     ground
     */
    public static BoundsArray city(int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        float half = side * CITY_SPACING * 0.5f;
        Rng rng = new Rng(7);
        BoundsArray bounds = new BoundsArray(count + 1);
        for (int i = 0; i < count; i++) {
            int gx = i % side, gz = i / side;
            float x = gx * CITY_SPACING - half, z = gz * CITY_SPACING - half;
            double hills = Noise.fbm2(Noise.Kind.SIMPLEX, gx * 0.006, gz * 0.006, 11, 4, 2.0, 0.5);
            double ridge = Math.max(0.0, hills);
            float height = 1.5f + (float) (90.0 * ridge * ridge) + (float) rng.nextDouble() * 4f;
            float e = CITY_FOOTPRINT * 0.5f;
            bounds.add(x - e, 0f, z - e, x + e, height, z + e);
        }
        bounds.add(-half - CITY_SPACING, -1f, -half - CITY_SPACING, half + CITY_SPACING, 0f, half + CITY_SPACING);
        return bounds;
    }

    /**
     * Builds a dense city of city blocks: big buildings that make good occluders, streets between
     * them, and small props (crates, cars, bins) scattered in the streets, which are what the
     * culling is there to remove when a building stands in front of them.
     *
     * <p>The buildings stand on a square grid with a pitch of {@link #BLOCK_PITCH} and a footprint
     * of {@link #BLOCK_FOOTPRINT}, so the streets are 6 wide; their heights follow fractal noise
     * from 8 to about 80. The grid is centred on the origin. The street between building columns
     * {@code gx} and {@code gx + 1} runs along {@code z} at {@code x = (gx - side / 2 + 1) * pitch -
     * pitch / 2}, which is where the scripted flights of the demos go.
     *
     * @param side the number of buildings along one side; at least 1
     * @param propsPerBuilding the number of street props per building; at least 0
     * @return the boxes, with the counts
     */
    public static Blocks blocks(int side, int propsPerBuilding) {
        int buildings = side * side;
        int props = buildings * propsPerBuilding;
        BoundsArray bounds = new BoundsArray(buildings + props + 1);
        Rng rng = new Rng(11);
        float half = side * BLOCK_PITCH * 0.5f;
        float e = BLOCK_FOOTPRINT * 0.5f;
        for (int i = 0; i < buildings; i++) {
            int gx = i % side, gz = i / side;
            float cx = (gx + 0.5f) * BLOCK_PITCH - half, cz = (gz + 0.5f) * BLOCK_PITCH - half;
            double hills = Noise.fbm2(Noise.Kind.SIMPLEX, gx * 0.045, gz * 0.045, 5, 4, 2.0, 0.5);
            float height = 8f + (float) (70.0 * Math.max(0.0, hills + 0.25)) + (float) rng.nextDouble() * 12f;
            bounds.add(cx - e, 0f, cz - e, cx + e, height, cz + e);
        }
        for (int i = 0; i < props; i++) {
            int b = rng.nextInt(buildings);
            int gx = b % side, gz = b / side;
            float cx = (gx + 0.5f) * BLOCK_PITCH - half, cz = (gz + 0.5f) * BLOCK_PITCH - half;
            float x = cx + (float) rng.nextDouble(-0.5, 0.5) * BLOCK_PITCH;
            float z = cz + (float) rng.nextDouble(-0.5, 0.5) * BLOCK_PITCH;
            if (Math.abs(x - cx) < e + 0.5f && Math.abs(z - cz) < e + 0.5f) {
                // inside the footprint: push it into the street on one axis
                float into = e + 0.5f + (float) rng.nextDouble() * (BLOCK_PITCH * 0.5f - e - 1f);
                if (rng.nextBoolean()) {
                    x = cx + (x < cx ? -into : into);
                } else {
                    z = cz + (z < cz ? -into : into);
                }
            }
            float w = 0.8f + (float) rng.nextDouble() * 1.6f, d = 0.8f + (float) rng.nextDouble() * 1.6f;
            float h = 0.5f + (float) rng.nextDouble() * 2.2f;
            bounds.add(x - w * 0.5f, 0f, z - d * 0.5f, x + w * 0.5f, h, z + d * 0.5f);
        }
        bounds.add(-half - BLOCK_PITCH, -1f, -half - BLOCK_PITCH, half + BLOCK_PITCH, 0f, half + BLOCK_PITCH);
        return new Blocks(bounds, buildings, props);
    }
}
