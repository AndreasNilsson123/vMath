package vmath.samples.framework;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL45.GL_BACK;
import static org.lwjgl.opengl.GL45.GL_BLEND;
import static org.lwjgl.opengl.GL45.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_RENDERER;
import static org.lwjgl.opengl.GL45.GL_VERSION;
import static org.lwjgl.opengl.GL45.glClear;
import static org.lwjgl.opengl.GL45.glClearColor;
import static org.lwjgl.opengl.GL45.glCullFace;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glGetString;
import static org.lwjgl.opengl.GL45.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

/**
 * Runs demos: it owns the window, the OpenGL 4.5 context, the main loop, the input, the timing and
 * the report, and calls a {@link Demo} for the work that is the demo's own.
 *
 * <p>There are three ways to run. <b>Interactive</b>: the window opens with a menu (or in the
 * demo that was asked for), and the keys {@code Tab}, {@code PageUp} and {@code PageDown} move
 * between the menu and the demos, which are disposed and created again on every switch.
 * <b>Scripted</b> ({@code --frames N}): each demo runs N frames with a fixed time step along its
 * fixed path, after the warm-up frames, then prints the averages, writes the screenshot and the
 * markdown report if asked, and the next demo starts. <b>Smoke</b> ({@code --smoke}): every
 * registered demo runs a few scripted frames with its smoke arguments, and the run fails if a demo
 * raises an OpenGL error, allocates more than its budget on the render thread, or draws a blank
 * frame.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that calls {@link #run}, which owns
 * the OpenGL context.
 */
public final class DemoRunner {

    private static final int SMOKE_FRAMES = 30;
    private static final int SMOKE_MINIMUM_COLORS = 16;

    private enum Action { QUIT, MENU, NEXT, PREVIOUS, DONE }

    private record Outcome(Action action, Stats stats, double allocated, BufferedImage image) {
    }

    private final RunOptions options;
    private final List<DemoEntry> entries;
    private long window;
    private Input input;
    private Hud hud;
    private GpuTimer gpu;
    private DebugRenderer debug;
    private FrameInfo frameInfo;
    private boolean vsync;
    private int screenshots;

    private DemoRunner(RunOptions options, List<DemoEntry> entries) {
        this.options = options;
        this.entries = entries;
        this.vsync = options.vsync();
    }

    /**
     * Runs the demos as the options say.
     *
     * @param options the command line; must not be {@code null}
     * @param entries the registered demos; must not be {@code null} or empty
     * @return the exit code: 0 for success, 1 if a smoke check failed
     * @throws IllegalArgumentException if a demo id is unknown or a demo rejects its arguments
     * @throws IllegalStateException if the window or the context cannot be created
     * @throws IOException if a screenshot or the report cannot be written
     */
    public static int run(RunOptions options, List<DemoEntry> entries) throws IOException {
        for (String id : options.demos()) {
            find(entries, id);
        }
        return new DemoRunner(options, entries).run();
    }

    private static int find(List<DemoEntry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).info().id().equals(id)) {
                return i;
            }
        }
        List<String> ids = new ArrayList<>();
        entries.forEach(e -> ids.add(e.info().id()));
        throw new IllegalArgumentException("unknown demo '" + id + "'; the demos are " + String.join(", ", ids));
    }

    private int run() throws IOException {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("cannot initialise GLFW");
        }
        try {
            createWindow();
            System.out.println("OpenGL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER));
            input = new Input();
            input.attach(window);
            frameInfo = new FrameInfo(input);
            hud = new Hud();
            gpu = new GpuTimer();
            debug = new DebugRenderer();
            try {
                if (options.smoke()) {
                    return smoke();
                }
                if (options.frames() > 0) {
                    scripted();
                    return 0;
                }
                interactive();
                return 0;
            } finally {
                debug.dispose();
                gpu.dispose();
                hud.dispose();
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private void createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(options.width(), options.height(), "vmath demos", NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("cannot create an OpenGL 4.5 window; the demos need a driver with OpenGL 4.5");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();
        glfwShowWindow(window);
    }

    private int smoke() {
        int failures = 0;
        for (DemoEntry entry : entries) {
            DemoInfo info = entry.info();
            String verdict;
            try {
                Outcome o = play(entry, info.smokeArgs(), true, SMOKE_FRAMES, options.warmup(), true);
                if (o.action() == Action.QUIT) {
                    verdict = "FAIL the window was closed";
                } else if (o.allocated() > info.allocationBudget()) {
                    verdict = String.format(Locale.ROOT, "FAIL allocated %,.0f B per frame, budget %,d B", o.allocated(), info.allocationBudget());
                } else if (Screenshot.isBlank(o.image(), SMOKE_MINIMUM_COLORS)) {
                    verdict = "FAIL the last frame is blank";
                } else {
                    verdict = String.format(Locale.ROOT, "PASS allocated %,.0f B per frame (budget %,d B)", o.allocated(), info.allocationBudget());
                }
            } catch (RuntimeException e) {
                verdict = "FAIL " + e.getMessage();
            }
            if (verdict.startsWith("FAIL")) {
                failures++;
            }
            System.out.printf("%-20s %s%n", info.id(), verdict);
        }
        System.out.println(failures == 0 ? "smoke: all " + entries.size() + " demos passed" : "smoke: " + failures + " of " + entries.size() + " demos failed");
        return failures == 0 ? 0 : 1;
    }

    private void scripted() throws IOException {
        boolean several = options.demos().size() > 1;
        for (String id : options.demos()) {
            DemoEntry entry = entries.get(find(entries, id));
            Outcome o = play(entry, options.demoArgs(), true, options.frames(), options.warmup(), options.screenshot() != null);
            if (o.action() == Action.QUIT) {
                return;
            }
            String heading = String.format(Locale.ROOT, "%s: %d measured frames after %d warm-up frames, window %d x %d, vsync %s%s", id, options.frames(), options.warmup(),
                    options.width(), options.height(), vsync ? "on" : "off", options.demoArgs().isEmpty() ? "" : ", options " + String.join(" ", options.demoArgs()));
            System.out.printf("%n%s%n%s", heading, Report.text(o.stats()));
            if (o.image() != null && options.screenshot() != null) {
                String file = several ? withId(options.screenshot(), id) : options.screenshot();
                Screenshot.write(o.image(), file);
                System.out.println("screenshot: " + file);
            }
            if (options.report() != null) {
                Files.writeString(Path.of(options.report()), Report.markdown(heading, o.stats()), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
    }

    private static String withId(String file, String id) {
        int dot = file.lastIndexOf('.');
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return dot > slash ? file.substring(0, dot) + "-" + id + file.substring(dot) : file + "-" + id;
    }

    private void interactive() throws IOException {
        int index = options.demos().isEmpty() ? -1 : find(entries, options.demos().get(0));
        List<String> args = options.demoArgs();
        while (true) {
            if (index < 0) {
                index = menu();
                if (index < 0) {
                    return;
                }
                args = List.of();
            }
            Outcome o = play(entries.get(index), args, false, 0, 0, false);
            args = List.of();
            switch (o.action()) {
                case QUIT -> {
                    return;
                }
                case MENU -> index = -1;
                case NEXT -> index = (index + 1) % entries.size();
                case PREVIOUS -> index = (index + entries.size() - 1) % entries.size();
                default -> throw new IllegalStateException("unexpected " + o.action());
            }
        }
    }

    /**
     * Shows the menu until a demo is chosen or the window is closed.
     *
     * @return the index of the chosen demo, or -1 to quit
     */
    private int menu() {
        glfwSetWindowTitle(window, "vmath demos");
        int selected = 0;
        int[] w = new int[1], h = new int[1];
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            input.poll(window);
            glfwGetFramebufferSize(window, w, h);
            int width = Math.max(1, w[0]), height = Math.max(1, h[0]);
            if (input.pressed(GLFW_KEY_ESCAPE)) {
                return -1;
            }
            if (input.pressed(GLFW_KEY_DOWN)) {
                selected = (selected + 1) % entries.size();
            }
            if (input.pressed(GLFW_KEY_UP)) {
                selected = (selected + entries.size() - 1) % entries.size();
            }
            if (input.pressed(GLFW_KEY_ENTER)) {
                return selected;
            }
            glViewport(0, 0, width, height);
            glClearColor(0.07f, 0.08f, 0.1f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            hud.begin(width, height);
            hud.color(0.6f, 0.85f, 1f).line("vmath demos: Up and Down to choose, Enter to start, Escape to quit");
            hud.gap(8f);
            for (int i = 0; i < entries.size(); i++) {
                DemoInfo info = entries.get(i).info();
                if (i == selected) {
                    hud.color(1f, 0.9f, 0.4f);
                } else {
                    hud.color(0.85f, 0.85f, 0.85f);
                }
                hud.line(String.format("%s %-18s %s", i == selected ? ">" : " ", info.id(), info.title()));
            }
            DemoInfo chosen = entries.get(selected).info();
            hud.gap(10f).color(0.7f, 0.9f, 0.7f).line(chosen.claim());
            hud.color(0.7f, 0.7f, 0.7f).line("controls: " + chosen.controls());
            hud.end();
            glfwSwapBuffers(window);
        }
        return -1;
    }

    /**
     * Runs one demo: creates it, runs frames until the limit or until the user leaves, and
     * disposes it.
     *
     * @param entry the demo; must not be {@code null}
     * @param args the demo's own arguments; must not be {@code null}
     * @param benchmark whether the run is scripted (fixed step, fixed path, no keys)
     * @param frames the number of measured frames of a scripted run
     * @param warmup the number of frames before the measuring starts
     * @param captureLast whether to read the last frame of a scripted run back
     * @return what happened, with the statistics and the captured frame
     */
    private Outcome play(DemoEntry entry, List<String> args, boolean benchmark, int frames, int warmup, boolean captureLast) {
        DemoInfo info = entry.info();
        Demo demo = entry.factory().apply(args);
        Stats stats = new Stats();
        int total = benchmark ? warmup + frames : Integer.MAX_VALUE;
        Action action = Action.DONE;
        BufferedImage image = null;
        int allocatedSeries;
        try (Arena arena = Arena.ofConfined()) {
            DemoContext ctx = new DemoContext(arena, stats, gpu, debug, args, info);
            System.out.println("starting " + info.id() + " ...");
            demo.create(ctx);
            int frameSeries = stats.series("frame", "ms", 3, "wall time of a frame, including the swap");
            allocatedSeries = stats.series("allocated", "B", 0, "render-thread allocation in update and render, per frame");
            int gpuSeries = stats.series("gpu", "ms", 3, "GL_TIME_ELAPSED of the commands the demo timed");
            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().threadId();
            boolean hudVisible = options.hud();
            double start = glfwGetTime(), last = start, fpsTime = start;
            int fpsFrames = 0;
            String fpsText = "";
            int[] w = new int[1], h = new int[1];
            long wallStart = System.nanoTime();
            glfwSetWindowTitle(window, "vmath demos: " + info.title());
            try {
                int frame = 0;
                while (frame < total) {
                    long frameStart = System.nanoTime();
                    glfwPollEvents();
                    if (glfwWindowShouldClose(window)) {
                        action = Action.QUIT;
                        break;
                    }
                    input.poll(window);
                    double now = glfwGetTime();
                    float dt = benchmark ? FrameInfo.SCRIPTED_DT : (float) Math.min(0.1, now - last);
                    double time = benchmark ? frame * (double) FrameInfo.SCRIPTED_DT : now - start;
                    last = now;
                    glfwGetFramebufferSize(window, w, h);
                    int width = Math.max(1, w[0]), height = Math.max(1, h[0]);
                    boolean shot = false;
                    if (!benchmark) {
                        if (input.pressed(GLFW_KEY_ESCAPE)) {
                            action = Action.QUIT;
                            break;
                        } else if (input.pressed(GLFW_KEY_TAB)) {
                            action = Action.MENU;
                            break;
                        } else if (input.pressed(GLFW_KEY_PAGE_DOWN)) {
                            action = Action.NEXT;
                            break;
                        } else if (input.pressed(GLFW_KEY_PAGE_UP)) {
                            action = Action.PREVIOUS;
                            break;
                        }
                        if (input.pressed(GLFW_KEY_V)) {
                            vsync = !vsync;
                            glfwSwapInterval(vsync ? 1 : 0);
                        }
                        if (input.pressed(GLFW_KEY_F1)) {
                            hudVisible = !hudVisible;
                        }
                        shot = input.pressed(GLFW_KEY_P);
                    }
                    stats.setMeasuring(!benchmark || frame >= warmup);
                    frameInfo.set(frame, dt, time, width, height, benchmark);

                    long a0 = mx.getThreadAllocatedBytes(tid);
                    demo.update(frameInfo);
                    glViewport(0, 0, width, height);
                    glClearColor(ctx.clearRed(), ctx.clearGreen(), ctx.clearBlue(), 1f);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    glEnable(GL_DEPTH_TEST);
                    glEnable(GL_CULL_FACE);
                    glCullFace(GL_BACK);
                    glDisable(GL_BLEND);
                    demo.render(frameInfo);
                    long a1 = mx.getThreadAllocatedBytes(tid);
                    long gpuNs = gpu.poll();
                    if (gpuNs >= 0) {
                        stats.recordNanos(gpuSeries, gpuNs);
                    }

                    fpsFrames++;
                    if (now - fpsTime >= 0.5) {
                        double fps = fpsFrames / (now - fpsTime);
                        fpsText = String.format(Locale.ROOT, "%.0f fps, %.2f ms", fps, 1000.0 / fps);
                        glfwSetWindowTitle(window, String.format(Locale.ROOT, "vmath demos: %s | %s", info.title(), fpsText));
                        fpsTime = now;
                        fpsFrames = 0;
                    }
                    if (hudVisible) {
                        hud.begin(width, height);
                        hud.color(1f, 1f, 1f).line(info.title() + "   " + (benchmark ? "scripted run, frame " + frame + " of " + total : fpsText));
                        hud.color(0.8f, 0.9f, 1f);
                        demo.hud(hud, frameInfo);
                        hud.color(0.75f, 0.75f, 0.75f);
                        hud.text(8f, height - 2 * hud.lineHeight() - 14f, info.controls());
                        hud.text(8f, height - hud.lineHeight() - 8f, "Tab menu | PageUp PageDown demo | V vsync | P screenshot | F1 text | Esc quit");
                        hud.end();
                    }
                    if (options.checkGl()) {
                        Gl.check(info.id() + ", frame " + frame);
                    }
                    if (captureLast && frame == total - 1) {
                        image = Screenshot.capture(width, height);
                    }
                    if (shot) {
                        saveScreenshot(info.id(), width, height);
                    }
                    glfwSwapBuffers(window);
                    stats.recordNanos(frameSeries, System.nanoTime() - frameStart);
                    stats.record(allocatedSeries, a1 - a0);
                    frame++;
                }
                if (benchmark && action == Action.DONE) {
                    demo.report(stats);
                    stats.note(String.format(Locale.ROOT, "wall time %.1f s", (System.nanoTime() - wallStart) / 1e9));
                }
            } finally {
                demo.dispose();
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new Outcome(action, stats, stats.average(allocatedSeries), image);
    }

    private void saveScreenshot(String id, int width, int height) throws IOException {
        String file = String.format("vmath-%s-%d.png", id, ++screenshots);
        Screenshot.write(Screenshot.capture(width, height), file);
        System.out.println("screenshot: " + file);
    }
}
