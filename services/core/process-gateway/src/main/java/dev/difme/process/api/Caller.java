package dev.difme.process.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Identity of the caller as asserted by APISIX after OIDC verification (X-Userinfo header, base64 JSON).
 * The service is reachable only through APISIX inside the mesh, so the header is trusted.
 */
public record Caller(String user, List<String> groups) {
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static Caller from(String userinfoHeader) {
        if (userinfoHeader == null || userinfoHeader.isBlank()) return new Caller("anonymous", List.of());
        try {
            Map<String, Object> m = JSON.readValue(Base64.getDecoder().decode(userinfoHeader), Map.class);
            List<String> groups = ((List<String>) m.getOrDefault("groups", List.of())).stream().map(g -> g.replaceFirst("^/", "")).toList();
            return new Caller(String.valueOf(m.getOrDefault("preferred_username", "unknown")), groups);
        } catch (Exception e) {
            return new Caller("anonymous", List.of());
        }
    }

    public void require(String... anyOf) {
        for (String g : anyOf) if (groups.contains(g)) return;
        throw new ForbiddenException("requires one of " + String.join(", ", anyOf));
    }

    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
