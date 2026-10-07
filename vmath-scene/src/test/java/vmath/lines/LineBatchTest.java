package vmath.lines;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link LineStyle} and {@link LineBatch}: validation, the style table, the drawing order, the
 * origin and the double-precision relative matrix.
 */
class LineBatchTest {

    private static final LineStyle S = LineStyle.pixels(2f);

    @Test
    void stylesAreValuesAndRefuseNonsense() {
        assertEquals(LineStyle.pixels(3f).withColor(0x11223344).withDash(1f, 2f), LineStyle.pixels(3f).withColor(0x11223344).withDash(1f, 2f));
        assertEquals(LineStyle.pixels(3f).hashCode(), LineStyle.pixels(3f).hashCode());
        assertNotEquals(LineStyle.pixels(3f), LineStyle.world(3f));
        assertNotEquals(LineStyle.pixels(3f), LineStyle.pixels(3f).withLayer(1));
        for (float bad : new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> LineStyle.pixels(bad), "width " + bad);
            assertThrows(IllegalArgumentException.class, () -> LineStyle.world(bad));
            assertThrows(IllegalArgumentException.class, () -> S.withWidth(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> S.withMiterLimit(0.5f));
        assertThrows(IllegalArgumentException.class, () -> S.withMiterLimit(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> S.withDash(1f), "an odd count");
        assertThrows(IllegalArgumentException.class, () -> S.withDash(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), "more than eight");
        assertThrows(IllegalArgumentException.class, () -> S.withDash(0f, 0f), "no length");
        assertThrows(IllegalArgumentException.class, () -> S.withDash(-1f, 2f));
        assertThrows(IllegalArgumentException.class, () -> S.withDash(Float.NaN, 2f));
        assertEquals(0, S.withDash().dashCount(), "an empty pattern is a solid line");
        assertEquals(7.0, S.withDash(3f, 4f).dashPeriod());
    }

    @Test
    void dashesInPixelsAreConvertedToWorldUnits() {
        LineStyle s = S.dashedInPixels(0.5f, 10f, 4f);
        assertArrayEquals(new float[] {5f, 2f}, s.dash());
        assertThrows(IllegalArgumentException.class, () -> S.dashedInPixels(0f, 1f, 1f));
    }

    @Test
    void theDashOfAStyleIsACopy() {
        float[] d = {1f, 2f};
        LineStyle s = S.withDash(d);
        d[0] = 99f;
        s.dash()[1] = 99f;
        assertArrayEquals(new float[] {1f, 2f}, s.dash());
    }

    @Test
    void polylinesNeedEnoughDistinctPoints() {
        LineBatch b = new LineBatch();
        assertThrows(IllegalArgumentException.class, () -> b.addPolyline(new double[] {0, 0, 0}, 0, 1, false, S));
        assertThrows(IllegalArgumentException.class, () -> b.addPolyline(new double[] {0, 0, 0, 0, 0, 0}, 0, 2, false, S), "two equal points are one");
        assertThrows(IllegalArgumentException.class, () -> b.addPolyline(new double[] {0, 0, 0, 1, 0, 0}, 0, 2, true, S), "a closed polyline needs three");
        assertThrows(IllegalArgumentException.class, () -> b.addPolyline(new double[] {0, 0, 0, Double.NaN, 0, 0}, 0, 2, false, S));
        assertThrows(IllegalArgumentException.class, () -> b.addPolyline(new double[] {0, 0, 0, Double.POSITIVE_INFINITY, 0, 0}, 0, 2, false, S));
        assertThrows(IllegalArgumentException.class, () -> b.addPolyline(new double[] {0, 0, 0}, 0, 2, false, S), "the array is too short");
        assertEquals(0, b.polylineCount(), "refused polylines are not added");
        assertEquals(0, b.addPolyline(new double[] {0, 0, 0, 1, 0, 0}, 0, 2, false, S));
        assertEquals(1, b.segmentCount());
    }

    @Test
    void repeatedPointsAndAClosingPointAreRemoved() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0}, 0, 6, true, S);
        assertEquals(3, b.pointCount(0), "the doubled points and the repeated first point are gone");
        assertEquals(3, b.segmentCount(), "a closed polyline of three points has three segments");
        b.addPolyline(new double[] {0, 0, 0, 1, 0, 0, 1, 1, 0}, 0, 3, false, S);
        assertEquals(5, b.segmentCount(), "an open one of three points has two");
        assertTrue(b.isClosed(0) && !b.isClosed(1));
    }

    @Test
    void theStyleTableHoldsEachStyleOnce() {
        LineBatch b = new LineBatch();
        double[] p = {0, 0, 0, 1, 0, 0};
        b.addPolyline(p, 0, 2, false, S);
        b.addPolyline(p, 0, 2, false, LineStyle.pixels(2f));
        b.addPolyline(p, 0, 2, false, S.withColor(0xFF0000FF));
        assertEquals(2, b.styleCount());
        assertEquals(b.styleIndexOf(0), b.styleIndexOf(1));
        assertNotEquals(b.styleIndexOf(0), b.styleIndexOf(2));
        assertEquals(S.withColor(0xFF0000FF), b.style(b.styleIndexOf(2)));
    }

    @Test
    void polylinesAreDrawnByLayerThenByInsertion() {
        LineBatch b = new LineBatch();
        double[] p = {0, 0, 0, 1, 0, 0};
        b.addPolyline(p, 0, 2, false, S.withLayer(2));
        b.addPolyline(p, 0, 2, false, S.withLayer(-1));
        b.addPolyline(p, 0, 2, false, S.withLayer(2));
        b.addPolyline(p, 0, 2, false, S.withLayer(-1));
        b.addPolyline(p, 0, 2, false, S);
        assertArrayEquals(new int[] {1, 3, 4, 0, 2}, b.drawOrder());
        b.addPolyline(p, 0, 2, false, S.withLayer(-5));
        assertArrayEquals(new int[] {5, 1, 3, 4, 0, 2}, b.drawOrder(), "the order follows additions");
    }

    @Test
    void boundsAndPointsAreKeptInDouble() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {4_000_000.25, -3, 7, 4_000_100.5, 9, -2, 3_999_990.0, 1, 0}, 0, 3, false, S);
        double[] box = new double[6];
        b.bounds(0, box);
        assertArrayEquals(new double[] {3_999_990.0, -3, -2, 4_000_100.5, 9, 7}, box);
        assertEquals(4_000_100.5, b.coordinate(0, 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.coordinate(0, 3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.coordinate(1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.coordinate(0, 0, 3));
    }

    @Test
    void relativePointsKeepPrecisionNearTheOriginAndFloatsAloneDoNot() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {4_000_000.125, 3_000_000.25, 0, 4_000_001.375, 3_000_000.5, 0}, 0, 2, false, S);
        b.setOrigin(4_000_000.0, 3_000_000.0, 0.0);
        float[] p = new float[3];
        b.relativePoint(0, 0, p, 0);
        assertArrayEquals(new float[] {0.125f, 0.25f, 0f}, p, 0f);
        b.relativePoint(0, 1, p, 0);
        assertArrayEquals(new float[] {1.375f, 0.5f, 0f}, p, 0f);
        assertNotEquals(4_000_000.125, (double) (float) 4_000_000.125, "a float near 4e6 has a spacing of 0.25: this is what the origin avoids");
        assertEquals(4_000_000.0, b.originX());
        assertEquals(3_000_000.0, b.originY());
        assertEquals(0.0, b.originZ());
        assertThrows(IllegalArgumentException.class, () -> b.setOrigin(Double.NaN, 0, 0));
    }

    @Test
    void theOriginIsTooFarWhenTheCameraHasMovedAwayFromIt() {
        LineBatch b = new LineBatch();
        b.setOrigin(1000, 0, 0);
        assertFalse(b.originTooFar(1000, 0, 0, 5000));
        assertFalse(b.originTooFar(5999, 0, 0, 5000));
        assertTrue(b.originTooFar(6001, 0, 0, 5000));
        assertTrue(b.originTooFar(1000, 3001, 4001, 5000), "the distance is three-dimensional");
        assertFalse(b.originTooFar(1000, 3000, 3999, 5000));
    }

    @Test
    void theRelativeMatrixIsTheProductInDoublePrecision() {
        LineBatch b = new LineBatch();
        b.setOrigin(4_000_000.0, 3_000_000.0, 12.0);
        double[] vp = new double[16];
        for (int i = 0; i < 16; i++) {
            vp[i] = 0.001 * (i + 1) + (i % 5 == 0 ? 1.0 : 0.0);
        }
        float[] m = new float[16];
        b.relativeViewProjection(vp, m);
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 4; r++) {
                assertEquals((float) vp[c * 4 + r], m[c * 4 + r]);
            }
        }
        for (int r = 0; r < 4; r++) {
            double expected = vp[r] * 4_000_000.0 + vp[4 + r] * 3_000_000.0 + vp[8 + r] * 12.0 + vp[12 + r];
            assertEquals((float) expected, m[12 + r], "row " + r);
        }
        assertThrows(IllegalArgumentException.class, () -> b.relativeViewProjection(new double[15], m));
        assertThrows(IllegalArgumentException.class, () -> b.relativeViewProjection(vp, new float[15]));
    }

    @Test
    void clearKeepsTheOriginAndEmptiesTheRest() {
        LineBatch b = new LineBatch();
        b.setOrigin(1, 2, 3);
        b.addPolyline(new double[] {0, 0, 0, 1, 0, 0}, 0, 2, false, S);
        b.clear();
        assertEquals(0, b.polylineCount());
        assertEquals(0, b.segmentCount());
        assertEquals(0, b.styleCount());
        assertEquals(1.0, b.originX());
        assertEquals(0, b.drawOrder().length);
        assertEquals(0, b.addPolyline(new double[] {5, 5, 5, 6, 5, 5}, 0, 2, false, S));
    }

    @Test
    void manyPolylinesGrowTheBatch() {
        LineBatch b = new LineBatch();
        for (int i = 0; i < 1000; i++) {
            b.addPolyline(new double[] {i, 0, 0, i + 1, 1, 0, i + 2, 0, 0}, 0, 3, i % 2 == 0, S.withColor(i % 7));
        }
        assertEquals(1000, b.polylineCount());
        assertEquals(7, b.styleCount());
        assertEquals(500 * 3 + 500 * 2, b.segmentCount());
        assertEquals(999, b.coordinate(999, 0, 0));
    }

    @Test
    void relativeValuesAreRefusedForMissingPoints() {
        LineBatch b = new LineBatch();
        b.addPolyline(new double[] {0, 0, 0, 1, 0, 0}, 0, 2, false, S);
        assertThrows(IndexOutOfBoundsException.class, () -> b.relativePoint(0, 2, new float[3], 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.relativePoint(1, 0, new float[3], 0));
        assertThrows(IndexOutOfBoundsException.class, () -> b.style(1));
        assertThrows(IndexOutOfBoundsException.class, () -> b.styleIndexOf(3));
        assertThrows(IndexOutOfBoundsException.class, () -> b.pointCount(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> b.isClosed(2));
    }
}
