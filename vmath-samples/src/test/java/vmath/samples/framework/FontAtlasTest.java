package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of the font atlas of the heads-up display.
 *
 * <p><b>Thread safety.</b> The atlas is immutable; the tests may run in parallel.
 */
class FontAtlasTest {

    private static final FontAtlas ATLAS = FontAtlas.bake(15);

    private static int coverageOfCell(int cell) {
        int cw = ATLAS.cellWidth(), ch = ATLAS.cellHeight();
        int x0 = (cell % 16) * cw, y0 = (cell / 16) * ch;
        int sum = 0;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                sum += ATLAS.coverage()[(y0 + y) * ATLAS.width() + x0 + x] & 255;
            }
        }
        return sum;
    }

    @Test
    void theImageIsAGridOfCellsHoldingEveryGlyphAndTheSolidBlock() {
        assertEquals(16 * ATLAS.cellWidth(), ATLAS.width());
        assertTrue(ATLAS.height() >= ((FontAtlas.solidCell() / 16) + 1) * ATLAS.cellHeight());
        assertEquals(ATLAS.width() * ATLAS.height(), ATLAS.coverage().length);
        assertEquals(95, FontAtlas.solidCell());
    }

    @Test
    void glyphsHaveInkAndTheSpaceHasNone() {
        assertEquals(0, coverageOfCell(FontAtlas.cell(' ')));
        for (char c = '!'; c <= '~'; c++) {
            assertTrue(coverageOfCell(FontAtlas.cell(c)) > 0, "no ink for " + c);
        }
    }

    @Test
    void theSolidCellIsCoveredEverywhereInside() {
        int cell = FontAtlas.solidCell();
        int cw = ATLAS.cellWidth(), ch = ATLAS.cellHeight();
        int x0 = (cell % 16) * cw, y0 = (cell / 16) * ch;
        for (int y = 1; y < ch - 1; y++) {
            for (int x = 1; x < cw - 1; x++) {
                assertEquals(255, ATLAS.coverage()[(y0 + y) * ATLAS.width() + x0 + x] & 255);
            }
        }
    }

    @Test
    void charactersOutsideAsciiAreShownAsQuestionMarks() {
        assertEquals(FontAtlas.cell('?'), FontAtlas.cell('å'));
        assertEquals(FontAtlas.cell('?'), FontAtlas.cell('\n'));
    }

    @Test
    void textureCoordinatesStayInsideTheCellAndTheImage() {
        for (int cell = 0; cell <= FontAtlas.solidCell(); cell++) {
            assertTrue(ATLAS.u0(cell) >= 0f && ATLAS.u1(cell) <= 1f && ATLAS.u0(cell) < ATLAS.u1(cell));
            assertTrue(ATLAS.v0(cell) >= 0f && ATLAS.v1(cell) <= 1f && ATLAS.v0(cell) < ATLAS.v1(cell));
        }
        assertEquals((ATLAS.cellWidth() - 2f) / ATLAS.width(), ATLAS.u1(3) - ATLAS.u0(3), 1e-6f);
    }
}
