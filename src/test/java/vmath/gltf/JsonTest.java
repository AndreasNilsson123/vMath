package vmath.gltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesEveryValueType() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(" {\"a\": [1, 2.5, -3e2, true, false, null, \"x\"], \"b\": {}, \"c\": []} ");
        @SuppressWarnings("unchecked")
        List<Object> a = (List<Object>) m.get("a");
        assertEquals(List.of(1.0, 2.5, -300.0, true, false), a.subList(0, 5));
        assertNull(a.get(5));
        assertEquals("x", a.get(6));
        assertTrue(((Map<?, ?>) m.get("b")).isEmpty());
        assertTrue(((List<?>) m.get("c")).isEmpty());
        assertEquals(List.of("a", "b", "c"), List.copyOf(m.keySet()), "key order is kept");
    }

    @Test
    void numbersFollowTheGrammar() {
        assertEquals(0.0, Json.parse("0"));
        assertEquals(-0.0, (Double) Json.parse("-0"), 0.0);
        assertEquals(1000.0, Json.parse("1E3"));
        assertEquals(0.015, Json.parse("1.5e-2"));
        for (String bad : new String[] {"01", "1.", ".5", "-", "+1", "1e", "1e+", "--1", "0x10", "NaN", "Infinity", "1e999"}) {
            assertThrows(GltfException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void stringsAndEscapes() {
        assertEquals("a\"b\\c/d\b\f\n\r\t", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""));
        assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
        assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""), "a surrogate pair joins");
        assertEquals("héllo", Json.parse("\"héllo\""));
        for (String bad : new String[] {"\"abc", "\"a\\qb\"", "\"\\u12\"", "\"\\u12g4\"", "\"a\nb\"", "\"a\tb\"", "\"\\"}) {
            assertThrows(GltfException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void structuralErrors() {
        for (String bad : new String[] {"", "   ", "{", "[1,", "[1 2]", "{\"a\" 1}", "{\"a\":}", "{a:1}", "[1,]", "{\"a\":1,}", "tru", "nul", "[] x", "{} {}", "'a'"}) {
            assertThrows(GltfException.class, () -> Json.parse(bad), "<" + bad + ">");
        }
    }

    @Test
    void nestingIsLimited() {
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Json.parse(ok);
        String deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2);
        assertThrows(GltfException.class, () -> Json.parse(deep));
        String huge = "[".repeat(100_000);
        assertThrows(GltfException.class, () -> Json.parse(huge), "hostile nesting must not overflow the stack");
    }

    @Test
    void duplicateKeysTakeTheLast() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\":1,\"a\":2}");
        assertEquals(2.0, m.get("a"));
    }
}
