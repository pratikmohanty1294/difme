package dev.difme.flink.bulk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

/**
 * Linked-record validation (ADR-011 hybrid, ADR-012 shared memory):
 *   static account master → one JVM-wide in-memory snapshot (zero queries per record)
 *   live account status + cross-run duplicates → batched async lookups (≈1,000 keys per query)
 *   DMN limit → memoised pure evaluation.
 */
public class LinkedValidation extends RichAsyncFunction<BulkRecord, BulkRecord> {
    private final ReferenceStore store;
    private final DmnRules limits;
    private final int batch;
    private final String snapshotKey;
    private transient Map<String, ReferenceStore.Account> accounts;
    private transient BatchLookup<String, String> status;
    private transient BatchLookup<String, Boolean> processed;

    public LinkedValidation(ReferenceStore store, DmnRules limits, int batch, String snapshotKey) {
        this.store = store;
        this.limits = limits;
        this.batch = batch;
        this.snapshotKey = snapshotKey;
    }

    @Override
    public void open(OpenContext ctx) throws Exception {
        store.open();
        limits.open();
        accounts = AccountSnapshot.get(store, snapshotKey);
        status = new BatchLookup<>(store::accountStatus, batch, 5);
        processed = new BatchLookup<>(store::processedRefs, batch, 5);
    }

    @Override
    public void asyncInvoke(BulkRecord r, ResultFuture<BulkRecord> out) {
        if (!r.valid()) { out.complete(List.of(r)); return; }
        ReferenceStore.Account debit = accounts.get(r.get(PaymentFileRules.DEBIT));
        ReferenceStore.Account credit = accounts.get(r.get(PaymentFileRules.CREDIT));
        if (debit == null) {   // no live lookups needed: the record is already rejected
            PaymentFileRules.linked(r, null, credit, null, false, limits);
            out.complete(List.of(r));
            return;
        }
        CompletableFuture<String> st = status.get(r.get(PaymentFileRules.DEBIT));
        CompletableFuture<Boolean> dup = processed.get(r.get(PaymentFileRules.REF));
        CompletableFuture.allOf(st, dup).whenComplete((v, err) -> {
            if (err != null) r.reject("lookup failed: " + err.getMessage());
            else PaymentFileRules.linked(r, debit, credit, st.join(), Boolean.TRUE.equals(dup.join()), limits);
            out.complete(List.of(r));
        });
    }

    @Override
    public void timeout(BulkRecord r, ResultFuture<BulkRecord> out) {
        out.complete(List.of(r.reject("lookup timed out")));
    }

    @Override
    public void close() throws Exception {
        if (status != null) { status.close(); processed.close(); }
        store.close();
    }
}
