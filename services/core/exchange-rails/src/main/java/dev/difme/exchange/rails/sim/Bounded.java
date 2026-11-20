package dev.difme.exchange.rails.sim;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe insertion-ordered map that drops its oldest entries past a cap, so a long-running simulator stays small. */
final class Bounded {
    static final int DEFAULT_CAP = 65_536;

    private Bounded() {}

    static <K, V> Map<K, V> map(int cap) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > cap;
            }
        });
    }

    static <K, V> Map<K, V> map() { return map(DEFAULT_CAP); }
}
