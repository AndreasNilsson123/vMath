package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL45.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL45.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL45.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL45.glClientWaitSync;
import static org.lwjgl.opengl.GL45.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteSync;
import static org.lwjgl.opengl.GL45.glFenceSync;
import static org.lwjgl.opengl.GL45.glMapNamedBufferRange;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glUnmapNamedBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Reads a few integers that a GPU pass produced (a count of survivors, an overflow counter) back to
 * the CPU a few frames late without ever waiting for the GPU.
 *
 * <p>A GPU-driven pass keeps its results on the GPU: the CPU does not need the count to draw. For the
 * heads-up display it is still nice to know, and reading it straight away would stall the pipeline.
 * Each frame {@link #begin} starts a slot, {@link #copy} copies integers from a GPU buffer into it
 * (a copy on the GPU, not a read), {@link #end} puts a fence behind it, and {@link #poll} reads the
 * slots whose fences have passed, newest last, into {@link #latest}. The values therefore describe
 * a frame two or three frames old.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class ReadbackRing {

    private final int slots;
    private final int ints;
    private final int buffer;
    private final IntBuffer mapped;
    private final long[] fences;
    private final boolean[] pending;
    private final int[] latest;
    private long issued;
    private int current = -1;

    /**
     * Creates the ring. The OpenGL context must be current.
     *
     * @param slots the number of frames that may be in flight; at least 2
     * @param ints the number of integers per slot; at least 1
     */
    public ReadbackRing(int slots, int ints) {
        this.slots = slots;
        this.ints = ints;
        this.fences = new long[slots];
        this.pending = new boolean[slots];
        this.latest = new int[ints];
        int flags = GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        buffer = glCreateBuffers();
        glNamedBufferStorage(buffer, (long) slots * ints * Integer.BYTES, flags);
        ByteBuffer bytes = glMapNamedBufferRange(buffer, 0, (long) slots * ints * Integer.BYTES, flags);
        mapped = bytes.order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    /**
     * Starts the slot of this frame, after reclaiming it if its fence from {@code slots} frames ago
     * has not been polled.
     */
    public void begin() {
        current = (int) (issued % slots);
        if (pending[current]) {
            glDeleteSync(fences[current]);
            pending[current] = false;
        }
    }

    /**
     * Copies integers from a GPU buffer into the slot of this frame; the copy runs on the GPU after
     * the commands issued so far.
     *
     * @param source the buffer to copy from
     * @param sourceByteOffset the byte offset in the source buffer
     * @param destination the index of the first integer in the slot to write
     * @param count the number of integers to copy
     */
    public void copy(int source, long sourceByteOffset, int destination, int count) {
        glCopyNamedBufferSubData(source, buffer, sourceByteOffset, ((long) current * ints + destination) * Integer.BYTES, (long) count * Integer.BYTES);
    }

    /**
     * Puts a fence behind the copies of this frame.
     */
    public void end() {
        fences[current] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        pending[current] = true;
        issued++;
    }

    /**
     * Reads the slots whose fences have passed, oldest first, so that {@link #latest} holds the
     * newest completed one.
     */
    public void poll() {
        for (long k = Math.max(0, issued - slots); k < issued; k++) {
            int s = (int) (k % slots);
            if (!pending[s]) {
                continue;
            }
            int status = glClientWaitSync(fences[s], 0, 0L);
            if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) {
                for (int i = 0; i < ints; i++) {
                    latest[i] = mapped.get(s * ints + i);
                }
                glDeleteSync(fences[s]);
                pending[s] = false;
            }
        }
    }

    /**
     * Reads one integer of the newest completed slot.
     *
     * @param index the index in the slot
     * @return the value, 0 until the first slot has completed
     */
    public int latest(int index) {
        return latest[index];
    }

    /**
     * Deletes the buffer and the fences that are still pending.
     */
    public void dispose() {
        for (int s = 0; s < slots; s++) {
            if (pending[s]) {
                glDeleteSync(fences[s]);
            }
        }
        glUnmapNamedBuffer(buffer);
        glDeleteBuffers(buffer);
    }
}
