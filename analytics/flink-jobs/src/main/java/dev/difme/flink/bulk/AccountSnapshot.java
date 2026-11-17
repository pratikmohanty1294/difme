package dev.difme.flink.bulk;

import java.util.HashMap;
import java.util.Map;

/**
 * Static account master shared by every parallel subtask in the same TaskManager JVM (ADR-011/012 shared memory):
 * loaded once with one sequential scan, then read lock-free by all task threads. Strings for currency and risk level
 * are interned into a handful of shared Account instances, so 1M accounts cost ~1M map entries, not 1M objects.
 */
public final class AccountSnapshot {
    private static volatile Map<String, ReferenceStore.Account> accounts;
    private static volatile String loadedFor;

    private AccountSnapshot() {}

    public static Map<String, ReferenceStore.Account> get(ReferenceStore store, String snapshotKey) throws Exception {
        Map<String, ReferenceStore.Account> a = accounts;
        if (a != null && snapshotKey.equals(loadedFor)) return a;
        synchronized (AccountSnapshot.class) {
            if (accounts == null || !snapshotKey.equals(loadedFor)) {
                Map<String, ReferenceStore.Account> m = new HashMap<>(1 << 21);
                Map<ReferenceStore.Account, ReferenceStore.Account> shared = new HashMap<>();
                store.loadAccounts((id, acc) -> m.put(id, shared.computeIfAbsent(acc, x -> x)));
                accounts = m;            // safe publication via volatile
                loadedFor = snapshotKey;
            }
            return accounts;
        }
    }
}
