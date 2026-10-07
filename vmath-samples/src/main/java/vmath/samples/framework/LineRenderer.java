package vmath.samples.framework;

import static org.lwjgl.opengl.GL46.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL46.GL_CULL_FACE;
import static org.lwjgl.opengl.GL46.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL46.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL46.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL46.GL_LINE_STRIP;
import static org.lwjgl.opengl.GL46.GL_RGBA32UI;
import static org.lwjgl.opengl.GL46.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL46.GL_TEXTURE0;
import static org.lwjgl.opengl.GL46.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL46.GL_TRIANGLES;
import static org.lwjgl.opengl.GL46.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL46.glActiveTexture;
import static org.lwjgl.opengl.GL46.glBindBuffer;
import static org.lwjgl.opengl.GL46.glBindBufferBase;
import static org.lwjgl.opengl.GL46.glBindTexture;
import static org.lwjgl.opengl.GL46.glBindVertexArray;
import static org.lwjgl.opengl.GL46.glBufferData;
import static org.lwjgl.opengl.GL46.glBufferSubData;
import static org.lwjgl.opengl.GL46.glDeleteBuffers;
import static org.lwjgl.opengl.GL46.glDeleteProgram;
import static org.lwjgl.opengl.GL46.glDeleteTextures;
import static org.lwjgl.opengl.GL46.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL46.glDisable;
import static org.lwjgl.opengl.GL46.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL46.glDrawArraysInstancedBaseInstance;
import static org.lwjgl.opengl.GL46.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL46.glGenBuffers;
import static org.lwjgl.opengl.GL46.glGenTextures;
import static org.lwjgl.opengl.GL46.glGenVertexArrays;
import static org.lwjgl.opengl.GL46.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL46.glGetUniformLocation;
import static org.lwjgl.opengl.GL46.glMultiDrawArrays;
import static org.lwjgl.opengl.GL46.glMultiDrawArraysIndirect;
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

/**
 * Issues what a {@link LineRenderPlan} writes on an OpenGL context, the way {@code docs/LINES.md}
 * says: each array bound in the form the strategy chose (instanced attributes, a texture buffer, a
 * storage block or a uniform block), the draws submitted in the form {@link DrawSubmission} chose
 * for the capabilities the plan was made with (an indirect multi-draw, {@code glMultiDrawArrays},
 * or a loop that moves the attributes by hand when there is no base instance), and the shaders
 * of the plan.
 *
 * <p>The renderer owns two CPU mirrors, {@link #dataMirror()} and {@link #styleMirror()}, that the
 * plan or a {@code LineSet} writes into; {@link #uploadData} copies a range of the mirror to the
 * GPU, so that a dynamic set uploads only its dirty ranges. {@link #setDraws} takes a draw list,
 * and {@link #draw} draws into the framebuffer that is bound, without clearing it: depth test and
 * face culling are switched off, blending is left as the caller set it.
 *
 * <p>The context may be newer than the capabilities of the plan, which is how the demos draw every
 * tier on one machine: the shader text and the calls are those of the tier.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe; OpenGL calls are only valid on the thread that owns the
 * context.
 */
public final class LineRenderer implements AutoCloseable {

    private final LineRenderPlan plan;
    private final GraphicsCapabilities caps;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment dataMirror;
    private final MemorySegment styleMirror;
    private final ByteBuffer dataView;
    private final ByteBuffer styleView;
    private final int vao = glGenVertexArrays();
    private final int program;
    private final int dataBuffer = glGenBuffers();
    private final int styleBuffer = glGenBuffers();
    private final int commandBuffer = glGenBuffers();
    private int dataTexture;
    private int styleTexture;
    private final int uViewProjection;
    private final int uViewport;
    private final int uWorldToPixel;
    private final int mode;
    private final boolean attributes;
    private final java.util.List<VertexBufferLayout.GlFormat> formats;
    private final int formatStride;
    private final int formatDivisor;
    private final long dataCapacity;
    private final long styleCapacity;
    private DrawList draws;
    private DrawSubmission submission;
    private IntBuffer firsts = BufferUtils.createIntBuffer(16);
    private IntBuffer counts = BufferUtils.createIntBuffer(16);
    private MemorySegment commands;
    private int commandCapacity;
    private int calls;

    /**
     * Creates the renderer for a plan.
     *
     * @param plan the plan; must not be {@code null}
     * @param caps the capabilities the plan was made with; must not be {@code null}
     * @param dataBytes the size of the data buffer, from {@link LineRenderPlan#dataBytes} or {@code LineSet#dataBytes}
     * @param styleBytes the size of the style buffer; for a uniform block at least the block is allocated
     * @throws IllegalStateException if the shaders do not compile or link on this driver
     */
    public LineRenderer(LineRenderPlan plan, GraphicsCapabilities caps, long dataBytes, long styleBytes) {
        this.plan = plan;
        this.caps = caps;
        this.mode = plan.primitive() == LineRenderPlan.Primitive.TRIANGLES ? GL_TRIANGLES : GL_LINE_STRIP;
        boolean hairline = plan.strategy() == LineStrategy.HAIRLINE;
        this.attributes = hairline || plan.segmentAccess().mode() == StructArrayAccess.Mode.VERTEX_ATTRIBUTE;
        VertexBufferLayout layout = attributes ? (hairline ? plan.hairlineLayout() : plan.segmentAccess().vertexLayout()) : null;
        this.formats = layout == null ? java.util.List.of() : layout.glFormats();   // made once: the list is a new one at every call
        this.formatStride = layout == null ? 0 : layout.stride();
        this.formatDivisor = layout != null && layout.perInstance() ? 1 : 0;
        this.dataCapacity = Math.max(dataBytes, 16);
        long styles = Math.max(styleBytes, 16);
        if (!hairline && plan.styleAccess().mode() == StructArrayAccess.Mode.UNIFORM_BLOCK) {
            styles = Math.max(styles, LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH * 64L); // the block is read whole
        }
        this.styleCapacity = styles;
        dataMirror = arena.allocate(dataCapacity, 16);
        styleMirror = arena.allocate(styleCapacity, 16);
        dataView = dataMirror.asByteBuffer();
        styleView = styleMirror.asByteBuffer();

        program = Gl.program(plan.vertexShader(), plan.fragmentShader());
        glUseProgram(program);
        uViewProjection = glGetUniformLocation(program, LineRenderPlan.U_VIEW_PROJECTION);
        uViewport = glGetUniformLocation(program, LineRenderPlan.U_VIEWPORT);
        uWorldToPixel = glGetUniformLocation(program, LineRenderPlan.U_WORLD_TO_PIXEL);

        glBindBuffer(GL_ARRAY_BUFFER, dataBuffer);
        glBufferData(GL_ARRAY_BUFFER, dataCapacity, GL_DYNAMIC_DRAW);
        if (!hairline) {
            StructArrayAccess seg = plan.segmentAccess(), sty = plan.styleAccess();
            if (seg.mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                dataTexture = glGenTextures();
                glBindTexture(GL_TEXTURE_BUFFER, dataTexture);
                glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, dataBuffer);
                if (!seg.hasExplicitBinding()) {
                    glUniform1i(glGetUniformLocation(program, seg.name() + "_texels"), LineRenderPlan.SEGMENT_SLOT);
                }
            }
            int target = styleTarget();
            glBindBuffer(target, styleBuffer);
            glBufferData(target, styleCapacity, GL_DYNAMIC_DRAW);
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
        Gl.check("creating the line renderer for " + plan.strategy());
    }

    private int styleTarget() {
        StructArrayAccess.Mode m = plan.styleAccess().mode();
        return m == StructArrayAccess.Mode.STORAGE_BLOCK ? GL_SHADER_STORAGE_BUFFER : m == StructArrayAccess.Mode.UNIFORM_BLOCK ? GL_UNIFORM_BUFFER : GL_ARRAY_BUFFER;
    }

    /**
     * Gives the CPU copy of the data buffer, which the plan writes into.
     *
     * @return the mirror, {@code dataBytes} long (at least 16)
     */
    public MemorySegment dataMirror() {
        return dataMirror;
    }

    /**
     * Gives the CPU copy of the style buffer.
     *
     * @return the mirror
     */
    public MemorySegment styleMirror() {
        return styleMirror;
    }

    /**
     * Copies a range of the data mirror to the GPU.
     *
     * @param offset the byte offset
     * @param length the number of bytes; 0 does nothing
     * @throws IllegalArgumentException if the range is outside the buffer
     */
    public void uploadData(long offset, long length) {
        if (length == 0) {
            return;
        }
        if (offset < 0 || length < 0 || offset + length > dataCapacity) {
            throw new IllegalArgumentException("the range " + offset + " + " + length + " is outside the " + dataCapacity + " bytes");
        }
        glBindBuffer(GL_ARRAY_BUFFER, dataBuffer);
        dataView.limit((int) (offset + length)).position((int) offset);
        glBufferSubData(GL_ARRAY_BUFFER, offset, dataView);
        dataView.clear();
    }

    /**
     * Copies the first bytes of the style mirror to the GPU.
     *
     * @param length the number of bytes
     * @throws IllegalArgumentException if it is more than the buffer
     */
    public void uploadStyles(long length) {
        if (plan.strategy() == LineStrategy.HAIRLINE || length == 0) {
            return;
        }
        if (length > styleCapacity) {
            throw new IllegalArgumentException("the style buffer has " + styleCapacity + " bytes, not " + length);
        }
        int target = styleTarget();
        glBindBuffer(target, styleBuffer);
        styleView.limit((int) length).position(0);
        glBufferSubData(target, 0, styleView);
        styleView.clear();
    }

    /**
     * Takes the draws to submit from now on and prepares them in the form the capabilities choose.
     *
     * @param list the draws, of the kind arrays; copied now, so it can change afterwards
     * @return the number of calls a frame makes: 1 for a multi-draw, one per draw for a loop
     */
    public int setDraws(DrawList list) {
        this.draws = list;
        submission = plan.submission(list);
        int n = list.size();
        if (submission == DrawSubmission.MULTI_DRAW_INDIRECT) {
            long need = Math.max(16L, (long) n * DrawCommandBuffer.Kind.ARRAYS.bytes());
            if (commands == null || commands.byteSize() < need) {
                commandCapacity = Math.max(n, commandCapacity * 2);
                commands = arena.allocate(Math.max(16L, (long) commandCapacity * DrawCommandBuffer.Kind.ARRAYS.bytes()), 16);
            }
            DrawCommandBuffer buffer = new DrawCommandBuffer(commands, DrawCommandBuffer.Kind.ARRAYS, false);
            list.writeIndirect(buffer);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
            ByteBuffer view = commands.asByteBuffer();
            view.limit((int) (n * (long) DrawCommandBuffer.Kind.ARRAYS.bytes()));
            glBufferData(GL_DRAW_INDIRECT_BUFFER, view, GL_DYNAMIC_DRAW);
            calls = 1;
        } else if (submission == DrawSubmission.MULTI_DRAW_CLIENT) {
            if (firsts.capacity() < n) {
                firsts = BufferUtils.createIntBuffer(Math.max(n, firsts.capacity() * 2));
                counts = BufferUtils.createIntBuffer(firsts.capacity());
            }
            firsts.clear();
            counts.clear();
            for (int i = 0; i < n; i++) {
                if (list.instanceCount(i) > 0) {
                    firsts.put(list.first(i));
                    counts.put(list.count(i));
                }
            }
            firsts.flip();
            counts.flip();
            calls = 1;
        } else {
            int c = 0;
            for (int i = 0; i < n; i++) {
                c += list.instanceCount(i) > 0 ? 1 : 0;
            }
            calls = c;
        }
        return calls;
    }

    /**
     * Gives the way the draws are submitted.
     *
     * @return the submission of the last {@link #setDraws}
     */
    public DrawSubmission submission() {
        return submission;
    }

    /**
     * Gives the number of draw calls a frame makes.
     *
     * @return the calls of the last {@link #setDraws}
     */
    public int calls() {
        return calls;
    }

    /**
     * Gives the plan.
     *
     * @return the plan
     */
    public LineRenderPlan plan() {
        return plan;
    }

    private void bindAttributes(long byteOffset) {
        glBindBuffer(GL_ARRAY_BUFFER, dataBuffer);
        for (VertexBufferLayout.GlFormat f : formats) {
            glEnableVertexAttribArray(f.location());
            if (f.integer()) {
                glVertexAttribIPointer(f.location(), f.size(), f.type(), formatStride, byteOffset + f.relativeOffset());
            } else {
                glVertexAttribPointer(f.location(), f.size(), f.type(), f.normalized(), formatStride, byteOffset + f.relativeOffset());
            }
            glVertexAttribDivisor(f.location(), formatDivisor);
        }
    }

    /**
     * Draws the lines into the framebuffer that is bound.
     *
     * @param viewProjection the matrix relative to the origin of the lines, column-major, 16 values
     * @param worldToPixel pixels per world unit at {@code w = 1}, for widths in world units
     * @param width the width of the viewport in pixels
     * @param height the height of the viewport in pixels
     * @throws IllegalStateException if no draws were set
     */
    public void draw(float[] viewProjection, float worldToPixel, int width, int height) {
        if (draws == null) {
            throw new IllegalStateException("setDraws has not been called");
        }
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(program);
        glUniformMatrix4fv(uViewProjection, false, viewProjection);
        if (plan.strategy() != LineStrategy.HAIRLINE) {
            glUniform2f(uViewport, width, height);
            glUniform1f(uWorldToPixel, worldToPixel);
            if (plan.segmentAccess().mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                glActiveTexture(GL_TEXTURE0 + LineRenderPlan.SEGMENT_SLOT);
                glBindTexture(GL_TEXTURE_BUFFER, dataTexture);
            }
            StructArrayAccess.Mode sm = plan.styleAccess().mode();
            if (sm == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                glActiveTexture(GL_TEXTURE0 + LineRenderPlan.STYLE_SLOT);
                glBindTexture(GL_TEXTURE_BUFFER, styleTexture);
            } else {
                glBindBufferBase(styleTarget(), LineRenderPlan.STYLE_SLOT, styleBuffer);
            }
        }
        glBindVertexArray(vao);
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
                boolean baseInstance = caps.has(GraphicsCapabilities.Feature.BASE_INSTANCE);
                for (int i = 0; i < draws.size(); i++) {
                    int instances = draws.instanceCount(i);
                    if (instances == 0) {
                        continue;
                    }
                    if (baseInstance) {
                        glDrawArraysInstancedBaseInstance(mode, draws.first(i), draws.count(i), instances, draws.baseInstance(i));
                    } else {
                        if (attributes) {
                            bindAttributes(stride * draws.baseInstance(i)); // no base instance: the attributes are moved by hand
                        }
                        glDrawArraysInstanced(mode, draws.first(i), draws.count(i), instances);
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        glDeleteProgram(program);
        glDeleteBuffers(dataBuffer);
        glDeleteBuffers(styleBuffer);
        glDeleteBuffers(commandBuffer);
        glDeleteVertexArrays(vao);
        if (dataTexture != 0) {
            glDeleteTextures(dataTexture);
        }
        if (styleTexture != 0) {
            glDeleteTextures(styleTexture);
        }
        arena.close();
    }
}
