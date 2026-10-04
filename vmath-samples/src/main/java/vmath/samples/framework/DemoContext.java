package vmath.samples.framework;

import java.lang.foreign.Arena;
import java.util.List;

/**
 * The services that the runner gives a demo: memory, statistics, the GPU timer, the debug line
 * renderer, the demo's own command-line arguments and the clear colour.
 *
 * <p>One context lives from {@link Demo#create} to {@link Demo#dispose}. Everything a demo
 * allocates from the arena is released when the demo is disposed.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class DemoContext {

    private final Arena arena;
    private final Stats stats;
    private final GpuTimer gpu;
    private final DebugRenderer debug;
    private final List<String> args;
    private final DemoInfo info;
    private float clearRed = 0.55f;
    private float clearGreen = 0.7f;
    private float clearBlue = 0.88f;

    /**
     * Creates the context of one demo run.
     *
     * @param arena the memory of the demo, closed after it is disposed; must not be {@code null}
     * @param stats the statistics of the run; must not be {@code null}
     * @param gpu the GPU timer; must not be {@code null}
     * @param debug the debug line renderer; must not be {@code null}
     * @param args the demo's own arguments; must not be {@code null}
     * @param info the description of the demo; must not be {@code null}
     */
    DemoContext(Arena arena, Stats stats, GpuTimer gpu, DebugRenderer debug, List<String> args, DemoInfo info) {
        this.arena = arena;
        this.stats = stats;
        this.gpu = gpu;
        this.debug = debug;
        this.args = List.copyOf(args);
        this.info = info;
    }

    /**
     * Reads the arena that owns the native memory of the demo.
     *
     * @return the arena; closed by the runner after {@link Demo#dispose}
     */
    public Arena arena() {
        return arena;
    }

    /**
     * Reads the statistics of the run, where the demo registers and records its series.
     *
     * @return the statistics; never {@code null}
     */
    public Stats stats() {
        return stats;
    }

    /**
     * Reads the GPU timer; the runner polls it after every frame and records the result in the
     * series named {@code gpu}.
     *
     * @return the timer; never {@code null}
     */
    public GpuTimer gpu() {
        return gpu;
    }

    /**
     * Reads the debug line renderer, shared by all demos.
     *
     * @return the renderer; never {@code null}
     */
    public DebugRenderer debug() {
        return debug;
    }

    /**
     * Reads the arguments that the demo's own options come from: everything on the command line
     * that the runner does not know.
     *
     * @return the arguments; unmodifiable and never {@code null}
     */
    public List<String> args() {
        return args;
    }

    /**
     * Reads the description of the running demo.
     *
     * @return the description; never {@code null}
     */
    public DemoInfo info() {
        return info;
    }

    /**
     * Sets the colour that the framebuffer is cleared to at the start of every frame.
     *
     * @param red the red component in 0 to 1
     * @param green the green component in 0 to 1
     * @param blue the blue component in 0 to 1
     */
    public void clearColor(float red, float green, float blue) {
        clearRed = red;
        clearGreen = green;
        clearBlue = blue;
    }

    float clearRed() {
        return clearRed;
    }

    float clearGreen() {
        return clearGreen;
    }

    float clearBlue() {
        return clearBlue;
    }
}
