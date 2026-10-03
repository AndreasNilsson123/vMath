package vmath.gltf;

import java.util.List;
import java.util.Map;

/**
 * A small strict JSON parser for glTF: objects become {@code Map<String, Object>}, arrays {@code List<Object>}, numbers {@code Double}, strings
 * {@code String}, booleans {@code Boolean} and null {@code null}. Nesting is limited to {@value #MAX_DEPTH} levels so that hostile input cannot overflow the
 * stack, and anything after the value, unescaped control characters in strings and malformed numbers are errors.
 */
final class Json {

    static final int MAX_DEPTH = 200;

    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object v = p.value(0);
        p.skipWhitespace();
        if (p.pos != p.s.length()) {
            throw p.error("unexpected content after the value");
        }
        return v;
    }

    private GltfException error(String message) {
        return new GltfException("JSON: " + message + " at character " + pos);
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH);
        }
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
        return value;
    }

    private JsonObject object(int depth) {
        pos++; // {
        JsonObject map = new JsonObject();
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw error("expected a string key");
            }
            String key = string();
            skipWhitespace();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipWhitespace();
            map.put(key, value(depth + 1));
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unterminated object");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private JsonArray array(int depth) {
        pos++; // [
        JsonArray list = new JsonArray();
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth + 1));
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unterminated array");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder sb = null;
        int start = pos;
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                String out = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                pos++;
                return out;
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(s, start, pos);
                pos++;
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("truncated \\u escape");
                        }
                        int cp = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) {
                                throw error("bad \\u escape");
                            }
                            cp = cp * 16 + d;
                        }
                        pos += 4;
                        sb.append((char) cp); // surrogate pairs arrive as two escapes and join in the builder
                    }
                    default -> throw error("bad escape \\" + e);
                }
                start = pos;
            } else {
                pos++;
            }
        }
    }

    private Double number() {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= s.length()) {
            throw error("bad number");
        }
        if (s.charAt(pos) == '0') {
            pos++;
        } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
            while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) {
                pos++;
            }
        } else {
            throw error("bad number");
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int digits = 0;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw error("bad number: no digits after '.'");
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            int digits = 0;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw error("bad number: no digits in the exponent");
            }
        }
        double d = Double.parseDouble(s.substring(start, pos));
        if (Double.isInfinite(d)) {
            throw error("number out of range");
        }
        return d;
    }
}
