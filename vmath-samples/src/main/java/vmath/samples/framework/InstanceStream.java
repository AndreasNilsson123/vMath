package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL45.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL45.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL45.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindBufferRange;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glClientWaitSync;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteSync;
import static org.lwjgl.opengl.GL45.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL45.glFenceSync;
import static org.lwjgl.opengl.GL45.glGetInteger;
import static org.lwjgl.opengl.GL45.glMapNamedBufferRange;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glUnmapNamedBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.InstanceWriter;
import vmath.mem.PersistentBufferRing;

/**
 * The GPU-driven instance path of a frame: the visible boxes written as 64-byte records into a
 * persistently mapped storage buffer, one region per frame in flight, and one indirect draw
 * command that draws them all.
 *
 * <p>The regions are managed by the library's {@link PersistentBufferRing} with fences from
 * {@code glFenceSync}. A frame is {@link #begin} (waits, if the GPU still uses the region about to
 * be written), {@link #write} (the survivors of the culling become instance records, straight into
 * mapped memory), {@link #draw} (binds the region as shader storage buffer 0 and issues
 * {@code glDrawElementsIndirect} with the command that {@link DrawCommandBuffer} wrote) and
 * {@link #end} (inserts the fence). A vertex shader reads the instance with
 * {@code gl_InstanceID}; the record layout is the one that {@link InstanceWriter} documents.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class InstanceStream implements AutoCloseable {

    private final int instanceBuffer;
    private final int commandBuffer;
    private final MemorySegment mapped;
    private final PersistentBufferRing<Long> ring;
    private final DrawCommandBuffer commands;
    private final ByteBuffer commandBytes;
    private final long regionBytes;
    private final int framesInFlight;
    private int written;

    /**
     * Creates the buffers.
     *
     * @param arena owns the memory of the draw command that is staged before it is uploaded; must
     *     not be {@code null}
     * @param capacity the largest number of instances a frame may write; at least 1
     * @param framesInFlight the number of regions, which is how far the CPU may run ahead of the
     *     GPU; at least 1
     */
    public InstanceStream(Arena arena, int capacity, int framesInFlight) {
        this.framesInFlight = framesInFlight;
        long alignment = Math.max(256, glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        regionBytes = (capacity * InstanceWriter.STRIDE + alignment - 1) / alignment * alignment;
        long totalBytes = regionBytes * framesInFlight;
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        instanceBuffer = glCreateBuffers();
        glNamedBufferStorage(instanceBuffer, totalBytes, flags);
        ByteBuffer buffer = glMapNamedBufferRange(instanceBuffer, 0, totalBytes, flags);
        mapped = MemorySegment.ofBuffer(buffer);
        MemorySegment commandSegment = arena.allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16);
        commandBytes = commandSegment.asByteBuffer();
        commands = new DrawCommandBuffer(commandSegment, DrawCommandBuffer.Kind.ELEMENTS, false);
        commandBuffer = glCreateBuffers();
        glNamedBufferStorage(commandBuffer, commandSegment.byteSize(), GL_DYNAMIC_STORAGE_BIT);
        ring = new PersistentBufferRing<>(mapped, framesInFlight, alignment, new Fences());
    }

    /**
     * Reads the size of one region.
     *
     * @return the bytes of one frame's region
     */
    public long regionBytes() {
        return regionBytes;
    }

    /**
     * Reads the number of regions.
     *
     * @return the frames in flight
     */
    public int framesInFlight() {
        return framesInFlight;
    }

    /**
     * Starts a frame: waits for the fence of the region that this frame will write, if the GPU has
     * not finished with it.
     */
    public void begin() {
        ring.beginFrame();
        written = 0;
    }

    /**
     * Writes the visible boxes of the culling result as instance records into this frame's region.
     *
     * @param visible the result of the culling; must not be {@code null}
     * @param bounds the boxes that were culled, in the same order; must not be {@code null}
     * @return the number of instances written
     */
    public int write(VisibilitySet visible, BoundsArray bounds) {
        written = InstanceWriter.writeVisibleBoxes(mapped, ring.regionOffset() / InstanceWriter.STRIDE, visible, bounds);
        return written;
    }

    /**
     * Writes one instance with a general transform into this frame's region, for scenes whose
     * instances are rotated (the boxes of a physics simulation), where the visibility-driven
     * {@link #write} would give scaled and translated boxes only. Call {@link #setCount} after
     * the last one.
     *
     * @param slot the position of the instance in the region, from 0
     * @param rows the transform as three rows of four floats, row-major: the linear part in the
     *     first three columns and the translation in the fourth; at least 12 floats; must not be
     *     {@code null}
     * @param userData the user data word of the record, which the shader reads as the id
     */
    public void writeInstance(int slot, float[] rows, int userData) {
        long base = ring.regionOffset() + slot * InstanceWriter.STRIDE;
        for (int k = 0; k < 12; k++) {
            mapped.set(ValueLayout.JAVA_FLOAT_UNALIGNED, base + 4L * k, rows[k]);
        }
        mapped.set(ValueLayout.JAVA_INT_UNALIGNED, base + InstanceWriter.OFFSET_USER_DATA, userData);
    }

    /**
     * Sets the number of instances that {@link #draw} draws, after they were written with
     * {@link #writeInstance}.
     *
     * @param count the number of instances written in this frame
     */
    public void setCount(int count) {
        written = count;
    }

    /**
     * Draws the instances written in this frame: stages and uploads the indirect command, binds
     * the region as shader storage buffer binding 0 and the mesh's vertex array, and issues one
     * indexed indirect draw of triangles with a 32-bit index. The caller has bound the program and
     * set its uniforms.
     *
     * @param mesh the mesh every instance is drawn with; must not be {@code null}
     */
    public void draw(GpuMesh mesh) {
        commands.clear();
        commands.addElements(mesh.indexCount(), written, 0, 0, 0);
        glNamedBufferSubData(commandBuffer, 0, commandBytes);
        glBindVertexArray(mesh.vao());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, instanceBuffer, ring.regionOffset(), Math.max(InstanceWriter.STRIDE, written * InstanceWriter.STRIDE));
        glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L);
    }

    /**
     * Ends the frame: inserts the fence that guards this frame's region.
     */
    public void end() {
        ring.endFrame();
    }

    /**
     * Reads how often a frame had to wait for the GPU.
     *
     * @return the number of stalls since the creation
     */
    public long stalls() {
        return ring.stalls();
    }

    /**
     * Waits for the GPU to finish with every region, unmaps and deletes the buffers.
     */
    @Override
    public void close() {
        ring.drain();
        ring.close();
        glUnmapNamedBuffer(instanceBuffer);
        glDeleteBuffers(instanceBuffer);
        glDeleteBuffers(commandBuffer);
    }

    /**
     * The fence operations of OpenGL for the ring of instance regions: a sync object per frame.
     */
    private static final class Fences implements PersistentBufferRing.FenceOps<Long> {

        @Override
        public Long insert() {
            return glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        @Override
        public boolean isSignaled(Long fence) {
            int status = glClientWaitSync(fence, 0, 0L);
            return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED;
        }

        @Override
        public void await(Long fence) {
            int status;
            do {
                status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            } while (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED);
        }

        @Override
        public void release(Long fence) {
            glDeleteSync(fence);
        }
    }
}
