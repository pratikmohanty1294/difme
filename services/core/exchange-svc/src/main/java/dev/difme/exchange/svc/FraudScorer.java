package dev.difme.exchange.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Client of the server-side fraud scorer (services/fraud-scorer, PDF §5.7). Used on the §11.1 FAIL path: when the
 * buyer's device can't vouch for its own risk score (edge attestation failed or absent), the intent carries the raw
 * features and the exchange scores them itself. Fails closed: no scorer, no answer in time or an error means no score.
 */
@ApplicationScoped
public class FraudScorer {
    /** The six model features (ml/fraud/train.py FEATURES), camelCase as the scorer's JSON expects. */
    public record Features(double amountZscore, double txnCount1h, double distinctCounterparties24h, double geoJumpKm1h,
                           double newDeviceFlag, double edgeScoreMean24h) {}

    public record Score(double probability, double riskScore, String decision, String model) {}

    @ConfigProperty(name = "difme.exchange.fraud-scorer-url") Optional<String> url;
    @ConfigProperty(name = "difme.exchange.fraud-scorer-timeout-ms", defaultValue = "500") long timeoutMs;
    @Inject ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

    /** Empty when the scorer is not configured, unreachable, slow or answers with an error (caller fails closed). */
    public Optional<Score> score(Features f) {
        if (url.isEmpty()) return Optional.empty();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.get() + "/v1/score"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(f)))
                    .build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) return Optional.empty();
            JsonNode n = json.readTree(r.body());
            return Optional.of(new Score(n.path("probability").asDouble(), n.path("riskScore").asDouble(),
                    n.path("decision").asText(), n.path("model").asText()));
        } catch (java.io.IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
