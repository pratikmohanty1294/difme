package dev.difme.exchange.svc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * End to end through HTTP on the FR-01 pack: eligibility (embedded DMN), a sealed-bid auction, and the award with the
 * conservation-identity split and FR-14 collateral. Amounts in paise: P = ₹1,00,000, d = 20 %, F = ₹1,000.
 */
@QuarkusTest
class ExchangeResourceTest {
    static String intent(int risk, long maxPriceMinor) {
        return """
                {"riskScore": %d, "offerSanctioned": true, "listMinor": 10000000, "discountBps": 2000,
                 "feeMinor": 100000, "maxPriceMinor": %d}""".formatted(risk, maxPriceMinor);
    }

    /** A bid with an eligible buying partner (flow A, mandate allows A, budget covers A) unless the deposit says otherwise. */
    static String bid(int node, long ask, int reputation, long latencyMicros, long depositMinor) {
        return """
                {"nodeRef": %d, "askMinor": %d, "reputation": %d, "latencyMicros": %d,
                 "partner": {"flow": "A", "intentAgeDays": 1, "depositMinor": %d, "budgetLeftMinor": 10000000,
                             "merchantApproved": true, "goodsInScope": true, "maxPerOrderMinor": 10000000, "validityDays": 30}}"""
                .formatted(node, ask, reputation, latencyMicros, depositMinor);
    }

    @Test
    void ineligibleIntentIsRefusedWithTheRuleFindings() {
        given().contentType(ContentType.JSON).body(intent(700, 9_500_000))
                .post("/v1/exchange/fr01-buy/intents")
                .then().statusCode(422).body("findings.code", hasItem("RISK_TOO_LOW"));
    }

    static String mandate(long depositMinor, int validityDays) {
        return """
                {"partnerRef": "bp-7", "limitMinor": 5000000, "maxPerOrderMinor": 2000000, "minYieldPct": 4,
                 "depositMinor": %d, "budgetTotalMinor": 4000000, "merchantCount": 3, "goodsCount": 12,
                 "validityDays": %d, "cardJurisdiction": "IN_DOMESTIC_AFA"}""".formatted(depositMinor, validityDays);
    }

    @Test
    void mandateIsValidatedWithTheExchangesOwnDepositRequirement() {
        // depositRequired = 5 % of the 40,000 budget = 2,000 (200,000 paise), computed by the exchange
        given().contentType(ContentType.JSON).body(mandate(200_000, 5))
                .post("/v1/exchange/fr01-buy/mandates")
                .then().statusCode(201).body("depositRequiredMinor", equalTo(200_000))
                .body("findings.code", hasItem("INDIA_PREAUTH_MANDATE"));
        given().contentType(ContentType.JSON).body(mandate(100_000, 10))
                .post("/v1/exchange/fr01-buy/mandates")
                .then().statusCode(422).body("findings.code", hasItem("DEPOSIT_SHORT"))
                .body("findings.code", hasItem("VALIDITY_TOO_LONG"));
    }

    @Test
    void unknownPackIs404() {
        given().contentType(ContentType.JSON).body(intent(900, 9_500_000))
                .post("/v1/exchange/nope/intents").then().statusCode(404);
    }

    @Test
    void auctionAwardsTheBestScoreWithSplitAndCollateral() throws InterruptedException {
        var opened = given().contentType(ContentType.JSON).body(intent(900, 9_500_000))
                .post("/v1/exchange/fr01-buy/intents")
                .then().statusCode(202).extract().jsonPath();
        long id = opened.getLong("auctionId");
        // Y_max = d·P − F − 1 = 2,000,000 − 100,000 − 1
        org.junit.jupiter.api.Assertions.assertEquals(1_899_999L, opened.getLong("maxYieldMinor"));
        String[] bids = {bid(1, 600_000, 80, 20_000, 3_000_000), bid(2, 400_000, 95, 5_000, 3_000_000)};
        for (String b : bids) {
            given().contentType(ContentType.JSON).body(b).post("/v1/exchange/auctions/" + id + "/bids").then().statusCode(202);
        }
        // Refused at the door by investor_eligibility: the yield asked exceeds the room (Y_max), ...
        given().contentType(ContentType.JSON).body(bid(3, 2_500_000, 99, 1_000, 3_000_000))
                .post("/v1/exchange/auctions/" + id + "/bids")
                .then().statusCode(422).body("findings.code", hasItem("PRICE_ABOVE_MAX"));
        // ... and the deposit is below the FR-14 collateral the exchange computes for A (2,880,000).
        given().contentType(ContentType.JSON).body(bid(4, 300_000, 99, 1_000, 1_000_000))
                .post("/v1/exchange/auctions/" + id + "/bids")
                .then().statusCode(422).body("findings.code", hasItem("DEPOSIT_SHORT"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && given().get("/v1/exchange/auctions/" + id).jsonPath().get("ranked") == null) {
            Thread.sleep(20);
        }
        given().get("/v1/exchange/auctions/" + id).then().statusCode(200)
                .body("ranked", equalTo(2))
                .body("top[0].nodeRef", equalTo(2))
                .body("top[1].nodeRef", equalTo(1))
                // A = P − d·P = 8,000,000; B = A + Y + F = 8,500,000; buyer saves 1,500,000
                .body("award.split.a", equalTo(8_000_000))
                .body("award.split.b", equalTo(8_500_000))
                .body("award.split.buyerSaves", equalTo(1_500_000))
                // D_req = max(50,000, 8,000,000 × 0.20 × 1.8 × φ(ESTABLISHED)=1.0) = 2,880,000
                .body("award.collateralMinor", equalTo(2_880_000));
        given().contentType(ContentType.JSON).body(bids[0])
                .post("/v1/exchange/auctions/" + id + "/bids").then().statusCode(409);
        // Evidence: intent + authorization, hash-linked, operator-sealed with a witness, and it verifies.
        given().get("/v1/exchange/auctions/" + id + "/evidence").then().statusCode(200)
                .body("sealed", equalTo(true))
                .body("artifacts.type", org.hamcrest.Matchers.contains("intent", "authorization"))
                .body("artifacts[1].payload.amount", equalTo(8_500_000))
                .body("artifacts[1].payload.winnerNode", equalTo(2))
                .body("verdict.hashes", equalTo(true))
                .body("verdict.links", equalTo(true))
                .body("verdict.seal", equalTo(true))
                .body("verdict.witnesses", equalTo(true));
        given().get("/v1/exchange/keys").then().statusCode(200)
                .body("'exchange-svc'", org.hamcrest.Matchers.notNullValue());
    }
}
