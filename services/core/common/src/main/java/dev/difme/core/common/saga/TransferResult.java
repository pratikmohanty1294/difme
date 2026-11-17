package dev.difme.core.common.saga;

public record TransferResult(String paymentId, String status, String ledgerTransferId, String failedStep) {}
