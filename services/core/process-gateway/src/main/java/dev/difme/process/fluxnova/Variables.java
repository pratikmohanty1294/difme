package dev.difme.process.fluxnova;

import dev.difme.process.fluxnova.FluxnovaClient.TypedValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain values ↔ Fluxnova typed variables. */
public final class Variables {
    private Variables() {}

    public static Map<String, TypedValue> typed(Map<String, Object> plain) {
        Map<String, TypedValue> out = new LinkedHashMap<>();
        plain.forEach((k, v) -> {
            if (v == null) return;
            String type = switch (v) {
                case Boolean b -> "Boolean";
                case Integer i -> "Long";
                case Long l -> "Long";
                case Double d -> "Double";
                case Float f -> "Double";
                case String s -> "String";
                default -> "Json";
            };
            out.put(k, new TypedValue(v instanceof Integer i ? (Object) i.longValue() : v, type));
        });
        return out;
    }

    public static Map<String, Object> plain(Map<String, TypedValue> typed) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (typed != null) typed.forEach((k, v) -> out.put(k, v == null ? null : v.value()));
        return out;
    }

    public static List<Map<String, Object>> plainRows(List<Map<String, TypedValue>> rows) {
        return rows.stream().map(Variables::plain).toList();
    }
}
