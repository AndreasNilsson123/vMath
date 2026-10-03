package vmath.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds comment and string-literal ranges in Java source, which the tree API does not expose.
 */
final class Lexical {

    private Lexical() {
    }

    /**
     * Returns {@code [start, end)} pairs for comments, string literals and text blocks.
     */
    static List<int[]> textRanges(String s) {
        List<int[]> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int e = s.indexOf('\n', i);
                e = e < 0 ? n : e;
                out.add(new int[] {i, e});
                i = e;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                out.add(new int[] {i, e});
                i = e;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !s.startsWith("\"\"\"", j)) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                int e = Math.min(n, j + 3);
                out.add(new int[] {i, e});
                i = e;
            } else if (c == '"') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '"' && s.charAt(j) != '\n') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                int e = Math.min(n, j + 1);
                out.add(new int[] {i, e});
                i = e;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '\'' && s.charAt(j) != '\n') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                i = Math.min(n, j + 1);
            } else {
                i++;
            }
        }
        return out;
    }
}
