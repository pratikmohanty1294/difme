package dev.difme.exchange.svc;

import dev.difme.exchange.kernel.Risk;
import dev.difme.exchange.rules.Finding;
import dev.difme.exchange.rules.PackRules;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
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

    public record Bid(int nodeRef, long askMinor, double reputation, long latencyMicros) {}

    @Inject Exchange exchange;

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
        long auction = exchange.open(packId, maxYield, new Exchange.Terms(in.listMinor(), in.discountBps(), in.feeMinor(), tier));
        if (auction < 0) return Response.status(503).entity(Map.of("error", "exchange busy, retry")).build();
        return Response.accepted(Map.of("auctionId", auction, "maxYieldMinor", maxYield, "findings", findings)).build();
    }

    @POST
    @Path("/auctions/{id}/bids")
    public Response bid(@PathParam("id") long auctionId, Bid b) {
        if (!exchange.isOpen(auctionId)) return Response.status(409).entity(Map.of("error", "auction not open")).build();
        if (!exchange.bid(auctionId, b.nodeRef(), b.askMinor(), b.reputation(), b.latencyMicros())) {
            return Response.status(503).entity(Map.of("error", "exchange busy, retry")).build();
        }
        return Response.accepted().build();
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
