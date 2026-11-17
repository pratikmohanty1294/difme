package dev.difme.core.cmd.domain;

import dev.difme.core.common.CommandContext;
import dev.difme.core.common.events.PaymentEvent;
import dev.difme.core.common.events.PaymentEvent.PaymentInitiated;
import dev.difme.core.common.events.PaymentEvent.PaymentRejected;
import java.util.UUID;

/**
 * Pure command handler (PDF §9 deterministic replay): all inputs are explicit, no clock / randomness / I/O.
 * Semgrep (policies/semgrep/purity.yaml) blocks merges that break this.
 */
public final class PaymentDecider {
    /** PDF §7.4 L0: edge score > 0.85 → soft block + server review; unattested scores are not trusted. */
    public static final double EDGE_SOFT_BLOCK = 0.85;

    private PaymentDecider() {}

    public static PaymentEvent decide(SubmitPayment cmd, CommandContext ctx) {
        UUID paymentId = ctx.newId(0);
        if (cmd.debitAccountId().equals(cmd.creditAccountId())) {
            return new PaymentRejected(ctx.newId(1), paymentId, ctx.now(), "debit and credit account must differ");
        }
        if (cmd.attested() && cmd.edgeFraudScore() != null && cmd.edgeFraudScore() > EDGE_SOFT_BLOCK) {
            return new PaymentRejected(ctx.newId(1), paymentId, ctx.now(), "edge fraud score above threshold");
        }
        return new PaymentInitiated(ctx.newId(1), paymentId, ctx.now(), cmd.idempotencyKey(), cmd.debitAccountId(),
                cmd.creditAccountId(), cmd.amount(), cmd.channel(), cmd.edgeFraudScore(), cmd.targetBank());
    }
}
