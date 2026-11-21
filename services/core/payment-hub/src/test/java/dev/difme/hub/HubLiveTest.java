package dev.difme.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.difme.hub.cbs.Fineract;
import dev.difme.hub.flow.Flows;
import dev.difme.hub.flow.Flows.Result;
import dev.difme.hub.psp.Hyperswitch;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hub against the real systems (Fineract; Hyperswitch → acquirer → scheme for cards), with Temporal's in-memory
 * server. Runs when HUB_LIVE_FINERACT is set (and HUB_LIVE_HYPERSWITCH for the card test); skipped otherwise.
 */
class HubLiveTest {
    private static final String FINERACT = System.getenv("HUB_LIVE_FINERACT");
    private static final String PSP = System.getenv("HUB_LIVE_HYPERSWITCH");
    private TestWorkflowEnvironment env;
    private Fineract cbs;
    private Hub hub;
    private final String run = UUID.randomUUID().toString().substring(0, 8);   // fresh accounts per run

    @BeforeEach
    void setUp() {
        assumeTrue(FINERACT != null, "HUB_LIVE_FINERACT not set");
        // HUB_LIVE_FINERACT_TOKEN_URL set: Keycloak login, USER/PASSWORD are the client id/secret (Fineract with OIDC).
        String tokenUrl = System.getenv("HUB_LIVE_FINERACT_TOKEN_URL");
        cbs = tokenUrl == null
                ? new Fineract(FINERACT, env("HUB_LIVE_FINERACT_USER", "mifos"), env("HUB_LIVE_FINERACT_PASSWORD", "password"), "default")
                : new Fineract(FINERACT, Fineract.clientCredentials(tokenUrl, env("HUB_LIVE_FINERACT_USER", "fineract-admin"),
                        System.getenv("HUB_LIVE_FINERACT_PASSWORD")), "default");
        Hyperswitch psp = PSP == null ? null : new Hyperswitch(PSP, System.getenv("HUB_LIVE_HYPERSWITCH_ADMIN"), System.getenv("HUB_LIVE_ACQUIRER_KEY"));
        env = TestWorkflowEnvironment.newInstance();
        Hub.register(env.getWorkerFactory(), cbs, psp);
        env.start();
        hub = new Hub(env.getWorkflowClient(), cbs, psp);
    }

    @AfterEach
    void tearDown() { if (env != null) env.close(); }

    private static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    @Test
    void escrowHoldReleaseAndRefundOnTheCoreBankingSystem() {
        String buyer = "buyer-" + run, escrow = "escrow-" + run, partner = "partner-" + run;
        assertEquals("DONE", hub.deposit("fund-" + run, buyer, 1_000_000).status());          // ₹10,000 arrives
        assertEquals("DONE", hub.deposit("fund-" + run, buyer, 1_000_000).status());          // repeated: not twice
        assertEquals(1_000_000, cbs.balanceMinor(buyer));

        Result hold = hub.transfer("hold-" + run, buyer, escrow, 850_000, "order hold");
        assertEquals("DONE", hold.status(), hold.detail());
        assertEquals(hold, hub.transfer("hold-" + run, buyer, escrow, 850_000, "order hold"));   // same answer, no second move
        assertEquals(150_000, cbs.balanceMinor(buyer));
        assertEquals(850_000, cbs.balanceMinor(escrow));

        Result tooMuch = hub.transfer("hold2-" + run, buyer, escrow, 150_001, "second order");
        assertEquals("INSUFFICIENT_FUNDS", tooMuch.status(), tooMuch.detail());
        assertEquals(150_000, cbs.balanceMinor(buyer));

        assertEquals("DONE", hub.transfer("release-" + run, escrow, partner, 800_000, "release to partner").status());
        assertEquals("DONE", hub.transfer("refund-" + run, escrow, buyer, 50_000, "refund remainder").status());
        assertEquals(0, cbs.balanceMinor(escrow));
        assertEquals(800_000, cbs.balanceMinor(partner));
        assertEquals(200_000, cbs.balanceMinor(buyer));
        // Conservation: what came in is exactly what the parties hold.
        assertEquals(1_000_000, cbs.balanceMinor(buyer) + cbs.balanceMinor(escrow) + cbs.balanceMinor(partner));
    }

    @Test
    void cardPaymentThroughPspNetworkAndCoreBanking() {
        assumeTrue(PSP != null, "HUB_LIVE_HYPERSWITCH not set");
        String merchant = "m" + run, card = env("HUB_LIVE_CARD", "4375510000000001");
        YearMonth exp = YearMonth.now().plusYears(2);
        Hyperswitch.Card c = new Hyperswitch.Card(card, String.format("%02d", exp.getMonthValue()), String.valueOf(exp.getYear()), "123", "Test");
        long before = cbs.balanceMinor(Flows.CARD_SETTLEMENT);

        Result paid = hub.pay("pay-" + run, merchant, 250_000, "INR", c);
        assertEquals("SETTLED", paid.status(), paid.detail());
        assertEquals(paid, hub.pay("pay-" + run, merchant, 250_000, "INR", c));     // retried call: no second charge
        assertEquals(250_000, cbs.balanceMinor("merchant-" + merchant));
        assertEquals(before - 250_000, cbs.balanceMinor(Flows.CARD_SETTLEMENT));

        Result declined = hub.pay("big-" + run, merchant, 999_999_999, "INR", c);   // over the card's limit at the issuer
        assertEquals("DECLINED", declined.status(), declined.detail());
        assertTrue(declined.detail().startsWith("51"), declined.detail());
        assertEquals(250_000, cbs.balanceMinor("merchant-" + merchant));

        Result refund = hub.refund("ret-" + run, "pay-" + run, merchant, 100_000, "returned");
        assertEquals("REFUNDED", refund.status(), refund.detail());
        assertEquals(150_000, cbs.balanceMinor("merchant-" + merchant));

        Result tooMuch = hub.refund("ret2-" + run, "pay-" + run, merchant, 200_000, "returned again");
        assertEquals("INSUFFICIENT_FUNDS", tooMuch.status(), tooMuch.detail());      // the merchant holds only ₹1,500
    }
}
