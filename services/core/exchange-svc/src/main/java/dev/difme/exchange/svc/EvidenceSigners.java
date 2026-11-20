package dev.difme.exchange.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.difme.exchange.kernel.EvidenceChain;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Evidence custody (decided 2026-11-20): the operator key lives in OpenBao transit (non-exportable; the exchange asks
 * OpenBao to sign), and the witness is an independent notary service with its own key, never the operator. Dev and
 * tests keep generated in-process keys.
 */
final class EvidenceSigners {
    private EvidenceSigners() {}

    /** A signer whose public key the exchange publishes at GET /v1/exchange/keys. */
    interface Published extends EvidenceChain.Signer {
        PublicKey publicKey();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (RFC 8410); followed by the raw 32-byte key. */
    private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");

    static PublicKey ed25519FromRaw(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) throw new GeneralSecurityException("an Ed25519 public key is 32 bytes, got " + raw.length);
        byte[] der = new byte[ED25519_SPKI.length + 32];
        System.arraycopy(ED25519_SPKI, 0, der, 0, ED25519_SPKI.length);
        System.arraycopy(raw, 0, der, ED25519_SPKI.length, 32);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * OpenBao transit: POST /v1/transit/sign/{key} with base64 input returns "vault:v{n}:{base64 signature}"; the
     * public key comes from GET /v1/transit/keys/{key} (latest version). The key must be type ed25519.
     */
    static Published transit(String name, String addr, Path tokenFile, String mount, String key) throws IOException, GeneralSecurityException {
        return transit(name, addr, () -> Files.readString(tokenFile).strip(), mount, key);
    }

    /** Supplies the OpenBao token for each call (a fixed file, or a Kubernetes-auth login that renews itself). */
    interface Token {
        String get() throws IOException;
    }

    /**
     * Kubernetes auth: logs in at auth/{authMount}/login with the pod's service-account JWT (read fresh each login, as
     * kubelet rotates projected tokens) and reuses the client token until 80 % of its lease has passed.
     */
    static Token kubernetesLogin(String addr, String authMount, String role, Path jwtFile) {
        String url = addr.replaceAll("/+$", "") + "/v1/auth/" + authMount + "/login";
        return new Token() {
            private String token;
            private long renewAtMs;

            public synchronized String get() throws IOException {
                long now = System.currentTimeMillis();
                if (token != null && now < renewAtMs) return token;
                String body = JSON.writeValueAsString(Map.of("role", role, "jwt", Files.readString(jwtFile).strip()));
                JsonNode auth = call(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)), null).path("auth");
                String t = auth.path("client_token").asText();
                if (t.isBlank()) throw new IOException("OpenBao login returned no token");
                token = t;
                renewAtMs = now + Math.max(5_000L, auth.path("lease_duration").asLong(60) * 800L);
                return token;
            }
        };
    }

    static Published transit(String name, String addr, Token token, String mount, String key) throws IOException, GeneralSecurityException {
        String base = addr.replaceAll("/+$", "") + "/v1/" + mount + "/";
        JsonNode meta = call(HttpRequest.newBuilder(URI.create(base + "keys/" + key)).GET(), token).path("data");
        if (!"ed25519".equals(meta.path("type").asText())) throw new GeneralSecurityException("transit key " + key + " is not ed25519");
        String latest = meta.path("latest_version").asText();
        PublicKey pk = ed25519FromRaw(Base64.getDecoder().decode(meta.path("keys").path(latest).path("public_key").asText()));
        return new Published() {
            public String name() { return name; }

            public PublicKey publicKey() { return pk; }

            public byte[] sign(byte[] message) throws IOException {
                String body = JSON.writeValueAsString(Map.of("input", Base64.getEncoder().encodeToString(message)));
                String sig = call(HttpRequest.newBuilder(URI.create(base + "sign/" + key))
                        .POST(HttpRequest.BodyPublishers.ofString(body)), token).path("data").path("signature").asText();
                int at = sig.lastIndexOf(':');
                if (!sig.startsWith("vault:") || at < 0) throw new IOException("unexpected transit signature format");
                return Base64.getDecoder().decode(sig.substring(at + 1));
            }
        };
    }

    /**
     * An independent notary: GET {url}/v1/key returns {"name", "publicKey": base64 raw 32 bytes}; POST {url}/v1/witness
     * with {"hash": hex} returns {"signature": base64}. The notary signs the 32 raw hash bytes, as the operator does.
     */
    static Published notary(String url) throws IOException, GeneralSecurityException {
        String base = url.replaceAll("/+$", "");
        JsonNode key = call(HttpRequest.newBuilder(URI.create(base + "/v1/key")).GET(), null);
        String name = key.path("name").asText();
        if (name.isBlank()) throw new IOException("the notary did not name itself");
        PublicKey pk = ed25519FromRaw(Base64.getDecoder().decode(key.path("publicKey").asText()));
        return new Published() {
            public String name() { return name; }

            public PublicKey publicKey() { return pk; }

            public byte[] sign(byte[] message) throws IOException {
                String body = JSON.writeValueAsString(Map.of("hash", HexFormat.of().formatHex(message)));
                return Base64.getDecoder().decode(call(HttpRequest.newBuilder(URI.create(base + "/v1/witness"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)), null).path("signature").asText());
            }
        };
    }

    private static JsonNode call(HttpRequest.Builder req, Token token) throws IOException {
        req.timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json");
        // The token is fetched per call so a rotated file or a renewed login takes effect at once; it is never logged.
        if (token != null) req.header("X-Vault-Token", token.get());
        try {
            HttpResponse<String> r = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + r.uri().getPath());
            return JSON.readTree(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
