package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_LINES;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindBufferBase;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDrawArrays;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;
import vmath.core.Mat4f;
import vmath.util.DebugLines;

/**
 * Draws the lines of a {@link DebugLines} buffer: boxes, spheres, frusta, skeletons, contact
 * points and whatever else a demo wants to outline.
 *
 * <p>The positions of the buffer (six floats per line) become the vertex attribute and the packed
 * colours (one {@code int} per line) a shader storage buffer that the vertex shader indexes with
 * {@code gl_VertexID / 2}, so the arrays of {@link DebugLines} are uploaded as they are. The
 * GPU buffers and the staging buffers they are filled from double in size when a frame has more
 * lines than they hold, so a steady-state frame allocates nothing. The lines are depth
 * tested against what the demo drew before.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class DebugRenderer {

    private static final int INITIAL_LINES = 4096;

    private final int program;
    private final int viewProjectionLocation;
    private final int vao;
    private int positions;
    private int colors;
    private int capacity;
    private FloatBuffer positionUpload;
    private IntBuffer colorUpload;

    /**
     * Creates the renderer. The OpenGL context must be current.
     */
    public DebugRenderer() {
        program = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(std430, binding = 1) readonly buffer Colors {
                    uint rgba[];
                };
                uniform mat4 viewProjection;
                out vec4 vColor;
                void main() {
                    vColor = unpackUnorm4x8(rgba[gl_VertexID >> 1]);
                    gl_Position = viewProjection * vec4(position, 1.0);
                }
                """, """
                #version 450 core
                in vec4 vColor;
                out vec4 color;
                void main() {
                    color = vColor;
                }
                """);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        vao = glCreateVertexArrays();
        allocate(INITIAL_LINES);
    }

    private void allocate(int lines) {
        if (positions != 0) {
            glDeleteBuffers(positions);
            glDeleteBuffers(colors);
        }
        capacity = lines;
        if (positionUpload != null) {
            MemoryUtil.memFree(positionUpload);
            MemoryUtil.memFree(colorUpload);
        }
        positionUpload = MemoryUtil.memAllocFloat(lines * 6);
        colorUpload = MemoryUtil.memAllocInt(lines);
        positions = glCreateBuffers();
        glNamedBufferStorage(positions, (long) lines * 6 * Float.BYTES, GL_DYNAMIC_STORAGE_BIT);
        colors = glCreateBuffers();
        glNamedBufferStorage(colors, (long) lines * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
        glVertexArrayVertexBuffer(vao, 0, positions, 0, 3 * Float.BYTES);
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);
    }

    /**
     * Uploads the lines and draws them. The framebuffer's depth test stays as the caller left it.
     *
     * @param lines the lines to draw; must not be {@code null}; the buffer is not cleared
     * @param viewProjection the matrix that maps the world to clip space; must not be {@code null}
     */
    public void draw(DebugLines lines, Mat4f viewProjection) {
        int n = lines.lineCount();
        if (n == 0) {
            return;
        }
        if (n > capacity) {
            int grown = capacity;
            while (grown < n) {
                grown *= 2;
            }
            allocate(grown);
        }
        positionUpload.clear();
        positionUpload.put(lines.positions(), 0, n * 6).flip();
        colorUpload.clear();
        colorUpload.put(lines.colors(), 0, n).flip();
        glNamedBufferSubData(positions, 0, positionUpload);
        glNamedBufferSubData(colors, 0, colorUpload);
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, viewProjection);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, colors);
        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 0, n * 2);
    }

    /**
     * Deletes the GPU objects.
     */
    public void dispose() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(positions);
        glDeleteBuffers(colors);
        glDeleteProgram(program);
        MemoryUtil.memFree(positionUpload);
        MemoryUtil.memFree(colorUpload);
    }
}
