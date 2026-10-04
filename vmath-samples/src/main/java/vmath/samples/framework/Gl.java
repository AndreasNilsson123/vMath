package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL45.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL45.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL45.GL_NO_ERROR;
import static org.lwjgl.opengl.GL45.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL45.glAttachShader;
import static org.lwjgl.opengl.GL45.glCompileShader;
import static org.lwjgl.opengl.GL45.glCreateProgram;
import static org.lwjgl.opengl.GL45.glCreateShader;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteShader;
import static org.lwjgl.opengl.GL45.glGetError;
import static org.lwjgl.opengl.GL45.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL45.glGetProgrami;
import static org.lwjgl.opengl.GL45.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL45.glGetShaderi;
import static org.lwjgl.opengl.GL45.glLinkProgram;
import static org.lwjgl.opengl.GL45.glProgramUniformMatrix4fv;
import static org.lwjgl.opengl.GL45.glShaderSource;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryStack;
import vmath.core.Mat4f;

/**
 * The small OpenGL helpers that every demo needs: building a shader program with readable errors,
 * setting a matrix uniform, and checking for errors.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless, but OpenGL calls are only valid on the thread that owns the
 * context.
 */
public final class Gl {

    private Gl() {
    }

    /**
     * Builds a program from a vertex and a fragment shader.
     *
     * @param vertexSource the GLSL of the vertex shader; must not be {@code null}
     * @param fragmentSource the GLSL of the fragment shader; must not be {@code null}
     * @return the program object; the caller deletes it with {@code glDeleteProgram}
     * @throws IllegalStateException if a shader does not compile or the program does not link; the
     *     message holds the driver's log and the source with line numbers
     */
    public static int program(String vertexSource, String fragmentSource) {
        int vs = compile(GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GL_FRAGMENT_SHADER, fragmentSource);
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("cannot link the program: " + log);
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("cannot compile a shader: " + log + "\n" + numbered(source));
        }
        return shader;
    }

    /**
     * Prefixes every line of a text with its number, which is how the driver's messages refer to
     * it.
     *
     * @param source the text; must not be {@code null}
     * @return the text with a right-aligned line number and a bar in front of each line
     */
    static String numbered(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(String.format("%4d | %s%n", i + 1, lines[i]));
        }
        return out.toString();
    }

    /**
     * Sets a {@code mat4} uniform of a program from a matrix, without allocating on the Java heap.
     *
     * @param program the program object
     * @param location the location of the uniform
     * @param matrix the matrix, written in column-major order; must not be {@code null}
     */
    public static void uniform(int program, int location, Mat4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m = stack.mallocFloat(16);
            matrix.writeTo(m, 0);
            glProgramUniformMatrix4fv(program, location, false, m);
        }
    }

    /**
     * Checks that OpenGL has no error pending.
     *
     * @param where what was just done, for the message; must not be {@code null}
     * @throws IllegalStateException if the context has recorded an error since the last check
     */
    public static void check(String where) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            StringBuilder all = new StringBuilder(String.format("0x%04X", error));
            for (int next = glGetError(); next != GL_NO_ERROR; next = glGetError()) {
                all.append(String.format(", 0x%04X", next));
            }
            throw new IllegalStateException("OpenGL error " + all + " " + where);
        }
    }
}
