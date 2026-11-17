package dev.difme.core.consistency;

/** Physical backends and the named Quarkus datasource / client each one maps to. */
public enum Backend {
    TIGERBEETLE("tigerbeetle"),
    YUGABYTE_SERIALIZABLE("settlements"),
    YUGABYTE_FOLLOWER("follower"),
    YUGABYTE_ONPREM("onprem"),
    VALKEY("valkey"),
    ICEBERG("trino"),
    TEMPORAL("temporal");

    public final String clientName;

    Backend(String clientName) { this.clientName = clientName; }
}
