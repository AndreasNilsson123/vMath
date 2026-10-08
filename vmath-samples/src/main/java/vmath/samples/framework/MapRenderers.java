package vmath.samples.framework;

import static org.lwjgl.opengl.GL46.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.IntUnaryOperator;
import org.lwjgl.BufferUtils;
import vmath.gl.DrawList;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.StructArrayAccess;
import vmath.gl.VertexBufferLayout;
import vmath.map.AreaBatch;
import vmath.map.AreaRenderPlan;
import vmath.map.ColorRamp;
import vmath.map.Hillshade;
import vmath.map.SymbolAtlas;
import vmath.map.SymbolBatch;
import vmath.map.SymbolRenderPlan;
import vmath.map.SymbolStrategy;
import vmath.map.TerrainGrid;
import vmath.map.TerrainShader;

/**
 * The OpenGL side of the map layer for the demos: issue what the plans of {@code vmath.map} write.
 * {@link Symbols} draws a {@code SymbolBatch} with any symbol strategy, {@link Areas} an
 * {@code AreaBatch}, {@link Quads} textured rectangles in projected coordinates (map tiles, terrain
 * images, masks), and {@link GpuTerrain} colours a height grid with the GLSL of
 * {@code TerrainShader}. Blending is switched on for the draw and off again.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe; OpenGL calls are only valid on the thread that owns the context.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MapRenderers.Quads quads = new MapRenderers.Quads();
 * int texture = MapRenderers.texture(256, 256, rgba);
 * quads.draw(texture, relativeMinX, relativeMinY, relativeMaxX, relativeMaxY, viewProjection, 1f, 1f, 1f, 1f);
 * }</pre>
 */
public final class MapRenderers {

    private MapRenderers() {
    }

    /**
     * Makes an RGBA texture.
     *
     * @param width the width in texels
     * @param height the height in texels
     * @param rgba {@code 4 * width * height} bytes, row 0 first
     * @return the texture name; delete it with {@code glDeleteTextures}
     */
    public static int texture(int width, int height, byte[] rgba) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        ByteBuffer b = BufferUtils.createByteBuffer(rgba.length);
        b.put(rgba).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return t;
    }

    /**
     * Replaces the pixels of a texture of the same size.
     *
     * @param texture the texture from {@link #texture}
     * @param width the width in texels
     * @param height the height in texels
     * @param rgba {@code 4 * width * height} bytes
     */
    public static void update(int texture, int width, int height, byte[] rgba) {
        glBindTexture(GL_TEXTURE_2D, texture);
        ByteBuffer b = BufferUtils.createByteBuffer(rgba.length);
        b.put(rgba).flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, b);
    }

    private static void blendOn() {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
    }

    /** Draws a {@code SymbolBatch} with the strategy of a {@code SymbolRenderPlan}. */
    public static final class Symbols implements AutoCloseable {
        private final SymbolRenderPlan plan;
        private final Arena arena = Arena.ofShared();
        private final MemorySegment mirror;
        private final ByteBuffer view;
        private final DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
        private final int program;
        private final int vao = glGenVertexArrays();
        private final int buffer = glGenBuffers();
        private final int bufferTexture;
        private final int atlasTexture;
        private final long capacityBytes;
        private int draws0;
        private long used;

        /**
         * Creates the renderer.
         *
         * @param plan the plan; must not be {@code null}
         * @param atlas the atlas whose pixels are the sprites; must not be {@code null}
         * @param capacitySymbols the most symbols a batch will have, at least 1
         * @throws IllegalStateException if the shaders do not compile
         */
        public Symbols(SymbolRenderPlan plan, SymbolAtlas atlas, int capacitySymbols) {
            this.plan = plan;
            capacityBytes = Math.max(16L, capacitySymbols * vmath.map.SymbolGpu.BYTES * (plan.strategy() == SymbolStrategy.EXPANDED ? SymbolRenderPlan.VERTICES_PER_SYMBOL : 1));
            mirror = arena.allocate(capacityBytes, 16);
            view = mirror.asByteBuffer();
            program = Gl.program(plan.vertexShader(), plan.fragmentShader());
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            glBufferData(GL_ARRAY_BUFFER, capacityBytes, GL_DYNAMIC_DRAW);
            StructArrayAccess access = plan.symbolAccess();
            if (access.mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                bufferTexture = glGenTextures();
                glActiveTexture(GL_TEXTURE0 + SymbolRenderPlan.SYMBOL_SLOT);
                glBindTexture(GL_TEXTURE_BUFFER, bufferTexture);
                glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, buffer);
                if (!access.hasExplicitBinding()) {
                    glUniform1i(glGetUniformLocation(program, access.name() + "_texels"), SymbolRenderPlan.SYMBOL_SLOT);
                }
            } else {
                bufferTexture = 0;
            }
            glActiveTexture(GL_TEXTURE0 + 1);
            atlasTexture = texture(atlas.width(), atlas.height(), atlas.pixels());
            glUniform1i(glGetUniformLocation(program, SymbolRenderPlan.U_ATLAS), 1);
            Gl.check("creating the symbol renderer");
        }

        /**
         * Gives the plan.
         *
         * @return the plan
         */
        public SymbolRenderPlan plan() {
            return plan;
        }

        /**
         * Writes the batch with the plan and uploads it.
         *
         * @param batch the symbols; at most the capacity
         * @param originX the projected x of the origin of the buffer
         * @param originY the projected y of the origin
         * @param colorMap a display palette; may be {@code null}
         * @return the number of bytes uploaded
         */
        public long upload(SymbolBatch batch, double originX, double originY, IntUnaryOperator colorMap) {
            draws0 = plan.write(batch, originX, originY, mirror, draws, colorMap);
            used = plan.dataBytes(batch);
            if (used > 0) {
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
                view.clear().limit((int) used);
                glBufferSubData(GL_ARRAY_BUFFER, 0, view);
            }
            return used;
        }

        /**
         * Gives the number of draw calls a frame makes.
         *
         * @return 0 or 1
         */
        public int calls() {
            return draws0;
        }

        /**
         * Draws the uploaded symbols.
         *
         * @param viewProjection the matrix relative to the origin of the upload
         * @param width the viewport width
         * @param height the viewport height
         * @param mapRotation the {@code u_mapRotation} uniform
         * @param pixelsPerUnit the {@code u_pixelsPerUnit} uniform
         */
        public void draw(float[] viewProjection, int width, int height, float mapRotation, float pixelsPerUnit) {
            if (draws0 == 0) {
                return;
            }
            blendOn();
            glUseProgram(program);
            glBindVertexArray(vao);
            glUniformMatrix4fv(glGetUniformLocation(program, SymbolRenderPlan.U_VIEW_PROJECTION), false, viewProjection);
            glUniform2f(glGetUniformLocation(program, SymbolRenderPlan.U_VIEWPORT), width, height);
            glUniform1f(glGetUniformLocation(program, SymbolRenderPlan.U_MAP_ROTATION), mapRotation);
            glUniform1f(glGetUniformLocation(program, SymbolRenderPlan.U_PIXELS_PER_UNIT), pixelsPerUnit);
            glUniform1f(glGetUniformLocation(program, SymbolRenderPlan.U_HANDEDNESS), 1f);
            if (bufferTexture != 0) {
                glActiveTexture(GL_TEXTURE0 + SymbolRenderPlan.SYMBOL_SLOT);
                glBindTexture(GL_TEXTURE_BUFFER, bufferTexture);
            } else {
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
                VertexBufferLayout layout = plan.symbolAccess().vertexLayout();
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
            if (plan.strategy() == SymbolStrategy.INSTANCED) {
                glDrawArraysInstanced(GL_TRIANGLES, draws.first(0), draws.count(0), draws.instanceCount(0));
            } else {
                glDrawArrays(GL_TRIANGLES, draws.first(0), draws.count(0));
            }
            glDisable(GL_BLEND);
        }

        @Override
        public void close() {
            glDeleteProgram(program);
            glDeleteBuffers(buffer);
            glDeleteVertexArrays(vao);
            glDeleteTextures(atlasTexture);
            if (bufferTexture != 0) {
                glDeleteTextures(bufferTexture);
            }
            arena.close();
        }
    }

    /** Draws an {@code AreaBatch} with the strategy of an {@code AreaRenderPlan}, as one indexed draw per run. */
    public static final class Areas implements AutoCloseable {
        private final AreaRenderPlan plan;
        private final Arena arena = Arena.ofShared();
        private final MemorySegment vertices;
        private final MemorySegment indices;
        private final MemorySegment styles;
        private final DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 64);
        private final int program;
        private final int vao = glGenVertexArrays();
        private final int vertexBuffer = glGenBuffers();
        private final int indexBuffer = glGenBuffers();
        private final int styleBuffer = glGenBuffers();
        private final int styleTexture;
        private final long vertexCapacity;
        private final long indexCapacity;
        private final long styleCapacity;
        private int count;

        /**
         * Creates the renderer.
         *
         * @param plan the plan; must not be {@code null}
         * @param maxVertices the most vertices a batch will have
         * @param maxTriangles the most triangles a batch will have
         * @throws IllegalStateException if the shaders do not compile
         */
        public Areas(AreaRenderPlan plan, int maxVertices, int maxTriangles) {
            this.plan = plan;
            vertexCapacity = Math.max(16L, (long) maxVertices * AreaRenderPlan.VERTEX_BYTES);
            indexCapacity = Math.max(16L, 12L * maxTriangles);
            styleCapacity = Math.max(16L, AreaRenderPlan.STYLE_TABLE_UNIFORM_LENGTH * AreaRenderPlan.STYLE_BYTES);
            vertices = arena.allocate(vertexCapacity, 16);
            indices = arena.allocate(indexCapacity, 16);
            styles = arena.allocate(styleCapacity, 16);
            program = Gl.program(plan.vertexShader(), plan.fragmentShader());
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertexCapacity, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexCapacity, GL_DYNAMIC_DRAW);
            StructArrayAccess sa = plan.styleAccess();
            int tex = 0;
            if (sa != null) {
                int target = target(sa.mode());
                glBindBuffer(target, styleBuffer);
                glBufferData(target, styleCapacity, GL_DYNAMIC_DRAW);
                switch (sa.mode()) {
                    case STORAGE_BLOCK, UNIFORM_BLOCK -> {
                        glBindBufferBase(target, AreaRenderPlan.STYLE_SLOT, styleBuffer);
                        if (sa.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK && !sa.hasExplicitBinding()) {
                            glUniformBlockBinding(program, glGetUniformBlockIndex(program, sa.name() + "_Block"), AreaRenderPlan.STYLE_SLOT);
                        }
                    }
                    case TEXTURE_BUFFER -> {
                        tex = glGenTextures();
                        glActiveTexture(GL_TEXTURE0 + AreaRenderPlan.STYLE_SLOT);
                        glBindTexture(GL_TEXTURE_BUFFER, tex);
                        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, styleBuffer);
                        if (!sa.hasExplicitBinding()) {
                            glUniform1i(glGetUniformLocation(program, sa.name() + "_texels"), AreaRenderPlan.STYLE_SLOT);
                        }
                    }
                    default -> throw new IllegalStateException("styles cannot be " + sa.mode());
                }
            }
            styleTexture = tex;
            Gl.check("creating the area renderer");
        }

        private static int target(StructArrayAccess.Mode m) {
            return m == StructArrayAccess.Mode.STORAGE_BLOCK ? GL_SHADER_STORAGE_BUFFER : m == StructArrayAccess.Mode.UNIFORM_BLOCK ? GL_UNIFORM_BUFFER : GL_ARRAY_BUFFER;
        }

        /**
         * Writes the batch with the plan and uploads it.
         *
         * @param batch the polygons; within the capacities
         * @param originX the projected x of the origin of the buffers
         * @param originY the projected y of the origin
         * @param colorMap a display palette; may be {@code null}
         * @return the number of draws
         */
        public int upload(AreaBatch batch, double originX, double originY, IntUnaryOperator colorMap) {
            count = plan.write(batch, originX, originY, vertices, indices, styles, draws, colorMap);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            ByteBuffer vb = vertices.asByteBuffer();
            vb.limit((int) plan.vertexBytes(batch));
            glBufferSubData(GL_ARRAY_BUFFER, 0, vb);
            glBindVertexArray(vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            ByteBuffer ib = indices.asByteBuffer();
            ib.limit((int) plan.indexBytes(batch));
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, ib);
            if (plan.styleAccess() != null) {
                int t = target(plan.styleAccess().mode());
                glBindBuffer(t, styleBuffer);
                ByteBuffer sb = styles.asByteBuffer();
                sb.limit((int) styleCapacity);
                glBufferSubData(t, 0, sb);
            }
            return count;
        }

        /**
         * Draws the uploaded polygons.
         *
         * @param viewProjection the matrix relative to the origin of the upload
         * @param patternOffsetX the first value of {@code u_patternOffset}
         * @param patternOffsetY the second value
         */
        public void draw(float[] viewProjection, float patternOffsetX, float patternOffsetY) {
            if (count == 0) {
                return;
            }
            blendOn();
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            VertexBufferLayout layout = plan.vertexLayout();
            for (VertexBufferLayout.GlFormat f : layout.glFormats()) {
                glEnableVertexAttribArray(f.location());
                if (f.integer()) {
                    glVertexAttribIPointer(f.location(), f.size(), f.type(), layout.stride(), f.relativeOffset());
                } else {
                    glVertexAttribPointer(f.location(), f.size(), f.type(), f.normalized(), layout.stride(), f.relativeOffset());
                }
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            glUniformMatrix4fv(glGetUniformLocation(program, AreaRenderPlan.U_VIEW_PROJECTION), false, viewProjection);
            glUniform2f(glGetUniformLocation(program, AreaRenderPlan.U_PATTERN_OFFSET), patternOffsetX, patternOffsetY);
            if (plan.styleAccess() != null) {
                StructArrayAccess.Mode m = plan.styleAccess().mode();
                if (m == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                    glActiveTexture(GL_TEXTURE0 + AreaRenderPlan.STYLE_SLOT);
                    glBindTexture(GL_TEXTURE_BUFFER, styleTexture);
                } else {
                    glBindBufferBase(target(m), AreaRenderPlan.STYLE_SLOT, styleBuffer);
                }
            }
            for (int i = 0; i < draws.size(); i++) {
                glDrawElements(GL_TRIANGLES, draws.count(i), GL_UNSIGNED_INT, 4L * draws.first(i));
            }
            glDisable(GL_BLEND);
        }

        @Override
        public void close() {
            glDeleteProgram(program);
            glDeleteBuffers(vertexBuffer);
            glDeleteBuffers(indexBuffer);
            glDeleteBuffers(styleBuffer);
            glDeleteVertexArrays(vao);
            if (styleTexture != 0) {
                glDeleteTextures(styleTexture);
            }
            arena.close();
        }
    }

    /** Draws a texture on a rectangle given in projected coordinates relative to the origin of the matrix. */
    public static final class Quads implements AutoCloseable {
        private final int program;
        private final int vao = glGenVertexArrays();

        /** Creates the renderer. */
        public Quads() {
            String v = "#version 330\nuniform mat4 u_viewProjection;\nuniform vec4 u_rect;\nuniform float u_flipV;\nout vec2 v_uv;\nvoid main() {\n    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));\n"
                    + "    gl_Position = u_viewProjection * vec4(mix(u_rect.xy, u_rect.zw, c), 0.0, 1.0);\n    v_uv = vec2(c.x, mix(1.0 - c.y, c.y, u_flipV));\n}\n";
            String f = "#version 330\nuniform sampler2D u_tex;\nuniform vec4 u_tint;\nin vec2 v_uv;\nout vec4 o_color;\nvoid main() {\n    o_color = texture(u_tex, v_uv) * u_tint;\n}\n";
            program = Gl.program(v, f);
        }

        /**
         * Draws a texture.
         *
         * @param texture the texture; row 0 is the north (maximum y) edge
         * @param minX the west edge relative to the origin
         * @param minY the south edge
         * @param maxX the east edge
         * @param maxY the north edge
         * @param viewProjection the matrix relative to the same origin
         * @param red the tint, multiplied with the texel
         * @param green the tint
         * @param blue the tint
         * @param alpha the tint
         */
        public void draw(int texture, float minX, float minY, float maxX, float maxY, float[] viewProjection, float red, float green, float blue, float alpha) {
            draw(texture, minX, minY, maxX, maxY, viewProjection, red, green, blue, alpha, false);
        }

        /**
         * Draws a texture whose row 0 may be the south edge (the output of {@link GpuTerrain}).
         *
         * @param texture the texture
         * @param minX the west edge relative to the origin
         * @param minY the south edge
         * @param maxX the east edge
         * @param maxY the north edge
         * @param viewProjection the matrix relative to the same origin
         * @param red the tint
         * @param green the tint
         * @param blue the tint
         * @param alpha the tint
         * @param rowZeroIsSouth {@code true} if the first row of the texture is the south edge
         */
        public void draw(int texture, float minX, float minY, float maxX, float maxY, float[] viewProjection, float red, float green, float blue, float alpha, boolean rowZeroIsSouth) {
            blendOn();
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glUniform1i(glGetUniformLocation(program, "u_tex"), 0);
            glUniformMatrix4fv(glGetUniformLocation(program, "u_viewProjection"), false, viewProjection);
            glUniform4f(glGetUniformLocation(program, "u_rect"), minX, minY, maxX, maxY);
            glUniform4f(glGetUniformLocation(program, "u_tint"), red, green, blue, alpha);
            glUniform1f(glGetUniformLocation(program, "u_flipV"), rowZeroIsSouth ? 1f : 0f);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glDisable(GL_BLEND);
        }

        @Override
        public void close() {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
        }
    }

    /** Colours a height grid on the GPU with the GLSL of {@code TerrainShader} into an RGBA texture of the grid's size. */
    public static final class GpuTerrain implements AutoCloseable {
        private final TerrainGrid grid;
        private final int program;
        private final int vao = glGenVertexArrays();
        private final int heights = glGenTextures();
        private final int rampTexture = glGenTextures();
        private final int framebuffer = glGenFramebuffers();
        private final int output;

        /**
         * Creates the renderer and uploads the heights.
         *
         * @param grid the grid; its heights are uploaded once
         * @param caps the capabilities that choose the GLSL version
         * @throws IllegalStateException if the shaders do not compile
         */
        public GpuTerrain(TerrainGrid grid, GraphicsCapabilities caps) {
            this.grid = grid;
            program = Gl.program(TerrainShader.vertexSource(caps), TerrainShader.fragmentSource(caps));
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, heights);
            FloatBuffer hb = BufferUtils.createFloatBuffer(grid.width() * grid.height());
            hb.put(grid.heights(), 0, grid.width() * grid.height()).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, grid.width(), grid.height(), 0, GL_RED, GL_FLOAT, hb);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            output = texture(grid.width(), grid.height(), new byte[4 * grid.width() * grid.height()]);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            Gl.check("creating the GPU terrain");
        }

        /**
         * Gives the texture that {@link #render} fills.
         *
         * @return the texture name; its first row is the south row, so draw it with {@code rowZeroIsSouth}
         */
        public int output() {
            return output;
        }

        /**
         * Colours the grid into the output texture.
         *
         * @param ramp the baked ramp
         * @param azimuth the direction the light comes from, radians clockwise from north
         * @param altitude the height of the light, radians
         * @param exaggeration the vertical exaggeration
         * @param strength how much the shade darkens, 0 to 1
         */
        public void render(ColorRamp.Baked ramp, double azimuth, double altitude, double exaggeration, double strength) {
            int[] oldViewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, oldViewport);
            int oldFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, heights);
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
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, grid.width(), grid.height());
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_FRAMEBUFFER, oldFramebuffer);
            glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            Gl.check("colouring the terrain on the GPU");
        }

        @Override
        public void close() {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            glDeleteTextures(heights);
            glDeleteTextures(rampTexture);
            glDeleteTextures(output);
            glDeleteFramebuffers(framebuffer);
        }
    }
}
