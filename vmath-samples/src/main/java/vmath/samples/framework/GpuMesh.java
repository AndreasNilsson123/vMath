package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import vmath.gl.VertexBufferLayout;
import vmath.mesh.Mesh;
import vmath.mesh.MeshExport;
import vmath.mesh.VertexLayout;

/**
 * A mesh in GPU memory: a vertex buffer, a 32-bit index buffer and a vertex array object whose
 * attribute formats come from the library's vertex layout.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class GpuMesh {

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;
    private final int vertexCount;
    private final int stride;

    private GpuMesh(int vao, int vbo, int ebo, int indexCount, int vertexCount, int stride) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.indexCount = indexCount;
        this.vertexCount = vertexCount;
        this.stride = stride;
    }

    /**
     * Exports a mesh with a vertex layout and uploads it.
     *
     * <p>The vertex array is described with {@code VertexBufferLayout.glFormats()}, so the
     * attribute locations match the inputs that {@code VertexBufferLayout.glslInputs()} writes
     * into a shader.
     *
     * @param arena owns the memory the mesh is exported into before it is copied to the GPU; must
     *     not be {@code null}
     * @param mesh the mesh; must not be {@code null}
     * @param layout the vertex layout the mesh is exported with; must not be {@code null}
     * @return the uploaded mesh
     */
    public static GpuMesh upload(Arena arena, Mesh mesh, VertexLayout layout) {
        MemorySegment vertices = arena.allocate(MeshExport.vertexBytes(mesh, layout), 16);
        MeshExport.writeVertices(mesh, layout, vertices, 0);
        MemorySegment indices = arena.allocate(MeshExport.indexBytes32(mesh), 16);
        MeshExport.writeIndices32(mesh, indices, 0);
        int vbo = glCreateBuffers();
        glNamedBufferStorage(vbo, vertices.asByteBuffer(), 0);
        int ebo = glCreateBuffers();
        glNamedBufferStorage(ebo, indices.asByteBuffer(), 0);
        int vao = glCreateVertexArrays();
        VertexBufferLayout bufferLayout = layout.toBufferLayout();
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, bufferLayout.stride());
        glVertexArrayElementBuffer(vao, ebo);
        List<VertexBufferLayout.GlFormat> formats = bufferLayout.glFormats();
        for (VertexBufferLayout.GlFormat f : formats) {
            glEnableVertexArrayAttrib(vao, f.location());
            if (f.integer()) {
                glVertexArrayAttribIFormat(vao, f.location(), f.size(), f.type(), f.relativeOffset());
            } else {
                glVertexArrayAttribFormat(vao, f.location(), f.size(), f.type(), f.normalized(), f.relativeOffset());
            }
            glVertexArrayAttribBinding(vao, f.location(), 0);
        }
        return new GpuMesh(vao, vbo, ebo, mesh.indexCount(), mesh.vertexCount(), bufferLayout.stride());
    }

    /**
     * Reads the vertex array object.
     *
     * @return the object name, to bind with {@code glBindVertexArray}
     */
    public int vao() {
        return vao;
    }

    /**
     * Reads the number of indices.
     *
     * @return the index count, which is three times the number of triangles
     */
    public int indexCount() {
        return indexCount;
    }

    /**
     * Reads the number of vertices.
     *
     * @return the vertex count
     */
    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Reads the size of one vertex in the buffer.
     *
     * @return the stride in bytes
     */
    public int stride() {
        return stride;
    }

    /**
     * Deletes the buffers and the vertex array object.
     */
    public void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
    }
}
