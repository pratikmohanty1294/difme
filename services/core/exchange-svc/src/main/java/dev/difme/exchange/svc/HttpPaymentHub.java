package dev.difme.exchange.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.difme.exchange.rails.PaymentHub;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The payment hub over HTTP (payment-hub /v1). 5xx and connection failures throw (the caller retries or fails over). */
final class HttpPaymentHub implements PaymentHub {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    HttpPaymentHub(String baseUrl) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    }

    @Override
    public Outcome deposit(String id, String account, long amountMinor) {
        ObjectNode b = JSON.createObjectNode().put("depositId", id).put("amountMinor", amountMinor);
        return post("v1/accounts/" + account + "/deposits", b);
    }

    @Override
    public Outcome transfer(String id, String from, String to, long amountMinor, String note) {
        ObjectNode b = JSON.createObjectNode().put("transferId", id).put("from", from).put("to", to).put("amountMinor", amountMinor).put("note", note);
        return post("v1/transfers", b);
    }

    @Override
    public Outcome payCard(String paymentId, String merchant, long amountMinor, CardOnFile card) {
        ObjectNode b = JSON.createObjectNode().put("paymentId", paymentId).put("merchant", merchant).put("amountMinor", amountMinor).put("currency", "INR");
        b.putObject("card").put("number", card.pan()).put("expiryMonth", card.expiryMonth()).put("expiryYear", card.expiryYear())
                .put("cvc", card.cvc()).put("holder", "DIFME partner");
        return post("v1/card-payments", b);
    }

    @Override
    public Outcome refundCard(String refundId, String paymentId, String merchant, long amountMinor, String reason) {
        ObjectNode b = JSON.createObjectNode().put("refundId", refundId).put("merchant", merchant).put("amountMinor", amountMinor).put("reason", reason);
        return post("v1/card-payments/" + paymentId + "/refunds", b);
    }

    private Outcome post(String path, ObjectNode body) {
        HttpRequest r = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("payment hub unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        JsonNode n;
        try {
            n = JSON.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("payment hub answered HTTP " + resp.statusCode() + " without JSON");
        }
        if (resp.statusCode() == 200) return new Outcome(n.path("status").asText(), n.path("detail").asText(null));
        if (resp.statusCode() == 422) return new Outcome("REJECTED", n.path("error").asText());
        throw new IllegalStateException("payment hub HTTP " + resp.statusCode() + ": " + n.path("error").asText());
    }
}
