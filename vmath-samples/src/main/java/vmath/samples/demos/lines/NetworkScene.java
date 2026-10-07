package vmath.samples.demos.lines;

import java.util.SplittableRandom;
import vmath.lines.LineStyle;

/**
 * A procedural river-and-road network for the line demos: meandering rivers drawn with a width in
 * world units, roads drawn twice (a dark casing under a bright fill), minor roads, dashed railways
 * and thin random walks in several colours, all over a rectangle of world units.
 *
 * <p>Every polyline has the same number of points, so the number of segments is known and the
 * runs of equal style (what the draws are made of) come from how the styles are dealt out.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless apart from the generator passed in; one thread at a time.
 */
final class NetworkScene {

    /** The width of the world in units. */
    static final double WIDTH = 8000.0;
    /** The height of the world in units. */
    static final double HEIGHT = 4500.0;

    private static final LineStyle RIVER = LineStyle.world(14f).withColor(0x3A7BD5FF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND).withLayer(0);
    private static final LineStyle CASING = LineStyle.pixels(7f).withColor(0x303030FF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND).withLayer(1);
    private static final LineStyle FILL = LineStyle.pixels(4f).withColor(0xF2A33AFF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND).withLayer(2);
    private static final LineStyle MINOR = LineStyle.pixels(2f).withColor(0xB8B8B8FF).withJoin(LineStyle.Join.ROUND).withLayer(1);
    private static final LineStyle RAIL = LineStyle.pixels(3f).withColor(0x202020FF).withDash(30f, 30f).withLayer(2);
    private static final LineStyle[] WALKS = new LineStyle[8];

    static {
        for (int i = 0; i < WALKS.length; i++) {
            int r = 90 + 20 * (i % 4), g = 120 + 15 * i, b = 255 - 25 * i;
            WALKS[i] = LineStyle.pixels(1f + 0.5f * (i % 3)).withColor(r << 24 | g << 16 | b << 8 | 0xFF).withLayer(0);
        }
    }

    private NetworkScene() {
    }

    /** Receives the polylines of the scene. */
    @FunctionalInterface
    interface Sink {
        /**
         * Receives one polyline.
         *
         * @param xyz the points as triples
         * @param pointCount the number of points
         * @param closed whether the last point is joined to the first
         * @param style the style
         */
        void polyline(double[] xyz, int pointCount, boolean closed, LineStyle style);
    }

    /**
     * Generates the network.
     *
     * @param polylines about how many polylines to make
     * @param points the points of each
     * @param seed the seed of the generator
     * @param sink receives the polylines in the order they are made
     */
    static void generate(int polylines, int points, long seed, Sink sink) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] xyz = new double[3 * points];
        int rivers = polylines * 4 / 100;
        int roadPairs = polylines * 10 / 100;                    // each pair is two polylines: 20 percent of the polylines
        int minors = polylines * 50 / 100;
        int rails = polylines * 5 / 100;
        int walks = Math.max(0, polylines - rivers - 2 * roadPairs - minors - rails);
        for (int i = 0; i < rivers; i++) {
            meander(rnd, xyz, points, 25.0, 0.10);
            sink.polyline(xyz, points, false, RIVER);
        }
        for (int i = 0; i < roadPairs; i++) {
            meander(rnd, xyz, points, 22.0, 0.12);
            sink.polyline(xyz, points, false, CASING);
            sink.polyline(xyz, points, false, FILL);
        }
        for (int i = 0; i < minors; i++) {
            meander(rnd, xyz, points, 12.0, 0.30);
            sink.polyline(xyz, points, false, MINOR);
        }
        for (int i = 0; i < rails; i++) {
            meander(rnd, xyz, points, 40.0, 0.02);
            sink.polyline(xyz, points, false, RAIL);
        }
        for (int i = 0; i < walks; i++) {
            meander(rnd, xyz, points, 9.0, 0.8);
            sink.polyline(xyz, points, false, WALKS[rnd.nextInt(WALKS.length)]);
        }
    }

    /** A walk whose heading turns smoothly: {@code curl} is the size of the random push on the turning rate at each step. */
    private static void meander(SplittableRandom rnd, double[] xyz, int points, double step, double curl) {
        double x = rnd.nextDouble() * WIDTH, y = rnd.nextDouble() * HEIGHT, heading = rnd.nextDouble() * Math.PI * 2;
        double rate = 0;
        for (int k = 0; k < points; k++) {
            rate = rate * 0.9 + (rnd.nextDouble() - 0.5) * curl;
            heading += rate;
            x += Math.cos(heading) * step * (0.8 + 0.4 * rnd.nextDouble());
            y += Math.sin(heading) * step * (0.8 + 0.4 * rnd.nextDouble());
            xyz[3 * k] = x;
            xyz[3 * k + 1] = y;
            xyz[3 * k + 2] = 0.0;
        }
    }
}
