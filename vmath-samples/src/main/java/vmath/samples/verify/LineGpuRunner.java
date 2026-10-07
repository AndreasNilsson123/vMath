package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.GL_BLEND;
import static org.lwjgl.opengl.GL46.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL46.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL46.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL46.GL_RGBA;
import static org.lwjgl.opengl.GL46.GL_RGBA8;
import static org.lwjgl.opengl.GL46.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL46.glBindFramebuffer;
import static org.lwjgl.opengl.GL46.glBindRenderbuffer;
import static org.lwjgl.opengl.GL46.glClear;
import static org.lwjgl.opengl.GL46.glClearColor;
import static org.lwjgl.opengl.GL46.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL46.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL46.glDisable;
import static org.lwjgl.opengl.GL46.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL46.glGenFramebuffers;
import static org.lwjgl.opengl.GL46.glGenRenderbuffers;
import static org.lwjgl.opengl.GL46.glReadPixels;
import static org.lwjgl.opengl.GL46.glRenderbufferStorage;
import static org.lwjgl.opengl.GL46.glViewport;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.lines.LineRenderPlan;
import vmath.samples.framework.Gl;
import vmath.samples.framework.LineRenderer;

/**
 * Draws the buffers of a {@link LineRenderPlan} into an offscreen target on a real OpenGL context
 * and reads the pixels back: the {@link LineRenderer} of the samples with a framebuffer around it.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe; OpenGL calls are only valid on the thread that owns
 * the context.
 */
public final class LineGpuRunner implements AutoCloseable {

    private final int width;
    private final int height;
    private final int framebuffer;
    private final int renderbuffer;

    /**
     * Creates the target.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     */
    public LineGpuRunner(int width, int height) {
        this.width = width;
        this.height = height;
        renderbuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffer);
        Gl.check("creating the target");
    }

    @Override
    public void close() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(framebuffer);
        glDeleteRenderbuffers(renderbuffer);
    }

    /** Everything one plan needs on the GPU for one set of draws, drawn into the target. */
    public final class Prepared implements AutoCloseable {
        private final LineRenderer renderer;
        /** How the draws are submitted. */
        public final DrawSubmission submission;
        /** The number of draw calls one frame makes. */
        public final int calls;

        /**
         * Uploads the buffers and sets the draws.
         *
         * @param plan the plan
         * @param caps the capabilities it was made with
         * @param data the data buffer as {@link LineRenderPlan#write} filled it
         * @param dataBytes its size
         * @param styles the style buffer
         * @param styleBytes its size
         * @param draws the draws
         * @throws IllegalStateException if the shaders do not compile or link
         */
        public Prepared(LineRenderPlan plan, GraphicsCapabilities caps, MemorySegment data, long dataBytes, MemorySegment styles, long styleBytes, DrawList draws) {
            renderer = new LineRenderer(plan, caps, dataBytes, styleBytes);
            MemorySegment.copy(data, 0, renderer.dataMirror(), 0, Math.min(data.byteSize(), renderer.dataMirror().byteSize()));
            MemorySegment.copy(styles, 0, renderer.styleMirror(), 0, Math.min(styles.byteSize(), renderer.styleMirror().byteSize()));
            renderer.uploadData(0, dataBytes);
            renderer.uploadStyles(styleBytes);
            calls = renderer.setDraws(draws);
            submission = renderer.submission();
            Gl.check("preparing " + plan.strategy());
        }

        /**
         * Clears the target and draws one frame.
         *
         * @param viewProjection the matrix, column-major
         * @param worldToPixel pixels per world unit at {@code w = 1}
         */
        public void draw(float[] viewProjection, float worldToPixel) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, width, height);
            glDisable(GL_BLEND);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            renderer.draw(viewProjection, worldToPixel, width, height);
            Gl.check("drawing " + renderer.plan().strategy());
        }

        /**
         * Reads the target back.
         *
         * @return RGBA bytes, row 0 at the bottom
         */
        public byte[] read() {
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
            renderer.close();
        }
    }
}
