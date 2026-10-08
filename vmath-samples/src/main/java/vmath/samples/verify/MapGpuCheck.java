package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL46.GL_FLOAT;
import static org.lwjgl.opengl.GL46.GL_R32F;
import static org.lwjgl.opengl.GL46.GL_RED;
import static org.lwjgl.opengl.GL46.glUniform3f;
import static org.lwjgl.opengl.GL46.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL46.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL46.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL46.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL46.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL46.glBindBufferBase;
import static org.lwjgl.opengl.GL46.glDrawElements;
import static org.lwjgl.opengl.GL46.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL46.glMultiDrawElements;
import static org.lwjgl.opengl.GL46.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL46.glUniformBlockBinding;
import static org.lwjgl.opengl.GL46.GL_BLEND;
import static org.lwjgl.opengl.GL46.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL46.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL46.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL46.GL_CULL_FACE;
import static org.lwjgl.opengl.GL46.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL46.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL46.GL_EXTENSIONS;
import static org.lwjgl.opengl.GL46.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46.GL_MAJOR_VERSION;
import static org.lwjgl.opengl.GL46.GL_MINOR_VERSION;
import static org.lwjgl.opengl.GL46.GL_NEAREST;
import static org.lwjgl.opengl.GL46.GL_NUM_EXTENSIONS;
import static org.lwjgl.opengl.GL46.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL46.GL_RGBA;
import static org.lwjgl.opengl.GL46.GL_RGBA32UI;
import static org.lwjgl.opengl.GL46.GL_RGBA8;
import static org.lwjgl.opengl.GL46.GL_TEXTURE0;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL46.GL_TRIANGLES;
import static org.lwjgl.opengl.GL46.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL46.glActiveTexture;
import static org.lwjgl.opengl.GL46.glBindBuffer;
import static org.lwjgl.opengl.GL46.glBindFramebuffer;
import static org.lwjgl.opengl.GL46.glBindRenderbuffer;
import static org.lwjgl.opengl.GL46.glBindTexture;
import static org.lwjgl.opengl.GL46.glBindVertexArray;
import static org.lwjgl.opengl.GL46.glBufferData;
import static org.lwjgl.opengl.GL46.glClear;
import static org.lwjgl.opengl.GL46.glClearColor;
import static org.lwjgl.opengl.GL46.glDeleteBuffers;
import static org.lwjgl.opengl.GL46.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL46.glDeleteProgram;
import static org.lwjgl.opengl.GL46.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL46.glDeleteTextures;
import static org.lwjgl.opengl.GL46.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL46.glDisable;
import static org.lwjgl.opengl.GL46.glDrawArrays;
import static org.lwjgl.opengl.GL46.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL46.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL46.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL46.glGenBuffers;
import static org.lwjgl.opengl.GL46.glGenFramebuffers;
import static org.lwjgl.opengl.GL46.glGenRenderbuffers;
import static org.lwjgl.opengl.GL46.glGenTextures;
import static org.lwjgl.opengl.GL46.glGenVertexArrays;
import static org.lwjgl.opengl.GL46.glGetInteger;
import static org.lwjgl.opengl.GL46.glGetStringi;
import static org.lwjgl.opengl.GL46.glGetUniformLocation;
import static org.lwjgl.opengl.GL46.glReadPixels;
import static org.lwjgl.opengl.GL46.glRenderbufferStorage;
import static org.lwjgl.opengl.GL46.glTexBuffer;
import static org.lwjgl.opengl.GL46.glTexImage2D;
import static org.lwjgl.opengl.GL46.glTexParameteri;
import static org.lwjgl.opengl.GL46.glUniform1f;
import static org.lwjgl.opengl.GL46.glUniform1i;
import static org.lwjgl.opengl.GL46.glUniform2f;
import static org.lwjgl.opengl.GL46.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL46.glUseProgram;
import static org.lwjgl.opengl.GL46.glVertexAttribDivisor;
import static org.lwjgl.opengl.GL46.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL46.glVertexAttribPointer;
import static org.lwjgl.opengl.GL46.glViewport;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import vmath.core.ClipSpace;
import vmath.geo.WebMercatorProjection;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.StructArrayAccess;
import vmath.gl.VertexBufferLayout;
import vmath.map.AreaBatch;
import vmath.map.AreaRenderPlan;
import vmath.map.AreaShaderModel;
import vmath.map.AreaStrategy;
import vmath.map.AreaStyle;
import vmath.map.ColorRamp;
import vmath.map.Hillshade;
import vmath.map.MapView2d;
import vmath.map.TerrainGrid;
import vmath.map.TerrainShader;
import vmath.map.TerrainShading;
import vmath.map.SymbolAtlas;
import vmath.map.SymbolBatch;
import vmath.map.SymbolGpu;
import vmath.map.SymbolRenderPlan;
import vmath.map.SymbolShaderModel;
import vmath.map.SymbolStrategy;
import vmath.samples.framework.Gl;

/**
 * Runs the shaders of the map layer on the OpenGL driver of the machine and compares what they
 * draw with the CPU models of {@code vmath.map}: the check that the GLSL, which nothing else in the
 * build compiles, does what the models define.
 *
 * <p>For every context version from 3.3 to 4.6 (as the capabilities of that version, so the shader
 * text is that of a driver of that version) and every symbol strategy, the program is compiled,
 * linked and run on a scene of symbols (rotated views, turning and fixed symbols, sizes in pixels
 * and in metres, offsets, hidden ones, a tint on a two-colour sprite), and the pixels are compared
 * with the triangles that {@link SymbolShaderModel} computes. A pixel is checked only where the
 * model is sure: its centre is clearly inside or clearly outside every triangle, and the sprite has
 * one colour around the sampled texel.
 *
 * <p>Needs a display and an OpenGL 4.6 driver (the newer context runs the older GLSL versions);
 * {@code ./gradlew -Psamples :vmath-samples:mapCheck}. Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class MapGpuCheck {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 200;

    private MapGpuCheck() {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    // ---------------------------------------------------------------- the scene

    private static SymbolAtlas atlas() {
        int w = 16, h = 16;
        byte[] two = new byte[w * h * 4];
        byte[] solid = new byte[8 * 8 * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4;
                boolean top = y < h / 2;
                two[o] = (byte) (top ? 255 : 40);
                two[o + 1] = (byte) (top ? 60 : 200);
                two[o + 2] = (byte) (top ? 40 : 255);
                two[o + 3] = (byte) 255;
            }
        }
        for (int i = 0; i < 64; i++) {
            solid[4 * i] = (byte) 220;
            solid[4 * i + 1] = (byte) 220;
            solid[4 * i + 2] = (byte) 30;
            solid[4 * i + 3] = (byte) 255;
        }
        return SymbolAtlas.builder().add("two", w, h, two).add("solid", 8, 8, solid).build(64);
    }

    private record Scene(String name, MapView2d view, SymbolBatch batch, float handedness) {
    }

    private static Scene scene(String name, SymbolAtlas atlas, MapView2d view, float handedness, long seed) {
        Random rnd = new Random(seed);
        SymbolBatch b = new SymbolBatch(16);
        float[] uv = new float[4];
        int cols = 6, rows = 5;
        double cell = 40.0;
        double mpp = view.mapUnitsPerPixel();
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                atlas.rect(rnd.nextInt(2), uv);
                // the cell centre in the window, back to projected metres
                double[] p = new double[2];
                view.screenToProjected(cell * (cx + 0.5), cell * (cy + 0.5), p);
                int flags = (rnd.nextBoolean() ? SymbolGpu.ROTATE_WITH_MAP : 0) | (rnd.nextInt(6) == 0 ? SymbolGpu.HIDDEN : 0);
                float size = 12f + rnd.nextInt(10);
                if (rnd.nextInt(4) == 0) {
                    flags |= SymbolGpu.SIZE_IN_MAP_UNITS;
                    size = (float) (size * mpp);
                }
                int tint = rnd.nextBoolean() ? 0xFFFFFFFF : 0xFFC0A0FF;
                int i = b.add(p[0], p[1], rnd.nextDouble() * 6.283, size, uv, tint, flags);
                b.setOffset(i, rnd.nextInt(7) - 3, rnd.nextInt(7) - 3);
            }
        }
        return new Scene(name, view, b, handedness);
    }

    // ---------------------------------------------------------------- the reference raster

    /** The colour {@code 0xRRGGBBAA} the model draws at each pixel centre, or -1 where it is not sure. */
    private static int[] reference(SymbolAtlas atlas, SymbolRenderPlan plan, Scene s) {
        int[] out = new int[WIDTH * HEIGHT];
        boolean[] sure = new boolean[WIDTH * HEIGHT];
        java.util.Arrays.fill(sure, true);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(16, plan.dataBytes(s.batch())), 16);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
            plan.write(s.batch(), s.view().centerX(), s.view().centerY(), data, draws);
            float[] vp = new float[16];
            s.view().viewProjection(ClipSpace.OPENGL, s.view().centerX(), s.view().centerY(), vp);
            SymbolShaderModel.evaluate(plan, data, draws, vp, WIDTH, HEIGHT, SymbolRenderPlan.mapRotation(s.view()), (float) s.view().pixelsPerMapUnit(), s.handedness(), (sym, x, y, u, v, color) -> {
                int x0 = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2])))), x1 = Math.min(WIDTH - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
                int y0 = Math.max(0, (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2])))), y1 = Math.min(HEIGHT - 1, (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
                double area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
                if (Math.abs(area) < 1e-9) {
                    return;
                }
                for (int py = y0; py <= y1; py++) {
                    for (int px = x0; px <= x1; px++) {
                        double cx = px + 0.5, cy = py + 0.5;
                        double w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / area;
                        double w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / area;
                        double w2 = 1 - w0 - w1;
                        double lengthScale = Math.sqrt(Math.abs(area)) + 1e-9;
                        double margin = 0.75 / lengthScale;            // about three quarters of a pixel
                        double lowest = Math.min(w0, Math.min(w1, w2));
                        int at = py * WIDTH + px;
                        if (lowest < -margin) {
                            continue;                                    // clearly outside this triangle
                        }
                        if (lowest < margin) {
                            sure[at] = false;                            // on the edge: the rasterisation rules decide
                            continue;
                        }
                        float uu = (float) (w0 * u[0] + w1 * u[1] + w2 * u[2]), vv = (float) (w0 * v[0] + w1 * v[1] + w2 * v[2]);
                        int texel = atlas.sampleNearest(uu, vv);
                        // not sure close to a colour edge of the sprite
                        float du = 0.45f / atlas.width(), dv = 0.45f / atlas.height();
                        boolean steady = atlas.sampleNearest(uu - du, vv) == texel && atlas.sampleNearest(uu + du, vv) == texel && atlas.sampleNearest(uu, vv - dv) == texel
                                && atlas.sampleNearest(uu, vv + dv) == texel;
                        if (!steady) {
                            sure[at] = false;
                            continue;
                        }
                        out[at] = multiply(texel, color);
                    }
                }
            });
        }
        for (int i = 0; i < out.length; i++) {
            if (!sure[i]) {
                out[i] = -1;
            }
        }
        return out;
    }

    private static int multiply(int a, int b) {
        int r = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int ca = (a >>> shift) & 255, cb = (b >>> shift) & 255;
            r |= Math.round(ca * cb / 255f) << shift;
        }
        return r;
    }

    // ---------------------------------------------------------------- the GPU side

    private static final class Target implements AutoCloseable {
        final int framebuffer = glGenFramebuffers();
        final int renderbuffer = glGenRenderbuffers();

        Target() {
            glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, WIDTH, HEIGHT);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffer);
            Gl.check("creating the target");
        }

        byte[] read() {
            ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[WIDTH * HEIGHT * 4];
            pixels.get(out);
            Gl.check("reading the pixels");
            return out;
        }

        @Override
        public void close() {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(framebuffer);
            glDeleteRenderbuffers(renderbuffer);
        }
    }

    private static byte[] drawSymbols(Target target, SymbolAtlas atlas, SymbolRenderPlan plan, Scene s) {
        int program = Gl.program(plan.vertexShader(), plan.fragmentShader());
        int vao = glGenVertexArrays();
        int dataBuffer = glGenBuffers();
        int texture = 0;
        int atlasTexture = glGenTextures();
        try (Arena arena = Arena.ofConfined()) {
            long bytes = Math.max(16, plan.dataBytes(s.batch()));
            MemorySegment data = arena.allocate(bytes, 16);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
            plan.write(s.batch(), s.view().centerX(), s.view().centerY(), data, draws);
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, dataBuffer);
            glBufferData(GL_ARRAY_BUFFER, data.asByteBuffer().limit((int) bytes), GL_DYNAMIC_DRAW);
            StructArrayAccess access = plan.symbolAccess();
            if (access.mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                texture = glGenTextures();
                glActiveTexture(GL_TEXTURE0 + SymbolRenderPlan.SYMBOL_SLOT);
                glBindTexture(GL_TEXTURE_BUFFER, texture);
                glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, dataBuffer);
                if (!access.hasExplicitBinding()) {
                    glUniform1i(glGetUniformLocation(program, access.name() + "_texels"), SymbolRenderPlan.SYMBOL_SLOT);
                }
            } else {
                VertexBufferLayout layout = access.vertexLayout();
                int divisor = plan.strategy() == SymbolStrategy.INSTANCED ? 1 : 0;
                for (VertexBufferLayout.GlFormat f : layout.glFormats()) {
                    glEnableVertexAttribArray(f.location());
                    if (f.integer()) {
                        glVertexAttribIPointer(f.location(), f.size(), f.type(), layout.stride(), f.relativeOffset());
                    } else {
                        glVertexAttribPointer(f.location(), f.size(), f.type(), f.normalized(), layout.stride(), f.relativeOffset());
                    }
                    glVertexAttribDivisor(f.location(), divisor);
                }
            }
            glActiveTexture(GL_TEXTURE0 + 1);
            glBindTexture(GL_TEXTURE_2D, atlasTexture);
            ByteBuffer pixels = BufferUtils.createByteBuffer(atlas.pixels().length);
            pixels.put(atlas.pixels()).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, atlas.width(), atlas.height(), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glUniform1i(glGetUniformLocation(program, SymbolRenderPlan.U_ATLAS), 1);

            float[] vp = new float[16];
            s.view().viewProjection(ClipSpace.OPENGL, s.view().centerX(), s.view().centerY(), vp);
            glUniformMatrix4fv(glGetUniformLocation(program, SymbolRenderPlan.U_VIEW_PROJECTION), false, vp);
            glUniform2f(glGetUniformLocation(program, SymbolRenderPlan.U_VIEWPORT), WIDTH, HEIGHT);
            glUniform1f(glGetUniformLocation(program, SymbolRenderPlan.U_MAP_ROTATION), SymbolRenderPlan.mapRotation(s.view()));
            glUniform1f(glGetUniformLocation(program, SymbolRenderPlan.U_PIXELS_PER_UNIT), (float) s.view().pixelsPerMapUnit());
            glUniform1f(glGetUniformLocation(program, SymbolRenderPlan.U_HANDEDNESS), s.handedness());

            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
            glViewport(0, 0, WIDTH, HEIGHT);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            if (draws.size() > 0) {
                if (draws.instanceCount(0) > 1 || plan.strategy() == SymbolStrategy.INSTANCED) {
                    glDrawArraysInstanced(GL_TRIANGLES, draws.first(0), draws.count(0), draws.instanceCount(0));
                } else {
                    glDrawArrays(GL_TRIANGLES, draws.first(0), draws.count(0));
                }
            }
            Gl.check("drawing " + plan.strategy());
            return target.read();
        } finally {
            glDeleteProgram(program);
            glDeleteBuffers(dataBuffer);
            glDeleteVertexArrays(vao);
            glDeleteTextures(atlasTexture);
            if (texture != 0) {
                glDeleteTextures(texture);
            }
        }
    }

    /** Compares the pixels with the reference; returns a message about the first problems, or null. */
    private static String compare(byte[] gpu, int[] expected, int[] sureCount) {
        int bad = 0, checked = 0, drawn = 0;
        String first = null;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int e = expected[y * WIDTH + x];
                if (e == -1) {
                    continue;
                }
                checked++;
                int o = (y * WIDTH + x) * 4;
                int r = gpu[o] & 255, g = gpu[o + 1] & 255, b = gpu[o + 2] & 255, a = gpu[o + 3] & 255;
                int er = e >>> 24, eg = (e >>> 16) & 255, eb = (e >>> 8) & 255, ea = e & 255;
                if (ea != 0) {
                    drawn++;
                }
                if (Math.abs(r - er) > 3 || Math.abs(g - eg) > 3 || Math.abs(b - eb) > 3 || Math.abs(a - ea) > 3) {
                    bad++;
                    if (first == null) {
                        first = "pixel " + x + "," + y + " is " + r + "," + g + "," + b + "," + a + ", the model says " + er + "," + eg + "," + eb + "," + ea;
                    }
                }
            }
        }
        sureCount[0] = checked;
        sureCount[1] = drawn;
        if (bad > 0) {
            return bad + " of " + checked + " checked pixels differ; first: " + first;
        }
        if (drawn < 500) {
            return "the scene draws only " + drawn + " checked pixels: the check would prove little";
        }
        return null;
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
     * Runs the matrix of versions, strategies and scenes of the symbols on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per version, strategy and scene
     */
    public static List<Outcome> checkSymbols(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        int major = glGetInteger(GL_MAJOR_VERSION), minor = glGetInteger(GL_MINOR_VERSION);
        SymbolAtlas atlas = atlas();
        MapView2d north = MapView2d.of(WebMercatorProjection.INSTANCE, Math.toRadians(40), Math.toRadians(-3), WIDTH, HEIGHT).withMetersPerPixel(200.0);
        Scene[] scenes = {
                scene("north up", atlas, north, 1f, 1),
                scene("rotated 35 degrees", atlas, north.withOrientation(MapView2d.Orientation.ANGLE, Math.toRadians(35)), 1f, 2),
                scene("rotated -100 degrees", atlas, north.withOrientation(MapView2d.Orientation.ANGLE, Math.toRadians(-100)), 1f, 3),
                scene("y-down handedness", atlas, north.withOrientation(MapView2d.Orientation.ANGLE, Math.toRadians(10)), -1f, 4)};
        int[][] versions = {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};
        try (Target target = new Target()) {
            for (int[] v : versions) {
                if (v[0] > major || v[0] == major && v[1] > minor) {
                    continue;
                }
                GraphicsCapabilities caps = GraphicsCapabilities.openGl(v[0], v[1], v[1] == 5 ? List.of("GL_ARB_shader_draw_parameters") : List.of());
                for (SymbolStrategy strategy : SymbolStrategy.values()) {
                    if (!SymbolStrategy.chooser().supports(strategy, caps)) {
                        continue;
                    }
                    SymbolRenderPlan plan = SymbolRenderPlan.force(strategy, caps);
                    String name = "GL " + v[0] + "." + v[1] + " (GLSL " + caps.glsl().number() + ") symbols " + strategy;
                    for (Scene scene : scenes) {
                        String problem;
                        try {
                            int[] counts = new int[2];
                            problem = compare(drawSymbols(target, atlas, plan, scene), reference(atlas, plan, scene), counts);
                        } catch (RuntimeException e) {
                            problem = e.getMessage();
                        }
                        Outcome o = new Outcome(name + " / " + scene.name(), problem == null, problem == null ? "same pixels as the model" : problem);
                        results.add(o);
                        if (out != null) {
                            out.println((o.ok() ? "ok    " : "FAIL  ") + o.name() + (o.ok() ? "" : "\n      " + o.detail()));
                        }
                    }
                }
            }
            // the comparison must be able to fail: the pixels of one scene against the reference of another are not accepted
            GraphicsCapabilities newest = GraphicsCapabilities.openGl(major, minor, driverExtensions());
            SymbolRenderPlan any = SymbolRenderPlan.choose(newest);
            String confused = compare(drawSymbols(target, atlas, any, scenes[0]), reference(atlas, any, scenes[1]), new int[2]);
            Outcome sensitivity = new Outcome("the comparison rejects the pixels of another scene", confused != null, confused == null ? "it accepted them" : "rejected: " + confused);
            results.add(sensitivity);
            if (out != null) {
                out.println((sensitivity.ok() ? "ok    " : "FAIL  ") + sensitivity.name());
            }
        }
        return results;
    }

    // ---------------------------------------------------------------- areas

    private static AreaBatch areaScene(long seed) {
        Random rnd = new Random(seed);
        AreaBatch b = new AreaBatch();
        int[] styles = {
                b.style(AreaStyle.solid(0x2060C0FF)),
                b.style(AreaStyle.hatch(0xC0000030, 0xFF2020FF, 7f, 2f, Math.toRadians(45))),
                b.style(AreaStyle.crosshatch(0x00000000, 0x20A020FF, 9f, 2f, Math.toRadians(10))),
                b.style(AreaStyle.dots(0xFFF0A0FF, 0x604000FF, 6f, 3f, Math.toRadians(20))),
                b.style(AreaStyle.hatch(0x808080FF, 0x000000FF, 12f, 5f, Math.toRadians(-60)))};
        int cols = 6, rows = 5;
        double cell = 40.0;
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                double x0 = cell * cx + 4, y0 = cell * cy + 4, x1 = cell * (cx + 1) - 4, y1 = cell * (cy + 1) - 4;
                int style = styles[(cx + 2 * cy) % styles.length];
                double j = rnd.nextInt(5);
                // the window is 240 x 200 and the view is centred: the cell origin is moved to the lower left corner of the window in projected metres
                double ox = -WIDTH / 2.0, oy = -HEIGHT / 2.0;
                if ((cx + cy) % 3 == 0) {
                    double[] xy = {x0, y0, x1, y0, x1, y1, x0, y1, x0 + 10, y0 + 10, x0 + 10, y1 - 10, x1 - 10 - j, y1 - 10, x1 - 10 - j, y0 + 10};
                    shift(xy, ox, oy);
                    b.addPolygon(xy, new int[] {4, 8}, style);
                } else if ((cx + cy) % 3 == 1) {
                    double[] xy = {x0 + j, y0, x1, y0 + j, x1 - j, y1, x0, y1 - 3};
                    shift(xy, ox, oy);
                    b.addPolygon(xy, 4, style);
                } else {
                    double[] xy = {(x0 + x1) / 2, y0, x1, (y0 + y1) / 2, (x0 + x1) / 2, y1, x0, (y0 + y1) / 2};
                    shift(xy, ox, oy);
                    b.addPolygon(xy, 4, style);
                }
            }
        }
        return b;
    }

    private static void shift(double[] xy, double dx, double dy) {
        for (int i = 0; i < xy.length; i += 2) {
            xy[i] += dx;
            xy[i + 1] += dy;
        }
    }

    /** An azimuthal equidistant map at the origin: a projected metre is a metre, and one metre is a pixel. */
    private static MapView2d areaView() {
        return MapView2d.of(new vmath.geo.AzimuthalEquidistant(0.0, 0.0), 0.0, 0.0, WIDTH, HEIGHT).withMetersPerPixel(1.0);
    }

    private static int[] areaReference(AreaRenderPlan plan, AreaBatch batch, MapView2d view, float[] patternOffset) {
        int[] out = new int[WIDTH * HEIGHT];
        boolean[] sure = new boolean[WIDTH * HEIGHT];
        java.util.Arrays.fill(sure, true);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vertices = arena.allocate(Math.max(16, plan.vertexBytes(batch)), 16), indices = arena.allocate(Math.max(16, plan.indexBytes(batch)), 16);
            MemorySegment styles = arena.allocate(Math.max(16, plan.styleBytes(batch)), 16);
            DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 1);
            plan.write(batch, view.centerX(), view.centerY(), vertices, indices, styles, draws);
            float[] vp = new float[16];
            view.viewProjection(ClipSpace.OPENGL, view.centerX(), view.centerY(), vp);
            AreaShaderModel.evaluate(plan, vertices, indices, styles, draws, vp, WIDTH, HEIGHT, (x, y, style) -> {
                int x0 = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2])))), x1 = Math.min(WIDTH - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
                int y0 = Math.max(0, (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2])))), y1 = Math.min(HEIGHT - 1, (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
                double area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
                if (Math.abs(area) < 1e-9) {
                    return;
                }
                double margin = 0.75 / (Math.sqrt(Math.abs(area)) + 1e-9);
                for (int py = y0; py <= y1; py++) {
                    for (int px = x0; px <= x1; px++) {
                        double cx = px + 0.5, cy = py + 0.5;
                        double w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / area;
                        double w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / area;
                        double w2 = 1 - w0 - w1;
                        double lowest = Math.min(w0, Math.min(w1, w2));
                        int at = py * WIDTH + px;
                        if (lowest < -margin) {
                            continue;
                        }
                        if (lowest < margin) {
                            sure[at] = false;
                            continue;
                        }
                        int c = AreaShaderModel.colorAt(style, (float) cx, (float) cy, patternOffset[0], patternOffset[1]);
                        boolean steady = true;
                        for (float d : new float[] {-0.3f, 0.3f}) {
                            steady &= AreaShaderModel.colorAt(style, (float) cx + d, (float) cy, patternOffset[0], patternOffset[1]) == c
                                    && AreaShaderModel.colorAt(style, (float) cx, (float) cy + d, patternOffset[0], patternOffset[1]) == c;
                        }
                        if (!steady) {
                            sure[at] = false;
                            continue;
                        }
                        out[at] = c;
                    }
                }
            });
        }
        for (int i = 0; i < out.length; i++) {
            if (!sure[i]) {
                out[i] = -1;
            }
        }
        return out;
    }

    private static int styleTarget(StructArrayAccess.Mode m) {
        return m == StructArrayAccess.Mode.STORAGE_BLOCK ? GL_SHADER_STORAGE_BUFFER : m == StructArrayAccess.Mode.UNIFORM_BLOCK ? GL_UNIFORM_BUFFER : GL_ARRAY_BUFFER;
    }

    private static byte[] drawAreas(Target target, AreaRenderPlan plan, AreaBatch batch, MapView2d view, float[] patternOffset, DrawSubmission submission) {
        int program = Gl.program(plan.vertexShader(), plan.fragmentShader());
        int vao = glGenVertexArrays();
        int vertexBuffer = glGenBuffers(), indexBuffer = glGenBuffers(), styleBuffer = glGenBuffers(), commandBuffer = glGenBuffers();
        int texture = 0;
        try (Arena arena = Arena.ofConfined()) {
            long vb = Math.max(16, plan.vertexBytes(batch)), ib = Math.max(16, plan.indexBytes(batch)), sb = Math.max(16, plan.styleBytes(batch));
            StructArrayAccess styles = plan.styleAccess();
            if (styles != null && styles.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK) {
                sb = Math.max(sb, AreaRenderPlan.STYLE_TABLE_UNIFORM_LENGTH * AreaRenderPlan.STYLE_BYTES);
            }
            MemorySegment vertices = arena.allocate(vb, 16), indices = arena.allocate(ib, 16), styleData = arena.allocate(sb, 16);
            DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 1);
            plan.write(batch, view.centerX(), view.centerY(), vertices, indices, styleData, draws);
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertices.asByteBuffer().limit((int) vb), GL_DYNAMIC_DRAW);
            for (VertexBufferLayout.GlFormat f : plan.vertexLayout().glFormats()) {
                glEnableVertexAttribArray(f.location());
                if (f.integer()) {
                    glVertexAttribIPointer(f.location(), f.size(), f.type(), plan.vertexLayout().stride(), f.relativeOffset());
                } else {
                    glVertexAttribPointer(f.location(), f.size(), f.type(), f.normalized(), plan.vertexLayout().stride(), f.relativeOffset());
                }
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.asByteBuffer().limit((int) ib), GL_DYNAMIC_DRAW);
            if (styles != null) {
                int t = styleTarget(styles.mode());
                glBindBuffer(t, styleBuffer);
                glBufferData(t, styleData.asByteBuffer().limit((int) sb), GL_DYNAMIC_DRAW);
                switch (styles.mode()) {
                    case STORAGE_BLOCK, UNIFORM_BLOCK -> {
                        glBindBufferBase(t, AreaRenderPlan.STYLE_SLOT, styleBuffer);
                        if (styles.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK && !styles.hasExplicitBinding()) {
                            glUniformBlockBinding(program, glGetUniformBlockIndex(program, styles.name() + "_Block"), AreaRenderPlan.STYLE_SLOT);
                        }
                    }
                    case TEXTURE_BUFFER -> {
                        texture = glGenTextures();
                        glActiveTexture(GL_TEXTURE0 + AreaRenderPlan.STYLE_SLOT);
                        glBindTexture(GL_TEXTURE_BUFFER, texture);
                        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, styleBuffer);
                        if (!styles.hasExplicitBinding()) {
                            glUniform1i(glGetUniformLocation(program, styles.name() + "_texels"), AreaRenderPlan.STYLE_SLOT);
                        }
                    }
                    default -> throw new IllegalStateException("styles cannot be " + styles.mode());
                }
            }
            float[] vp = new float[16];
            view.viewProjection(ClipSpace.OPENGL, view.centerX(), view.centerY(), vp);
            glUniformMatrix4fv(glGetUniformLocation(program, AreaRenderPlan.U_VIEW_PROJECTION), false, vp);
            glUniform2f(glGetUniformLocation(program, AreaRenderPlan.U_PATTERN_OFFSET), patternOffset[0], patternOffset[1]);

            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
            glViewport(0, 0, WIDTH, HEIGHT);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            int n = draws.size();
            switch (submission) {
                case MULTI_DRAW_INDIRECT -> {
                    MemorySegment commands = arena.allocate(Math.max(16L, (long) n * DrawCommandBuffer.Kind.ELEMENTS.bytes()), 16);
                    draws.writeIndirect(new DrawCommandBuffer(commands, DrawCommandBuffer.Kind.ELEMENTS, false));
                    glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
                    ByteBuffer commandView = commands.asByteBuffer();
                    commandView.limit(n * DrawCommandBuffer.Kind.ELEMENTS.bytes());
                    glBufferData(GL_DRAW_INDIRECT_BUFFER, commandView, GL_DYNAMIC_DRAW);
                    glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, n, 0);
                }
                case MULTI_DRAW_CLIENT -> {
                    java.nio.IntBuffer counts = BufferUtils.createIntBuffer(n);
                    PointerBuffer offsets = BufferUtils.createPointerBuffer(n);
                    for (int i = 0; i < n; i++) {
                        counts.put(draws.count(i));
                        offsets.put(4L * draws.first(i));
                    }
                    counts.flip();
                    offsets.flip();
                    glMultiDrawElements(GL_TRIANGLES, counts, GL_UNSIGNED_INT, offsets);
                }
                case DRAW_LOOP -> {
                    for (int i = 0; i < n; i++) {
                        glDrawElements(GL_TRIANGLES, draws.count(i), GL_UNSIGNED_INT, 4L * draws.first(i));
                    }
                }
            }
            Gl.check("drawing areas " + plan.strategy() + " " + submission);
            return target.read();
        } finally {
            glDeleteProgram(program);
            glDeleteBuffers(vertexBuffer);
            glDeleteBuffers(indexBuffer);
            glDeleteBuffers(styleBuffer);
            glDeleteBuffers(commandBuffer);
            glDeleteVertexArrays(vao);
            if (texture != 0) {
                glDeleteTextures(texture);
            }
        }
    }

    /**
     * Runs the matrix of versions, style-table modes, strategies and submissions of the filled
     * areas on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per case
     */
    public static List<Outcome> checkAreas(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        int major = glGetInteger(GL_MAJOR_VERSION), minor = glGetInteger(GL_MINOR_VERSION);
        MapView2d view = areaView();
        AreaBatch batch = areaScene(7);
        float[][] offsets = {{0f, 0f}, {3.25f, -1.5f}};
        int[][] versions = {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};
        try (Target target = new Target()) {
            for (int[] v : versions) {
                if (v[0] > major || v[0] == major && v[1] > minor) {
                    continue;
                }
                for (boolean uniformOnly : new boolean[] {false, true}) {
                    GraphicsCapabilities base = GraphicsCapabilities.openGl(v[0], v[1], List.of());
                    GraphicsCapabilities caps = uniformOnly ? base.without(GraphicsCapabilities.Feature.TEXTURE_BUFFERS, GraphicsCapabilities.Feature.STORAGE_BUFFERS) : base;
                    for (AreaStrategy strategy : AreaStrategy.values()) {
                        AreaRenderPlan plan = AreaRenderPlan.force(strategy, caps);
                        for (DrawSubmission submission : DrawSubmission.values()) {
                            DrawList probe = new DrawList(DrawList.Kind.ELEMENTS, 1);
                            probe.addElements(3, 0, 0, 1, 0, 0);
                            try {
                                DrawSubmission.force(submission, caps, probe);
                            } catch (UnsupportedOperationException notHere) {
                                continue;
                            }
                            for (float[] offset : offsets) {
                                String name = "GL " + v[0] + "." + v[1] + " (GLSL " + caps.glsl().number() + ")" + (uniformOnly ? " uniform block" : "") + " areas " + strategy + " " + submission
                                        + (offset[0] != 0f ? " offset" : "");
                                String problem;
                                try {
                                    problem = compareAreas(drawAreas(target, plan, batch, view, offset, submission), areaReference(plan, batch, view, offset), strategy == AreaStrategy.STYLE_TABLE);
                                } catch (RuntimeException e) {
                                    problem = e.getMessage();
                                }
                                Outcome o = new Outcome(name, problem == null, problem == null ? "same pixels as the model" : problem);
                                results.add(o);
                                if (out != null) {
                                    out.println((o.ok() ? "ok    " : "FAIL  ") + o.name() + (o.ok() ? "" : "\n      " + o.detail()));
                                }
                            }
                        }
                    }
                }
            }
            GraphicsCapabilities newest = GraphicsCapabilities.openGl(major, minor, driverExtensions());
            AreaRenderPlan any = AreaRenderPlan.choose(newest);
            String confused = compareAreas(drawAreas(target, any, batch, view, offsets[0], DrawSubmission.DRAW_LOOP), areaReference(any, batch, view, offsets[1]), true);
            Outcome sensitivity = new Outcome("the comparison rejects a pattern that is shifted", confused != null, confused == null ? "it accepted them" : "rejected: " + confused);
            results.add(sensitivity);
            if (out != null) {
                out.println((sensitivity.ok() ? "ok    " : "FAIL  ") + sensitivity.name());
            }
        }
        return results;
    }

    private static String compareAreas(byte[] gpu, int[] expected, boolean patterns) {
        int[] counts = new int[2];
        String problem = compare(gpu, expected, counts);
        if (problem == null && patterns && counts[0] < 2000) {
            return "only " + counts[0] + " pixels were sure";
        }
        return problem;
    }

    // ---------------------------------------------------------------- terrain

    private static TerrainGrid terrain() {
        Random rnd = new Random(11);
        float[] h = new float[WIDTH * HEIGHT];
        // hills: a sum of a few smooth bumps, with a patch of no data
        double[][] bumps = new double[6][4];
        for (double[] b : bumps) {
            b[0] = rnd.nextDouble() * WIDTH;
            b[1] = rnd.nextDouble() * HEIGHT;
            b[2] = 15 + rnd.nextDouble() * 40;
            b[3] = 200 + rnd.nextDouble() * 900;
        }
        for (int r = 0; r < HEIGHT; r++) {
            for (int c = 0; c < WIDTH; c++) {
                double v = 100;
                for (double[] b : bumps) {
                    double d2 = (c - b[0]) * (c - b[0]) + (r - b[1]) * (r - b[1]);
                    v += b[3] * Math.exp(-d2 / (2 * b[2] * b[2]));
                }
                h[r * WIDTH + c] = (float) v;
            }
        }
        for (int r = 20; r < 40; r++) {
            for (int c = 30; c < 60; c++) {
                h[r * WIDTH + c] = Float.NaN;
            }
        }
        return new TerrainGrid(h, WIDTH, HEIGHT, 0, 0, 30.0 * (WIDTH - 1), 30.0 * (HEIGHT - 1), 1.0);
    }

    private static byte[] drawTerrain(Target target, TerrainGrid grid, ColorRamp.Baked ramp, double azimuth, double altitude, double exaggeration, double strength, GraphicsCapabilities caps) {
        int program = Gl.program(TerrainShader.vertexSource(caps), TerrainShader.fragmentSource(caps));
        int vao = glGenVertexArrays();
        int heights = glGenTextures(), rampTexture = glGenTextures();
        try {
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, heights);
            java.nio.FloatBuffer hb = BufferUtils.createFloatBuffer(grid.width() * grid.height());
            hb.put(grid.heights(), 0, grid.width() * grid.height()).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, grid.width(), grid.height(), 0, GL_RED, GL_FLOAT, hb);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glActiveTexture(GL_TEXTURE0 + 1);
            glBindTexture(GL_TEXTURE_2D, rampTexture);
            byte[] row = new byte[4 * ramp.texels()];
            ramp.writeRgba(row);
            ByteBuffer rb = BufferUtils.createByteBuffer(row.length);
            rb.put(row).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ramp.texels(), 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, rb);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glUniform1i(glGetUniformLocation(program, TerrainShader.U_HEIGHTS), 0);
            glUniform1i(glGetUniformLocation(program, TerrainShader.U_RAMP), 1);
            glUniform2f(glGetUniformLocation(program, TerrainShader.U_RAMP_RANGE), ramp.min(), ramp.max());
            glUniform1i(glGetUniformLocation(program, TerrainShader.U_RAMP_TEXELS), ramp.texels());
            double[] sun = Hillshade.sunVector(azimuth, altitude);
            glUniform3f(glGetUniformLocation(program, TerrainShader.U_SUN), (float) sun[0], (float) sun[1], (float) sun[2]);
            double[] cell = TerrainShader.cell(grid);
            glUniform2f(glGetUniformLocation(program, TerrainShader.U_CELL), (float) cell[0], (float) cell[1]);
            glUniform1f(glGetUniformLocation(program, TerrainShader.U_EXAGGERATION), (float) exaggeration);
            glUniform1f(glGetUniformLocation(program, TerrainShader.U_STRENGTH), (float) strength);
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
            glViewport(0, 0, WIDTH, HEIGHT);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            Gl.check("drawing the terrain");
            byte[] bottomUp = target.read();
            byte[] topDown = new byte[bottomUp.length];
            for (int r = 0; r < HEIGHT; r++) {
                System.arraycopy(bottomUp, 4 * WIDTH * (HEIGHT - 1 - r), topDown, 4 * WIDTH * r, 4 * WIDTH);
            }
            return topDown;
        } finally {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            glDeleteTextures(heights);
            glDeleteTextures(rampTexture);
        }
    }

    /**
     * Compares the terrain shader with {@link TerrainShading} on every GLSL version, for a ramp that
     * blends and a stepped one relative to a reference altitude.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per version and ramp
     */
    public static List<Outcome> checkTerrain(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        int major = glGetInteger(GL_MAJOR_VERSION), minor = glGetInteger(GL_MINOR_VERSION);
        TerrainGrid grid = terrain();
        ColorRamp.Baked blend = ColorRamp.linear(new double[] {0, 300, 700, 1200}, new int[] {0x204020FF, 0x80A040FF, 0xB09060FF, 0xFFFFFFFF}).bake(0f, 1300f, 256);
        ColorRamp.Baked relative = ColorRamp.relativeSteps(600.0, new double[] {-300.0, 0.0, 200.0}, new int[] {0x00000000, 0x40C040FF, 0xE0C000FF, 0xE00000FF}).bake(0f, 1300f, 256);
        double azimuth = Math.toRadians(315), altitude = Math.toRadians(40);
        int[][] versions = {{3, 3}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {4, 5}, {4, 6}};
        try (Target target = new Target()) {
            for (int[] v : versions) {
                if (v[0] > major || v[0] == major && v[1] > minor) {
                    continue;
                }
                GraphicsCapabilities caps = GraphicsCapabilities.openGl(v[0], v[1], List.of());
                for (int k = 0; k < 2; k++) {
                    ColorRamp.Baked ramp = k == 0 ? blend : relative;
                    double strength = k == 0 ? 0.7 : 1.0, exaggeration = k == 0 ? 1.0 : 3.0;
                    String name = "GL " + v[0] + "." + v[1] + " (GLSL " + caps.glsl().number() + ") terrain " + (k == 0 ? "blended ramp" : "relative stepped ramp, exaggeration 3");
                    String problem;
                    try {
                        byte[] expected = new byte[4 * WIDTH * HEIGHT];
                        TerrainShading.render(grid, ramp, azimuth, altitude, exaggeration, strength, expected);
                        problem = compareTerrain(drawTerrain(target, grid, ramp, azimuth, altitude, exaggeration, strength, caps), expected);
                    } catch (RuntimeException e) {
                        problem = e.getMessage();
                    }
                    Outcome o = new Outcome(name, problem == null, problem == null ? "same pixels as the CPU shading" : problem);
                    results.add(o);
                    if (out != null) {
                        out.println((o.ok() ? "ok    " : "FAIL  ") + o.name() + (o.ok() ? "" : "\n      " + o.detail()));
                    }
                }
            }
            // the comparison must be able to fail: another light is not accepted
            GraphicsCapabilities newest = GraphicsCapabilities.openGl(major, minor, driverExtensions());
            byte[] other = new byte[4 * WIDTH * HEIGHT];
            TerrainShading.render(grid, blend, Math.toRadians(135), altitude, 1.0, 0.7, other);
            String confused = compareTerrain(drawTerrain(target, grid, blend, azimuth, altitude, 1.0, 0.7, newest), other);
            Outcome sensitivity = new Outcome("the terrain comparison rejects another light", confused != null, confused == null ? "it accepted them" : "rejected: " + confused);
            results.add(sensitivity);
            if (out != null) {
                out.println((sensitivity.ok() ? "ok    " : "FAIL  ") + sensitivity.name());
            }
        }
        return results;
    }

    /** Pixels may differ by 3 in a channel, and a few in a thousand may differ more (a height on the edge of a ramp texel). */
    private static String compareTerrain(byte[] gpu, byte[] expected) {
        int bad = 0;
        String first = null;
        for (int i = 0; i < expected.length; i += 4) {
            boolean differs = false;
            for (int k = 0; k < 4; k++) {
                differs |= Math.abs((gpu[i + k] & 255) - (expected[i + k] & 255)) > 3;
            }
            if (differs) {
                bad++;
                if (first == null) {
                    first = "pixel " + (i / 4 % WIDTH) + "," + (i / 4 / WIDTH) + " is " + (gpu[i] & 255) + "," + (gpu[i + 1] & 255) + "," + (gpu[i + 2] & 255) + "," + (gpu[i + 3] & 255)
                            + ", the CPU says " + (expected[i] & 255) + "," + (expected[i + 1] & 255) + "," + (expected[i + 2] & 255) + "," + (expected[i + 3] & 255);
                }
            }
        }
        if (bad > WIDTH * HEIGHT / 500) {
            return bad + " of " + WIDTH * HEIGHT + " pixels differ; first: " + first;
        }
        return null;
    }

    /**
     * Runs every check of the map layer on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return all outcomes
     */
    public static List<Outcome> checkAll(PrintStream out) {
        List<Outcome> all = new ArrayList<>(checkSymbols(out));
        all.addAll(checkAreas(out));
        all.addAll(checkTerrain(out));
        return all;
    }

    /**
     * Runs the check from the command line; exits with 1 if a case fails, 2 if there is no driver.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        long window = LineGpuCheck.openContext();
        if (window == 0) {
            System.err.println("no OpenGL 4.3 or newer context could be opened");
            System.exit(2);
        }
        try {
            System.out.println("driver: " + LineGpuCheck.driver());
            List<Outcome> results = checkAll(System.out);
            long failed = results.stream().filter(o -> !o.ok()).count();
            System.out.println(results.size() - failed + " of " + results.size() + " cases agree with the models");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            LineGpuCheck.closeContext(window);
        }
    }
}
