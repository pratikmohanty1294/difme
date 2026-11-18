package dev.difme.exchange.svc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/** End to end through HTTP: FR-01 eligibility (DMN), a 50 ms sealed-bid auction, the ranked result. */
@QuarkusTest
class ExchangeResourceTest {
    static String intent(int risk, int maxPrice) {
        return """
                {"riskScore": %d, "maxPrice": %d, "minViable": 90000, "listPrice": 100000,
                 "offerSanctioned": true, "maxAcceptableMinor": 10000}""".formatted(risk, maxPrice);
    }

    @Test
    void ineligibleIntentIsRefusedWithTheRuleFindings() {
        given().contentType(ContentType.JSON).body(intent(700, 95_000))
                .post("/v1/exchange/fr01-buy/intents")
                .then().statusCode(422).body("findings.code", hasItem("RISK_TOO_LOW"));
    }

    @Test
    void unknownPackIs404() {
        given().contentType(ContentType.JSON).body(intent(900, 95_000))
                .post("/v1/exchange/nope/intents").then().statusCode(404);
    }

    @Test
    void auctionRanksBidsAndKeepsHotBackups() throws InterruptedException {
        long id = given().contentType(ContentType.JSON).body(intent(900, 95_000))
                .post("/v1/exchange/fr01-buy/intents")
                .then().statusCode(202).extract().jsonPath().getLong("auctionId");
        // Yields asked by three buying partners (ask <= the 10,000 discount pool).
        String[] bids = {
                "{\"nodeRef\": 1, \"askMinor\": 6000, \"reputation\": 80, \"latencyMicros\": 20000}",
                "{\"nodeRef\": 2, \"askMinor\": 4000, \"reputation\": 95, \"latencyMicros\": 5000}",
                "{\"nodeRef\": 3, \"askMinor\": 12000, \"reputation\": 99, \"latencyMicros\": 1000}"};  // above the pool
        for (String b : bids) {
            given().contentType(ContentType.JSON).body(b).post("/v1/exchange/auctions/" + id + "/bids").then().statusCode(202);
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && given().get("/v1/exchange/auctions/" + id).jsonPath().get("ranked") == null) {
            Thread.sleep(20);
        }
        given().get("/v1/exchange/auctions/" + id).then().statusCode(200)
                .body("ranked", equalTo(2))
                .body("top[0].nodeRef", equalTo(2))
                .body("top[1].nodeRef", equalTo(1));
        given().contentType(ContentType.JSON).body(bids[0])
                .post("/v1/exchange/auctions/" + id + "/bids").then().statusCode(409);
    }
}
