package dev.difme.process.rules;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.difme.process.fluxnova.FluxnovaClient;
import dev.difme.process.fluxnova.Variables;
import dev.difme.process.formio.FormioClient;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Evaluates a form's DMN (key + pinned version from the form's metadata) and caches results by input hash, so
 * live typing doesn't hit the engine on every keystroke.
 */
@ApplicationScoped
public class RuleService {
    public record FormMeta(String decisionKey, Integer decisionVersion, String processKey) {}

    private final FluxnovaClient engine;
    private final FormioClient forms;
    private final String formioKey;
    private final Cache<String, List<Map<String, Object>>> results;
    private final Cache<String, FormMeta> meta;

    public RuleService(@RestClient FluxnovaClient engine, @RestClient FormioClient forms,
                       @ConfigProperty(name = "difme.formio.api-key") String formioKey,
                       @ConfigProperty(name = "difme.rules.cache.max-size") long size,
                       @ConfigProperty(name = "difme.rules.cache.ttl") Duration ttl) {
        this.engine = engine;
        this.forms = forms;
        this.formioKey = formioKey;
        this.results = Caffeine.newBuilder().maximumSize(size).expireAfterWrite(ttl).build();
        this.meta = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(1)).build();
    }

    @SuppressWarnings("unchecked")
    public FormMeta formMeta(String formPath) {
        return meta.get(formPath, p -> {
            Map<String, Object> form = forms.form(p, formioKey);
            Map<String, Object> props = (Map<String, Object>) form.getOrDefault("properties", Map.of());
            Object v = props.get("decisionVersion");
            return new FormMeta((String) props.get("decisionKey"), v == null ? null : Integer.valueOf(v.toString()),
                    (String) props.get("processKey"));
        });
    }

    public Map<String, FieldRule> fieldRules(String formPath, Map<String, Object> data) {
        FormMeta m = formMeta(formPath);
        if (m.decisionKey() == null) return Map.of();
        return FieldRules.merge(evaluate(m.decisionKey(), m.decisionVersion(), data));
    }

    public List<Map<String, Object>> evaluate(String key, Integer version, Map<String, Object> inputs) {
        String cacheKey = key + "@" + version + "#" + hash(new TreeMap<>(inputs).toString());
        return results.get(cacheKey, k -> {
            var body = new FluxnovaClient.Evaluate(Variables.typed(inputs));
            if (version == null) return Variables.plainRows(engine.evaluateLatest(key, body));
            String id = engine.decisions(key, version).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("decision " + key + " v" + version + " not deployed")).id();
            return Variables.plainRows(engine.evaluateById(id, body));
        });
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
