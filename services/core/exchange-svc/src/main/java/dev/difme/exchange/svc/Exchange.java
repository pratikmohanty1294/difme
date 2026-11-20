package dev.difme.exchange.svc;

import dev.difme.exchange.rails.AgentCredentialPort;
import dev.difme.exchange.rails.EscrowPort;
import dev.difme.exchange.rails.Failover;
import dev.difme.exchange.rails.MerchantCheckoutPort;
import dev.difme.exchange.rails.sim.SimRails;
import dev.difme.exchange.kernel.BidBook;
import dev.difme.exchange.kernel.CommerceSplit;
import dev.difme.exchange.kernel.EvidenceChain;
import dev.difme.exchange.kernel.Reputation;
import dev.difme.exchange.kernel.Risk;
import dev.difme.exchange.matching.MatchingEngine;
import dev.difme.exchange.matching.MatchingIntake;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.SystemNanoClock;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Hosts one matching engine per vertical pack. Request threads publish into the pack's off-heap ring (lock-free);
 * the engine agent runs on its own thread with a backoff idle strategy (no pinned busy-spin on small hosts) and hands
 * closed auctions to {@link #onClosed}, which copies the top of the ranking into an immutable result.
 */
@Startup
@ApplicationScoped
public class Exchange {
    public record Ranked(int nodeRef, long askMinor, double score) {}

    /** Commercial terms of the intent, needed to settle the award (all minor units). */
    public record Terms(long listMinor, int discountBps, long feeMinor, Risk.Tier partnerTier) {}

    /**
     * The award: the winner's split under the conservation identity (A, Y, F, B, buyer saving; FR-01 v4.1) and the
     * collateral the winner must lock before execution (FR-14, D_req = max(D_min, V·α·γ·φ)). Open owner questions:
     * V is taken as A (what the partner pays the merchant) and the partner tier defaults to ESTABLISHED.
     */
    public record Award(Ranked winner, CommerceSplit split, long collateralMinor) {}

    /** What the exchange (not the bidder) knows about an open auction; the facts bid-time rules are checked against. */
    public record Context(long cardPriceMinor, long yieldRoomMinor, long depositNeededMinor) {}

    public record Result(long auctionId, String pack, int ranked, List<Ranked> top, Award award) {}

    /**
     * The award carried out over the rails: the buyer's B held in escrow, the winner and hot backups tried in rank
     * order (silent failover, FR-15 slashing), principal A released to whoever bought. Yield Y and fee F stay held
     * until the fulfilment proof (pack {@code settlement.split_release}); with nobody able to buy, B is refunded.
     */
    public record Execution(long auctionId, String merchantId, long boughtBy, boolean fulfilled, List<Failover.Attempt> attempts,
                            long slashedMinor, EscrowPort.Hold escrow) {}

    /** Dev and verification clusters run a simulator on every rail (ADR-018); real providers register the same ports. */
    private final SimRails rails = new SimRails();
    private final AtomicLong stan = new AtomicLong();
    private Map<Long, Execution> executions;

    /** Proof of delivery accepted: fee F released, yield Y released at yieldReleaseAtMs (pack return window). */
    public record Fulfilment(long auctionId, boolean verified, List<String> problems, EscrowPort.Hold escrow, long yieldReleaseAtMs) {}

    /** Merchant and carrier signing keys ("merchant:<id>", "carrier:<name>"), registered at /v1/exchange/parties. */
    private final Map<String, PublicKey> partyKeys = new ConcurrentHashMap<>();
    /** FR-16 reputation the exchange has learned per partner ("pack:nodeRef"), from execution and fulfilment outcomes. */
    private final Map<String, Double> reputation = new ConcurrentHashMap<>();
    private Map<Long, Fulfilment> fulfilments;
    /** Overrides every pack's return window when >= 0 (tests can't wait days). */
    @ConfigProperty(name = "difme.exchange.return-window-override-ms", defaultValue = "-1") long returnWindowOverrideMs;

    @ConfigProperty(name = "difme.exchange.packs") List<String> packIds;
    @ConfigProperty(name = "difme.exchange.max-auctions") int maxAuctions;
    @ConfigProperty(name = "difme.exchange.bids-per-auction") int bidsPerAuction;
    @ConfigProperty(name = "difme.exchange.ring-bytes") int ringBytes;
    @ConfigProperty(name = "difme.exchange.results-kept") int resultsKept;
    /** Overrides every pack's auction window when > 0 (tests: HTTP round trips on a cold JVM exceed 50 ms). */
    @ConfigProperty(name = "difme.exchange.window-override-ms", defaultValue = "0") long windowOverrideMs;
    @Inject MeterRegistry metrics;

    private final Map<String, VerticalPack> packs = new ConcurrentHashMap<>();
    private final Map<String, MatchingIntake> intakes = new ConcurrentHashMap<>();
    private final List<AgentRunner> runners = new ArrayList<>();
    private final Map<Long, String> openAuctions = new ConcurrentHashMap<>();
    private final Map<Long, Terms> terms = new ConcurrentHashMap<>();
    private final Map<Long, Context> contexts = new ConcurrentHashMap<>();
    private Map<Long, EvidenceChain> chains;

    // Evidence signing (B-chain per auction, ADR-020). Dev: both keys are generated at start and published at
    // GET /v1/exchange/keys. Open owner question: who witnesses in production (an independent notary service or an
    // HSM-held key) and where the operator key lives (OpenBao transit).
    static final String OPERATOR = "exchange-svc", WITNESS = "exchange-witness";
    private final KeyPair operatorKey = ed25519(), witnessKey = ed25519();
    // Ids stay below 2^53 so JSON clients (JavaScript, the PWAs) read them exactly; millis x 1000 also keeps them
    // increasing across restarts. (millis << 16 exceeded 2^53 and the bids went to a rounded id; found by the test.)
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000L);
    private Map<Long, Result> results;

    @PostConstruct
    void start() {
        int keep = resultsKept;
        results = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Result> e) {
                return size() > keep;
            }
        });
        chains = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, EvidenceChain> e) {
                return size() > keep;
            }
        });
        executions = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Execution> e) {
                return size() > keep;
            }
        });
        fulfilments = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Fulfilment> e) {
                return size() > keep;
            }
        });
        for (String id : packIds) {
            VerticalPack pack;
            try {
                pack = VerticalPack.fromClasspath(id, Thread.currentThread().getContextClassLoader());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot load vertical pack " + id, e);
            }
            rails.registry.requireAll(id, pack.rails());   // a pack can't go live with a rail nobody implements
            ManyToOneRingBuffer ring = MatchingIntake.newRing(ringBytes);
            MatchingEngine engine = new MatchingEngine(pack.weights(), pack.latencyMaxMicros(), maxAuctions,
                    bidsPerAuction, ring, (auction, book, ranked) -> onClosed(id, pack.backups(), auction, book, ranked),
                    new SystemNanoClock(), 4096);
            AgentRunner runner = new AgentRunner(new BackoffIdleStrategy(100, 10, 1_000, 1_000_000),
                    e -> metrics.counter("difme_exchange_engine_errors_total", "pack", id).increment(), null, engine);
            AgentRunner.startOnThread(runner);
            runners.add(runner);
            packs.put(id, pack);
            intakes.put(id, new MatchingIntake(ring));
        }
    }

    public VerticalPack pack(String id) {
        return packs.get(id);
    }

    /** Opens an auction; returns its id, or -1 when the pack's ring is full (backpressure). */
    public long open(String pack, long maxAcceptableMinor, Terms t, Map<String, Object> intentEvidence) {
        long id = ids.incrementAndGet();
        VerticalPack p = packs.get(pack);
        EvidenceChain chain = new EvidenceChain("auction-" + id, EvidenceChain.Kind.B);
        Map<String, Object> intent = new LinkedHashMap<>(intentEvidence);
        intent.put("pack", pack);
        intent.put("auctionId", id);
        intent.put("maxYieldMinor", maxAcceptableMinor);
        chain.append(EvidenceChain.Type.intent, EvidenceChain.Signal.Deterministic, intent);
        chains.put(id, chain);
        long cardPrice = t.listMinor() - Math.round(t.listMinor() * (t.discountBps() / 10_000.0));
        contexts.put(id, new Context(cardPrice, maxAcceptableMinor, Risk.requiredCollateral(cardPrice,
                p.collateral().alpha(), p.collateral().gammaRev(), t.partnerTier(), p.collateral().minMinor())));
        openAuctions.put(id, pack);
        terms.put(id, t);
        long window = windowOverrideMs > 0 ? windowOverrideMs * 1_000_000L : p.windowNanos();
        if (!intakes.get(pack).open(id, maxAcceptableMinor, window)) {
            openAuctions.remove(id);
            terms.remove(id);
            contexts.remove(id);
            return -1;
        }
        return id;
    }

    /** Publishes a bid; false when the auction is unknown/closed or the ring is full. */
    public boolean bid(long auctionId, int nodeRef, long askMinor, double reputation, long latencyMicros) {
        String pack = openAuctions.get(auctionId);
        return pack != null && intakes.get(pack).bid(auctionId, nodeRef, askMinor, reputation, latencyMicros);
    }

    /** Registered mandates (in memory for now; persistence and budget draw-down come with settlement). */
    /** A registered mandate and what the exchange tracks for it (the partner cannot misstate these). */
    public record MandateState(String pack, ExchangeResource.Mandate terms, long registeredAtMillis, AtomicLong budgetLeftMinor) {
        public int ageDays() {
            return (int) ((System.currentTimeMillis() - registeredAtMillis) / 86_400_000L);
        }
    }

    private final Map<Long, MandateState> mandates = new ConcurrentHashMap<>();
    private final AtomicLong mandateIds = new AtomicLong(System.currentTimeMillis() * 1000L);
    /** auction id -> (bidder nodeRef -> mandate id), so the award can draw down the winner's budget. */
    private final Map<Long, Map<Integer, Long>> bidMandates = new ConcurrentHashMap<>();

    public long registerMandate(String pack, ExchangeResource.Mandate m) {
        long id = mandateIds.incrementAndGet();
        mandates.put(id, new MandateState(pack, m, System.currentTimeMillis(), new AtomicLong(m.budgetTotalMinor())));
        return id;
    }

    public MandateState mandate(long id) {
        return mandates.get(id);
    }

    public void bindBidMandate(long auctionId, int nodeRef, long mandateId) {
        bidMandates.computeIfAbsent(auctionId, k -> new ConcurrentHashMap<>()).put(nodeRef, mandateId);
    }

    public Context context(long auctionId) {
        return contexts.get(auctionId);
    }

    public String packOf(long auctionId) {
        return openAuctions.get(auctionId);
    }

    public EvidenceChain evidence(long auctionId) {
        return chains.get(auctionId);
    }

    public Map<String, PublicKey> publicKeys() {
        return Map.of(OPERATOR, operatorKey.getPublic(), WITNESS, witnessKey.getPublic());
    }

    private static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The rail implementations (a method, not a field: callers usually hold a CDI client proxy, whose fields are its own). */
    SimRails rails() {
        return rails;
    }

    /** Carries out an awarded auction once; repeated calls return the first outcome. Null when there is no award yet. */
    public Execution execute(long auctionId, String merchantId) {
        Result r = results.get(auctionId);
        if (r == null || r.award() == null) return null;
        synchronized (executions) {
            Execution done = executions.get(auctionId);
            if (done != null) return done;
            VerticalPack p = packs.get(r.pack());
            CommerceSplit s = r.award().split();
            String holdId = "auction-" + auctionId;
            rails.escrow.hold(holdId, "buyer-" + auctionId, s.b());
            UUID instruction = new UUID(auctionId, 0L);          // the buyer's delegated purchase instruction
            long now = System.currentTimeMillis();
            List<Failover.Candidate> ranked = r.top().stream()
                    .map(t -> new Failover.Candidate(t.nodeRef(), r.award().collateralMinor())).toList();
            Failover.Result f = Failover.run(ranked, s.a(),
                    new Failover.SlashingBps(p.slashing().fundsBps(), p.slashing().otherDeclineBps()), partner -> {
                        var token = rails.credentials.issue(
                                new AgentCredentialPort.Instruction(instruction, partner, merchantId, s.a(), "INR"), now);
                        String field11 = String.format("%06d", stan.incrementAndGet() % 1_000_000);
                        return rails.checkout.authorize(new MerchantCheckoutPort.Authorization(field11, token.token(),
                                merchantId, s.a(), "INR"), now).responseCode();
                    });
            for (Failover.Attempt at : f.attempts()) {
                if (at.slashMinor() > 0) {        // a decline the partner answers for (FR-15) is a failed task (FR-16)
                    learn(r.pack(), at.partnerId(), 0, s.a(), Reputation.PRIOR_UNVERIFIED);
                }
            }
            EscrowPort.Hold hold = f.fulfilled()
                    ? rails.escrow.release(holdId, "principal", "partner-" + f.winner(), s.a())
                    : rails.escrow.refund(holdId);
            if (!f.fulfilled()) {
                EvidenceChain chain = chains.get(auctionId);
                if (chain != null) {
                    Map<String, Object> refund = new LinkedHashMap<>();
                    refund.put("reason", "no partner could buy");
                    refund.put("amount", hold.refundedMinor());
                    refund.put("currency", "INR");
                    chain.append(EvidenceChain.Type.refund, EvidenceChain.Signal.Deterministic, refund);
                }
            }
            metrics.counter("difme_exchange_executions_total", "pack", r.pack(), "fulfilled", f.fulfilled() ? "yes" : "no").increment();
            Execution e = new Execution(auctionId, merchantId, f.winner(), f.fulfilled(), f.attempts(), f.totalSlashMinor(), hold);
            executions.put(auctionId, e);
            return e;
        }
    }

    /**
     * The reputation used to rank a bid: the exchange's own number once the partner has an outcome on record; until
     * then the fixed cold-start prior (80). A self-reported score is ignored: it would be trivially gameable
     * (decided 2026-11-20).
     */
    public double reputationOf(String pack, int nodeRef, double selfReported) {
        return reputation.getOrDefault(pack + ":" + nodeRef, Reputation.PRIOR_UNVERIFIED);
    }

    /** The tracked value, or null when the partner has no outcome on record yet. */
    public Double trackedReputation(String pack, int nodeRef) {
        return reputation.get(pack + ":" + nodeRef);
    }

    private void learn(String pack, long nodeRef, double taskScore, long valueMinor, double coldStart) {
        VerticalPack p = packs.get(pack);
        reputation.compute(pack + ":" + nodeRef, (k, prev) -> Reputation.update(prev == null ? coldStart : prev, taskScore,
                valueMinor, p.reputationReferenceMinor(), p.reputationAlphaBase()));
    }

    public void registerPartyKey(String name, PublicKey key) {
        partyKeys.put(name, key);
    }

    /**
     * Checks the fulfilment proof of an executed auction. On success the fee F goes to the exchange, the yield Y is
     * scheduled after the pack's return window, and a fulfillment artifact joins the evidence chain. Null when the
     * auction has no successful execution; a failed check returns the problems and changes nothing.
     */
    public Fulfilment fulfil(long auctionId, ProofOfDelivery.Proof proof) {
        Execution e = executions.get(auctionId);
        Result r = results.get(auctionId);
        if (e == null || !e.fulfilled() || r == null || r.award() == null) return null;
        synchronized (fulfilments) {
            Fulfilment done = fulfilments.get(auctionId);
            if (done != null) return done;
            CommerceSplit s = r.award().split();
            String holdId = "auction-" + auctionId;
            List<String> problems = ProofOfDelivery.check(proof, auctionId, e.merchantId(), s.a(), partyKeys);
            if (!problems.isEmpty()) return new Fulfilment(auctionId, false, problems, rails.escrow.get(holdId), 0);
            EscrowPort.Hold hold = s.fee() > 0 ? rails.escrow.release(holdId, "fee", "exchange", s.fee()) : rails.escrow.get(holdId);
            long window = returnWindowOverrideMs >= 0 ? returnWindowOverrideMs : packs.get(r.pack()).returnWindowDays() * 86_400_000L;
            long yieldAt = proof.delivery().deliveredAtMs() + window;
            EvidenceChain chain = chains.get(auctionId);
            if (chain != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("invoiceId", proof.invoice().invoiceId());
                f.put("merchantId", proof.invoice().merchantId());
                f.put("amount", proof.invoice().amountMinor());
                f.put("currency", "INR");
                f.put("carrier", proof.delivery().carrier());
                f.put("trackingId", proof.delivery().trackingId());
                f.put("deliveredAtMs", proof.delivery().deliveredAtMs());
                f.put("yieldReleaseAtMs", yieldAt);
                chain.append(EvidenceChain.Type.fulfillment, EvidenceChain.Signal.Attested, f);
            }
            learn(r.pack(), e.boughtBy(), 100, s.a(), Reputation.PRIOR_UNVERIFIED);   // delivered: a successful task
            Fulfilment ok = new Fulfilment(auctionId, true, List.of(), hold, yieldAt);
            fulfilments.put(auctionId, ok);
            metrics.counter("difme_exchange_fulfilments_total", "pack", r.pack()).increment();
            return ok;
        }
    }

    /** Releases the yield Y once the return window has passed. Null when there is no accepted fulfilment. */
    public EscrowPort.Hold settle(long auctionId, long nowMs) {
        Fulfilment f = fulfilments.get(auctionId);
        Result r = results.get(auctionId);
        Execution e = executions.get(auctionId);
        if (f == null || r == null || e == null) return null;
        if (nowMs < f.yieldReleaseAtMs()) throw new IllegalStateException("return window open for another " + (f.yieldReleaseAtMs() - nowMs) + " ms");
        long y = r.award().split().yield();
        String holdId = "auction-" + auctionId;
        return y > 0 ? rails.escrow.release(holdId, "yield", "partner-" + e.boughtBy(), y) : rails.escrow.get(holdId);
    }

    public Result result(long auctionId) {
        return results.get(auctionId);
    }

    public boolean isOpen(long auctionId) {
        return openAuctions.containsKey(auctionId);
    }

    /** Engine thread: copy the winner plus hot backups (PRD: silent failover) out of the reusable book. */
    private void onClosed(String pack, int backups, long auctionId, BidBook book, int ranked) {
        int keep = Math.min(ranked, 1 + backups);
        List<Ranked> top = new ArrayList<>(keep);
        for (int r = 0; r < keep; r++) {
            int b = book.atRank(r);
            top.add(new Ranked(book.node(b), book.ask(b), book.score(b)));
        }
        Terms t = terms.remove(auctionId);
        contexts.remove(auctionId);
        Map<Integer, Long> bound = bidMandates.remove(auctionId);
        Award award = null;
        if (!top.isEmpty() && t != null) {
            VerticalPack p = packs.get(pack);
            Ranked w = top.get(0);
            CommerceSplit split = CommerceSplit.of(t.listMinor(), t.discountBps(), w.askMinor(), t.feeMinor(), p.guaranteeFundBps());
            long collateral = Risk.requiredCollateral(split.a(), p.collateral().alpha(), p.collateral().gammaRev(),
                    t.partnerTier(), p.collateral().minMinor());
            award = new Award(w, split, collateral);
            // Draw the card price A from the winner's mandate budget (it was checked against the budget at bid time;
            // a concurrent award on the same mandate can still race, so never let it go negative).
            Long mandateId = bound == null ? null : bound.get(w.nodeRef());
            MandateState ms = mandateId == null ? null : mandates.get(mandateId);
            if (ms != null) ms.budgetLeftMinor().updateAndGet(left -> Math.max(0, left - split.a()));
        }
        EvidenceChain chain = chains.get(auctionId);
        if (chain != null) {
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("ranked", ranked);
            List<Object> ranking = new ArrayList<>();
            for (Ranked r : top) ranking.add(Map.of("nodeRef", r.nodeRef(), "askMinor", r.askMinor(),
                    "scoreMicros", Math.round(r.score() * 1_000_000)));          // canonical JSON is integers only
            auth.put("ranking", ranking);
            if (award != null) {
                auth.put("winnerNode", award.winner().nodeRef());
                auth.put("a", award.split().a());
                auth.put("yield", award.split().yield());
                auth.put("fee", award.split().fee());
                auth.put("amount", award.split().b());                          // B, what the buyer authorises
                auth.put("currency", "INR");
                auth.put("buyerSaves", award.split().buyerSaves());
                auth.put("collateralMinor", award.collateralMinor());
            }
            chain.append(EvidenceChain.Type.authorization, EvidenceChain.Signal.Deterministic, auth);
            chain.seal(OPERATOR, operatorKey.getPrivate(), Map.of(WITNESS, witnessKey.getPrivate()));
        }
        results.put(auctionId, new Result(auctionId, pack, ranked, List.copyOf(top), award));
        openAuctions.remove(auctionId);
        metrics.counter("difme_exchange_auctions_closed_total", "pack", pack, "matched", ranked > 0 ? "yes" : "no").increment();
    }

    @PreDestroy
    void stop() {
        runners.forEach(AgentRunner::close);
    }
}
