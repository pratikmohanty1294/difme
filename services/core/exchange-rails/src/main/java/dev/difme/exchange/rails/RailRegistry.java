package dev.difme.exchange.rails;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Which implementation serves each rail. A pack names its rails; {@link #requireAll} refuses to bring a pack live
 * while any of them has no implementation, so a misconfigured vertical fails at start-up, not on the first order.
 */
public final class RailRegistry {
    private final Map<RailId, Object> ports = new EnumMap<>(RailId.class);

    public <T> RailRegistry register(RailId rail, Class<T> port, T implementation) {
        if (!port.isInstance(implementation)) throw new IllegalArgumentException(rail.id + " needs a " + port.getSimpleName());
        ports.put(rail, implementation);
        return this;
    }

    public <T> T port(RailId rail, Class<T> type) {
        Object p = ports.get(rail);
        if (p == null) throw new IllegalStateException("rail " + rail.id + " has no implementation");
        return type.cast(p);
    }

    /** Throws listing every rail the pack names that is unknown or has no implementation. */
    public void requireAll(String packId, List<String> rails) {
        List<String> missing = rails.stream().filter(r -> {
            try { return !ports.containsKey(RailId.of(r)); } catch (IllegalArgumentException e) { return true; }
        }).collect(Collectors.toList());
        if (!missing.isEmpty()) throw new IllegalStateException("pack " + packId + ": rails without an implementation: " + missing);
    }
}
