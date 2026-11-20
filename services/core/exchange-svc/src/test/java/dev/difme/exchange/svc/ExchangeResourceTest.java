package dev.difme.exchange.svc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * End to end through HTTP on the FR-01 pack: eligibility (embedded DMN), a sealed-bid auction, and the award with the
 * conservation-identity split and FR-14 collateral. Amounts in paise: P = ₹1,00,000, d = 20 %, F = ₹1,000.
 */
@QuarkusTest
class ExchangeResourceTest {
    @Inject Exchange exchange;
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

    /** Runs one small auction (P = ₹20,000, A = ₹16,000) with a single mandate-backed bid; returns the bid's status. */
    static int auctionWithMandate(long mandateId) throws InterruptedException {
        long id = given().contentType(ContentType.JSON).body("""
                {"riskScore": 900, "offerSanctioned": true, "listMinor": 2000000, "discountBps": 2000,
                 "feeMinor": 20000, "maxPriceMinor": 1900000}""")
                .post("/v1/exchange/fr01-buy/intents").then().statusCode(202).extract().jsonPath().getLong("auctionId");
        int status = given().contentType(ContentType.JSON).body("""
                {"nodeRef": 9, "askMinor": 100000, "reputation": 90, "latencyMicros": 5000,
                 "partner": {"flow": "A", "intentAgeDays": 0, "depositMinor": 0, "budgetLeftMinor": 999999999,
                             "merchantApproved": true, "goodsInScope": true, "maxPerOrderMinor": 0, "validityDays": 0,
                             "mandateId": %d}}""".formatted(mandateId))
                .post("/v1/exchange/auctions/" + id + "/bids").then().extract().statusCode();
        if (status == 202) {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && given().get("/v1/exchange/auctions/" + id).jsonPath().get("award") == null) {
                Thread.sleep(20);
            }
        }
        return status;
    }

    @Test
    void mandateBudgetIsTheExchangesToTrackAndIsDrawnDownByAwards() throws InterruptedException {
        // Budget ₹40,000, deposit ₹6,000 (covers the FR-14 collateral for A = ₹16,000: 5,760); limits from the mandate,
        // not from the bid (the bid claims a huge budget and zero limits, which the exchange ignores).
        long mandateId = given().contentType(ContentType.JSON).body("""
                {"partnerRef": "bp-9", "limitMinor": 5000000, "maxPerOrderMinor": 2000000, "minYieldPct": 4,
                 "depositMinor": 600000, "budgetTotalMinor": 4000000, "merchantCount": 2, "goodsCount": 5,
                 "validityDays": 5, "cardJurisdiction": "IN_DOMESTIC_AFA"}""")
                .post("/v1/exchange/fr01-buy/mandates").then().statusCode(201).extract().jsonPath().getLong("mandateId");
        org.junit.jupiter.api.Assertions.assertEquals(202, auctionWithMandate(mandateId));   // budget 40,000 -> 24,000
        org.junit.jupiter.api.Assertions.assertEquals(202, auctionWithMandate(mandateId));   // 24,000 -> 8,000
        org.junit.jupiter.api.Assertions.assertEquals(422, auctionWithMandate(mandateId));   // 8,000 < A: BUDGET_EXHAUSTED
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

    /** ADR-018 rails: escrow holds B, the winner's card can't cover A (51), the backup buys, the winner is slashed. */
    @Test
    void awardIsExecutedOverTheRailsWithFailoverToTheBackup() throws Exception {
        long id = given().contentType(ContentType.JSON).body(intent(900, 9_500_000))
                .post("/v1/exchange/fr01-buy/intents").then().statusCode(202).extract().jsonPath().getLong("auctionId");
        given().contentType(ContentType.JSON).body("{\"merchantId\": \"m-1\"}")
                .post("/v1/exchange/auctions/" + id + "/execute").then().statusCode(409);   // still open: no award yet
        given().contentType(ContentType.JSON).body(bid(41, 600_000, 80, 20_000, 3_000_000))
                .post("/v1/exchange/auctions/" + id + "/bids").then().statusCode(202);
        given().contentType(ContentType.JSON).body(bid(42, 400_000, 95, 5_000, 3_000_000))
                .post("/v1/exchange/auctions/" + id + "/bids").then().statusCode(202);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && given().get("/v1/exchange/auctions/" + id).jsonPath().get("award") == null) {
            Thread.sleep(20);
        }
        exchange.rails().checkout.limit(42, 1);            // the winner's card can't cover A = 8,000,000
        given().contentType(ContentType.JSON).body("{}").post("/v1/exchange/auctions/" + id + "/execute").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"merchantId\": \"m-1\"}")
                .post("/v1/exchange/auctions/" + id + "/execute").then().statusCode(200)
                .body("fulfilled", equalTo(true))
                .body("boughtBy", equalTo(41))
                .body("attempts[0].partnerId", equalTo(42))
                .body("attempts[0].responseCode", equalTo("51"))
                .body("attempts[1].responseCode", equalTo("00"))
                // FR-15: β = iso_51_bps 200 (2 % of A), μ = 1.2 seamless failover: 8,000,000 × 0.02 × 1.2
                .body("slashedMinor", equalTo(192_000))
                // escrow: B = A + Y + F = 8,000,000 + 400,000 + 100,000 held; principal A released; Y + F stay held
                .body("escrow.heldMinor", equalTo(8_500_000))
                .body("escrow.releasedMinor", equalTo(8_000_000))
                .body("escrow.state", equalTo("PARTLY_RELEASED"));
        given().contentType(ContentType.JSON).body("{\"merchantId\": \"m-1\"}")
                .post("/v1/exchange/auctions/" + id + "/execute").then().statusCode(200).body("boughtBy", equalTo(41));   // once only
        given().contentType(ContentType.JSON).body("{\"merchantId\": \"m-1\"}")
                .post("/v1/exchange/auctions/987654321/execute").then().statusCode(404);

        // FR-16: the 51 is a failed task for partner 42 (fast drop from the 80 prior, V = A, V_ref = 5,000,000)
        var p42 = given().get("/v1/exchange/fr01-buy/partners/42").then().statusCode(200).body("tracked", equalTo(true))
                .extract().jsonPath();
        org.junit.jupiter.api.Assertions.assertEquals(60.889771, p42.getDouble("reputation"), 0.001);
        given().get("/v1/exchange/fr01-buy/partners/41").then().statusCode(200).body("tracked", equalTo(false));
        // ---- FR-01 proof of delivery: signed invoice + carrier event, then fee F now and yield Y after the window ----
        java.security.KeyPair merchant = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        java.security.KeyPair carrier = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        for (var party : java.util.Map.of("merchant:m-1", merchant, "carrier:bluedart", carrier).entrySet()) {
            given().contentType(ContentType.JSON).body(java.util.Map.of("name", party.getKey(), "publicKey",
                    java.util.Base64.getEncoder().encodeToString(party.getValue().getPublic().getEncoded())))
                    .post("/v1/exchange/parties").then().statusCode(201);
        }
        long delivered = System.currentTimeMillis();
        given().contentType(ContentType.JSON).body(proof(merchant, carrier, id, 7_999_999, delivered))
                .post("/v1/exchange/auctions/" + id + "/fulfilment").then().statusCode(422)
                .body("problems[0]", org.hamcrest.Matchers.containsString("differs from the principal A"));
        given().post("/v1/exchange/auctions/" + id + "/settle").then().statusCode(409);          // nothing accepted yet
        given().contentType(ContentType.JSON).body(proof(merchant, carrier, id, 8_000_000, delivered))
                .post("/v1/exchange/auctions/" + id + "/fulfilment").then().statusCode(200)
                .body("verified", equalTo(true))
                .body("escrow.releasedMinor", equalTo(8_100_000));                               // A + fee F
        given().post("/v1/exchange/auctions/" + id + "/settle").then().statusCode(200)
                .body("state", equalTo("SETTLED"))
                .body("releasedMinor", equalTo(8_500_000));                                      // A + F + Y = B
        org.junit.jupiter.api.Assertions.assertEquals(81.911023,                                 // delivered: slow gain
                given().get("/v1/exchange/fr01-buy/partners/41").then().statusCode(200).extract().jsonPath().getDouble("reputation"), 0.001);
        given().get("/v1/exchange/auctions/" + id + "/evidence").then().statusCode(200)
                .body("artifacts.type", hasItem("fulfillment"))
                .body("verdict.hashes", equalTo(true));
    }

    static java.util.Map<String, Object> proof(java.security.KeyPair merchant, java.security.KeyPair carrier, long auction,
                                              long amount, long deliveredAt) throws Exception {
        var invoice = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("invoiceId", "inv-" + auction, "merchantId", "m-1",
                "auctionId", auction, "amountMinor", amount, "currency", "INR"));
        var delivery = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("carrier", "bluedart", "trackingId", "BD123",
                "auctionId", auction, "status", "DELIVERED", "deliveredAtMs", deliveredAt));
        invoice.put("signature", sign(merchant, invoice));
        delivery.put("signature", sign(carrier, delivery));
        return java.util.Map.of("invoice", invoice, "delivery", delivery);
    }

    static String sign(java.security.KeyPair k, java.util.Map<String, Object> unsigned) throws Exception {
        var s = java.security.Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        var payload = new java.util.TreeMap<>(unsigned);
        payload.remove("signature");
        s.update(dev.difme.exchange.kernel.Canonical.json(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(s.sign());
    }
}
