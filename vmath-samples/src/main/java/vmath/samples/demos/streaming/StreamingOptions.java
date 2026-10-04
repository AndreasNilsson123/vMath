package vmath.samples.demos.streaming;

import java.util.List;

/**
 * The options of the {@link StreamingDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param world the number of cells along a side of the world
 * @param radius the radius of the loaded neighbourhood in cells
 * @param poolMb the size of the pool in megabytes
 * @param pool the allocator of the pool
 * @param regionMb the size of one region of the staging ring in megabytes
 * @param budgetMb the most that is uploaded in one frame, in megabytes
 * @param framesInFlight the number of regions of the ring
 * @param particles the number of points written into the ring every frame
 * @param gpuLoad the cost of the filler pass on the GPU, in thousands of loop iterations per pixel
 * @param speed the speed of the camera in cells per second
 * @param teleport the seconds between teleports, or 0 for none
 * @param verify whether to read the pool back and compare it with the chunks
 */
record StreamingOptions(int world, int radius, int poolMb, StreamPool.Kind pool, int regionMb, int budgetMb, int framesInFlight, int particles, int gpuLoad, double speed, double teleport,
                        boolean verify) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the streaming-ring demo:
              --world N            cells along a side of the world (default 160)
              --radius N           radius of the loaded neighbourhood in cells (default 14)
              --pool-mb N          size of the chunk pool in megabytes (default 30)
              --pool KIND          first-fit, best-fit or slab (default first-fit)
              --region-mb N        size of one region of the staging ring (default 16)
              --budget-mb N        most uploaded per frame (default 8)
              --frames-in-flight N regions of the ring, 1 to 4 (default 3)
              --particles N        points written into the ring every frame (default 200000)
              --gpu-load N         filler pass on the GPU, thousands of iterations per pixel, 0 for none (default 2)
              --speed S            camera speed in cells per second (default 25)
              --teleport SECONDS   seconds between jumps to a new place, 0 for none (default 3)
              --verify             read the pool back and compare it with the chunks
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static StreamingOptions parse(List<String> args) {
        int world = 160, radius = 14, poolMb = 30, regionMb = 16, budgetMb = 8, framesInFlight = 3, particles = 200_000, gpuLoad = 2;
        double speed = 25.0, teleport = 3.0;
        StreamPool.Kind pool = StreamPool.Kind.FIRST_FIT;
        boolean verify = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--world" -> world = (int) number(args, ++i);
                case "--radius" -> radius = (int) number(args, ++i);
                case "--pool-mb" -> poolMb = (int) number(args, ++i);
                case "--pool" -> {
                    if (++i >= args.size()) {
                        throw new IllegalArgumentException("--pool needs a kind\n" + USAGE);
                    }
                    pool = switch (args.get(i)) {
                        case "first-fit" -> StreamPool.Kind.FIRST_FIT;
                        case "best-fit" -> StreamPool.Kind.BEST_FIT;
                        case "slab" -> StreamPool.Kind.SLAB;
                        default -> throw new IllegalArgumentException("unknown pool " + args.get(i) + "\n" + USAGE);
                    };
                }
                case "--region-mb" -> regionMb = (int) number(args, ++i);
                case "--budget-mb" -> budgetMb = (int) number(args, ++i);
                case "--frames-in-flight" -> framesInFlight = (int) number(args, ++i);
                case "--particles" -> particles = (int) number(args, ++i);
                case "--gpu-load" -> gpuLoad = (int) number(args, ++i);
                case "--speed" -> speed = number(args, ++i);
                case "--teleport" -> teleport = number(args, ++i);
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (radius < 2 || radius > 40 || world < 2 * radius + 4 || world > 1024 || poolMb < 2 || poolMb > 1024 || regionMb < 1 || regionMb > 256 || budgetMb < 1 || budgetMb > regionMb || framesInFlight < 1
                || framesInFlight > 4 || particles < 0 || particles > 2_000_000 || gpuLoad < 0 || gpuLoad > 1000 || !(speed >= 0.0) || !(teleport >= 0.0)) {
            throw new IllegalArgumentException("an option is out of range (the budget must not exceed the region)\n" + USAGE);
        }
        return new StreamingOptions(world, radius, poolMb, pool, regionMb, budgetMb, framesInFlight, particles, gpuLoad, speed, teleport, verify);
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
