package org.hedgewars.android.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the lobby protocol (one object per line). Objects are
 * {@code Map<String,Object>}, arrays {@code List<Object>}, numbers {@code Long}, plus String, Boolean and null.
 * Written here so that the relay core runs on a plain JVM and on Android without org.json.
 */
public final class Json {
    /** Limits for text that comes from other phones: nesting, elements per container, nodes in all, string length. */
    public static final int MAX_DEPTH = 32, MAX_ITEMS = 4096, MAX_NODES = 20000, MAX_STRING = 65536;

    private final String s;
    private int i, depth, nodes;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing text at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("not an object");
        return (Map<String, Object>) o;
    }

    private void ws() {
        while (i < s.length() && s.charAt(i) <= ' ') i++;
    }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        if (++nodes > MAX_NODES) throw new IllegalArgumentException("too many values");
        char c = s.charAt(i);
        if (c == '{' || c == '[') {
            if (++depth > MAX_DEPTH) throw new IllegalArgumentException("nested more than " + MAX_DEPTH + " levels");
            Object o = c == '{' ? object() : array();
            depth--;
            return o;
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        if (c == '-') i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (st == i || (c == '-' && i == st + 1) || i - st > 18) throw new IllegalArgumentException("bad value at " + st);
        return Long.valueOf(s.substring(st, i));
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        for (;;) {
            if (m.size() >= MAX_ITEMS) throw new IllegalArgumentException("object too large");
            ws();
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i++) != ':') throw new IllegalArgumentException("expected : at " + (i - 1));
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("expected , at " + (i - 1));
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        for (;;) {
            if (l.size() >= MAX_ITEMS) throw new IllegalArgumentException("array too large");
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("expected , at " + (i - 1));
        }
    }

    private String string() {
        if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
        i++;
        StringBuilder b = new StringBuilder();
        for (;;) {
            if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
            if (b.length() > MAX_STRING) throw new IllegalArgumentException("string too long");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    // Writing ---------------------------------------------------------------------------------

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) quote(b, (String) o);
        else if (o instanceof Number || o instanceof Boolean) b.append(o);
        else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object x : (Iterable<Object>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, x);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + o.getClass());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // Typed access ----------------------------------------------------------------------------

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : def;
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    /** Builds an object from alternating keys and values. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }
}
