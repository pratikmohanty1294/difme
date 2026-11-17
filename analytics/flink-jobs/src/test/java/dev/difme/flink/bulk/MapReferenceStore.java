package dev.difme.flink.bulk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** In-memory reference data for tests. */
class MapReferenceStore implements ReferenceStore {
    private final int accounts;

    MapReferenceStore(int accounts) { this.accounts = accounts; }

    @Override public void open() {}

    @Override
    public void loadAccounts(BiConsumer<String, Account> sink) {
        for (int n = 0; n < accounts; n++) sink.accept("ACC" + n, new Account("INR", n % 10 == 0 ? "HIGH" : "LOW"));
    }

    @Override
    public Map<String, String> accountStatus(Collection<String> ids) {
        Map<String, String> m = new HashMap<>();
        for (String id : ids) m.put(id, id.equals("ACC13") ? "FROZEN" : "ACTIVE");
        return m;
    }

    @Override
    public Map<String, Boolean> processedRefs(Collection<String> refs) {
        Map<String, Boolean> m = new HashMap<>();
        for (String r : refs) if (r.equals("OLD1")) m.put(r, true);
        return m;
    }
}
