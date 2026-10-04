package vmath.samples.demos.shadows;

import java.util.List;

/**
 * The options of the {@link ShadowDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param blocks the number of buildings along one side of the city
 * @param props the number of street props per building
 * @param cascades the number of cascades
 * @param map the width of a shadow map in texels
 * @param lambda the blend of logarithmic and uniform split spacing
 * @param distance the shadow distance in metres
 * @param stabilize whether the cascades are snapped to texels
 * @param verify whether to check the casters, the shadow maps and the lookup at the start
 * @param tint whether to start with the scene tinted by cascade
 * @param show whether to start with the volumes of the cascades drawn
 * @param footprint whether the footprint test of {@code CascadeCasters} is applied from the start
 */
record ShadowOptions(int blocks, int props, int cascades, int map, float lambda, float distance, boolean stabilize, boolean verify, boolean tint, boolean show, boolean footprint) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the cascaded-shadows demo:
              --blocks N           buildings along one side of the city (default 40: 1,600 buildings)
              --props N            street props per building (default 30)
              --cascades N         number of cascades, 1 to 8 (default 4)
              --map N              shadow map size in texels (default 2048)
              --lambda L           split spacing, 0 uniform to 1 logarithmic (default 0.8)
              --distance D         shadow distance in metres (default 400)
              --no-stabilize       fit each cascade tightly instead of snapping it to texels (the edges shimmer)
              --no-footprint       start with the footprint test of CascadeCasters off (every box in a cascade's frustum is drawn into its map)
              --tint               start with the scene tinted by cascade
              --show               start with the volumes of the cascades drawn
              --verify             check the casters, the maps and the lookup against brute force
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static ShadowOptions parse(List<String> args) {
        int blocks = 40, props = 30, cascades = 4, map = 2048;
        float lambda = 0.8f, distance = 400f;
        boolean stabilize = true, verify = false, tint = false, show = false, footprint = true;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--blocks" -> blocks = (int) number(args, ++i);
                case "--props" -> props = (int) number(args, ++i);
                case "--cascades" -> cascades = (int) number(args, ++i);
                case "--map" -> map = (int) number(args, ++i);
                case "--lambda" -> lambda = (float) number(args, ++i);
                case "--distance" -> distance = (float) number(args, ++i);
                case "--no-stabilize" -> stabilize = false;
                case "--no-footprint" -> footprint = false;
                case "--tint" -> tint = true;
                case "--show" -> show = true;
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (blocks < 2 || blocks > 200 || props < 0 || cascades < 1 || cascades > 8 || map < 128 || map > 8192 || lambda < 0f || lambda > 1f || !(distance > 5f)) {
            throw new IllegalArgumentException("an option is out of range\n" + USAGE);
        }
        return new ShadowOptions(blocks, props, cascades, map, lambda, distance, stabilize, verify, tint, show, footprint);
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
