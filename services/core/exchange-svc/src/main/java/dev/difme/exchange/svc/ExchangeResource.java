package dev.difme.exchange.svc;

import dev.difme.exchange.kernel.EvidenceChain;
import dev.difme.exchange.kernel.Risk;
import dev.difme.exchange.rules.Finding;
import dev.difme.exchange.rules.PackRules;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP edge of the exchange (ADR-020). Eligibility first (embedded DMN), then the sealed-bid auction. */
@Path("/v1/exchange")
public class ExchangeResource {
    /**
     * A buyer's intent, all amounts in minor units. The exchange derives the rest from it: A = P − d·P (card price),
     * the DMN inputs (in major units, as the rule tables are written), and the auction's maximum acceptable yield,
     * Y_max = d·P − F − 1, so the buyer always pays less than list (vertical.yaml price_basis).
     */
    public record Intent(double riskScore, boolean offerSanctioned, long listMinor, int discountBps, long feeMinor,
                         long maxPriceMinor, Risk.Tier partnerTier) {}

    /**
     * The buying partner's facts for bid-time eligibility (FR-01 investor_eligibility). Minor units; the rule tables
     * are written in major units, as for the buyer rules.
     */
    public record Partner(String flow, int intentAgeDays, long depositMinor, long budgetLeftMinor, boolean merchantApproved,
                          boolean goodsInScope, long maxPerOrderMinor, int validityDays, Long mandateId) {}

    public record Bid(int nodeRef, long askMinor, double reputation, long latencyMicros, Partner partner) {}

    /** A buying partner's standing mandate (FR-01 v4.1): minor units except the yield floor (percent) and counts. */
    public record Mandate(String partnerRef, long limitMinor, long maxPerOrderMinor, double minYieldPct, long depositMinor,
                          long budgetTotalMinor, int merchantCount, int goodsCount, int validityDays, String cardJurisdiction) {}

    @Inject Exchange exchange;

    /**
     * Registers a mandate after the pack's mandate_validation rules. The required security deposit is computed by the
     * exchange (vertical.yaml collateral.mandate_deposit_bps of the budget), never taken from the partner.
     */
    @POST
    @Path("/{pack}/mandates")
    public Response mandate(@PathParam("pack") String packId, Mandate m) {
        VerticalPack pack = exchange.pack(packId);
        if (pack == null) return Response.status(404).entity(Map.of("error", "unknown vertical pack " + packId)).build();
        if (!pack.rules().decisionIds().contains("mandate_validation")) {
            return Response.status(400).entity(Map.of("error", "this vertical takes no mandates")).build();
        }
        long depositRequired = Math.round(m.budgetTotalMinor() * (pack.collateral().mandateDepositBps() / 10_000.0));
        Map<String, Object> in = new java.util.HashMap<>();
        in.put("maxPerOrder", m.maxPerOrderMinor() / 100.0);
        in.put("limit", m.limitMinor() / 100.0);
        in.put("minYieldPct", m.minYieldPct());
        in.put("deposit", m.depositMinor() / 100.0);
        in.put("depositRequired", depositRequired / 100.0);
        in.put("budgetTotal", m.budgetTotalMinor() / 100.0);
        in.put("merchantCount", m.merchantCount());
        in.put("goodsCount", m.goodsCount());
        in.put("validityDays", m.validityDays());
        in.put("cardJurisdiction", m.cardJurisdiction());
        List<Finding> findings = pack.rules().evaluate("mandate_validation", in);
        if (!PackRules.allowed(findings)) {
            return Response.status(422).entity(Map.of("findings", findings, "depositRequiredMinor", depositRequired)).build();
        }
        long id = exchange.registerMandate(packId, m);
        return Response.status(201).entity(Map.of("mandateId", id, "depositRequiredMinor", depositRequired, "findings", findings)).build();
    }

    @POST
    @Path("/{pack}/intents")
    public Response intent(@PathParam("pack") String packId, Intent in) {
        VerticalPack pack = exchange.pack(packId);
        if (pack == null) return Response.status(404).entity(Map.of("error", "unknown vertical pack " + packId)).build();
        if (in.listMinor() <= 0 || in.discountBps() <= 0 || in.discountBps() >= 10_000 || in.feeMinor() < 0) {
            return Response.status(400).entity(Map.of("error", "listMinor > 0, 0 < discountBps < 10000, feeMinor >= 0")).build();
        }
        long discount = Math.round(in.listMinor() * (in.discountBps() / 10_000.0));
        long cardPrice = in.listMinor() - discount;
        List<Finding> findings = pack.rules().evaluate("buyer_intent_eligibility", Map.of(
                "riskScore", in.riskScore(),
                "maxPrice", in.maxPriceMinor() / 100.0,
                "minViable", (cardPrice + in.feeMinor()) / 100.0,
                "listPrice", in.listMinor() / 100.0,
                "offerSanctioned", in.offerSanctioned()));
        if (!PackRules.allowed(findings)) return Response.status(422).entity(Map.of("findings", findings)).build();
        long maxYield = discount - in.feeMinor() - 1;
        if (maxYield <= 0) return Response.status(422).entity(Map.of("error", "the fee leaves no yield inside the discount")).build();
        Risk.Tier tier = in.partnerTier() == null ? Risk.Tier.ESTABLISHED : in.partnerTier();
        Map<String, Object> evidence = new LinkedHashMap<>();       // canonical JSON: integers, strings, booleans
        evidence.put("riskScore", Math.round(in.riskScore()));
        evidence.put("offerSanctioned", in.offerSanctioned());
        evidence.put("listMinor", in.listMinor());
        evidence.put("discountBps", in.discountBps());
        evidence.put("feeMinor", in.feeMinor());
        evidence.put("maxPriceMinor", in.maxPriceMinor());
        evidence.put("partnerTier", tier.name());
        evidence.put("findings", findings.stream().map(Finding::code).toList());
        long auction = exchange.open(packId, maxYield, new Exchange.Terms(in.listMinor(), in.discountBps(), in.feeMinor(), tier), evidence);
        if (auction < 0) return Response.status(503).entity(Map.of("error", "exchange busy, retry")).build();
        return Response.accepted(Map.of("auctionId", auction, "maxYieldMinor", maxYield, "findings", findings)).build();
    }

    @POST
    @Path("/auctions/{id}/bids")
    public Response bid(@PathParam("id") long auctionId, Bid b) {
        if (!exchange.isOpen(auctionId)) return Response.status(409).entity(Map.of("error", "auction not open")).build();
        VerticalPack pack = exchange.pack(exchange.packOf(auctionId));
        Exchange.Context ctx = exchange.context(auctionId);
        if (pack != null && ctx != null && pack.rules().decisionIds().contains("investor_eligibility")) {
            Partner pt = b.partner();
            if (pt == null) return Response.status(400).entity(Map.of("error", "this vertical needs the partner block on bids")).build();
            // With a registered mandate the exchange's own records replace the bidder's claims for limits, age and budget.
            Exchange.MandateState ms = pt.mandateId() == null ? null : exchange.mandate(pt.mandateId());
            if (pt.mandateId() != null && ms == null) return Response.status(404).entity(Map.of("error", "unknown mandate")).build();
            if (ms != null && !ms.pack().equals(exchange.packOf(auctionId))) {
                return Response.status(409).entity(Map.of("error", "mandate belongs to another vertical")).build();
            }
            Map<String, Object> in = new java.util.HashMap<>();
            in.put("flow", pt.flow());
            in.put("intentAgeDays", ms != null ? ms.ageDays() : pt.intentAgeDays());
            in.put("validityDays", ms != null ? ms.terms().validityDays() : pt.validityDays());
            in.put("deposit", (ms != null ? ms.terms().depositMinor() : pt.depositMinor()) / 100.0);
            in.put("maxDepositNeeded", ctx.depositNeededMinor() / 100.0);   // the exchange's own number, not the bidder's
            in.put("amountA", ctx.cardPriceMinor() / 100.0);
            in.put("maxPerOrder", (ms != null ? ms.terms().maxPerOrderMinor() : pt.maxPerOrderMinor()) / 100.0);
            in.put("budgetLeft", (ms != null ? ms.budgetLeftMinor().get() : pt.budgetLeftMinor()) / 100.0);
            in.put("merchantApproved", pt.merchantApproved());
            in.put("goodsInScope", pt.goodsInScope());
            in.put("yieldAsk", b.askMinor() / 100.0);
            in.put("yieldRoom", ctx.yieldRoomMinor() / 100.0);
            List<Finding> findings = pack.rules().evaluate("investor_eligibility", in);
            if (!PackRules.allowed(findings)) return Response.status(422).entity(Map.of("findings", findings)).build();
        }
        if (b.partner() != null && b.partner().mandateId() != null) exchange.bindBidMandate(auctionId, b.nodeRef(), b.partner().mandateId());
        if (!exchange.bid(auctionId, b.nodeRef(), b.askMinor(), b.reputation(), b.latencyMicros())) {
            return Response.status(503).entity(Map.of("error", "exchange busy, retry")).build();
        }
        return Response.accepted().build();
    }

    /** The auction's B-chain (hash-linked, Ed25519-sealed at close) and a fresh verification verdict. */
    @GET
    @Path("/auctions/{id}/evidence")
    public Response evidence(@PathParam("id") long auctionId) {
        EvidenceChain chain = exchange.evidence(auctionId);
        if (chain == null) return Response.status(404).build();
        Map<String, java.security.PublicKey> keys = exchange.publicKeys();
        return Response.ok(Map.of("chain", chain.id(), "artifacts", chain.artifacts(),
                "sealed", chain.seal() != null, "verdict", chain.verify(keys::get))).build();
    }

    /** Public keys for independent verification of the evidence seals (X.509 SubjectPublicKeyInfo, base64). */
    @GET
    @Path("/keys")
    public Map<String, String> keys() {
        Map<String, String> out = new LinkedHashMap<>();
        exchange.publicKeys().forEach((name, k) -> out.put(name, Base64.getEncoder().encodeToString(k.getEncoded())));
        return out;
    }

    public record Execute(String merchantId) {}

    /** Carries out the award over the rails (escrow hold, failover across winner and backups, principal release). */
    @POST
    @Path("/auctions/{id}/execute")
    public Response execute(@PathParam("id") long auctionId, Execute req) {
        if (req == null || req.merchantId() == null || req.merchantId().isBlank()) {
            return Response.status(400).entity(Map.of("error", "merchantId is required")).build();
        }
        Exchange.Execution e = exchange.execute(auctionId, req.merchantId());
        if (e != null) return Response.ok(e).build();
        return exchange.isOpen(auctionId) || exchange.result(auctionId) != null
                ? Response.status(409).entity(Map.of("error", "auction has no award yet")).build()
                : Response.status(404).build();
    }

    @GET
    @Path("/auctions/{id}")
    public Response result(@PathParam("id") long auctionId) {
        Exchange.Result r = exchange.result(auctionId);
        if (r != null) return Response.ok(r).build();
        if (exchange.isOpen(auctionId)) return Response.ok(Map.of("auctionId", auctionId, "status", "OPEN")).build();
        return Response.status(404).build();
    }
}
