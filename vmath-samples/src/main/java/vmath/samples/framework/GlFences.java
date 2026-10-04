package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL45.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL45.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL45.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL45.glClientWaitSync;
import static org.lwjgl.opengl.GL45.glDeleteSync;
import static org.lwjgl.opengl.GL45.glFenceSync;

import vmath.mem.PersistentBufferRing;

/**
 * The fence operations of {@code PersistentBufferRing} for OpenGL: {@code glFenceSync},
 * {@code glClientWaitSync} and {@code glDeleteSync}, with the sync objects as {@code Long} handles.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless, but OpenGL calls are only valid on the thread that owns the
 * context.
 */
public final class GlFences implements PersistentBufferRing.FenceOps<Long> {

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
