package vmath.samples.demos.culling;

import java.util.List;

/**
 * The options of the {@link CullingLabDemo}, parsed from the arguments that the launcher passes
 * on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param instances the number of boxes in the city
 * @param method a part of the name of the method to use ({@code scalar}, {@code simd},
 *     {@code parallel}, {@code bvh}, {@code tree}, {@code octree}, {@code grid}), or {@code null}
 *     for the first (interactive) or for all in turn (scripted)
 * @param threads the number of threads of the parallel kernel
 * @param segment the number of frames that a scripted run spends on each method before it goes
 *     on to the next
 * @param verify whether every frame's result is compared with the scalar kernel's, which fails the
 *     run if a method misses a box that the scalar kernel finds
 * @param animate whether a fiftieth of the boxes move every frame, which is what the structures'
 *     update cost is measured with
 * @param showBvh whether to start with the boxes of one level of the BVH drawn
 */
record CullingLabOptions(int instances, String method, int threads, int segment, boolean verify, boolean animate, boolean showBvh) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the culling-lab demo:
              --instances N        boxes in the city (default 250000)
              --method NAME        use the method whose name contains NAME (scalar, simd, parallel, bvh, tree, octree, grid); a scripted run then stays on it
              --threads N          threads of the parallel kernel (default 4)
              --segment N          frames a scripted run spends on each method (default 120)
              --verify             compare every result with the scalar kernel and fail on a missed box
              --animate            move a fiftieth of the boxes every frame
              --show-bvh           start with the boxes of one BVH level drawn
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or not positive; the message includes the usage text
     */
    static CullingLabOptions parse(List<String> args) {
        int instances = 250_000, threads = 4, segment = 120;
        String method = null;
        boolean verify = false, animate = false, showBvh = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--instances" -> instances = intValue(args, ++i);
                case "--threads" -> threads = intValue(args, ++i);
                case "--segment" -> segment = intValue(args, ++i);
                case "--method" -> {
                    if (++i >= args.size()) {
                        throw new IllegalArgumentException("--method needs a name\n" + USAGE);
                    }
                    method = args.get(i);
                }
                case "--verify" -> verify = true;
                case "--animate" -> animate = true;
                case "--show-bvh" -> showBvh = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (instances < 1 || threads < 1 || segment < 1) {
            throw new IllegalArgumentException("the counts must be positive\n" + USAGE);
        }
        return new CullingLabOptions(instances, method, threads, segment, verify, animate, showBvh);
    }

    private static int intValue(List<String> args, int i) {
        if (i >= args.size()) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number\n" + USAGE);
        }
        try {
            return Integer.parseInt(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number, not " + args.get(i) + "\n" + USAGE);
        }
    }
}
