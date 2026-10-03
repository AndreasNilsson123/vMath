package vmath.gl;

/**
 * Alignment arithmetic shared by the layout types.
 */
final class Align {

    private Align() {
    }

    /**
     * Rounds {@code value} up to the next multiple of {@code multiple}.
     */
    static int roundUp(int value, int multiple) {
        return (value + multiple - 1) / multiple * multiple;
    }

    static long roundUp(long value, long multiple) {
        return (value + multiple - 1) / multiple * multiple;
    }
}
