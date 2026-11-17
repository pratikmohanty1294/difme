package dev.difme.core.cmd.persistence;

import dev.difme.core.common.events.PaymentEvent;

public record PaymentCommitted(PaymentEvent event) {}
