package vmath.samples.demos.terrain;

import java.util.List;

/**
 * The options of the {@link TerrainDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param chunks the number of chunks along each side of the terrain
 * @param cells the number of grid cells along the side of a chunk at full detail
 * @param budget the screen-space error in pixels that the levels of detail may have
 * @param noLod whether every visible chunk is drawn at full detail, for comparison
 * @param free whether the camera starts free instead of on the rail
 * @param parameterSpeed whether the rail starts with equal steps of the spline parameter instead
 *     of equal steps of arc length
 */
record TerrainOptions(int chunks, int cells, float budget, boolean noLod, boolean free, boolean parameterSpeed) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the terrain demo:
              --chunks N           chunks along each side (default 16, so 256 chunks of 256 m: a 4 km square)
              --cells N            grid cells along a chunk at full detail (default 48)
              --budget PIXELS      screen-space error that the levels may have (default 2)
              --no-lod             draw every visible chunk at full detail
              --free               start with the free camera instead of the rail
              --parameter-speed    move along the rail by equal steps of the spline parameter (the speed then varies)
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static TerrainOptions parse(List<String> args) {
        int chunks = 16, cells = 48;
        float budget = 2f;
        boolean noLod = false, free = false, parameterSpeed = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--chunks" -> chunks = (int) number(args, ++i);
                case "--cells" -> cells = (int) number(args, ++i);
                case "--budget" -> budget = (float) number(args, ++i);
                case "--no-lod" -> noLod = true;
                case "--free" -> free = true;
                case "--parameter-speed" -> parameterSpeed = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (chunks < 2 || chunks > 64 || cells < 8 || cells > 128 || !(budget > 0f)) {
            throw new IllegalArgumentException("--chunks must be from 2 to 64, --cells from 8 to 128 and --budget positive\n" + USAGE);
        }
        return new TerrainOptions(chunks, cells, budget, noLod, free, parameterSpeed);
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
