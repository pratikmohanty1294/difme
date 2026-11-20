package dev.difme.exchange.rails;

import java.util.UUID;

/**
 * Card network agentic credential (card-network agentic-commerce programme class): a single-use token bound to
 * one delegated purchase instruction, capped at the instructed amount and merchant.
 */
public interface AgentCredentialPort {
    record Instruction(UUID mandateId, long partnerId, String merchantId, long amountMinor, String currency) {}

    record Token(String token, String cryptogram, Instruction instruction, long expiresAtMs) {}

    /** Issues a token for the instruction; idempotent per (mandate, partner, merchant). */
    Token issue(Instruction instruction, long nowMs);

    /** True once, for an unexpired token used at its own merchant for at most its amount; the token is then spent. */
    boolean redeem(String token, String merchantId, long amountMinor, long nowMs);

    void revoke(String token);
}
