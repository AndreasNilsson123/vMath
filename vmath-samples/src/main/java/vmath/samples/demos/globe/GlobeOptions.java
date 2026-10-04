package vmath.samples.demos.globe;

import java.util.List;

/**
 * The options of the {@link GlobeDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param minZoom the zoom level of the coarsest tiles, which are always resident
 * @param maxZoom the finest zoom level
 * @param cells the number of quads along the edge of a tile
 * @param imageSize the width of a tile image in pixels
 * @param pixels the largest distance in pixels between the vertices of a tile on the screen
 * @param resident the number of tiles that fit in the GPU's buffers
 * @param flight how long the scripted descent takes, in seconds
 * @param threads the number of tile worker threads
 * @param naive whether to start with the naive single-precision pipeline instead of the floating
 *     origin
 * @param free whether to start with the free camera instead of the scripted descent
 * @param noHorizon whether to start with the horizon culling off
 * @param verify whether to check the selection, the horizon culling and the uploaded vertices
 */
record GlobeOptions(int minZoom, int maxZoom, int cells, int imageSize, double pixels, int resident, double flight, int threads, boolean naive, boolean free, boolean noHorizon, boolean verify) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the globe demo:
              --max-zoom N         finest tile level (default 17: tiles of about 300 m at the equator)
              --min-zoom N         coarsest tile level, always resident (default 2: 16 tiles)
              --cells N            quads along a tile edge (default 32)
              --image N            width of a tile image in pixels (default 128)
              --pixels P           largest on-screen distance between tile vertices in pixels (default 6)
              --resident N         tiles that fit in the GPU buffers (default 3072, at most 4095)
              --flight SECONDS     duration of the scripted descent (default 12)
              --threads N          tile worker threads (default: processors minus two, at least two)
              --naive              start with the naive float pipeline (no floating origin)
              --free               start with the free camera (W A S D, Space, Control, Shift, mouse)
              --no-horizon         start with horizon culling off
              --verify             check the selection, the horizon culling and the uploaded vertices
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static GlobeOptions parse(List<String> args) {
        int minZoom = 2, maxZoom = 17, cells = 32, imageSize = 128, resident = 3072;
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        double pixels = 6.0, flight = 12.0;
        boolean naive = false, free = false, noHorizon = false, verify = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--max-zoom" -> maxZoom = (int) number(args, ++i);
                case "--min-zoom" -> minZoom = (int) number(args, ++i);
                case "--cells" -> cells = (int) number(args, ++i);
                case "--image" -> imageSize = (int) number(args, ++i);
                case "--pixels" -> pixels = number(args, ++i);
                case "--resident" -> resident = (int) number(args, ++i);
                case "--flight" -> flight = number(args, ++i);
                case "--threads" -> threads = (int) number(args, ++i);
                case "--naive" -> naive = true;
                case "--free" -> free = true;
                case "--no-horizon" -> noHorizon = true;
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (minZoom < 1 || minZoom > 4 || maxZoom < minZoom || maxZoom > 22 || cells < 4 || cells > 128 || imageSize < 16 || imageSize > 1024 || !(pixels > 0.0) || resident < 64 || resident > 4095
                || resident < 3 * (1 << (2 * minZoom)) || !(flight > 0.0) || threads < 1 || threads > 64) {
            throw new IllegalArgumentException("an option is out of range\n" + USAGE);
        }
        return new GlobeOptions(minZoom, maxZoom, cells, imageSize, pixels, resident, flight, threads, naive, free, noHorizon, verify);
    }

    private static double number(List<String> args, int i) {
        if (i >= args.size()) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number\n" + USAGE);
        }
        try {
            return Double.parseDouble(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number, not " + args.get(i) + "\n" + USAGE);
        }
    }
}
