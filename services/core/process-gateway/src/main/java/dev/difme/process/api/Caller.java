package dev.difme.process.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Caller identity from the bearer token. Envoy Gateway's SecurityPolicy has already verified the JWT signature,
 * issuer and expiry against Keycloak's JWKS (ADR-015), and this service is only reachable through it, so the payload
 * is read without re-verifying.
 */
public record Caller(String user, List<String> groups) {
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static Caller from(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return new Caller("anonymous", List.of());
        try {
            String[] parts = authorization.substring(7).split("\\.");
            Map<String, Object> claims = JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            List<String> groups = ((List<String>) claims.getOrDefault("groups", List.of())).stream()
                    .map(g -> g.replaceFirst("^/", "")).toList();
            return new Caller(String.valueOf(claims.getOrDefault("preferred_username", "unknown")), groups);
        } catch (Exception e) {
            return new Caller("anonymous", List.of());
        }
    }

    public void require(String... anyOf) {
        for (String g : anyOf) if (groups.contains(g)) return;
        throw new ForbiddenException("requires one of " + String.join(", ", anyOf));
    }
}
