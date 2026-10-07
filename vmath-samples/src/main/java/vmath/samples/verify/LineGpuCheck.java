package vmath.samples.verify;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL46.GL_EXTENSIONS;
import static org.lwjgl.opengl.GL46.GL_MAJOR_VERSION;
import static org.lwjgl.opengl.GL46.GL_MINOR_VERSION;
import static org.lwjgl.opengl.GL46.GL_NUM_EXTENSIONS;
import static org.lwjgl.opengl.GL46.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL46.GL_RENDERER;
import static org.lwjgl.opengl.GL46.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL46.GL_VERSION;
import static org.lwjgl.opengl.GL46.glBeginQuery;
import static org.lwjgl.opengl.GL46.glEndQuery;
import static org.lwjgl.opengl.GL46.glFinish;
import static org.lwjgl.opengl.GL46.glGenQueries;
import static org.lwjgl.opengl.GL46.glGetInteger;
import static org.lwjgl.opengl.GL46.glGetQueryObjecti64;
import static org.lwjgl.opengl.GL46.glGetString;
import static org.lwjgl.opengl.GL46.glGetStringi;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;
import vmath.lines.CoverageRaster;
import vmath.lines.LineBatch;
import vmath.lines.LineExpander;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineSet;
import vmath.lines.LineStrategy;
import vmath.lines.LineStyle;

/**
 * Runs the shaders of every line strategy on the OpenGL driver of the machine and compares what
 * they draw with the reference of {@code vmath.lines}: the check that the GLSL, which nothing
 * else in the build compiles, does what {@code LineGeometry} defines.
 *
 * <p>For every context version from 3.3 to 4.6 (as the capabilities of that version, so the strategy
 * and the shader text are those a driver of that version would get) and every strategy it allows,
 * the program is compiled, linked and run on four scenes (a grid of cells with a colour each, a
 * random overlapping scene, a perspective scene with widths in world units, and a scene at four
 * million units from the origin), and the pixels are compared with the coverage that the reference
 * expansion computes. {@code --bench} also measures what each strategy costs.
 *
 * <p>Needs a display and an OpenGL 4.6 driver (the newer context runs the older GLSL versions);
 * {@code ./gradlew -Psamples :vmath-samples:lineCheck}. Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class LineGpuCheck {

    private static final int SIZE = 200;

    private LineGpuCheck() {
    }

    /** A scene to draw: the lines, the matrix, and what to check. */
    private record Scene(String name, LineBatch batch, float[] viewProjection, float worldToPixel, int[] cellColors, boolean unionOnly) {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    // ---------------------------------------------------------------- scenes

    private static final LineStyle[] STYLES = {
            LineStyle.pixels(3f).withColor(0xFF0000FF),
            LineStyle.pixels(9f).withColor(0x00FF00FF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND),
            LineStyle.pixels(5f).withColor(0x0000FFFF).withCap(LineStyle.Cap.SQUARE).withJoin(LineStyle.Join.BEVEL).withDash(12f, 7f),
            LineStyle.pixels(6f).withColor(0xFFFF00FF).withMiterLimit(2f).withDash(20f, 5f, 3f, 5f),
            LineStyle.pixels(2f).withColor(0xFF00FFFF).withLayer(-1),
            LineStyle.pixels(7f).withColor(0x00FFFFFF).withLayer(1).withJoin(LineStyle.Join.ROUND)};

    private static float[] orthographic() {
        float w = SIZE;
        return new float[] {2f / w, 0, 0, 0, 0, 2f / w, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
    }

    private static Scene gridScene(Random rnd) {
        LineBatch b = new LineBatch();
        int[] colors = new int[25];
        for (int cell = 0; cell < 25; cell++) {
            int cx = cell % 5, cy = cell / 5;
            LineStyle style = STYLES[cell % STYLES.length];
            int n = 2 + rnd.nextInt(4);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < n; k++) {
                xyz[3 * k] = cx * 40 + 12 + rnd.nextInt(17);
                xyz[3 * k + 1] = cy * 40 + 12 + rnd.nextInt(17);
            }
            try {
                b.addPolyline(xyz, 0, n, n >= 3 && cell % 4 == 0, style);
                colors[cell] = style.color();
            } catch (IllegalArgumentException repeated) {
                colors[cell] = 0;
            }
        }
        return new Scene("grid", b, orthographic(), 1f, colors, false);
    }

    private static Scene randomScene(Random rnd) {
        LineBatch b = new LineBatch();
        for (int i = 0; i < 40; i++) {
            int n = 2 + rnd.nextInt(5);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < n; k++) {
                xyz[3 * k] = 20 + rnd.nextInt(160);
                xyz[3 * k + 1] = 20 + rnd.nextInt(160);
            }
            try {
                b.addPolyline(xyz, 0, n, n >= 3 && rnd.nextInt(3) == 0, STYLES[rnd.nextInt(STYLES.length)]);
            } catch (IllegalArgumentException repeated) {
                // too few distinct points
            }
        }
        return new Scene("random", b, orthographic(), 1f, null, true);
    }

    private static Scene perspectiveScene(Random rnd) {
        LineBatch b = new LineBatch();
        LineStyle thin = LineStyle.pixels(3f).withColor(0xFF0000FF), world = LineStyle.world(0.4f).withColor(0x00FF00FF).withCap(LineStyle.Cap.ROUND).withDash(1f, 0.5f);
        for (int i = 0; i < 25; i++) {
            int n = 2 + rnd.nextInt(4);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < n; k++) {
                double z = -(6 + rnd.nextInt(30));
                xyz[3 * k] = (rnd.nextDouble() - 0.5) * 0.9 * -z;
                xyz[3 * k + 1] = (rnd.nextDouble() - 0.5) * 0.9 * -z;
                xyz[3 * k + 2] = z;
            }
            b.addPolyline(xyz, 0, n, n >= 3 && i % 3 == 0, i % 2 == 0 ? thin : world);
        }
        float f = (float) (1.0 / Math.tan(Math.toRadians(30))), near = 0.1f, far = 100f;
        float[] vp = {f, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) / (near - far), -1f, 0, 0, 2f * far * near / (near - far), 0f};
        return new Scene("perspective", b, vp, 0.5f * SIZE * vp[5], null, true);
    }

    private static Scene farScene(Random rnd) {
        double shift = 4_000_000.0;
        LineBatch b = new LineBatch();
        for (int i = 0; i < 12; i++) {
            int n = 3 + rnd.nextInt(3);
            double[] xyz = new double[3 * n];
            for (int k = 0; k < n; k++) {
                xyz[3 * k] = shift + 20 + rnd.nextInt(160);
                xyz[3 * k + 1] = shift + 20 + rnd.nextInt(160);
            }
            try {
                b.addPolyline(xyz, 0, n, false, STYLES[i % STYLES.length]);
            } catch (IllegalArgumentException repeated) {
                // too few distinct points
            }
        }
        b.setOrigin(shift, shift, 0.0);
        double w = SIZE;
        double[] world = {2 / w, 0, 0, 0, 0, 2 / w, 0, 0, 0, 0, 1, 0, -1 - 2 * shift / w, -1 - 2 * shift / w, 0, 1};
        float[] relative = new float[16];
        b.relativeViewProjection(world, relative);
        return new Scene("far origin", b, relative, 1f, null, true);
    }

    // ---------------------------------------------------------------- comparison

    private static CoverageRaster reference(Scene s) {
        CoverageRaster r = new CoverageRaster(SIZE, SIZE);
        LineExpander.expand(s.batch(), s.viewProjection(), SIZE, SIZE, s.worldToPixel(), (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> r.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        return r;
    }

    /** Compares the pixels of a triangle strategy with the coverage: returns null when they agree, else what is wrong. */
    private static String compareTriangles(Scene s, CoverageRaster expected, byte[] pixels) {
        int missing = 0, extra = 0, wrongColor = 0, covered = 0, filled = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int o = (y * SIZE + x) * 4;
                boolean on = (pixels[o + 3] & 255) != 0;
                float c = expected.coverage(x, y);
                covered += c > 0 ? 1 : 0;
                filled += on ? 1 : 0;
                if (on && c < 0.05f) {
                    extra++;
                } else if (!on && c > 0.95f) {
                    missing++;
                }
                if (!s.unionOnly() && on && c == 1f) {
                    int cell = (y / 40) * 5 + x / 40;
                    int rgba = (pixels[o] & 255) << 24 | (pixels[o + 1] & 255) << 16 | (pixels[o + 2] & 255) << 8 | pixels[o + 3] & 255;
                    if (s.cellColors()[cell] != 0 && rgba != s.cellColors()[cell]) {
                        wrongColor++;
                    }
                }
            }
        }
        if (covered < 100 || filled < 100) {
            return "the scene draws too little: reference " + covered + " pixels, GPU " + filled;
        }
        int tolerance = Math.max(2, covered / 500);   // 0.2 % of the pixels: edges where the rasterisation rules and the four-by-four samples disagree
        if (missing + extra > tolerance || wrongColor > 0) {
            return missing + " fully covered pixels missing, " + extra + " pixels drawn outside the reference, " + wrongColor + " of the wrong colour (reference " + covered + " pixels, GPU " + filled + ")";
        }
        return null;
    }

    /**
     * Draws a batch with a plan on the GPU into a square target and counts how it differs from the
     * reference expansion: the check the line demos show next to their numbers.
     *
     * @param plan the plan (not the hairline strategy)
     * @param caps the capabilities the plan was made with
     * @param batch the lines
     * @param viewProjection the matrix relative to the origin of the batch
     * @param worldToPixel pixels per world unit at {@code w = 1}
     * @param size the width and height of the target in pixels
     * @return {@code {covered, missing, extra}}: the pixels the reference covers, the pixels it covers
     *     fully that the GPU left empty, and the pixels the GPU drew where the reference has almost no
     *     coverage; both counts are 0 or a few edge pixels when the shader does what the reference does
     */
    public static int[] probe(LineRenderPlan plan, GraphicsCapabilities caps, LineBatch batch, float[] viewProjection, float worldToPixel, int size) {
        CoverageRaster expected = new CoverageRaster(size, size);
        LineExpander.expand(batch, viewProjection, size, size, worldToPixel, (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> expected.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
        byte[] pixels;
        try (LineGpuRunner runner = new LineGpuRunner(size, size); Arena arena = Arena.ofConfined()) {
            long dataBytes = plan.dataBytes(batch), styleBytes = plan.styleBytes(batch);
            MemorySegment data = allocate(arena, dataBytes), styles = allocate(arena, styleBytes);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 16);
            plan.write(batch, data, styles, draws);
            try (LineGpuRunner.Prepared prepared = runner.new Prepared(plan, caps, data, dataBytes, styles, styleBytes, draws)) {
                prepared.draw(viewProjection, worldToPixel);
                pixels = prepared.read();
            }
        }
        int covered = 0, missing = 0, extra = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean on = (pixels[(y * size + x) * 4 + 3] & 255) != 0;
                float c = expected.coverage(x, y);
                covered += c > 0 ? 1 : 0;
                if (on && c < 0.05f) {
                    extra++;
                } else if (!on && c > 0.95f) {
                    missing++;
                }
            }
        }
        return new int[] {covered, missing, extra};
    }

    /** Hairlines: every drawn pixel is near a segment and every segment has a drawn pixel near its middle. */
    private static String compareHairlines(Scene s, byte[] pixels) {
        LineBatch b = s.batch();
        List<double[]> segments = new ArrayList<>();
        float[] vp = s.viewProjection();
        float[] p = new float[3];
        for (int poly = 0; poly < b.polylineCount(); poly++) {
            int n = b.pointCount(poly);
            int count = b.isClosed(poly) ? n : n - 1;
            for (int i = 0; i < count; i++) {
                double[] a = project(b, poly, i, vp, p), c = project(b, poly, (i + 1) % n, vp, p);
                if (a != null && c != null) {
                    segments.add(new double[] {a[0], a[1], c[0], c[1]});
                }
            }
        }
        int far = 0, lost = 0, filled = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((pixels[(y * SIZE + x) * 4 + 3] & 255) == 0) {
                    continue;
                }
                filled++;
                double best = Double.MAX_VALUE;
                for (double[] sg : segments) {
                    best = Math.min(best, distance(x + 0.5, y + 0.5, sg));
                }
                if (best > 1.0) {
                    far++;
                }
            }
        }
        for (double[] sg : segments) {
            double mx = (sg[0] + sg[2]) / 2, my = (sg[1] + sg[3]) / 2;
            boolean near = false;
            for (int dy = -1; dy <= 1 && !near; dy++) {
                for (int dx = -1; dx <= 1 && !near; dx++) {
                    int px = (int) Math.floor(mx) + dx, py = (int) Math.floor(my) + dy;
                    near = px >= 0 && px < SIZE && py >= 0 && py < SIZE && (pixels[(py * SIZE + px) * 4 + 3] & 255) != 0;
                }
            }
            if (!near) {
                lost++;
            }
        }
        if (filled < 50) {
            return "the hairlines draw too little: " + filled + " pixels";
        }
        return far > 0 || lost > 0 ? far + " pixels farther than a pixel from every segment, " + lost + " segments without a pixel near their middle" : null;
    }

    /** The point on the screen in pixels, or null if it is not in front of the camera. */
    private static double[] project(LineBatch b, int polyline, int point, float[] vp, float[] scratch) {
        b.relativePoint(polyline, point, scratch, 0);
        double x = vp[0] * scratch[0] + vp[4] * scratch[1] + vp[8] * scratch[2] + vp[12];
        double y = vp[1] * scratch[0] + vp[5] * scratch[1] + vp[9] * scratch[2] + vp[13];
        double w = vp[3] * scratch[0] + vp[7] * scratch[1] + vp[11] * scratch[2] + vp[15];
        return w > 0 ? new double[] {(x / w * 0.5 + 0.5) * SIZE, (y / w * 0.5 + 0.5) * SIZE} : null;
    }

    private static double distance(double px, double py, double[] s) {
        double dx = s[2] - s[0], dy = s[3] - s[1];
        double len2 = dx * dx + dy * dy;
        double t = len2 > 0 ? Math.max(0, Math.min(1, ((px - s[0]) * dx + (py - s[1]) * dy) / len2)) : 0;
        double ex = px - (s[0] + t * dx), ey = py - (s[1] + t * dy);
        return Math.sqrt(ex * ex + ey * ey);
    }

    // ---------------------------------------------------------------- the runs

    private static MemorySegment allocate(Arena arena, long bytes) {
        return arena.allocate(Math.max(bytes, 16));
    }

    /** Draws one scene with one plan on the GPU and compares it with the reference; returns null when it agrees. */
    private static String runScene(LineGpuRunner runner, LineRenderPlan plan, GraphicsCapabilities caps, Scene scene, CoverageRaster expected) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = allocate(arena, plan.dataBytes(scene.batch())), styles = allocate(arena, plan.styleBytes(scene.batch()));
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 16);
            plan.write(scene.batch(), data, styles, draws);
            try (LineGpuRunner.Prepared prepared = runner.new Prepared(plan, caps, data, plan.dataBytes(scene.batch()), styles, plan.styleBytes(scene.batch()), draws)) {
                prepared.draw(scene.viewProjection(), scene.worldToPixel());
                byte[] pixels = prepared.read();
                return plan.strategy() == LineStrategy.HAIRLINE ? compareHairlines(scene, pixels) : compareTriangles(scene, expected, pixels);
            }
        }
    }

    private static List<String> driverExtensions() {
        List<String> names = new ArrayList<>();
        int n = glGetInteger(GL_NUM_EXTENSIONS);
        for (int i = 0; i < n; i++) {
            names.add(glGetStringi(GL_EXTENSIONS, i));
        }
        return names;
    }

    /**
     * Runs the matrix of versions, strategies and scenes on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per version, strategy and scene
     */
    public static List<Outcome> checkMatrix(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        int major = glGetInteger(GL_MAJOR_VERSION), minor = glGetInteger(GL_MINOR_VERSION);
        Random rnd = new Random(2024);
        Scene[] scenes = {gridScene(rnd), randomScene(rnd), perspectiveScene(rnd), farScene(rnd)};
        CoverageRaster[] expected = new CoverageRaster[scenes.length];
        for (int i = 0; i < scenes.length; i++) {
            expected[i] = reference(scenes[i]);
        }
        int[][] versions = {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};
        try (LineGpuRunner runner = new LineGpuRunner(SIZE, SIZE)) {
            // the comparison must be able to fail: pixels of one scene against the reference of another are not accepted
            GraphicsCapabilities newest = GraphicsCapabilities.openGl(major, minor, driverExtensions());
            LineRenderPlan any = LineRenderPlan.choose(newest);
            String confused = any.strategy() == LineStrategy.HAIRLINE ? "x" : runScene(runner, any, newest, scenes[0], expected[1]);
            Outcome sensitivity = new Outcome("the comparison rejects the pixels of another scene", confused != null, confused == null ? "it accepted them" : "rejected: " + confused);
            results.add(sensitivity);
            if (out != null) {
                out.println((sensitivity.ok() ? "ok    " : "FAIL  ") + sensitivity.name());
            }
            for (int[] v : versions) {
                if (v[0] > major || v[0] == major && v[1] > minor) {
                    continue;
                }
                // 4.5 gets the extension that gives gl_DrawIDARB there; 4.6 has it in the core
                List<String> extensions = v[1] == 5 ? List.of("GL_ARB_shader_draw_parameters") : List.of();
                for (boolean uniformOnly : new boolean[] {false, true}) {
                    GraphicsCapabilities base = GraphicsCapabilities.openGl(v[0], v[1], extensions);
                    // a context that has neither storage buffers nor texture buffers reads the styles from a uniform block
                    GraphicsCapabilities caps = uniformOnly ? base.without(GraphicsCapabilities.Feature.TEXTURE_BUFFERS, GraphicsCapabilities.Feature.STORAGE_BUFFERS) : base;
                    for (LineStrategy strategy : LineStrategy.values()) {
                        String name = "GL " + v[0] + "." + v[1] + " (GLSL " + caps.glsl().number() + ")" + (uniformOnly ? " styles in a uniform block" : "") + " " + strategy;
                        if (!LineStrategy.chooser().supports(strategy, caps) || uniformOnly && strategy == LineStrategy.HAIRLINE) {
                            continue;
                        }
                        LineRenderPlan plan = LineRenderPlan.force(strategy, caps);
                        for (int i = 0; i < scenes.length; i++) {
                            String problem;
                            try {
                                problem = runScene(runner, plan, caps, scenes[i], expected[i]);
                            } catch (RuntimeException e) {
                                problem = e.getMessage();
                            }
                            Outcome o = new Outcome(name + " / " + scenes[i].name(), problem == null, problem == null ? "same pixels as the reference" : problem);
                            results.add(o);
                            if (out != null) {
                                out.println((o.ok() ? "ok    " : "FAIL  ") + o.name() + (o.ok() ? "" : "\n      " + o.detail()));
                            }
                        }
                    }
                }
            }
        }
        return results;
    }

    // ---------------------------------------------------------------- measurements

    private static Scene network(int polylines, int points, int w, int h) {
        Random rnd = new Random(5);
        LineBatch b = new LineBatch();
        LineStyle[] styles = new LineStyle[8];
        for (int i = 0; i < styles.length; i++) {
            styles[i] = LineStyle.pixels(1f + i * 0.7f).withColor(0x40 + i * 20 << 24 | 0x80 << 16 | (i * 30) << 8 | 0xFF).withCap(i % 2 == 0 ? LineStyle.Cap.BUTT : LineStyle.Cap.ROUND)
                    .withJoin(i % 3 == 0 ? LineStyle.Join.MITER : LineStyle.Join.ROUND);
        }
        double[] xyz = new double[3 * points];
        for (int p = 0; p < polylines; p++) {
            double x = rnd.nextDouble() * w, y = rnd.nextDouble() * h, heading = rnd.nextDouble() * Math.PI * 2;
            for (int k = 0; k < points; k++) {
                heading += (rnd.nextDouble() - 0.5) * 0.6;
                x += Math.cos(heading) * (3 + rnd.nextDouble() * 4);
                y += Math.sin(heading) * (3 + rnd.nextDouble() * 4);
                xyz[3 * k] = x;
                xyz[3 * k + 1] = y;
                xyz[3 * k + 2] = 0;
            }
            b.addPolyline(xyz, 0, points, false, styles[(p / 7) % styles.length]);   // runs of seven of the same style
        }
        float[] vp = {2f / w, 0, 0, 0, 0, 2f / h, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
        return new Scene("network", b, vp, 1f, null, true);
    }

    /**
     * Measures what each strategy costs for a network of polylines on this driver.
     *
     * @param out where to print the table
     * @param polylines the number of polylines
     * @param points the points of each
     */
    public static void bench(PrintStream out, int polylines, int points) {
        int w = 1920, h = 1080;
        Scene scene = network(polylines, points, w, h);
        int major = glGetInteger(GL_MAJOR_VERSION), minor = glGetInteger(GL_MINOR_VERSION);
        GraphicsCapabilities caps = GraphicsCapabilities.openGl(major, minor, driverExtensions());
        out.printf(Locale.ROOT, "%d polylines of %d points: %d segments, %d distinct styles, %dx%d%n", scene.batch().polylineCount(), points, scene.batch().segmentCount(), scene.batch().styleCount(), w, h);
        out.println("| Context | Strategy | Submission | Draws | Calls | Data (MB) | Styles (KB) | Write on the CPU (ms) | GPU time per frame (ms) |");
        out.println("|---|---|---|---|---|---|---|---|---|");
        GraphicsCapabilities gl33 = GraphicsCapabilities.openGl(3, 3, List.of()), gl42 = GraphicsCapabilities.openGl(4, 2, List.of());
        Object[][] rows = {
                {"OpenGL " + major + "." + minor, caps, LineStrategy.INDIRECT_DRAW_ID}, {"OpenGL " + major + "." + minor, caps, LineStrategy.INDIRECT_INSTANCE_STYLE},
                {"OpenGL " + major + "." + minor, caps, LineStrategy.EXPANDED_MULTIDRAW}, {"OpenGL " + major + "." + minor, caps, LineStrategy.INSTANCED_LOOP},
                {"OpenGL " + major + "." + minor, caps, LineStrategy.HAIRLINE},
                {"OpenGL 4.2", gl42, LineStrategy.INSTANCED_LOOP}, {"OpenGL 3.3", gl33, LineStrategy.EXPANDED_MULTIDRAW}, {"OpenGL 3.3", gl33, LineStrategy.INSTANCED_LOOP},
                {"OpenGL 3.3", gl33, LineStrategy.HAIRLINE}};
        try (LineGpuRunner runner = new LineGpuRunner(w, h)) {
            for (Object[] row : rows) {
                GraphicsCapabilities c = (GraphicsCapabilities) row[1];
                LineStrategy strategy = (LineStrategy) row[2];
                if (!LineStrategy.chooser().supports(strategy, c)) {
                    continue;
                }
                LineRenderPlan plan = LineRenderPlan.force(strategy, c);
                try (Arena arena = Arena.ofConfined()) {
                    long dataBytes = plan.dataBytes(scene.batch()), styleBytes = plan.styleBytes(scene.batch());
                    MemorySegment data = allocate(arena, dataBytes), styles = allocate(arena, styleBytes);
                    DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1024);
                    for (int i = 0; i < 5; i++) {
                        plan.write(scene.batch(), data, styles, draws);
                    }
                    int reps = 20;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < reps; i++) {
                        plan.write(scene.batch(), data, styles, draws);
                    }
                    double writeMs = (System.nanoTime() - t0) / 1e6 / reps;
                    try (LineGpuRunner.Prepared prepared = runner.new Prepared(plan, c, data, dataBytes, styles, styleBytes, draws)) {
                        for (int i = 0; i < 10; i++) {
                            prepared.draw(scene.viewProjection(), scene.worldToPixel());
                        }
                        glFinish();
                        int query = glGenQueries();
                        int frames = 60;
                        double[] times = new double[frames];
                        for (int i = 0; i < frames; i++) {
                            glBeginQuery(GL_TIME_ELAPSED, query);
                            prepared.draw(scene.viewProjection(), scene.worldToPixel());
                            glEndQuery(GL_TIME_ELAPSED);
                            times[i] = glGetQueryObjecti64(query, GL_QUERY_RESULT) / 1e6;
                        }
                        java.util.Arrays.sort(times);
                        out.printf(Locale.ROOT, "| %s | `%s` | %s | %d | %d | %.2f | %.2f | %.2f | %.2f |%n", row[0], strategy, prepared.submission, draws.size(), prepared.calls, dataBytes / 1048576.0,
                                styleBytes / 1024.0, writeMs, times[frames / 2]);
                    }
                }
            }
        }
        // the dynamic set: a few edits and changes per frame, against uploading everything
        LineRenderPlan plan = LineRenderPlan.choose(caps);
        LineSet set = new LineSet(plan, scene.batch().segmentCount() * 2 + 1000);
        Random rnd = new Random(9);
        List<Long> handles = new ArrayList<>();
        double[] xyz = new double[3 * points];
        for (int p = 0; p < polylines; p++) {
            for (int k = 0; k < points; k++) {
                xyz[3 * k] = rnd.nextDouble() * w;
                xyz[3 * k + 1] = rnd.nextDouble() * h;
            }
            handles.add(set.add(xyz, 0, points, false, STYLES[p % STYLES.length]));
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = allocate(arena, set.dataBytes()), styles = allocate(arena, set.styleBytes() + 4096);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, polylines);
            set.update(data, styles, draws);
            long full = set.usedUnits() * (set.dataBytes() / set.capacityUnits()), dirty = 0;
            long t0 = System.nanoTime();
            int frames = 200;
            for (int f = 0; f < frames; f++) {
                for (int e = 0; e < polylines / 50; e++) {
                    int i = rnd.nextInt(handles.size());
                    for (int k = 0; k < points; k++) {
                        xyz[3 * k] = rnd.nextDouble() * w;
                        xyz[3 * k + 1] = rnd.nextDouble() * h;
                    }
                    set.set(handles.get(i), xyz, 0, points, false);
                }
                set.update(data, styles, draws);
                for (int r = 0; r < set.dirtyRangeCount(); r++) {
                    dirty += set.dirtyLength(r);
                }
            }
            double ms = (System.nanoTime() - t0) / 1e6 / frames;
            out.printf(Locale.ROOT, "LineSet, %d of %d polylines edited per frame with %s: %.1f KB uploaded per frame as dirty ranges against %.1f MB for all the records in use (%.1f%%), %.3f ms per update on the CPU%n",
                    polylines / 50, polylines, plan.strategy(), dirty / (double) frames / 1024.0, full / 1048576.0, 100.0 * dirty / frames / full, ms);
        }
    }

    // ---------------------------------------------------------------- the context

    /**
     * Opens a hidden window with a core context of the newest version the driver gives.
     *
     * @return the window handle, with the context current; 0 if there is none
     */
    public static long openContext() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            return 0;
        }
        int[][] tries = {{4, 6}, {4, 5}, {4, 3}};
        for (int[] t : tries) {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, t[0]);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, t[1]);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            long window = glfwCreateWindow(64, 64, "line check", 0, 0);
            if (window != 0) {
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                return window;
            }
        }
        glfwTerminate();
        return 0;
    }

    /**
     * Closes the window of {@link #openContext}.
     *
     * @param window the handle
     */
    public static void closeContext(long window) {
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    /**
     * Describes the driver.
     *
     * @return the version and renderer strings
     */
    public static String driver() {
        return glGetString(GL_VERSION) + " | " + glGetString(GL_RENDERER);
    }

    /**
     * Runs the check from the command line; exits with 1 if a case fails, 2 if there is no driver.
     *
     * @param args {@code --bench} to measure as well
     */
    public static void main(String[] args) {
        long window = openContext();
        if (window == 0) {
            System.err.println("no OpenGL 4.3 or newer context could be opened");
            System.exit(2);
        }
        try {
            System.out.println("driver: " + driver());
            List<Outcome> results = checkMatrix(System.out);
            long failed = results.stream().filter(o -> !o.ok()).count();
            System.out.println(results.size() - failed + " of " + results.size() + " cases agree with the reference");
            for (String a : args) {
                if (a.equals("--bench")) {
                    bench(System.out, 5000, 64);
                }
            }
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            closeContext(window);
        }
    }
}
