package vmath.core;

import java.util.Locale;

/**
 * The text side of the vector, quaternion and matrix types: the tolerant reading of a list of
 * numbers that {@code parse} of each type uses, and the fixed-point formatting that its
 * {@code format} uses.
 *
 * <p><b>What {@link #tokens} accepts.</b> Any of the forms that the types print or that people
 * write: the {@code toString} of a record ({@code Vec3f[x=1.0, y=2.0, z=3.0]}), the compact form
 * ({@code (1.0, 2.0, 3.0)}), the rows of a matrix as {@code format} prints them
 * ({@code | 1 0 0 | } on three lines), or a bare list ({@code 1 2 3}, {@code 1,2,3},
 * {@code [1; 2; 3]}). Brackets of every kind ({@code ()[]{}}) and the bar {@code |} are ignored,
 * and numbers are separated by commas, semicolons or white space. If the numbers carry labels
 * ({@code x=1}) every one must, and they may come in any order; without labels they are taken in
 * the order of the type (the rows from the top for a matrix, which is how it is printed). A type
 * name in front of a bracket must be the name of the type that parses (a {@code Vec3f} does not
 * read {@code Quatf[...]}).
 *
 * <p>Internal: the public surface is {@code parse}, {@code toCompactString}, {@code format} and
 * {@code toMatrixString} of the types.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 */
final class Text {

    static final String[] VEC2 = {"x", "y"};
    static final String[] VEC3 = {"x", "y", "z"};
    static final String[] VEC4 = {"x", "y", "z", "w"};
    static final String[] MAT2 = {"m00", "m01", "m10", "m11"};
    static final String[] MAT3 = {"m00", "m01", "m02", "m10", "m11", "m12", "m20", "m21", "m22"};
    static final String[] MAT4 = {"m00", "m01", "m02", "m03", "m10", "m11", "m12", "m13", "m20", "m21", "m22", "m23", "m30", "m31", "m32", "m33"};
    /** For an unlabelled matrix: position {@code k} in reading order (row by row) is the component {@code order[k]} of the record (column by column). */
    static final int[] ROWS2 = transposed(2);
    static final int[] ROWS3 = transposed(3);
    static final int[] ROWS4 = transposed(4);

    private Text() {
    }

    private static int[] transposed(int n) {
        int[] order = new int[n * n];
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                order[row * n + col] = col * n + row;
            }
        }
        return order;
    }

    /**
     * Splits {@code text} into the numbers of a value of type {@code type}, in the order of the
     * record's components.
     *
     * @param text the text; must not be {@code null}
     * @param type the name of the type, such as {@code "Vec3f"}, for the optional prefix and the messages
     * @param labels the component names in record order
     * @param order for unlabelled text: where the k-th number goes in record order; {@code null} for the identity
     * @return the number texts, {@code labels.length} of them, in record order
     * @throws IllegalArgumentException if the text has the wrong number of values, mixes labelled and
     *     unlabelled ones, repeats or does not know a label, or starts with the name of another type
     */
    static String[] tokens(CharSequence text, String type, String[] labels, int[] order) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        String s = text.toString().trim();
        int n = labels.length;
        // an optional type name before the first bracket
        int id = 0;
        while (id < s.length() && Character.isJavaIdentifierPart(s.charAt(id))) {
            id++;
        }
        if (id > 0 && id < s.length() && isOpen(s.charAt(id)) && Character.isUpperCase(s.charAt(0))) {
            String name = s.substring(0, id);
            if (!name.equals(type)) {
                throw new IllegalArgumentException("expected a " + type + " but the text starts with " + name + ": " + abbreviate(s));
            }
            s = s.substring(id);
        }
        String[] found = new String[n];
        int count = 0;
        boolean labelled = false, unlabelled = false;
        int i = 0, len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (isSeparator(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < len && !isSeparator(s.charAt(i))) {
                i++;
            }
            String token = s.substring(start, i);
            int eq = token.indexOf('=');
            String value = token;
            int slot = count;
            if (eq >= 0) {
                String label = token.substring(0, eq);
                value = token.substring(eq + 1);
                slot = indexOf(labels, label);
                if (slot < 0) {
                    throw new IllegalArgumentException("unknown component '" + label + "' for a " + type + " (expected " + String.join(", ", labels) + "): " + abbreviate(s));
                }
                labelled = true;
            } else {
                unlabelled = true;
            }
            if (labelled && unlabelled) {
                throw new IllegalArgumentException("either every number of a " + type + " has a label or none: " + abbreviate(s));
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("a label without a number in " + abbreviate(s));
            }
            if (count >= n) {
                count++;
                continue; // counted for the message below
            }
            if (!labelled && order != null) {
                slot = order[slot];
            }
            if (found[slot] != null) {
                throw new IllegalArgumentException("the component " + labels[slot] + " of a " + type + " is given twice: " + abbreviate(s));
            }
            found[slot] = value;
            count++;
        }
        if (count != n) {
            throw new IllegalArgumentException("a " + type + " has " + n + " numbers, the text has " + count + ": " + abbreviate(s));
        }
        return found;
    }

    private static boolean isOpen(char c) {
        return c == '[' || c == '(' || c == '{';
    }

    private static boolean isSeparator(char c) {
        return c == ',' || c == ';' || c == '|' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || Character.isWhitespace(c);
    }

    private static int indexOf(String[] labels, String label) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) {
                return i;
            }
        }
        return -1;
    }

    private static String abbreviate(String s) {
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() <= 80 ? "'" + flat + "'" : "'" + flat.substring(0, 77) + "...'";
    }

    /**
     * Formats a number with a fixed count of decimals in the locale-independent way (a point, no
     * grouping); {@code NaN} and the infinities print as words.
     *
     * @param v the value
     * @param decimals the digits after the point, 0 to 17
     * @return the text
     * @throws IllegalArgumentException if {@code decimals} is outside 0 to 17
     */
    static String fixed(double v, int decimals) {
        if (decimals < 0 || decimals > 17) {
            throw new IllegalArgumentException("decimals must be from 0 to 17: " + decimals);
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /**
     * Lays out a square matrix, given row by row, as lines of right-aligned columns between bars.
     *
     * @param rowMajor the {@code n * n} values, row by row
     * @param n the size
     * @param decimals the digits after the point
     * @return the lines joined with a line feed, without a final one
     */
    static String matrix(double[] rowMajor, int n, int decimals) {
        String[] cells = new String[n * n];
        int width = 0;
        for (int i = 0; i < cells.length; i++) {
            cells[i] = fixed(rowMajor[i], decimals);
            width = Math.max(width, cells[i].length());
        }
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < n; row++) {
            if (row > 0) {
                out.append('\n');
            }
            out.append('|');
            for (int col = 0; col < n; col++) {
                String cell = cells[row * n + col];
                out.append(' ');
                for (int pad = cell.length(); pad < width; pad++) {
                    out.append(' ');
                }
                out.append(cell);
            }
            out.append(" |");
        }
        return out.toString();
    }
}
