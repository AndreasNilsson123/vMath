package vmath.bulk;

/** The two ways of closing gaps in an array of fixed-size elements, shared by the containers. */
final class Compaction {

    private Compaction() {
    }

    /**
     * Moves the last element of {@code data[0..size)} into slot {@code i}.
     *
     * @return the index the moved element had (the old last index), or -1 if {@code i} was the last element and nothing moved
     */
    static int swapRemove(float[] data, int stride, int size, int i) {
        int last = size - 1;
        if (i == last) {
            return -1;
        }
        System.arraycopy(data, last * stride, data, i * stride, stride);
        return last;
    }

    /** Keeps the elements whose bit is set in {@code keep}, moving them down in their original order. Returns the new size. */
    static int stable(float[] data, int stride, int size, VisibilitySet keep) {
        int out = 0;
        int i = keep.nextSetBit(0);
        while (i >= 0 && i < size) {
            if (out != i) {
                System.arraycopy(data, i * stride, data, out * stride, stride);
            }
            out++;
            i = keep.nextSetBit(i + 1);
        }
        return out;
    }
}
