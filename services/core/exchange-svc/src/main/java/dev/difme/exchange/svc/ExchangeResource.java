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
                         long maxPriceMinor, FraudScorer.Features fraudFeatures) {}

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
    @Inject FraudScorer fraudScorer;
    /** Dev and verification clusters: a simulated merchant and carrier sign the proof of delivery (SimParties). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "difme.exchange.sim-parties", defaultValue = "true") boolean simParties;

    /**
     * Registers a mandate after the pack's mandate_validation rules. The required security deposit is computed by the
     * exchange (vertical.yaml collateral.mandate_deposit_bps of the budget), never taken from the partner.
     */
    @POST
    @Path("/{pack}/mandates")
    public Response mandate(@PathParam("pack") String packId, Mandate m) {
        VerticalPack pack = exchange.pack(packId);
        if (pack == null) return Response.status(404).entity(Map.of("error", "unknown vertical pack " + packId)).build();
        if (m == null) return Response.status(400).entity(Map.of("error", "a mandate body is required")).build();
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
        if (in == null) return Response.status(400).entity(Map.of("error", "an intent body is required")).build();
        if (in.listMinor() <= 0 || in.discountBps() <= 0 || in.discountBps() >= 10_000 || in.feeMinor() < 0) {
            return Response.status(400).entity(Map.of("error", "listMinor > 0, 0 < discountBps < 10000, feeMinor >= 0")).build();
        }
        // §11.1 FAIL path: raw features instead of an attested edge score → the exchange scores them server-side and
        // that score replaces the client's. No score (scorer down, slow, erroring) fails closed: RISK_UNSCORED blocks.
        double riskScore = in.riskScore();
        FraudScorer.Score server = null;
        if (in.fraudFeatures() != null) {
            server = fraudScorer.score(in.fraudFeatures()).orElse(null);
            if (server == null) return Response.status(422).entity(Map.of("findings", List.of(new Finding("RISK_UNSCORED",
                    Finding.Severity.BLOCK, "The device could not be verified and the server-side risk check is unavailable; try again shortly.")))).build();
            riskScore = server.riskScore();
        }
        long discount = Math.round(in.listMinor() * (in.discountBps() / 10_000.0));
        long cardPrice = in.listMinor() - discount;
        List<Finding> findings = pack.rules().evaluate("buyer_intent_eligibility", Map.of(
                "riskScore", riskScore,
                "maxPrice", in.maxPriceMinor() / 100.0,
                "minViable", (cardPrice + in.feeMinor()) / 100.0,
                "listPrice", in.listMinor() / 100.0,
                "offerSanctioned", in.offerSanctioned()));
        if (!PackRules.allowed(findings)) return Response.status(422).entity(Map.of("findings", findings)).build();
        long maxYield = discount - in.feeMinor() - 1;
        if (maxYield <= 0) return Response.status(422).entity(Map.of("error", "the fee leaves no yield inside the discount")).build();
        Map<String, Object> evidence = new LinkedHashMap<>();       // canonical JSON: integers, strings, booleans
        evidence.put("riskScore", Math.round(riskScore));
        evidence.put("riskSource", server == null ? "edge" : "server:" + server.model());
        if (server != null) evidence.put("riskDecision", server.decision());
        evidence.put("offerSanctioned", in.offerSanctioned());
        evidence.put("listMinor", in.listMinor());
        evidence.put("discountBps", in.discountBps());
        evidence.put("feeMinor", in.feeMinor());
        evidence.put("maxPriceMinor", in.maxPriceMinor());
        evidence.put("findings", findings.stream().map(Finding::code).toList());
        long auction = exchange.open(packId, maxYield, new Exchange.Terms(in.listMinor(), in.discountBps(), in.feeMinor()), evidence);
        if (auction < 0) return Response.status(503).entity(Map.of("error", "exchange busy, retry")).build();
        int standing = standingBids(auction);
        return Response.accepted(Map.of("auctionId", auction, "maxYieldMinor", maxYield, "findings", findings, "standingBids", standing,
                "riskSource", evidence.get("riskSource"))).build();
    }

    /**
     * Standing mandates are resting orders (decided 2026-11-20): the 50 ms batch window is too short for a person or a
     * polling app, so when an intent opens the exchange bids for every standing mandate of the vertical, asking the
     * mandate's yield floor (minYieldPct of A). Each bid goes through the same investor_eligibility rules and mandate
     * records as an external bid; ineligible mandates simply don't bid. Remote agents still bid through /bids or the hub.
     */
    int standingBids(long auctionId) {
        Exchange.Context ctx = exchange.context(auctionId);
        String packId = exchange.packOf(auctionId);
        if (ctx == null || packId == null) return 0;
        int placed = 0;
        for (long mandateId : exchange.standingMandates(packId)) {
            Exchange.MandateState ms = exchange.mandate(mandateId);
            if (ms == null) continue;
            long ask = (long) Math.ceil(ctx.cardPriceMinor() * (ms.terms().minYieldPct() / 100.0));
            if (ask > ctx.yieldRoomMinor() || ms.budgetLeftMinor().get() < ctx.cardPriceMinor()) continue;
            Partner p = new Partner("A", ms.ageDays(), ms.terms().depositMinor(), ms.budgetLeftMinor().get(), true, true,
                    ms.terms().maxPerOrderMinor(), ms.terms().validityDays(), mandateId);
            // Co-located with the exchange: no network hop, so latency is reported as zero.
            if (bid(auctionId, new Bid(ms.nodeRef(), ask, 0, 0, p)).getStatus() == 202) placed++;
        }
        return placed;
    }

    /** What a buying partner sees about one mandate: what is left, what it won and earned, and its tier. */
    @GET
    @Path("/{pack}/mandates/{id}")
    public Response mandateState(@PathParam("pack") String packId, @PathParam("id") long mandateId) {
        Exchange.MandateState ms = exchange.mandate(mandateId);
        if (ms == null || !ms.pack().equals(packId)) return Response.status(404).build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mandateId", mandateId);
        out.put("partnerRef", ms.terms().partnerRef());
        out.put("nodeRef", ms.nodeRef());
        out.put("standing", ms.standing());
        out.put("budgetTotalMinor", ms.terms().budgetTotalMinor());
        out.put("budgetLeftMinor", ms.budgetLeftMinor().get());
        out.put("awards", ms.awards().get());
        out.put("yieldEarnedMinor", ms.yieldEarnedMinor().get());
        out.put("minYieldPct", ms.terms().minYieldPct());
        out.put("ageDays", ms.ageDays());
        out.put("validityDays", ms.terms().validityDays());
        out.put("tier", exchange.tierOf(packId, ms.nodeRef()).name());
        return Response.ok(out).build();
    }

    @POST
    @Path("/auctions/{id}/bids")
    public Response bid(@PathParam("id") long auctionId, Bid b) {
        if (b == null) return Response.status(400).entity(Map.of("error", "a bid body is required")).build();
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
            // The exchange's own number at this partner's own tier (from its delivery history), not the bidder's claim.
            in.put("maxDepositNeeded", exchange.depositNeeded(auctionId, b.nodeRef()) / 100.0);
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
        double rep = exchange.reputationOf(exchange.packOf(auctionId), b.nodeRef(), b.reputation());   // FR-16: tracked once known
        if (!exchange.bid(auctionId, b.nodeRef(), b.askMinor(), rep, b.latencyMicros())) {
            return Response.status(503).entity(Map.of("error", "exchange busy, retry")).build();
        }
        return Response.accepted().build();
    }

    /** The auction's B-chain (hash-linked, Ed25519-sealed at close) and a fresh verification verdict. */
    @GET
    @Path("/auctions/{id}/evidence")
    public Response evidence(@PathParam("id") long auctionId) {
        exchange.awaitSeal(auctionId, 2_000);
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

    /** The exchange's FR-16 reputation for a partner in a vertical (null until its first outcome). */
    @GET
    @Path("/{pack}/partners/{nodeRef}")
    public Response partner(@PathParam("pack") String packId, @PathParam("nodeRef") int nodeRef) {
        if (exchange.pack(packId) == null) return Response.status(404).build();
        Double tracked = exchange.trackedReputation(packId, nodeRef);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", packId);
        out.put("nodeRef", nodeRef);
        out.put("reputation", tracked);
        out.put("tracked", tracked != null);
        out.put("verifiedDeliveries", exchange.verifiedDeliveries(packId, nodeRef));
        out.put("tier", exchange.tierOf(packId, nodeRef).name());
        return Response.ok(out).build();
    }

    public record Execute(String merchantId) {}

    public record Party(String name, String publicKey) {}

    /** Registers a merchant ("merchant:<id>") or carrier ("carrier:<name>") Ed25519 key (X.509 SubjectPublicKeyInfo, base64). */
    @POST
    @Path("/parties")
    public Response party(Party p) {
        if (p == null || p.name() == null || !(p.name().startsWith("merchant:") || p.name().startsWith("carrier:")) || p.publicKey() == null) {
            return Response.status(400).entity(Map.of("error", "name must start with merchant: or carrier:, publicKey required")).build();
        }
        try {
            var key = java.security.KeyFactory.getInstance("Ed25519")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(p.publicKey())));
            exchange.registerPartyKey(p.name(), key);
            return Response.status(201).build();
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "publicKey is not an Ed25519 X.509 key")).build();
        }
    }

    /** FR-01 proof of delivery: signed merchant invoice + carrier delivery event. */
    @POST
    @Path("/auctions/{id}/fulfilment")
    public Response fulfilment(@PathParam("id") long auctionId, ProofOfDelivery.Proof proof) {
        Exchange.Fulfilment f = exchange.fulfil(auctionId, proof);
        if (f == null) return Response.status(409).entity(Map.of("error", "auction has no successful execution")).build();
        return f.verified() ? Response.ok(f).build() : Response.status(422).entity(f).build();
    }

    /**
     * Dev only: the simulated merchant and carrier deliver an executed order and submit their signed proof, which is
     * checked exactly like a real one. 404 when simulated parties are off.
     */
    @POST
    @Path("/auctions/{id}/sim/delivery")
    public Response simDelivery(@PathParam("id") long auctionId) {
        if (!simParties) return Response.status(404).build();
        Exchange.Execution e = exchange.execution(auctionId);
        Exchange.Result r = exchange.result(auctionId);
        if (e == null || !e.fulfilled() || r == null || r.award() == null) {
            return Response.status(409).entity(Map.of("error", "auction has no successful execution")).build();
        }
        return fulfilment(auctionId, exchange.simParties().deliver(auctionId, e.merchantId(), r.award().split().a(), System.currentTimeMillis()));
    }

    /** Releases the yield Y after the return window. */
    @POST
    @Path("/auctions/{id}/settle")
    public Response settle(@PathParam("id") long auctionId) {
        try {
            var hold = exchange.settle(auctionId, System.currentTimeMillis());
            if (hold == null) return Response.status(409).entity(Map.of("error", "no accepted fulfilment")).build();
            return Response.ok(hold).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

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
