package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;
import vmath.core.Mat3f;
import vmath.core.Vec3f;

class Std140Test {

    @Test
    void mat3IsThreePaddedColumns() {
        Mat3f m = new Mat3f(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f);
        FloatBuffer buf = FloatBuffer.allocate(Std140.MAT3_FLOATS);
        for (int i = 0; i < buf.capacity(); i++) {
            buf.put(i, -1f); // garbage that must be overwritten
        }
        Std140.putMat3(buf, 0, m);
        float[] expected = {1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], buf.get(i), 0f, "index " + i);
        }
    }

    @Test
    void vec3IsPadded() {
        FloatBuffer buf = FloatBuffer.allocate(8);
        buf.put(7, -1f);
        Std140.putVec3(buf, 4, new Vec3f(1f, 2f, 3f));
        assertEquals(3f, buf.get(6), 0f, "z");
        assertEquals(0f, buf.get(7), 0f, "pad");
    }
}
