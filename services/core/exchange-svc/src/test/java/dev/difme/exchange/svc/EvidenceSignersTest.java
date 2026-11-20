package dev.difme.exchange.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.difme.exchange.kernel.EvidenceChain;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** OpenBao transit (operator) and notary (witness) signers against stand-in HTTP servers speaking the same API. */
class EvidenceSignersTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static byte[] raw(KeyPair kp) {
        byte[] der = kp.getPublic().getEncoded();
        return Arrays.copyOfRange(der, der.length - 32, der.length);
    }

    private static byte[] sign(KeyPair kp, byte[] msg) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(msg);
        return s.sign();
    }

    private static void reply(HttpExchange ex, int code, Object body) throws java.io.IOException {
        byte[] b = JSON.writeValueAsBytes(body);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void transitOperatorAndNotaryWitnessSealAChainThatVerifies() throws Exception {
        KeyPair op = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair notary = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<String> seenToken = new AtomicReference<>();
        String url = start();
        server.createContext("/v1/transit/keys/evidence-operator", ex -> reply(ex, 200, Map.of("data", Map.of(
                "type", "ed25519", "latest_version", 2,
                "keys", Map.of("2", Map.of("public_key", Base64.getEncoder().encodeToString(raw(op))))))));
        server.createContext("/v1/transit/sign/evidence-operator", ex -> {
            seenToken.set(ex.getRequestHeaders().getFirst("X-Vault-Token"));
            try {
                byte[] in = Base64.getDecoder().decode(JSON.readTree(ex.getRequestBody()).path("input").asText());
                reply(ex, 200, Map.of("data", Map.of("signature", "vault:v2:" + Base64.getEncoder().encodeToString(sign(op, in)))));
            } catch (Exception e) {
                reply(ex, 500, Map.of());
            }
        });
        server.createContext("/v1/key", ex -> reply(ex, 200, Map.of("name", "notary-1",
                "publicKey", Base64.getEncoder().encodeToString(raw(notary)))));
        server.createContext("/v1/witness", ex -> {
            try {
                byte[] h = HexFormat.of().parseHex(JSON.readTree(ex.getRequestBody()).path("hash").asText());
                reply(ex, 200, Map.of("signature", Base64.getEncoder().encodeToString(sign(notary, h))));
            } catch (Exception e) {
                reply(ex, 500, Map.of());
            }
        });
        server.start();
        Path token = Files.createTempFile("bao", ".token");
        Files.writeString(token, "s.test-token\n", StandardCharsets.UTF_8);

        var operator = EvidenceSigners.transit("exchange-svc", url, token, "transit", "evidence-operator");
        var witness = EvidenceSigners.notary(url);
        assertEquals("notary-1", witness.name());

        EvidenceChain c = new EvidenceChain("auction-1", EvidenceChain.Kind.B);
        c.append(EvidenceChain.Type.intent, EvidenceChain.Signal.Deterministic, Map.of("pack", "fr01-buy"));
        c.seal(operator, List.of(witness));
        assertEquals("s.test-token", seenToken.get());
        Map<String, java.security.PublicKey> keys = Map.of(operator.name(), operator.publicKey(), witness.name(), witness.publicKey());
        EvidenceChain.Verdict v = c.verify(keys::get);
        assertTrue(v.seal() && v.witnesses(), v.notes().toString());
        // A key the transit engine did not use does not verify the seal.
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertFalse(c.verify(Map.of(operator.name(), other.getPublic(), witness.name(), witness.publicKey())::get).seal());
    }

    @Test
    void kubernetesLoginIsReusedUntilItsLeaseIsMostlyUsed() throws Exception {
        java.util.concurrent.atomic.AtomicInteger logins = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<String> jwt = new AtomicReference<>();
        String url = start();
        server.createContext("/v1/auth/kubernetes/login", ex -> {
            var in = JSON.readTree(ex.getRequestBody());
            jwt.set(in.path("jwt").asText());
            assertEquals("exchange-evidence", in.path("role").asText());
            reply(ex, 200, Map.of("auth", Map.of("client_token", "s.login-" + logins.incrementAndGet(), "lease_duration", 3600)));
        });
        server.start();
        Path sa = Files.createTempFile("sa", ".jwt");
        Files.writeString(sa, "eyJ.sa.token\n");
        var token = EvidenceSigners.kubernetesLogin(url, "kubernetes", "exchange-evidence", sa);
        assertEquals("s.login-1", token.get());
        assertEquals("s.login-1", token.get());
        assertEquals(1, logins.get());
        assertEquals("eyJ.sa.token", jwt.get());
    }

    @Test
    void aNonEd25519TransitKeyIsRefused() throws Exception {
        String url = start();
        server.createContext("/v1/transit/keys/evidence-operator", ex -> reply(ex, 200, Map.of("data", Map.of(
                "type", "aes256-gcm96", "latest_version", 1, "keys", Map.of("1", 1)))));
        server.start();
        Path token = Files.createTempFile("bao", ".token");
        Files.writeString(token, "t");
        assertThrows(java.security.GeneralSecurityException.class,
                () -> EvidenceSigners.transit("exchange-svc", url, token, "transit", "evidence-operator"));
    }
}
