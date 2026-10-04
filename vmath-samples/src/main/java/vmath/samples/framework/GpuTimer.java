package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL45.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL45.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL45.glBeginQuery;
import static org.lwjgl.opengl.GL45.glCreateQueries;
import static org.lwjgl.opengl.GL45.glDeleteQueries;
import static org.lwjgl.opengl.GL45.glEndQuery;
import static org.lwjgl.opengl.GL45.glGetQueryObjecti;
import static org.lwjgl.opengl.GL45.glGetQueryObjectui64;

/**
 * Times a stretch of GPU work with {@code GL_TIME_ELAPSED} queries, read a few frames after they
 * were issued so that the CPU never waits for the GPU.
 *
 * <p>A demo brackets the commands it wants timed with {@link #begin} and {@link #end} (once per
 * frame; OpenGL allows one active time-elapsed query per timer), and the runner calls {@link #poll}
 * after every frame for the timer of the context, which hands back the oldest result that is ready,
 * or -1. A demo that wants more than one timed stretch creates further timers and polls them itself.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class GpuTimer {

    private final int[] queries = new int[4];
    private long issued;
    private long consumed;

    /**
     * Creates the queries. The OpenGL context must be current.
     */
    public GpuTimer() {
        for (int i = 0; i < queries.length; i++) {
            queries[i] = glCreateQueries(GL_TIME_ELAPSED);
        }
    }

    /**
     * Starts timing the GPU commands that follow.
     */
    public void begin() {
        glBeginQuery(GL_TIME_ELAPSED, queries[(int) (issued % queries.length)]);
    }

    /**
     * Stops timing; the result becomes available a few frames later through {@link #poll}.
     */
    public void end() {
        glEndQuery(GL_TIME_ELAPSED);
        issued++;
    }

    /**
     * Reads the oldest result that is ready.
     *
     * @return the elapsed GPU time in nanoseconds of a query issued about
     *     {@code queries.length} frames ago, or -1 if there is none that is ready and unread
     */
    public long poll() {
        long oldestIssue = issued - queries.length;
        if (oldestIssue < consumed) {
            return -1L;
        }
        int oldest = queries[(int) (oldestIssue % queries.length)];
        if (glGetQueryObjecti(oldest, GL_QUERY_RESULT_AVAILABLE) == 0) {
            return -1L;
        }
        consumed = oldestIssue + 1;
        return glGetQueryObjectui64(oldest, GL_QUERY_RESULT);
    }

    /**
     * Deletes the queries.
     */
    public void dispose() {
        for (int q : queries) {
            glDeleteQueries(q);
        }
    }
}
