package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.*;

import java.io.PrintStream;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import vmath.gl.GpuWriter;
import vmath.mem.PersistentBufferRing;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GlFences;

/**
 * Exercises {@code PersistentBufferRing} with real fences on the OpenGL driver: a persistently and
 * coherently mapped buffer is cut into three regions, every frame writes a pattern of its own into its
 * region and dispatches a compute shader that is slow on purpose and reads the region only at its end, and
 * after all the frames every result must hold the pattern of its frame. If the ring let the CPU write a region
 * that the GPU had not finished with, the result of an earlier frame would hold a later frame's pattern.
 *
 * <p>The control runs the same frames without the ring's fences (every frame writes the same region at once):
 * on a GPU that is behind the CPU, which the slow shader makes it, results are overwritten, which shows that the
 * check can fail. The control is reported but does not fail the run if this GPU kept up.
 *
 * <p>Needs a display and an OpenGL 4.4 driver ({@code ARB_buffer_storage}); {@code ./gradlew -Psamples
 * :vmath-samples:gpuIt}. Internal: part of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class RingGpuCheck {

    private static final int FRAMES = 48;
    private static final int WORDS = 4096;
    private static final long REGION = WORDS * 4L;

    private static final String SHADER = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) readonly buffer In { uint src[]; };
            layout(std430, binding = 1) buffer Out { uint dst[]; };
            void main() {
                uint i = gl_GlobalInvocationID.x;
                uint acc = i;
                for (int k = 0; k < 60000; k++) {
                    acc = acc * 1664525u + 1013904223u;
                }
                if (acc == 0x9E3779B9u && i == 0xFFFFFFFFu) {
                    dst[0] ^= 1u;
                }
                dst[i] = src[i];
            }
            """;

    private RingGpuCheck() {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    private static int pattern(int frame, int word) {
        return frame * 1_000_003 + word * 7 + 1;
    }

    /**
     * Runs the frames; returns the number of frames whose result is not their own pattern and the number of stalls.
     *
     * @param useRing whether the fences of the ring are used
     */
    private static long[] run(boolean useRing) {
        int program = Gl.computeProgram(SHADER);
        int ringBuffer = glGenBuffers();
        int regions = 3;
        long size = regions * REGION;
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ringBuffer);
        glBufferStorage(GL_SHADER_STORAGE_BUFFER, size, flags);
        ByteBuffer mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, size, flags);
        MemorySegment segment = MemorySegment.ofBuffer(mapped);
        int result = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, result);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) FRAMES * REGION, GL_DYNAMIC_COPY);
        long stalls = 0;
        GlFences fences = new GlFences();
        try (PersistentBufferRing<Long> ring = new PersistentBufferRing<>(segment, regions, 256, fences)) {
            glUseProgram(program);
            for (int f = 0; f < FRAMES; f++) {
                long offset;
                if (useRing) {
                    ring.beginFrame();
                    offset = ring.allocate(REGION, 16);
                } else {
                    offset = 0;                                    // every frame writes the same region without waiting
                }
                MemorySegment slice = useRing ? ring.slice(offset, REGION) : segment.asSlice(0, REGION);
                for (int w = 0; w < WORDS; w++) {
                    GpuWriter.putInt(slice, 4L * w, pattern(f, w));
                }
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, ringBuffer, useRing ? ring.regionOffset() + (offset - ring.regionOffset()) : offset, REGION);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, result, (long) f * REGION, REGION);
                glDispatchCompute(WORDS / 64, 1, 1);
                if (useRing) {
                    ring.endFrame();
                }
            }
            stalls = useRing ? ring.stalls() : 0;
            ring.drain();
        }
        glFinish();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, result);
        ByteBuffer out = BufferUtils.createByteBuffer((int) ((long) FRAMES * REGION));
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, out);
        long wrong = 0;
        for (int f = 0; f < FRAMES; f++) {
            boolean ok = true;
            for (int w = 0; w < WORDS && ok; w++) {
                ok = out.getInt((int) ((long) f * REGION + 4L * w)) == pattern(f, w);
            }
            wrong += ok ? 0 : 1;
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ringBuffer);
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        glDeleteBuffers(ringBuffer);
        glDeleteBuffers(result);
        glDeleteProgram(program);
        Gl.check("running the ring check");
        return new long[] {wrong, stalls};
    }

    /**
     * Runs the check on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return the outcome with the ring, and the control without it
     */
    public static List<Outcome> check(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        long[] with;
        try {
            with = run(true);
        } catch (RuntimeException e) {
            with = new long[] {-1, 0};
            results.add(new Outcome("the ring with real fences", false, String.valueOf(e.getMessage())));
        }
        if (with[0] >= 0) {
            results.add(new Outcome("the ring with real fences", with[0] == 0, with[0] == 0 ? FRAMES + " frames, every result holds the pattern of its own frame, " + with[1] + " stalls of the ring"
                    : with[0] + " of " + FRAMES + " frames were overwritten while the GPU still read them"));
        }
        try {
            long[] without = run(false);
            results.add(new Outcome("control: the same frames without the fences", true, without[0] + " of " + FRAMES + " frames were overwritten while in use"
                    + (without[0] == 0 ? " (this GPU kept up: the control proves nothing here)" : " (so the check can fail, and the ring prevents it)")));
        } catch (RuntimeException e) {
            results.add(new Outcome("control: the same frames without the fences", true, "could not run: " + e.getMessage()));
        }
        if (out != null) {
            for (Outcome o : results) {
                out.println((o.ok() ? "ok    " : "FAIL  ") + o.name() + "\n      " + o.detail());
            }
        }
        return results;
    }
}
