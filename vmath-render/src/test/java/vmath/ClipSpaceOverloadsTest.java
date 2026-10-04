package vmath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import vmath.lighting.ClusterGrid;
import vmath.core.ClipSpace;
import vmath.geo.DepthRange;
import vmath.gpucull.HiZPyramid;

/**
 * The {@link ClipSpace} overloads of {@link ClusterGrid} and {@link HiZPyramid}: each is the
 * overload with the explicit flags, called with what the clip space says.
 */
class ClipSpaceOverloadsTest {

    @Test
    void clusterGridFollowsTheYDirectionOfTheClipSpace() {
        for (ClipSpace space : ClipSpace.values()) {
            ClusterGrid byFlag = ClusterGrid.of(1f, 1.5f, 0.2f, 500f, 1280, 720, 64, 24, space.yDown());
            ClusterGrid bySpace = ClusterGrid.of(1f, 1.5f, 0.2f, 500f, 1280, 720, 64, 24, space);
            assertEquals(byFlag.yDown(), bySpace.yDown(), space.toString());
            assertEquals(byFlag.clusterCount(), bySpace.clusterCount());
            assertEquals(byFlag.near(), bySpace.near());
            assertEquals(byFlag.far(), bySpace.far());
        }
    }

    @Test
    void hiZPyramidTakesDepthRangeAndYDirectionFromTheClipSpace() {
        float[] depth = new float[16 * 8];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = (i * 37 % 101) / 100f;
        }
        for (ClipSpace space : ClipSpace.values()) {
            HiZPyramid byFlags = HiZPyramid.fromDepth(depth, 16, 8, DepthRange.of(space), space.yDown());
            HiZPyramid bySpace = HiZPyramid.fromDepth(depth, 16, 8, space);
            assertEquals(byFlags.yDown(), bySpace.yDown(), space.toString());
            assertEquals(byFlags.depthRange(), bySpace.depthRange());
            assertEquals(byFlags.levels(), bySpace.levels());
            for (int l = 0; l < byFlags.levels(); l++) {
                assertArrayEquals(byFlags.level(l), bySpace.level(l));
            }
        }
    }
}
