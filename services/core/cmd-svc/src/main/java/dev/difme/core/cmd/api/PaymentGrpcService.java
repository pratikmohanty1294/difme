package dev.difme.core.cmd.api;

import dev.difme.core.cmd.app.PaymentService;
import dev.difme.core.cmd.domain.SubmitPayment;
import dev.difme.core.common.Money;
import dev.difme.core.common.Uuid7;
import dev.difme.grpc.payments.v1.PaymentCommandService;
import dev.difme.grpc.payments.v1.SubmitPaymentRequest;
import dev.difme.grpc.payments.v1.SubmitPaymentResponse;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.UUID;

/** gRPC bridge target for the Quinn hub (PDF §5.2 / §7.1 step 2). */
@GrpcService
public class PaymentGrpcService implements PaymentCommandService {
    @Inject PaymentService service;

    @Override
    @RunOnVirtualThread
    public Uni<SubmitPaymentResponse> submitPayment(SubmitPaymentRequest r) {
        if (!Uuid7.isValid(r.getIdempotencyKey())) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT.withDescription("idempotency_key must be UUIDv7").asRuntimeException());
        }
        SubmitPayment cmd = new SubmitPayment(UUID.fromString(r.getIdempotencyKey()), UUID.fromString(r.getDebitAccountId()),
                UUID.fromString(r.getCreditAccountId()), new Money(r.getAmount().getAmountMinor(), r.getAmount().getCurrency()),
                r.getChannel().name().replace("CHANNEL_", ""), r.getEdgeFraudScore(), !r.getAttestationToken().isEmpty(),
                r.getTargetBank().isEmpty() ? null : r.getTargetBank());
        PaymentService.Outcome o = service.submit(cmd, r.getTraceId());
        SubmitPaymentResponse.Status status = switch (o.status()) {
            case "DUPLICATE" -> SubmitPaymentResponse.Status.STATUS_DUPLICATE;
            case "REJECTED" -> SubmitPaymentResponse.Status.STATUS_REJECTED_FRAUD;
            case "REJECTED_RATE_LIMIT" -> SubmitPaymentResponse.Status.STATUS_REJECTED_RATE_LIMIT;
            default -> SubmitPaymentResponse.Status.STATUS_ACCEPTED;
        };
        SubmitPaymentResponse.Builder b = SubmitPaymentResponse.newBuilder().setStatus(status);
        if (o.paymentId() != null) b.setPaymentId(o.paymentId().toString());
        if (o.workflowId() != null) b.setWorkflowId(o.workflowId());
        return Uni.createFrom().item(b.build());
    }
}
