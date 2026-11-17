package dev.difme.flink.bulk;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

/** Linked-record sources (ADR-011 hybrid): static reference data (snapshotted) and volatile state (always live). */
public interface ReferenceStore extends Serializable, AutoCloseable {
    record Account(String currency, String riskLevel) implements Serializable {}

    void open() throws Exception;

    /** Static: streams the whole account master once (one sequential scan) into the shared snapshot. */
    void loadAccounts(java.util.function.BiConsumer<String, Account> sink) throws Exception;

    /** Volatile: current account status. Never cached. */
    Map<String, String> accountStatus(Collection<String> ids);

    /** Volatile: external references already processed by previous runs (cross-file duplicates). */
    Map<String, Boolean> processedRefs(Collection<String> refs);

    @Override
    default void close() throws Exception {}

    static <T> Consumer<T> noop() { return t -> {}; }
}
