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
    public record Terms(long listMinor, int discountBps, long feeMinor) {}

    /**
     * The award: the winner's split under the conservation identity (A, Y, F, B, buyer saving; FR-01 v4.1) and the
     * collateral the winner must lock before execution (FR-14, D_req = max(D_min, V·α·γ·φ)). V is A (what the partner
     * pays the merchant); φ comes from the winner's tier, which the exchange derives from its own history (decided
     * 2026-11-20): COLD_START until enough verified deliveries, then ESTABLISHED.
     */
    public record Award(Ranked winner, CommerceSplit split, long collateralMinor, Risk.Tier tier) {}

    /** What the exchange (not the bidder) knows about an open auction; the facts bid-time rules are checked against. */
    public record Context(long cardPriceMinor, long yieldRoomMinor) {}

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
    /** Verified deliveries per partner ("pack:nodeRef"): accepted proofs of delivery, the basis of the tier. */
    private final Map<String, Integer> deliveries = new ConcurrentHashMap<>();
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

    // Evidence signing (B-chain per auction, ADR-020), decided 2026-11-20: the operator key lives in OpenBao transit
    // (non-exportable) and the witness is an independent notary service. Dev and tests: generated in-process keys.
    // Public keys are published at GET /v1/exchange/keys.
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(Exchange.class);
    static final String OPERATOR = "exchange-svc", WITNESS = "exchange-witness";
    @ConfigProperty(name = "difme.evidence.operator", defaultValue = "local") String operatorMode;
    @ConfigProperty(name = "difme.evidence.openbao-addr", defaultValue = "http://openbao.security:8200") String openbaoAddr;
    /** A static token file (tests, break-glass); when it doesn't exist the exchange logs in with Kubernetes auth. */
    @ConfigProperty(name = "difme.evidence.openbao-token-file", defaultValue = "/var/run/secrets/openbao/token") String openbaoTokenFile;
    @ConfigProperty(name = "difme.evidence.openbao-role", defaultValue = "exchange-evidence") String openbaoRole;
    @ConfigProperty(name = "difme.evidence.openbao-jwt-file", defaultValue = "/var/run/secrets/openbao-sa/token") String openbaoJwtFile;
    @ConfigProperty(name = "difme.evidence.transit-mount", defaultValue = "transit") String transitMount;
    @ConfigProperty(name = "difme.evidence.transit-key", defaultValue = "evidence-operator") String transitKey;
    @ConfigProperty(name = "difme.evidence.notary-url") java.util.Optional<String> notaryUrl;
    private EvidenceSigners.Published operator, witness;
    /** Remote signers take milliseconds, so seals are made off the matching thread; local keys seal inline. */
    private boolean sealAsync;
    private final java.util.concurrent.ExecutorService sealer = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, java.util.concurrent.Future<?>> pendingSeals = new ConcurrentHashMap<>();
    // Ids stay below 2^53 so JSON clients (JavaScript, the PWAs) read them exactly; millis x 1000 also keeps them
    // increasing across restarts. (millis << 16 exceeded 2^53 and the bids went to a rounded id; found by the test.)
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000L);
    private Map<Long, Result> results;

    // Durable state (StateStore): off unless a JDBC URL is configured (tests and local runs keep everything in memory).
    @ConfigProperty(name = "difme.exchange.store.url") java.util.Optional<String> storeUrl;
    @ConfigProperty(name = "difme.exchange.store.user", defaultValue = "difme") String storeUser;
    @ConfigProperty(name = "difme.exchange.store.password") java.util.Optional<String> storePassword;   // dev YugabyteDB: none
    @ConfigProperty(name = "difme.exchange.store.flush-ms", defaultValue = "50") long storeFlushMs;
    /** Dev only: keep the in-process evidence keys here so chains sealed before a restart still verify after it. */
    @ConfigProperty(name = "difme.evidence.local-keys-dir") java.util.Optional<String> localKeysDir;
    private StateStore store = StateStore.none();

    record MandateRecord(long id, String pack, ExchangeResource.Mandate terms, long registeredAtMillis, long budgetLeftMinor,
                         int nodeRef, int awards, long yieldEarnedMinor, MandateStatus status) {}
    record ChainRecord(long auctionId, String id, EvidenceChain.Kind kind, List<EvidenceChain.Artifact> artifacts, EvidenceChain.Seal seal) {}
    record KeyRecord(String name, String x509) {}
    record ScoreRecord(String key, double value) {}
    record CountRecord(String key, int count) {}

    @PostConstruct
    void start() {
        try {
            operator = "transit".equals(operatorMode)
                    ? EvidenceSigners.transit(OPERATOR, openbaoAddr, java.nio.file.Files.exists(java.nio.file.Path.of(openbaoTokenFile))
                            ? () -> java.nio.file.Files.readString(java.nio.file.Path.of(openbaoTokenFile)).strip()
                            : EvidenceSigners.kubernetesLogin(openbaoAddr, "kubernetes", openbaoRole, java.nio.file.Path.of(openbaoJwtFile)),
                            transitMount, transitKey)
                    : localSigner(OPERATOR);
            witness = notaryUrl.filter(u -> !u.isBlank()).isPresent()
                    ? EvidenceSigners.notary(notaryUrl.get()) : localSigner(WITNESS);
        } catch (IOException | GeneralSecurityException e) {
            // Fail the start: sealing evidence with a stand-in key would make chains that verify against the wrong party.
            throw new IllegalStateException("evidence signers unavailable (" + operatorMode + ", notary "
                    + notaryUrl.orElse("local") + "): " + e.getMessage(), e);
        }
        if (operator.name().equals(witness.name())) throw new IllegalStateException("the witness must not be the operator");
        sealAsync = "transit".equals(operatorMode) || notaryUrl.filter(u -> !u.isBlank()).isPresent();
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
        returns = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Return> e) {
                return size() > keep;
            }
        });
        if (storeUrl.filter(u -> !u.isBlank()).isPresent()) {
            try {
                store = new StateStore(storeUrl.get(), storeUser, storePassword.orElse(""), storeFlushMs);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("exchange state store unavailable: " + e.getMessage(), e);
            }
            restore();
            metrics.gauge("difme_exchange_state_pending", store, StateStore::pending);
        }
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

    /** Rebuilds the durable state at start (before the matching engines run). */
    private void restore() {
        var json = StateStore.JSON;
        try {
            for (var n : store.load("partykey")) {
                KeyRecord k = json.treeToValue(n, KeyRecord.class);
                partyKeys.put(k.name(), java.security.KeyFactory.getInstance("Ed25519")
                        .generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(k.x509()))));
            }
            for (var n : store.load("reputation")) { ScoreRecord r = json.treeToValue(n, ScoreRecord.class); reputation.put(r.key(), r.value()); }
            for (var n : store.load("deliveries")) { CountRecord c = json.treeToValue(n, CountRecord.class); deliveries.put(c.key(), c.count()); }
            long maxMandate = 0, maxAuction = 0;
            for (var n : store.load("mandate")) {
                MandateRecord m = json.treeToValue(n, MandateRecord.class);
                mandates.put(m.id(), new MandateState(m.pack(), m.terms(), m.registeredAtMillis(), new AtomicLong(m.budgetLeftMinor()),
                        m.nodeRef(), new java.util.concurrent.atomic.AtomicInteger(m.awards()), new AtomicLong(m.yieldEarnedMinor()),
                        new java.util.concurrent.atomic.AtomicReference<>(m.status())));
                partnerNodes.put(m.terms().partnerRef(), m.nodeRef());
                nextPartnerNode.accumulateAndGet(m.nodeRef(), Math::max);
                maxMandate = Math.max(maxMandate, m.id());
            }
            mandateIds.accumulateAndGet(maxMandate, Math::max);
            for (var n : store.load("result")) { Result r = json.treeToValue(n, Result.class); results.put(r.auctionId(), r); maxAuction = Math.max(maxAuction, r.auctionId()); }
            for (var n : store.load("chain")) {
                ChainRecord c = json.treeToValue(n, ChainRecord.class);
                chains.put(c.auctionId(), EvidenceChain.restore(c.id(), c.kind(), c.artifacts(), c.seal()));
            }
            for (var n : store.load("execution")) { Execution e = json.treeToValue(n, Execution.class); executions.put(e.auctionId(), e); }
            for (var n : store.load("fulfilment")) { Fulfilment f = json.treeToValue(n, Fulfilment.class); fulfilments.put(f.auctionId(), f); }
            for (var n : store.load("return")) { Return r = json.treeToValue(n, Return.class); returns.put(r.auctionId(), r); }
            for (var n : store.load("escrow")) rails.escrow.restore(json.treeToValue(n, EscrowPort.Hold.class));
            ids.accumulateAndGet(maxAuction, Math::max);
            LOG.infof("exchange state restored: %d mandates, %d results, %d executions, %d deliveries, %d returns",
                    mandates.size(), results.size(), executions.size(), fulfilments.size(), returns.size());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | GeneralSecurityException e) {
            throw new IllegalStateException("stored exchange state is unreadable: " + e.getMessage(), e);
        }
    }

    private void saveMandate(long id) {
        MandateState m = mandates.get(id);
        if (m != null) store.put("mandate", id, new MandateRecord(id, m.pack(), m.terms(), m.registeredAtMillis(),
                m.budgetLeftMinor().get(), m.nodeRef(), m.awards().get(), m.yieldEarnedMinor().get(), m.status().get()));
    }

    /** Saves everything the exchange holds for one auction (coalesced: only the newest version is written). */
    private void saveAuction(long auctionId) {
        if (!store.enabled()) return;
        Result r = results.get(auctionId);
        if (r != null) store.put("result", auctionId, r);
        EvidenceChain c = chains.get(auctionId);
        if (c != null) store.put("chain", auctionId, new ChainRecord(auctionId, c.id(), c.kind(), c.artifacts(), c.seal()));
        Execution e = executions.get(auctionId);
        if (e != null) store.put("execution", auctionId, e);
        Fulfilment f = fulfilments.get(auctionId);
        if (f != null) store.put("fulfilment", auctionId, f);
        Return ret = returns.get(auctionId);
        if (ret != null) store.put("return", auctionId, ret);
        try {
            store.put("escrow", "auction-" + auctionId, rails.escrow.get("auction-" + auctionId));
        } catch (IllegalArgumentException noHold) {
            // no escrow hold yet (not executed)
        }
    }

    /** Persists a mandate change made outside the exchange's own methods (status changes via the API). */
    public void mandateChanged(long id) {
        saveMandate(id);
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
        contexts.put(id, new Context(cardPrice, maxAcceptableMinor));
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
    /** ACTIVE bids; PAUSED stops new bids until resumed; REVOKED (the partner terminated it) is final. */
    public enum MandateStatus { ACTIVE, PAUSED, REVOKED }

    public record MandateState(String pack, ExchangeResource.Mandate terms, long registeredAtMillis, AtomicLong budgetLeftMinor,
                               int nodeRef, java.util.concurrent.atomic.AtomicInteger awards, AtomicLong yieldEarnedMinor,
                               java.util.concurrent.atomic.AtomicReference<MandateStatus> status) {
        public int ageDays() {
            return (int) ((System.currentTimeMillis() - registeredAtMillis) / 86_400_000L);
        }

        /** Still a standing order: active, within its validity and with budget left. */
        public boolean standing() {
            return status.get() == MandateStatus.ACTIVE && ageDays() < terms.validityDays() && budgetLeftMinor.get() > 0;
        }

        /** Pause, resume or revoke. Revoked is final; returns false when the change isn't allowed. */
        public boolean setStatus(MandateStatus next) {
            MandateStatus cur;
            do {
                cur = status.get();
                if (cur == MandateStatus.REVOKED && next != MandateStatus.REVOKED) return false;
            } while (!status.compareAndSet(cur, next));
            return true;
        }
    }

    /** Stable bidder node per partner (standing mandates bid as their partner's node; above the range agents use). */
    private final Map<String, Integer> partnerNodes = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextPartnerNode = new java.util.concurrent.atomic.AtomicInteger(1_000_000);

    public int nodeRefOf(String partnerRef) {
        return partnerNodes.computeIfAbsent(partnerRef, k -> nextPartnerNode.incrementAndGet());
    }

    /** Ids of the pack's mandates that are standing orders right now. */
    public List<Long> standingMandates(String pack) {
        List<Long> out = new ArrayList<>();
        mandates.forEach((id, m) -> { if (m.pack().equals(pack) && m.standing()) out.add(id); });
        return out;
    }

    private final Map<Long, MandateState> mandates = new ConcurrentHashMap<>();
    private final AtomicLong mandateIds = new AtomicLong(System.currentTimeMillis() * 1000L);
    /** auction id -> (bidder nodeRef -> mandate id), so the award can draw down the winner's budget. */
    private final Map<Long, Map<Integer, Long>> bidMandates = new ConcurrentHashMap<>();

    public long registerMandate(String pack, ExchangeResource.Mandate m) {
        long id = mandateIds.incrementAndGet();
        mandates.put(id, new MandateState(pack, m, System.currentTimeMillis(), new AtomicLong(m.budgetTotalMinor()),
                nodeRefOf(m.partnerRef()), new java.util.concurrent.atomic.AtomicInteger(), new AtomicLong(),
                new java.util.concurrent.atomic.AtomicReference<>(MandateStatus.ACTIVE)));
        saveMandate(id);
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

    /** FR-14 collateral this partner would have to lock for this auction, at the partner's own tier; -1 if not open. */
    public long depositNeeded(long auctionId, int nodeRef) {
        Context c = contexts.get(auctionId);
        String pack = openAuctions.get(auctionId);
        if (c == null || pack == null) return -1;
        VerticalPack p = packs.get(pack);
        return Risk.requiredCollateral(c.cardPriceMinor(), p.collateral().alpha(), p.collateral().gammaRev(),
                tierOf(pack, nodeRef), p.collateral().minMinor());
    }

    /** The partner's tier from the exchange's own records, never from the intent or the bid. */
    public Risk.Tier tierOf(String pack, long nodeRef) {
        VerticalPack p = packs.get(pack);
        String k = pack + ":" + nodeRef;
        return tierFor(deliveries.getOrDefault(k, 0), reputation.get(k), p == null ? Integer.MAX_VALUE : p.establishedAfterDeliveries());
    }

    /**
     * COLD_START (φ 2.0) until the partner has {@code threshold} verified deliveries and a tracked reputation no lower
     * than the cold-start prior; then ESTABLISHED (φ 1.0). ENTERPRISE (φ 0.6) needs a signed enterprise agreement and is
     * never reached from history alone.
     */
    static Risk.Tier tierFor(int verifiedDeliveries, Double trackedReputation, int threshold) {
        return verifiedDeliveries >= threshold && trackedReputation != null && trackedReputation >= Reputation.PRIOR_UNVERIFIED
                ? Risk.Tier.ESTABLISHED : Risk.Tier.COLD_START;
    }

    public int verifiedDeliveries(String pack, int nodeRef) {
        return deliveries.getOrDefault(pack + ":" + nodeRef, 0);
    }

    public String packOf(long auctionId) {
        return openAuctions.get(auctionId);
    }

    public EvidenceChain evidence(long auctionId) {
        return chains.get(auctionId);
    }

    public Map<String, PublicKey> publicKeys() {
        return Map.of(operator.name(), operator.publicKey(), witness.name(), witness.publicKey());
    }

    /** Waits briefly for a seal still being made by a remote signer, so a reader right after the award sees it. */
    public void awaitSeal(long auctionId, long timeoutMs) {
        java.util.concurrent.Future<?> f = pendingSeals.get(auctionId);
        if (f == null) return;
        try {
            f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // unsealed: the verdict reports it
        }
    }

    /**
     * Dev keys: fresh per start, or kept as PKCS#8/X.509 files in {@code difme.evidence.local-keys-dir} when set, so
     * stored chains still verify after a restart. Production signs with OpenBao transit and the notary instead.
     */
    private KeyPair localKeys(String name) throws GeneralSecurityException, IOException {
        if (localKeysDir.filter(d -> !d.isBlank()).isEmpty()) return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        java.nio.file.Path dir = java.nio.file.Path.of(localKeysDir.get());
        java.nio.file.Path priv = dir.resolve(name + ".key"), pub = dir.resolve(name + ".pub");
        var kf = java.security.KeyFactory.getInstance("Ed25519");
        if (java.nio.file.Files.exists(priv) && java.nio.file.Files.exists(pub)) {
            return new KeyPair(kf.generatePublic(new java.security.spec.X509EncodedKeySpec(java.nio.file.Files.readAllBytes(pub))),
                    kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.nio.file.Files.readAllBytes(priv))));
        }
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(pub, kp.getPublic().getEncoded());
        java.nio.file.Files.write(priv, kp.getPrivate().getEncoded());
        try {
            java.nio.file.Files.setPosixFilePermissions(priv, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        return kp;
    }

    private EvidenceSigners.Published localSigner(String name) throws GeneralSecurityException, IOException {
        KeyPair kp = localKeys(name);
        EvidenceChain.Signer s = EvidenceChain.local(name, kp.getPrivate());
        return new EvidenceSigners.Published() {
            public String name() { return name; }

            public PublicKey publicKey() { return kp.getPublic(); }

            public byte[] sign(byte[] message) throws Exception { return s.sign(message); }
        };
    }

    private void seal(String pack, long auctionId, EvidenceChain chain) {
        try {
            chain.seal(operator, List.of(witness));
            saveAuction(auctionId);
        } catch (RuntimeException e) {
            metrics.counter("difme_exchange_seal_failures_total", "pack", pack).increment();
            LOG.warnf("evidence chain %s left unsealed: %s", chain.id(), e.getMessage());
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
            saveAuction(auctionId);
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
        String key = pack + ":" + nodeRef;
        store.put("reputation", key, new ScoreRecord(key, reputation.get(key)));
    }

    public void registerPartyKey(String name, PublicKey key) {
        partyKeys.put(name, key);
        store.put("partykey", name, new KeyRecord(name, java.util.Base64.getEncoder().encodeToString(key.getEncoded())));
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
            int delivered = deliveries.merge(r.pack() + ":" + e.boughtBy(), 1, Integer::sum);
            store.put("deliveries", r.pack() + ":" + e.boughtBy(), new CountRecord(r.pack() + ":" + e.boughtBy(), delivered));
            Fulfilment ok = new Fulfilment(auctionId, true, List.of(), hold, yieldAt);
            fulfilments.put(auctionId, ok);
            saveAuction(auctionId);
            metrics.counter("difme_exchange_fulfilments_total", "pack", r.pack()).increment();
            return ok;
        }
    }

    /**
     * A buyer's return inside the pack's return window (after an accepted proof of delivery, before the yield is
     * released). Two legs, as in FR-01: the merchant refunds A·r to the buying partner's card, which the exchange claws
     * back; the buyer gets B·r at once, made of that A·r, the frozen yield Y·r (refunded from escrow) and the fee F·r
     * (reversed). The partner is made whole and loses only the yield on what came back.
     */
    public record Return(long auctionId, int fractionBps, long buyerRefundMinor, long merchantRefundMinor, long partnerClawbackMinor,
                         long yieldVoidedMinor, long feeReversedMinor, EscrowPort.Hold escrow) {}

    private Map<Long, Return> returns;

    public Return returnOrder(long auctionId, int fractionBps, long nowMs) {
        if (fractionBps <= 0 || fractionBps > 10_000) throw new IllegalArgumentException("fractionBps must be 1..10000");
        Fulfilment f = fulfilments.get(auctionId);
        Result r = results.get(auctionId);
        Execution e = executions.get(auctionId);
        if (f == null || r == null || e == null) return null;
        synchronized (returns) {
            if (returns.containsKey(auctionId)) throw new IllegalStateException("this order already has a return");
            if (nowMs >= f.yieldReleaseAtMs()) throw new IllegalStateException("the return window has closed");
            CommerceSplit s = r.award().split();
            long merchant = Math.round(s.a() * (fractionBps / 10_000.0));
            long yieldBack = Math.round(s.yield() * (fractionBps / 10_000.0));
            long feeBack = Math.round(s.fee() * (fractionBps / 10_000.0));
            String holdId = "auction-" + auctionId;
            EscrowPort.Hold hold = yieldBack > 0 ? rails.escrow.refund(holdId, "return", yieldBack) : rails.escrow.get(holdId);
            Return ret = new Return(auctionId, fractionBps, merchant + yieldBack + feeBack, merchant, merchant, yieldBack, feeBack, hold);
            EvidenceChain chain = chains.get(auctionId);
            if (chain != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("fractionBps", fractionBps);
                p.put("buyerRefund", ret.buyerRefundMinor());
                p.put("merchantRefundToPartner", merchant);
                p.put("partnerClawback", merchant);
                p.put("yieldVoided", yieldBack);
                p.put("feeReversed", feeBack);
                p.put("currency", "INR");
                chain.append(EvidenceChain.Type.refund, EvidenceChain.Signal.Asserted, p);
            }
            returns.put(auctionId, ret);
            saveAuction(auctionId);
            metrics.counter("difme_exchange_returns_total", "pack", r.pack()).increment();
            return ret;
        }
    }

    public Return returned(long auctionId) {
        return returns.get(auctionId);
    }

    /** Releases the yield Y once the return window has passed. Null when there is no accepted fulfilment. */
    public EscrowPort.Hold settle(long auctionId, long nowMs) {
        Fulfilment f = fulfilments.get(auctionId);
        Result r = results.get(auctionId);
        Execution e = executions.get(auctionId);
        if (f == null || r == null || e == null) return null;
        if (nowMs < f.yieldReleaseAtMs()) throw new IllegalStateException("return window open for another " + (f.yieldReleaseAtMs() - nowMs) + " ms");
        Return ret = returns.get(auctionId);
        long y = r.award().split().yield() - (ret == null ? 0 : ret.yieldVoidedMinor());   // only the yield on what was kept
        String holdId = "auction-" + auctionId;
        EscrowPort.Hold h = y > 0 ? rails.escrow.release(holdId, "yield", "partner-" + e.boughtBy(), y) : rails.escrow.get(holdId);
        saveAuction(auctionId);
        return h;
    }

    public Result result(long auctionId) {
        return results.get(auctionId);
    }

    public Execution execution(long auctionId) {
        return executions.get(auctionId);
    }

    private final SimParties simParties = new SimParties(this);

    /** The simulated merchant and carrier (dev and verification clusters only; see ExchangeResource.simDelivery). */
    SimParties simParties() {
        return simParties;
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
            Risk.Tier tier = tierOf(pack, w.nodeRef());
            long collateral = Risk.requiredCollateral(split.a(), p.collateral().alpha(), p.collateral().gammaRev(),
                    tier, p.collateral().minMinor());
            award = new Award(w, split, collateral, tier);
            // Draw the card price A from the winner's mandate budget (it was checked against the budget at bid time;
            // a concurrent award on the same mandate can still race, so never let it go negative).
            Long mandateId = bound == null ? null : bound.get(w.nodeRef());
            MandateState ms = mandateId == null ? null : mandates.get(mandateId);
            if (ms != null) {
                ms.budgetLeftMinor().updateAndGet(left -> Math.max(0, left - split.a()));
                ms.awards().incrementAndGet();
                ms.yieldEarnedMinor().addAndGet(split.yield());
                saveMandate(mandateId);
            }
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
                auth.put("winnerTier", award.tier().name());
            }
            chain.append(EvidenceChain.Type.authorization, EvidenceChain.Signal.Deterministic, auth);
            if (sealAsync) {
                var f = java.util.concurrent.CompletableFuture.runAsync(() -> seal(pack, auctionId, chain), sealer);
                pendingSeals.put(auctionId, f);
                f.whenComplete((v, e) -> pendingSeals.remove(auctionId));      // runs at once if already done
            } else {
                seal(pack, auctionId, chain);
            }
        }
        results.put(auctionId, new Result(auctionId, pack, ranked, List.copyOf(top), award));
        openAuctions.remove(auctionId);
        saveAuction(auctionId);
        metrics.counter("difme_exchange_auctions_closed_total", "pack", pack, "matched", ranked > 0 ? "yes" : "no").increment();
    }

    @PreDestroy
    void stop() {
        runners.forEach(AgentRunner::close);
        store.close();   // flushes what's pending
    }
}
