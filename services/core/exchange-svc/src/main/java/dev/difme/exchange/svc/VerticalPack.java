package dev.difme.exchange.svc;

import dev.difme.exchange.kernel.Weights;
import dev.difme.exchange.rules.PackRules;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** One vertical pack as served: its FR-17 weights and auction parameters from vertical.yaml, plus compiled rules. */
public record VerticalPack(String id, Weights weights, long windowNanos, long latencyMaxMicros, int backups, PackRules rules,
                           Collateral collateral, int guaranteeFundBps, List<String> rails, Slashing slashing, int returnWindowDays,
                           double reputationAlphaBase, long reputationReferenceMinor, int establishedAfterDeliveries) {
    /** FR-15 per-code bases from vertical.yaml {@code slashing:} (basis points of A). */
    public record Slashing(int fundsBps, int otherDeclineBps) {}

    /** FR-14 inputs from vertical.yaml {@code collateral:} (alpha, gamma_rev, min_minor). */
    public record Collateral(double alpha, double gammaRev, long minMinor, int mandateDepositBps) {}


    @SuppressWarnings("unchecked")
    static VerticalPack fromClasspath(String id, ClassLoader cl) throws IOException {
        String base = "verticals/" + id + "/";
        Map<String, Object> v;
        try (InputStream in = cl.getResourceAsStream(base + "vertical.yaml")) {
            if (in == null) throw new IOException("pack " + id + " not on the classpath");
            v = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        Map<String, Object> w = (Map<String, Object>) v.get("weights");
        Map<String, Object> a = (Map<String, Object>) v.get("auction");
        List<String> rules = (List<String>) v.getOrDefault("rules", List.of());
        Map<String, Object> c = (Map<String, Object>) v.get("collateral");
        Map<String, Object> st = (Map<String, Object>) v.getOrDefault("settlement", Map.of());
        Map<String, Object> sl = (Map<String, Object>) v.getOrDefault("slashing", Map.of());
        List<String> rails = (List<String>) v.getOrDefault("rails", List.of());
        Map<String, Object> rep = (Map<String, Object>) v.getOrDefault("reputation", Map.of());
        PackRules compiled = PackRules.load(id, rules, f -> cl.getResourceAsStream(base + f));
        return new VerticalPack(id,
                new Weights(num(w, "price"), num(w, "reputation"), num(w, "latency")),
                (long) num(a, "window_ms") * 1_000_000L,
                (long) num(a, "latency_max_ms") * 1_000L,
                (int) num(a, "backups"),
                compiled,
                new Collateral(num(c, "alpha"), num(c, "gamma_rev"), (long) num(c, "min_minor"),
                        c.get("mandate_deposit_bps") instanceof Number md ? md.intValue() : 0),
                st.get("guarantee_fund_bps_of_fee") instanceof Number n ? n.intValue() : 0,
                List.copyOf(rails),
                new Slashing(sl.get("iso_51_bps") instanceof Number f ? f.intValue() : 0,
                        sl.get("other_decline_bps") instanceof Number o ? o.intValue() : 0),
                st.get("return_window_days") instanceof Number rw ? rw.intValue() : 0,
                rep.get("alpha_base") instanceof Number ab ? ab.doubleValue() : 0.10,
                rep.get("reference_minor") instanceof Number rm ? rm.longValue() : 5_000_000L,
                rep.get("established_after_deliveries") instanceof Number ea ? ea.intValue() : 3);
    }

    private static double num(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof Number n)) throw new IllegalArgumentException("vertical.yaml: " + key + " missing or not a number");
        return n.doubleValue();
    }
}
