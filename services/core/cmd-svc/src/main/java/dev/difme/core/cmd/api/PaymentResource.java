package dev.difme.core.cmd.api;

import dev.difme.core.cmd.app.PaymentService;
import dev.difme.core.cmd.domain.SubmitPayment;
import dev.difme.core.common.Money;
import dev.difme.core.common.Uuid7;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;

/** REST surface per api/openapi/difme.openapi.yaml (createPayment, createTransfer). */
@Path("/v1")
@RunOnVirtualThread
public class PaymentResource {
    public record MoneyDto(long amountMinor, String currency) {}
    public record PaymentRequest(String debitAccountId, String creditAccountId, MoneyDto amount, String channel,
                                 Double edgeFraudScore, String attestationToken, String targetBank) {}

    @Inject PaymentService service;

    @POST @Path("/payments")
    public Response createPayment(@HeaderParam("Idempotency-Key") String key, @HeaderParam("traceparent") String trace,
                                  PaymentRequest body) {
        return handle(key, trace, body);
    }

    @POST @Path("/transfers")
    public Response createTransfer(@HeaderParam("Idempotency-Key") String key, @HeaderParam("traceparent") String trace,
                                   PaymentRequest body) {
        if (body.targetBank() == null) return problem(422, "targetBank is required for transfers");
        return handle(key, trace, body);
    }

    private Response handle(String key, String trace, PaymentRequest b) {
        if (!Uuid7.isValid(key)) return problem(422, "Idempotency-Key must be a UUIDv7");
        SubmitPayment cmd;
        try {
            cmd = new SubmitPayment(UUID.fromString(key), UUID.fromString(b.debitAccountId()),
                    UUID.fromString(b.creditAccountId()), new Money(b.amount().amountMinor(), b.amount().currency()),
                    b.channel(), b.edgeFraudScore(), b.attestationToken() != null, b.targetBank());
        } catch (IllegalArgumentException | NullPointerException e) {
            return problem(422, e.getMessage());
        }
        PaymentService.Outcome o = service.submit(cmd, trace);
        return switch (o.status()) {
            case "REJECTED_RATE_LIMIT" -> problem(429, "rate limit exceeded");
            case "REJECTED" -> problem(422, "payment rejected");
            default -> Response.accepted(Map.of("paymentId", o.paymentId().toString(),
                    "workflowId", o.workflowId(), "status", o.status())).build();
        };
    }

    private static Response problem(int status, String detail) {
        return Response.status(status).type("application/problem+json")
                .entity(Map.of("type", "about:blank", "status", status, "detail", String.valueOf(detail))).build();
    }
}
