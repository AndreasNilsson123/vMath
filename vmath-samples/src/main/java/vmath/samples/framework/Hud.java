package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_BLEND;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_NEAREST;
import static org.lwjgl.opengl.GL45.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL45.GL_R8;
import static org.lwjgl.opengl.GL45.GL_RED;
import static org.lwjgl.opengl.GL45.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glBlendFunc;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glDrawArrays;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glPixelStorei;
import static org.lwjgl.opengl.GL45.glProgramUniform2f;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTextureSubImage2D;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;
import static org.lwjgl.opengl.GL45.glDepthMask;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The heads-up display: text and translucent rectangles in window pixels, drawn on top of the
 * frame with one draw call.
 *
 * <p>The characters come from a {@link FontAtlas} that is baked with Java 2D when the display is
 * created and uploaded as a one-channel texture. Between {@link #begin} and {@link #end} the
 * calls append quads (two triangles each, eight floats per vertex: position, texture coordinate
 * and colour) to a float array that {@code end} uploads and draws, so a frame allocates nothing
 * beyond the strings the caller formats. Positions are in pixels with the origin at the top left.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the
 * OpenGL context.
 */
public final class Hud {

    private static final int FLOATS_PER_VERTEX = 8;
    private static final int FLOATS_PER_QUAD = 6 * FLOATS_PER_VERTEX;
    private static final int MAX_QUADS = 16_384;
    private static final float MARGIN = 8f;

    private final FontAtlas atlas;
    private final int program;
    private final int screenLocation;
    private final int vao;
    private final int vbo;
    private final int texture;
    private final float[] vertices = new float[MAX_QUADS * FLOATS_PER_QUAD];
    private final java.nio.FloatBuffer upload = MemoryUtil.memAllocFloat(MAX_QUADS * FLOATS_PER_QUAD);
    private int floats;
    private int width = 1;
    private int height = 1;
    private float cursorY;
    private float r = 1f;
    private float g = 1f;
    private float b = 1f;

    /**
     * Creates the display: bakes the font, uploads it and builds the shader and the buffers. The
     * OpenGL context must be current.
     */
    Hud() {
        atlas = FontAtlas.bake(15);
        program = Gl.program("""
                #version 450 core
                layout(location = 0) in vec2 position;
                layout(location = 1) in vec2 uv;
                layout(location = 2) in vec4 tint;
                uniform vec2 screen;
                out vec2 vUv;
                out vec4 vTint;
                void main() {
                    vUv = uv;
                    vTint = tint;
                    gl_Position = vec4(position.x / screen.x * 2.0 - 1.0, 1.0 - position.y / screen.y * 2.0, 0.0, 1.0);
                }
                """, """
                #version 450 core
                in vec2 vUv;
                in vec4 vTint;
                layout(binding = 0) uniform sampler2D atlas;
                out vec4 color;
                void main() {
                    color = vec4(vTint.rgb, vTint.a * texture(atlas, vUv).r);
                }
                """);
        screenLocation = glGetUniformLocation(program, "screen");
        texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, GL_R8, atlas.width(), atlas.height());
        ByteBuffer pixels = MemoryUtil.memAlloc(atlas.coverage().length);
        try {
            pixels.put(atlas.coverage()).flip();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTextureSubImage2D(texture, 0, 0, 0, atlas.width(), atlas.height(), GL_RED, GL_UNSIGNED_BYTE, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        vbo = glCreateBuffers();
        glNamedBufferStorage(vbo, (long) vertices.length * Float.BYTES, GL_DYNAMIC_STORAGE_BIT);
        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, FLOATS_PER_VERTEX * Float.BYTES);
        int[] sizes = {2, 2, 4};
        int offset = 0;
        for (int i = 0; i < sizes.length; i++) {
            glEnableVertexArrayAttrib(vao, i);
            glVertexArrayAttribFormat(vao, i, sizes[i], GL_FLOAT, false, offset * Float.BYTES);
            glVertexArrayAttribBinding(vao, i, 0);
            offset += sizes[i];
        }
    }

    /**
     * Starts a frame of text: empties the quad list and puts the cursor at the top left.
     *
     * @param width the width of the framebuffer in pixels
     * @param height the height of the framebuffer in pixels
     */
    void begin(int width, int height) {
        this.width = width;
        this.height = height;
        floats = 0;
        cursorY = MARGIN;
        r = g = b = 1f;
    }

    /**
     * Sets the colour of the text that follows.
     *
     * @param red the red component in 0 to 1
     * @param green the green component in 0 to 1
     * @param blue the blue component in 0 to 1
     * @return this display, for chaining
     */
    public Hud color(float red, float green, float blue) {
        r = red;
        g = green;
        b = blue;
        return this;
    }

    /**
     * Reads the advance of one character.
     *
     * @return the width of a character in pixels; the font is monospaced
     */
    public float advance() {
        return atlas.cellWidth() - 2f;
    }

    /**
     * Reads the height of a line of text.
     *
     * @return the line height in pixels
     */
    public float lineHeight() {
        return atlas.cellHeight() - 2f;
    }

    /**
     * Reads the width of the framebuffer of the current frame.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Reads the height of the framebuffer of the current frame.
     *
     * @return the height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Adds a line of text at the cursor, on a dark translucent backdrop, and moves the cursor down.
     *
     * @param text the characters; must not be {@code null}, and anything but printable ASCII is
     *     drawn as a question mark
     * @return this display, for chaining
     */
    public Hud line(String text) {
        text(MARGIN, cursorY, text);
        cursorY += lineHeight() + 2f;
        return this;
    }

    /**
     * Moves the cursor down by a gap, to separate groups of lines.
     *
     * @param pixels the gap in pixels
     * @return this display, for chaining
     */
    public Hud gap(float pixels) {
        cursorY += pixels;
        return this;
    }

    /**
     * Adds a line of text at a position, on a dark translucent backdrop.
     *
     * @param x the left edge in pixels from the left of the window
     * @param y the top edge in pixels from the top of the window
     * @param text the characters; must not be {@code null}
     * @return this display, for chaining
     */
    public Hud text(float x, float y, String text) {
        int n = text.length();
        if (n == 0 || floats + (n + 1) * FLOATS_PER_QUAD > vertices.length) {
            return this;
        }
        float adv = advance(), lh = lineHeight();
        rect(x - 3f, y - 1f, n * adv + 6f, lh + 2f, 0f, 0f, 0f, 0.55f);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                continue;
            }
            int cell = FontAtlas.cell(c);
            quad(x + i * adv, y, adv, lh, atlas.u0(cell), atlas.v0(cell), atlas.u1(cell), atlas.v1(cell), r, g, b, 1f);
        }
        return this;
    }

    /**
     * Adds a filled rectangle.
     *
     * @param x the left edge in pixels
     * @param y the top edge in pixels
     * @param w the width in pixels
     * @param h the height in pixels
     * @param red the red component in 0 to 1
     * @param green the green component in 0 to 1
     * @param blue the blue component in 0 to 1
     * @param alpha the opacity in 0 to 1
     * @return this display, for chaining
     */
    public Hud rect(float x, float y, float w, float h, float red, float green, float blue, float alpha) {
        if (floats + FLOATS_PER_QUAD > vertices.length) {
            return this;
        }
        int cell = FontAtlas.solidCell();
        float u = (atlas.u0(cell) + atlas.u1(cell)) * 0.5f, v = (atlas.v0(cell) + atlas.v1(cell)) * 0.5f;
        quad(x, y, w, h, u, v, u, v, red, green, blue, alpha);
        return this;
    }

    private void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, float cr, float cg, float cb, float ca) {
        vertex(x, y, u0, v0, cr, cg, cb, ca);
        vertex(x + w, y, u1, v0, cr, cg, cb, ca);
        vertex(x + w, y + h, u1, v1, cr, cg, cb, ca);
        vertex(x, y, u0, v0, cr, cg, cb, ca);
        vertex(x + w, y + h, u1, v1, cr, cg, cb, ca);
        vertex(x, y + h, u0, v1, cr, cg, cb, ca);
    }

    private void vertex(float x, float y, float u, float v, float cr, float cg, float cb, float ca) {
        float[] a = vertices;
        int i = floats;
        a[i] = x;
        a[i + 1] = y;
        a[i + 2] = u;
        a[i + 3] = v;
        a[i + 4] = cr;
        a[i + 5] = cg;
        a[i + 6] = cb;
        a[i + 7] = ca;
        floats += FLOATS_PER_VERTEX;
    }

    /**
     * Uploads the quads of the frame and draws them with blending on and depth testing off, then
     * puts depth testing and culling back on and blending off, which is the state a demo starts
     * a frame in.
     */
    void end() {
        if (floats == 0) {
            return;
        }
        upload.clear();
        upload.put(vertices, 0, floats).flip();
        glNamedBufferSubData(vbo, 0, upload);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glUseProgram(program);
        glProgramUniform2f(program, screenLocation, width, height);
        glBindTextureUnit(0, texture);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    /**
     * Deletes the GPU objects and the native upload buffer.
     */
    void dispose() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteTextures(texture);
        glDeleteProgram(program);
        MemoryUtil.memFree(upload);
    }
}
