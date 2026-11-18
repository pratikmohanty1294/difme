package dev.difme.exchange.svc;

import dev.difme.exchange.kernel.BidBook;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public record Result(long auctionId, String pack, int ranked, List<Ranked> top) {}

    @ConfigProperty(name = "difme.exchange.packs") List<String> packIds;
    @ConfigProperty(name = "difme.exchange.max-auctions") int maxAuctions;
    @ConfigProperty(name = "difme.exchange.bids-per-auction") int bidsPerAuction;
    @ConfigProperty(name = "difme.exchange.ring-bytes") int ringBytes;
    @ConfigProperty(name = "difme.exchange.results-kept") int resultsKept;
    @Inject MeterRegistry metrics;

    private final Map<String, VerticalPack> packs = new ConcurrentHashMap<>();
    private final Map<String, MatchingIntake> intakes = new ConcurrentHashMap<>();
    private final List<AgentRunner> runners = new ArrayList<>();
    private final Map<Long, String> openAuctions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() << 16);
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
        for (String id : packIds) {
            VerticalPack pack;
            try {
                pack = VerticalPack.fromClasspath(id, Thread.currentThread().getContextClassLoader());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot load vertical pack " + id, e);
            }
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
    public long open(String pack, long maxAcceptableMinor) {
        long id = ids.incrementAndGet();
        VerticalPack p = packs.get(pack);
        openAuctions.put(id, pack);
        if (!intakes.get(pack).open(id, maxAcceptableMinor, p.windowNanos())) {
            openAuctions.remove(id);
            return -1;
        }
        return id;
    }

    /** Publishes a bid; false when the auction is unknown/closed or the ring is full. */
    public boolean bid(long auctionId, int nodeRef, long askMinor, double reputation, long latencyMicros) {
        String pack = openAuctions.get(auctionId);
        return pack != null && intakes.get(pack).bid(auctionId, nodeRef, askMinor, reputation, latencyMicros);
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
        results.put(auctionId, new Result(auctionId, pack, ranked, List.copyOf(top)));
        openAuctions.remove(auctionId);
        metrics.counter("difme_exchange_auctions_closed_total", "pack", pack, "matched", ranked > 0 ? "yes" : "no").increment();
    }

    @PreDestroy
    void stop() {
        runners.forEach(AgentRunner::close);
    }
}
