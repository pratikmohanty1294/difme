package dev.difme.flink.bulk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

/**
 * Async linked-record validation (ADR-011 hybrid):
 *   static account master → batched lookup + per-task cache (never re-queried during the run)
 *   live account status and cross-run duplicates → batched lookup, never cached
 * then the embedded DMN limit check. All DB access is batched; nothing queries per record.
 */
public class LinkedValidation extends RichAsyncFunction<BulkRecord, BulkRecord> {
    private final ReferenceStore store;
    private final DmnRules limits;
    private final int batch;
    private transient Cache<String, ReferenceStore.Account> accountCache;
    private transient BatchLookup<String, ReferenceStore.Account> accounts;
    private transient BatchLookup<String, String> status;
    private transient BatchLookup<String, Boolean> processed;

    public LinkedValidation(ReferenceStore store, DmnRules limits, int batch) {
        this.store = store;
        this.limits = limits;
        this.batch = batch;
    }

    @Override
    public void open(OpenContext ctx) throws Exception {
        store.open();
        limits.open();
        accountCache = Caffeine.newBuilder().maximumSize(2_000_000).build();
        accounts = new BatchLookup<>(store::accounts, batch, 5);
        status = new BatchLookup<>(store::accountStatus, batch, 5);
        processed = new BatchLookup<>(store::processedRefs, batch, 5);
    }

    private CompletableFuture<ReferenceStore.Account> account(String id) {
        ReferenceStore.Account hit = accountCache.getIfPresent(id);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        return accounts.get(id).thenApply(a -> { if (a != null) accountCache.put(id, a); return a; });
    }

    @Override
    public void asyncInvoke(BulkRecord r, ResultFuture<BulkRecord> out) {
        if (!r.valid()) { out.complete(List.of(r)); return; }
        var debit = account(r.get("debit_account"));
        var credit = account(r.get("credit_account"));
        var st = status.get(r.get("debit_account"));
        var dup = processed.get(r.get("external_ref"));
        CompletableFuture.allOf(debit, credit, st, dup).whenComplete((v, err) -> {
            if (err != null) r.reject("lookup failed: " + err.getMessage());
            else PaymentFileRules.linked(r, debit.join(), credit.join(), st.join(), Boolean.TRUE.equals(dup.join()), limits);
            out.complete(List.of(r));
        });
    }

    @Override
    public void timeout(BulkRecord r, ResultFuture<BulkRecord> out) {
        out.complete(List.of(r.reject("lookup timed out")));
    }

    @Override
    public void close() throws Exception {
        if (accounts != null) { accounts.close(); status.close(); processed.close(); }
        store.close();
    }

    static Map<String, Boolean> none(Collection<String> keys) { return new HashMap<>(); }
}
