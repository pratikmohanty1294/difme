package dev.difme.flink.bulk;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

/** Linked-record sources (ADR-011 hybrid): static reference data (cached) and volatile state (always live). */
public interface ReferenceStore extends Serializable, AutoCloseable {
    record Account(String id, String currency, String riskLevel) implements Serializable {}
    record LiveState(String id, String status, boolean alreadyProcessed) implements Serializable {}

    void open() throws Exception;

    /** Static: account master. Safe to cache for the whole run. */
    Map<String, Account> accounts(Collection<String> ids);

    /** Volatile: current account status. Never cached. */
    Map<String, String> accountStatus(Collection<String> ids);

    /** Volatile: external references already processed by previous runs (cross-file duplicates). */
    Map<String, Boolean> processedRefs(Collection<String> refs);

    @Override
    default void close() throws Exception {}
}
