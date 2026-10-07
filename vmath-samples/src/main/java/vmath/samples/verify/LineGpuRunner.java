package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL46.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL46.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL46.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL46.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46.GL_LINE_STRIP;
import static org.lwjgl.opengl.GL46.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL46.GL_RGBA;
import static org.lwjgl.opengl.GL46.GL_RGBA32UI;
import static org.lwjgl.opengl.GL46.GL_RGBA8;
import static org.lwjgl.opengl.GL46.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL46.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL46.GL_TEXTURE0;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL46.GL_TRIANGLES;
import static org.lwjgl.opengl.GL46.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL46.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL46.glActiveTexture;
import static org.lwjgl.opengl.GL46.glBindBuffer;
import static org.lwjgl.opengl.GL46.glBindBufferBase;
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
import static org.lwjgl.opengl.GL46.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL46.glDrawArraysInstancedBaseInstance;
import static org.lwjgl.opengl.GL46.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL46.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL46.glGenBuffers;
import static org.lwjgl.opengl.GL46.glGenFramebuffers;
import static org.lwjgl.opengl.GL46.glGenRenderbuffers;
import static org.lwjgl.opengl.GL46.glGenTextures;
import static org.lwjgl.opengl.GL46.glGenVertexArrays;
import static org.lwjgl.opengl.GL46.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL46.glGetUniformLocation;
import static org.lwjgl.opengl.GL46.glMultiDrawArrays;
import static org.lwjgl.opengl.GL46.glMultiDrawArraysIndirect;
import static org.lwjgl.opengl.GL46.glReadPixels;
import static org.lwjgl.opengl.GL46.glRenderbufferStorage;
import static org.lwjgl.opengl.GL46.glTexBuffer;
import static org.lwjgl.opengl.GL46.glUniform1f;
import static org.lwjgl.opengl.GL46.glUniform1i;
import static org.lwjgl.opengl.GL46.glUniform2f;
import static org.lwjgl.opengl.GL46.glUniformBlockBinding;
import static org.lwjgl.opengl.GL46.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL46.glUseProgram;
import static org.lwjgl.opengl.GL46.glVertexAttribDivisor;
import static org.lwjgl.opengl.GL46.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL46.glVertexAttribPointer;
import static org.lwjgl.opengl.GL46.glViewport;
import static org.lwjgl.opengl.GL46.GL_BLEND;
import static org.lwjgl.opengl.GL46.GL_CULL_FACE;
import static org.lwjgl.opengl.GL46.GL_DEPTH_TEST;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.StructArrayAccess;
import vmath.gl.VertexBufferLayout;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineStrategy;
import vmath.samples.framework.Gl;

/**
 * Issues the buffers, the draws and the shaders of a {@link LineRenderPlan} on a real OpenGL
 * context, the way {@code docs/LINES.md} says to: it binds each array in the form the strategy
 * chose, submits the draws in the form {@link DrawSubmission} chose, and reads the pixels back.
 *
 * <p>This is what the line strategies were not tested with before there was a driver: the CPU
 * model of {@code vmath.lines} reads the same buffers, and this class shows that the GLSL does
 * the same. It does not use anything that the capabilities of the plan do not promise, except
 * that the context is a newer one than the text asks for.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe; OpenGL calls are only valid on the thread that owns
 * the context.
 */
final class LineGpuRunner implements AutoCloseable {

    private final int width;
    private final int height;
    private final int framebuffer;
    private final int renderbuffer;
    private final int vao;

    LineGpuRunner(int width, int height) {
        this.width = width;
        this.height = height;
        renderbuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffer);
        vao = glGenVertexArrays();
        Gl.check("creating the target");
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteFramebuffers(framebuffer);
        glDeleteRenderbuffers(renderbuffer);
    }

    /** Everything one plan needs on the GPU for one set of draws. */
    final class Prepared implements AutoCloseable {
        private final LineRenderPlan plan;
        private final GraphicsCapabilities caps;
        private final DrawList draws;
        private final Arena arena = Arena.ofConfined();
        private final int program;
        private final int dataBuffer;
        private final int styleBuffer;
        private int dataTexture;
        private int styleTexture;
        private int commandBuffer;
        private IntBuffer firsts;
        private IntBuffer counts;
        /** How the draws are submitted. */
        final DrawSubmission submission;
        private final int uViewProjection;
        private final int uViewport;
        private final int uWorldToPixel;
        private final int mode;
        /** The number of draw calls one frame makes. */
        final int calls;

        Prepared(LineRenderPlan plan, GraphicsCapabilities caps, MemorySegment data, long dataBytes, MemorySegment styles, long styleBytes, DrawList draws) {
            this.plan = plan;
            this.caps = caps;
            this.draws = draws;
            this.mode = plan.primitive() == LineRenderPlan.Primitive.TRIANGLES ? GL_TRIANGLES : GL_LINE_STRIP;
            program = Gl.program(plan.vertexShader(), plan.fragmentShader());
            glUseProgram(program);
            uViewProjection = glGetUniformLocation(program, LineRenderPlan.U_VIEW_PROJECTION);
            uViewport = glGetUniformLocation(program, LineRenderPlan.U_VIEWPORT);
            uWorldToPixel = glGetUniformLocation(program, LineRenderPlan.U_WORLD_TO_PIXEL);

            dataBuffer = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, dataBuffer);
            glBufferData(GL_ARRAY_BUFFER, direct(data, Math.max(dataBytes, 16)), GL_STATIC_DRAW);
            styleBuffer = glGenBuffers();
            if (plan.strategy() != LineStrategy.HAIRLINE) {
                StructArrayAccess seg = plan.segmentAccess(), sty = plan.styleAccess();
                if (seg.mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                    dataTexture = glGenTextures();
                    glBindTexture(GL_TEXTURE_BUFFER, dataTexture);
                    glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, dataBuffer);
                    if (!seg.hasExplicitBinding()) {
                        glUniform1i(glGetUniformLocation(program, seg.name() + "_texels"), LineRenderPlan.SEGMENT_SLOT);
                    }
                }
                int target = sty.mode() == StructArrayAccess.Mode.STORAGE_BLOCK ? GL_SHADER_STORAGE_BUFFER : sty.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK ? GL_UNIFORM_BUFFER : GL_ARRAY_BUFFER;
                glBindBuffer(target, styleBuffer);
                long size = Math.max(styleBytes, sty.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK ? LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH * 64L : 16);
                glBufferData(target, direct(styles, size), GL_STATIC_DRAW);
                switch (sty.mode()) {
                    case STORAGE_BLOCK, UNIFORM_BLOCK -> {
                        glBindBufferBase(target, LineRenderPlan.STYLE_SLOT, styleBuffer);
                        if (sty.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK && !sty.hasExplicitBinding()) {
                            glUniformBlockBinding(program, glGetUniformBlockIndex(program, sty.name() + "_Block"), LineRenderPlan.STYLE_SLOT);
                        }
                    }
                    case TEXTURE_BUFFER -> {
                        styleTexture = glGenTextures();
                        glBindTexture(GL_TEXTURE_BUFFER, styleTexture);
                        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, styleBuffer);
                        if (!sty.hasExplicitBinding()) {
                            glUniform1i(glGetUniformLocation(program, sty.name() + "_texels"), LineRenderPlan.STYLE_SLOT);
                        }
                    }
                    default -> throw new IllegalStateException("styles cannot be " + sty.mode());
                }
            }
            submission = plan.submission(draws);
            if (submission == DrawSubmission.MULTI_DRAW_INDIRECT) {
                MemorySegment commands = arena.allocate(Math.max(16L, (long) draws.size() * DrawCommandBuffer.Kind.ARRAYS.bytes()));
                DrawCommandBuffer buffer = new DrawCommandBuffer(commands, DrawCommandBuffer.Kind.ARRAYS, false);
                draws.writeIndirect(buffer);
                commandBuffer = glGenBuffers();
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
                glBufferData(GL_DRAW_INDIRECT_BUFFER, commands.asByteBuffer(), GL_STATIC_DRAW);
                calls = 1;
            } else if (submission == DrawSubmission.MULTI_DRAW_CLIENT) {
                int[] f = new int[draws.size()], c = new int[draws.size()];
                int n = draws.copyFirsts(f);
                draws.copyCounts(c);
                firsts = BufferUtils.createIntBuffer(Math.max(n, 1)).put(f, 0, n).flip();
                counts = BufferUtils.createIntBuffer(Math.max(n, 1)).put(c, 0, n).flip();
                calls = 1;
            } else {
                calls = draws.size();
            }
            Gl.check("preparing " + plan.strategy());
        }

        private ByteBuffer direct(MemorySegment s, long size) {
            MemorySegment copy = arena.allocate(size);
            MemorySegment.copy(s, 0, copy, 0, Math.min(s.byteSize(), size));
            return copy.asByteBuffer();
        }

        private void bindAttributes(long byteOffset) {
            VertexBufferLayout layout = plan.strategy() == LineStrategy.HAIRLINE ? plan.hairlineLayout() : plan.segmentAccess().vertexLayout();
            glBindBuffer(GL_ARRAY_BUFFER, dataBuffer);
            for (VertexBufferLayout.GlFormat f : layout.glFormats()) {
                glEnableVertexAttribArray(f.location());
                if (f.integer()) {
                    glVertexAttribIPointer(f.location(), f.size(), f.type(), layout.stride(), byteOffset + f.relativeOffset());
                } else {
                    glVertexAttribPointer(f.location(), f.size(), f.type(), f.normalized(), layout.stride(), byteOffset + f.relativeOffset());
                }
                glVertexAttribDivisor(f.location(), layout.perInstance() ? 1 : 0);
            }
        }

        /** Draws one frame into the target, which is cleared first. */
        void draw(float[] viewProjection, float worldToPixel) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, width, height);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(program);
            glUniformMatrix4fv(uViewProjection, false, viewProjection);
            if (plan.strategy() != LineStrategy.HAIRLINE) {
                glUniform2f(uViewport, width, height);
                glUniform1f(uWorldToPixel, worldToPixel);
                if (plan.segmentAccess().mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                    glActiveTexture(GL_TEXTURE0 + LineRenderPlan.SEGMENT_SLOT);
                    glBindTexture(GL_TEXTURE_BUFFER, dataTexture);
                }
                if (plan.styleAccess().mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                    glActiveTexture(GL_TEXTURE0 + LineRenderPlan.STYLE_SLOT);
                    glBindTexture(GL_TEXTURE_BUFFER, styleTexture);
                }
                if (plan.styleAccess().mode() == StructArrayAccess.Mode.STORAGE_BLOCK) {
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, LineRenderPlan.STYLE_SLOT, styleBuffer);
                } else if (plan.styleAccess().mode() == StructArrayAccess.Mode.UNIFORM_BLOCK) {
                    glBindBufferBase(GL_UNIFORM_BUFFER, LineRenderPlan.STYLE_SLOT, styleBuffer);
                }
            }
            glBindVertexArray(vao);
            boolean attributes = plan.strategy() == LineStrategy.HAIRLINE || plan.segmentAccess().mode() == StructArrayAccess.Mode.VERTEX_ATTRIBUTE;
            if (attributes) {
                bindAttributes(0);
            }
            switch (submission) {
                case MULTI_DRAW_INDIRECT -> {
                    glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
                    glMultiDrawArraysIndirect(mode, 0L, draws.size(), 0);
                }
                case MULTI_DRAW_CLIENT -> glMultiDrawArrays(mode, firsts, counts);
                case DRAW_LOOP -> {
                    long stride = attributes && plan.strategy() != LineStrategy.HAIRLINE ? plan.segmentAccess().vertexLayout().stride() : 0;
                    for (int i = 0; i < draws.size(); i++) {
                        if (draws.instanceCount(i) == 0) {
                            continue;
                        }
                        if (caps.has(GraphicsCapabilities.Feature.BASE_INSTANCE)) {
                            glDrawArraysInstancedBaseInstance(mode, draws.first(i), draws.count(i), draws.instanceCount(i), draws.baseInstance(i));
                        } else {
                            if (attributes) {
                                bindAttributes(stride * draws.baseInstance(i)); // no base instance: move the attributes by hand
                            }
                            glDrawArraysInstanced(mode, draws.first(i), draws.count(i), draws.instanceCount(i));
                        }
                    }
                }
            }
            Gl.check("drawing " + plan.strategy());
        }

        /** Reads the target back as RGBA, row 0 at the bottom. */
        byte[] read() {
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[width * height * 4];
            pixels.get(out);
            Gl.check("reading the pixels");
            return out;
        }

        @Override
        public void close() {
            glDeleteProgram(program);
            glDeleteBuffers(dataBuffer);
            glDeleteBuffers(styleBuffer);
            if (commandBuffer != 0) {
                glDeleteBuffers(commandBuffer);
            }
            if (dataTexture != 0) {
                glDeleteTextures(dataTexture);
            }
            if (styleTexture != 0) {
                glDeleteTextures(styleTexture);
            }
            arena.close();
        }
    }
}
