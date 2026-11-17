package dev.difme.core.cmd.domain;

import dev.difme.core.common.Money;
import java.util.UUID;

public record SubmitPayment(UUID idempotencyKey, UUID debitAccountId, UUID creditAccountId, Money amount,
                            String channel, Double edgeFraudScore, boolean attested, String targetBank) {}
