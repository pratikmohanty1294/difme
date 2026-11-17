package dev.difme.core.consistency;

import java.util.EnumMap;
import java.util.Map;

/** PDF §14 Consistency Decision Matrix as code. Single source for every service and for the router API. */
public final class RoutingMatrix {
    private static final Map<Consistency, Backend> ROUTES = new EnumMap<>(Consistency.class);

    static {
        ROUTES.put(Consistency.STRICT, Backend.TIGERBEETLE);
        ROUTES.put(Consistency.SERIALIZABLE, Backend.YUGABYTE_SERIALIZABLE);
        ROUTES.put(Consistency.EVENTUAL, Backend.YUGABYTE_FOLLOWER);
        ROUTES.put(Consistency.CRDT, Backend.VALKEY);
        ROUTES.put(Consistency.LINEARIZABLE, Backend.FOUNDATIONDB);
        ROUTES.put(Consistency.APPEND_ONLY, Backend.CASSANDRA);
        ROUTES.put(Consistency.SNAPSHOT, Backend.ICEBERG);
        ROUTES.put(Consistency.DURABLE, Backend.TEMPORAL);
        ROUTES.put(Consistency.BEST_EFFORT, Backend.VALKEY);
        ROUTES.put(Consistency.ONPREM, Backend.YUGABYTE_ONPREM);
    }

    private RoutingMatrix() {}

    public static Backend route(Consistency c) {
        return ROUTES.get(c);
    }

    public static Map<Consistency, Backend> all() {
        return Map.copyOf(ROUTES);
    }
}
