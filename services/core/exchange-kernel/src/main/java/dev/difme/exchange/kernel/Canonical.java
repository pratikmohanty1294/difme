package dev.difme.exchange.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON for evidence hashing: keys sorted (UTF-16 order, as in RFC 8785 and JS {@code Object.keys().sort()}),
 * no whitespace, strings escaped like {@code JSON.stringify}. Numbers are integers only: money is in minor units and
 * floats are refused, so the output is byte-identical to what the browser, edge and server each produce.
 * Accepted values: null, Boolean, Integer, Long, String, Map with String keys, List.
 */
public final class Canonical {
    private static final long MAX_SAFE = (1L << 53) - 1;

    private Canonical() {}

    public static String json(Object value) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, value);
        return sb.toString();
    }

    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Integer i -> sb.append(i.intValue());
            case Long l -> {
                if (Math.abs(l) > MAX_SAFE) throw new IllegalArgumentException("integer outside the JSON-safe range: " + l);
                sb.append(l.longValue());
            }
            case String s -> string(sb, s);
            case Map<?, ?> m -> {
                TreeMap<String, Object> sorted = new TreeMap<>();
                m.forEach((k, val) -> {
                    if (!(k instanceof String key)) throw new IllegalArgumentException("non-string key: " + k);
                    sorted.put(key, val);
                });
                sb.append('{');
                boolean first = true;
                for (var e : sorted.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    string(sb, e.getKey());
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(sb, list.get(i));
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("not canonical-JSON (floats are refused): " + v.getClass().getName());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    boolean lone = Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < s.length()
                            && Character.isLowSurrogate(s.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || lone) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
