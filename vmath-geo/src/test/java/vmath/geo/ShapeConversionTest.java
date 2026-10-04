package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;

/** Float shapes converted to double and back must come out identical: {@code toFloat} on the double twins mirrors {@code toDouble}. */
class ShapeConversionTest {

    final Rnd rnd = Rnd.create();

    @Test
    void everyShapeSurvivesAFloatDoubleFloatRoundTrip() {
        for (int i = 0; i < Rnd.N; i++) {
            Planef plane = Planef.fromPointNormal(rnd.nextVec3f(), rnd.nextVec3f().normalize());
            assertEquals(plane, plane.toDouble().toFloat());
            Rayf ray = Rayf.of(rnd.nextVec3f(), rnd.nextVec3f());
            assertEquals(ray, ray.toDouble().toFloat());
            Trianglef tri = Trianglef.of(rnd.nextVec3f(), rnd.nextVec3f(), rnd.nextVec3f());
            assertEquals(tri, tri.toDouble().toFloat());
            Obbf obb = Obbf.of(rnd.nextVec3f(), rnd.nextVec3f().abs().add(Vec3f.splat(0.1f)), rnd.nextUnitQuatf());
            assertEquals(obb, obb.toDouble().toFloat());
            Frustumf frustum = Frustumf.fromViewProjection(
                    Mat4f.perspective(1f, 1.5f, 0.1f, 50f, ClipSpace.D3D).mul(Mat4f.lookAt(rnd.nextVec3f(), Vec3f.ZERO, Vec3f.UNIT_Y)), DepthRange.ZERO_TO_ONE);
            assertEquals(frustum, frustum.toDouble().toFloat());
            Quatf unused = Quatf.IDENTITY;
            assertEquals(Quatf.IDENTITY, unused);
        }
    }
}
