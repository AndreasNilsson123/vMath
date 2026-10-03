package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;

@GenerateDouble
class FrameTransformfTest {
    @Eps(d = 1e-9)
    static final float EPS = 5e-4f;

    final Rnd rnd = Rnd.create();

    static final Frame WORLD = Frame.of("world");
    static final Frame SHIP = Frame.of("ship");
    static final Frame GUN = Frame.of("gun");

    private Transformf random() {
        return new Transformf(rnd.nextVec3f(), rnd.nextUnitQuatf(), new Vec3f(2f, 2f, 2f));
    }

    @Test
    void composesWhenTheFramesMeetAndAppliesTheInnerTransformFirst() {
        for (int i = 0; i < N; i++) {
            FrameTransformf worldFromShip = FrameTransformf.of(SHIP, WORLD, random());
            FrameTransformf shipFromGun = FrameTransformf.of(GUN, SHIP, random());
            FrameTransformf worldFromGun = worldFromShip.mul(shipFromGun);
            check(worldFromGun.source().equals(GUN) && worldFromGun.target().equals(WORLD), i, "the frames of the product");
            Vec3f p = rnd.nextVec3f();
            check(worldFromGun.transformPosition(p).approxEquals(worldFromShip.transformPosition(shipFromGun.transformPosition(p)), EPS), i, "position");
            check(worldFromGun.transformDirection(p).approxEquals(worldFromShip.transformDirection(shipFromGun.transformDirection(p)), EPS), i, "direction");
            check(worldFromGun.toMat4().approxEquals(worldFromShip.toMat4().mul(shipFromGun.toMat4()), EPS), i, "matrix");
        }
    }

    @Test
    void composingTheWrongFramesIsAnError() {
        FrameTransformf worldFromShip = FrameTransformf.of(SHIP, WORLD, Transformf.IDENTITY);
        FrameTransformf shipFromGun = FrameTransformf.of(GUN, SHIP, Transformf.IDENTITY);
        boolean threw = false;
        try {
            shipFromGun.mul(worldFromShip);
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().contains("ship") && e.getMessage().contains("world") && e.getMessage().contains("gun");
        }
        check(threw, 0, "the wrong order names the frames");
        threw = false;
        try {
            worldFromShip.mul(worldFromShip);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, 1, "a transform cannot be composed with itself unless it is a loop");
        check(FrameTransformf.identity(WORLD).mul(FrameTransformf.identity(WORLD)).approxEquals(FrameTransformf.identity(WORLD), 0f), 2, "a loop can");
    }

    @Test
    void theInverseSwapsTheFrames() {
        for (int i = 0; i < N; i++) {
            FrameTransformf a = FrameTransformf.of(SHIP, WORLD, random());
            FrameTransformf inverse = a.inverse();
            check(inverse.source().equals(WORLD) && inverse.target().equals(SHIP), i, "swapped");
            Vec3f p = rnd.nextVec3f();
            check(inverse.transformPosition(a.transformPosition(p)).approxEquals(p, EPS), i, "round trip");
            check(a.inverse().mul(a).approxEquals(FrameTransformf.identity(SHIP), EPS * 10f), i, "inverse * a is the identity of the source frame");
        }
    }

    @Test
    void thePointsFrameIsChecked() {
        FrameTransformf a = FrameTransformf.of(SHIP, WORLD, Transformf.ofTranslation(new Vec3f(1f, 2f, 3f)));
        check(a.transformPosition(SHIP, Vec3f.ZERO).approxEquals(new Vec3f(1f, 2f, 3f), 0f), 0, "the right frame");
        check(a.transformDirection(SHIP, new Vec3f(1f, 0f, 0f)).approxEquals(new Vec3f(1f, 0f, 0f), 0f), 1, "a direction");
        boolean threw = false;
        try {
            a.transformPosition(WORLD, Vec3f.ZERO);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, 2, "a point in the target frame is refused");
        threw = false;
        try {
            a.transformDirection(GUN, Vec3f.ZERO);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, 3, "and so is a direction");
    }

    @Test
    void nullsAreRefusedAndTheComparisonLooksAtTheFrames() {
        boolean threw = false;
        try {
            FrameTransformf.of(null, WORLD, Transformf.IDENTITY);
        } catch (NullPointerException e) {
            threw = true;
        }
        check(threw, 0, "null source");
        FrameTransformf a = FrameTransformf.of(SHIP, WORLD, Transformf.IDENTITY);
        check(a.approxEquals(FrameTransformf.of(SHIP, WORLD, Transformf.IDENTITY), 0f), 1, "equal");
        check(!a.approxEquals(FrameTransformf.of(GUN, WORLD, Transformf.IDENTITY), 0f), 2, "different source");
        check(!a.approxEquals(FrameTransformf.of(SHIP, GUN, Transformf.IDENTITY), 0f), 3, "different target");
        check(a.isFinite(), 4, "finite");
        check(!FrameTransformf.of(SHIP, WORLD, Transformf.ofTranslation(new Vec3f(Float.NaN, 0f, 0f))).isFinite(), 5, "NaN");
        check(a.toString().contains("ship -> world"), 6, "toString names the frames: " + a);
    }
}
