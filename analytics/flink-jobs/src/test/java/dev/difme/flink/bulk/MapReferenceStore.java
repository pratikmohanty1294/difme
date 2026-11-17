package dev.difme.flink.bulk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** In-memory reference data for tests. */
class MapReferenceStore implements ReferenceStore {
    private final int accounts;

    MapReferenceStore(int accounts) { this.accounts = accounts; }

    @Override public void open() {}

    @Override
    public Map<String, Account> accounts(Collection<String> ids) {
        Map<String, Account> m = new HashMap<>();
        for (String id : ids) {
            int n = Integer.parseInt(id.substring(3));
            if (n < accounts) m.put(id, new Account(id, "INR", n % 10 == 0 ? "HIGH" : "LOW"));
        }
        return m;
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
