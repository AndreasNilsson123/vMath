package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.*;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.lwjgl.BufferUtils;
import vmath.bulk.BoundsArray;
import vmath.occlusion.CoherentCulling;
import vmath.occlusion.OcclusionQueries;
import vmath.occlusion.VisibilityHistory;
import vmath.samples.framework.Gl;
import vmath.spatial.StaticBvh;

/**
 * Runs {@code CoherentCulling} against the occlusion queries of a real OpenGL driver: boxes are
 * drawn into a framebuffer with a depth buffer, the queries are {@code GL_SAMPLES_PASSED} queries of
 * the same boxes with colour and depth writes off, and after every frame the image that the culling
 * drew is compared, object for object, with the image of drawing all the objects.
 *
 * <p>The scene is that of {@code CoherentCullingTest} (boxes on whole pixels of a 64 by 64 grid, a
 * distinct depth each), seen from a camera that pans, so that what is hidden and what is not changes
 * from frame to frame. The driver answers the queries when it likes (they are read when they are
 * ready, or when nothing else is left to do), so this is the check that the real latency of a GPU
 * does not make the culling leave out something that shows.
 *
 * <p>Needs a display and an OpenGL 3.3 driver; {@code ./gradlew -Psamples :vmath-samples:cullCheck}.
 * Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class OcclusionGpuCheck {

    private static final int SIZE = 64;

    private OcclusionGpuCheck() {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    private static final String VERTEX = """
            #version 330
            uniform vec3 u_min;
            uniform vec3 u_max;
            uniform float u_window;
            const int INDEX[36] = int[36](0, 1, 2, 2, 1, 3, 4, 6, 5, 5, 6, 7, 0, 4, 1, 1, 4, 5, 2, 3, 6, 6, 3, 7, 0, 2, 4, 4, 2, 6, 1, 5, 3, 3, 5, 7);
            void main() {
                int c = INDEX[gl_VertexID];
                vec3 p = vec3((c & 1) == 0 ? u_min.x : u_max.x, (c & 2) == 0 ? u_min.y : u_max.y, (c & 4) == 0 ? u_min.z : u_max.z);
                gl_Position = vec4((p.x - u_window) / 32.0 - 1.0, p.y / 32.0 - 1.0, p.z / 1000.0 - 1.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 330
            uniform vec3 u_id;
            out vec4 o_color;
            void main() {
                o_color = vec4(u_id, 1.0);
            }
            """;

    /** The scene: boxes with whole pixel corners in x and y and a distinct front depth each. */
    private static final class Scene {
        final int n;
        final int[] x0, y0, x1, y1;
        final float[] z;
        final BoundsArray bounds;

        Scene(int n, long seed, int extent) {
            this.n = n;
            Random rnd = new Random(seed);
            x0 = new int[n];
            y0 = new int[n];
            x1 = new int[n];
            y1 = new int[n];
            z = new float[n];
            bounds = new BoundsArray(n);
            Integer[] perm = new Integer[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            java.util.Collections.shuffle(Arrays.asList(perm), rnd);
            for (int i = 0; i < n; i++) {
                x0[i] = rnd.nextInt(extent);
                y0[i] = rnd.nextInt(SIZE);
                x1[i] = x0[i] + 2 + rnd.nextInt(9);
                y1[i] = y0[i] + 2 + rnd.nextInt(9);
                z[i] = 1f + perm[i] * 0.5f;
                bounds.add(x0[i], y0[i], z[i], x1[i], y1[i], z[i] + 1f);
            }
        }
    }

    /** The framebuffer, the program and the queries. */
    private static final class Gpu implements OcclusionQueries, AutoCloseable {
        final int framebuffer = glGenFramebuffers();
        final int color = glGenRenderbuffers();
        final int depth = glGenRenderbuffers();
        final int program;
        final int vao = glGenVertexArrays();
        final int uMin, uMax, uWindow, uId;
        final Scene scene;
        int queries;

        Gpu(Scene scene) {
            this.scene = scene;
            glBindRenderbuffer(GL_RENDERBUFFER, color);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, SIZE, SIZE);
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            program = Gl.program(VERTEX, FRAGMENT);
            uMin = glGetUniformLocation(program, "u_min");
            uMax = glGetUniformLocation(program, "u_max");
            uWindow = glGetUniformLocation(program, "u_window");
            uId = glGetUniformLocation(program, "u_id");
            Gl.check("creating the occlusion check");
        }

        void begin(int window) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, SIZE, SIZE);
            glUseProgram(program);
            glBindVertexArray(vao);
            glUniform1f(uWindow, window);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            glClearColor(0f, 0f, 0f, 0f);
            glClearDepth(1.0);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            queries = 0;
        }

        void draw(int object) {
            glUniform3f(uMin, scene.x0[object], scene.y0[object], scene.z[object]);
            glUniform3f(uMax, scene.x1[object], scene.y1[object], scene.z[object] + 1f);
            int code = object + 1;
            glUniform3f(uId, (code & 255) / 255f, ((code >> 8) & 255) / 255f, ((code >> 16) & 255) / 255f);
            glDrawArrays(GL_TRIANGLES, 0, 36);
        }

        @Override
        public int issue(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            int q = glGenQueries();
            glUniform3f(uMin, minX, minY, minZ);
            glUniform3f(uMax, maxX, maxY, maxZ);
            glColorMask(false, false, false, false);
            glDepthMask(false);
            glBeginQuery(GL_SAMPLES_PASSED, q);
            glDrawArrays(GL_TRIANGLES, 0, 36);
            glEndQuery(GL_SAMPLES_PASSED);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            queries++;
            return q;
        }

        @Override
        public boolean isReady(int query) {
            return glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) != 0;
        }

        @Override
        public int visibleSamples(int query) {
            int r = glGetQueryObjecti(query, GL_QUERY_RESULT);
            glDeleteQueries(query);
            return r;
        }

        int[] read() {
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            int[] ids = new int[SIZE * SIZE];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = (pixels.get(4 * i) & 255) | (pixels.get(4 * i + 1) & 255) << 8 | (pixels.get(4 * i + 2) & 255) << 16;
            }
            Gl.check("reading the image");
            return ids;
        }

        @Override
        public void close() {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            glDeleteFramebuffers(framebuffer);
            glDeleteRenderbuffers(color);
            glDeleteRenderbuffers(depth);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    private static float[] planes(int window) {
        return new float[] {1, 0, 0, -window, -1, 0, 0, window + SIZE, 0, 1, 0, 0, 0, -1, 0, SIZE, 0, 0, 1, 0, 0, 0, -1, 5000};
    }

    /**
     * Runs the check on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per scene
     */
    public static List<Outcome> check(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        for (int[] setup : new int[][] {{600, 220, 1}, {1500, 220, 3}, {3000, 70, 3}}) {
            Scene scene = new Scene(setup[0], 40 + setup[0], setup[1]);
            int interval = setup[2];
            String name = scene.n + " boxes, the camera panning, a visible leaf asked about every " + interval + " frame" + (interval == 1 ? "" : "s");
            String problem = null;
            long queries = 0, nodes = 0, drawnTotal = 0;
            try (Gpu gpu = new Gpu(scene)) {
                StaticBvh bvh = StaticBvh.build(scene.bounds, 4);
                CoherentCulling culling = new CoherentCulling(bvh, new VisibilityHistory(scene.n));
                culling.setQueryInterval(interval);
                for (int frame = 0; frame < 60 && problem == null; frame++) {
                    int window = frame * 2;
                    gpu.begin(window);
                    int[] drawn = {0};
                    CoherentCulling.Stats s = culling.cull(planes(window), SIZE / 2f + window, SIZE / 2f, -2000f, gpu, o -> {
                        gpu.draw(o);
                        drawn[0]++;
                    });
                    int[] image = gpu.read();
                    gpu.begin(window);
                    for (int i = 0; i < scene.n; i++) {
                        gpu.draw(i);
                    }
                    int[] brute = gpu.read();
                    if (!Arrays.equals(image, brute)) {
                        int bad = 0;
                        for (int i = 0; i < image.length; i++) {
                            bad += image[i] != brute[i] ? 1 : 0;
                        }
                        problem = "frame " + frame + ": " + bad + " of " + image.length + " pixels differ from the image of all the objects";
                    }
                    queries += s.queries();
                    nodes += s.nodesVisited();
                    drawnTotal += drawn[0];
                }
            } catch (RuntimeException e) {
                problem = String.valueOf(e.getMessage());
            }
            Outcome o = new Outcome(name, problem == null, problem == null ? String.format("same image as drawing everything; %d queries over 60 frames (%d nodes visited), %d objects drawn of %d", queries,
                    nodes, drawnTotal, 60L * scene.n) : problem);
            results.add(o);
            if (out != null) {
                out.println((o.ok() ? "ok    " : "FAIL  ") + o.name() + "\n      " + o.detail());
            }
        }
        return results;
    }

    /**
     * Runs the check from the command line; exits with 1 if a case fails, 2 if there is no driver.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        long window = LineGpuCheck.openContext();
        if (window == 0) {
            System.err.println("no OpenGL context could be opened");
            System.exit(2);
        }
        try {
            System.out.println("driver: " + LineGpuCheck.driver());
            List<Outcome> results = check(System.out);
            long failed = results.stream().filter(o -> !o.ok()).count();
            System.out.println(results.size() - failed + " of " + results.size() + " scenes agree with drawing everything");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
