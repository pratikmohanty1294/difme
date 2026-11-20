package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.AgentCredentialPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Deterministic agentic-token issuer: same instruction, same token; single use; amount and merchant capped. */
public final class SimAgentCredential implements AgentCredentialPort {
    static final long TTL_MS = 15 * 60_000L;
    private final Map<String, Token> issued = Bounded.map();
    private final Map<String, Boolean> spent = Bounded.map();

    @Override
    public Token issue(Instruction in, long nowMs) {
        String key = in.mandateId() + "|" + in.partnerId() + "|" + in.merchantId();
        String token = "tok_" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return issued.computeIfAbsent(token, t -> new Token(t, sha256(t + in.amountMinor()).substring(0, 28), in, nowMs + TTL_MS));
    }

    @Override
    public boolean redeem(String token, String merchantId, long amountMinor, long nowMs) {
        Token t = issued.get(token);
        if (t == null || nowMs > t.expiresAtMs() || !t.instruction().merchantId().equals(merchantId)
                || amountMinor > t.instruction().amountMinor()) return false;
        return spent.putIfAbsent(token, Boolean.TRUE) == null;
    }

    @Override
    public void revoke(String token) { spent.put(token, Boolean.TRUE); }

    /** The instruction behind a token (the simulated issuer uses it to find the partner's card). */
    public Optional<Instruction> instructionOf(String token) {
        return Optional.ofNullable(issued.get(token)).map(Token::instruction);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
