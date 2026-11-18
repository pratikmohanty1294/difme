package dev.difme.exchange.matching;

import static org.junit.jupiter.api.Assertions.*;

import dev.difme.exchange.kernel.BidBook;
import dev.difme.exchange.kernel.Weights;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {
    static final Weights W = new Weights(0.60, 0.25, 0.15);   // FR-01 pack weights
    static final long WINDOW = 50_000_000L;                    // 50 ms

    record Result(long auction, int ranked, int winnerNode, long winnerAsk, double winnerScore) {}

    static final class Clock implements NanoClock {
        long now;
        public long nanoTime() { return now; }
    }

    final Clock clock = new Clock();
    final List<Result> results = new ArrayList<>();
    final ManyToOneRingBuffer ring = MatchingIntake.newRing(64 * 1024);
    final MatchingIntake intake = new MatchingIntake(ring);
    final MatchingEngine engine = new MatchingEngine(W, 50_000, 16, 64, ring, this::collect, clock, 1000);

    void collect(long id, BidBook book, int ranked) {
        if (ranked == 0) { results.add(new Result(id, 0, -1, 0, 0)); return; }
        int w = book.atRank(0);
        results.add(new Result(id, ranked, book.node(w), book.ask(w), book.score(w)));
    }

    @Test
    void highestUniversalScoreWinsWhenTheWindowCloses() {
        assertTrue(intake.open(1, 100_000, WINDOW));
        intake.bid(1, 10, 92_000, 80, 20_000);   // cheap-ish, mid reputation, mid latency
        intake.bid(1, 11, 95_000, 98, 5_000);    // pricier, best reputation, fast
        intake.bid(1, 12, 90_000, 60, 45_000);   // cheapest, weak reputation, slow
        engine.doWork();
        assertTrue(results.isEmpty(), "must not close before the window ends");
        clock.now += WINDOW;
        engine.doWork();
        assertEquals(1, results.size());
        Result r = results.get(0);
        assertEquals(3, r.ranked());
        double s10 = BidBook.score(92_000, 100_000, 80, 20_000, 50_000, W);
        double s11 = BidBook.score(95_000, 100_000, 98, 5_000, 50_000, W);
        double s12 = BidBook.score(90_000, 100_000, 60, 45_000, 50_000, W);
        int expected = s10 >= s11 && s10 >= s12 ? 10 : (s11 >= s12 ? 11 : 12);
        assertEquals(expected, r.winnerNode());
        assertEquals(Math.max(s10, Math.max(s11, s12)), r.winnerScore(), 1e-12);
        assertEquals(0, engine.openAuctions());
    }

    @Test
    void askAboveTheBuyersMaximumIsExcluded() {
        intake.open(2, 100_000, WINDOW);
        intake.bid(2, 20, 120_000, 99, 1_000);
        intake.bid(2, 21, 99_000, 50, 10_000);
        clock.now += WINDOW;
        engine.doWork();
        engine.doWork();
        assertEquals(1, results.get(0).ranked());
        assertEquals(21, results.get(0).winnerNode());
    }

    @Test
    void concurrentAuctionsStayIsolatedAndSlotsAreReused() {
        for (int round = 0; round < 3; round++) {          // 3 x 16 auctions through 16 slots
            for (long a = 0; a < 16; a++) {
                long id = round * 100 + a;
                assertTrue(intake.open(id, 50_000, WINDOW));
                intake.bid(id, (int) a, 40_000 + a, 90, 1_000);
            }
            engine.doWork();
            assertEquals(16, engine.openAuctions());
            clock.now += WINDOW;
            engine.doWork();
            assertEquals(0, engine.openAuctions());
        }
        assertEquals(48, results.size());
        for (Result r : results) assertEquals(r.auction() % 100, r.winnerNode());
        assertEquals(0, engine.rejected());
    }

    @Test
    void lateAndUnknownBidsAreDroppedNotApplied() {
        intake.bid(99, 1, 10, 50, 10);                     // auction never opened
        intake.open(3, 1_000, WINDOW);
        clock.now += WINDOW;
        engine.doWork();
        engine.doWork();
        intake.bid(3, 2, 10, 50, 10);                      // after close
        engine.doWork();
        assertEquals(2, engine.dropped());
        assertEquals(0, results.get(0).ranked());
    }

    @Test
    void manyProducerThreadsLoseNoBids() throws Exception {
        int producers = 4, perProducer = 60;               // 4 auctions x 60 bids (book capacity 64)
        for (long a = 0; a < producers; a++) intake.open(a, 1_000_000, WINDOW);
        engine.doWork();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final long auction = p;
            threads.add(Thread.ofPlatform().start(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < perProducer; i++) {
                    while (!intake.bid(auction, i, 500_000 + i, 70, 1_000)) Thread.onSpinWait();
                }
            }));
        }
        go.countDown();
        for (Thread t : threads) t.join();
        engine.doWork();
        clock.now += WINDOW;
        engine.doWork();
        assertEquals(producers, results.size());
        for (Result r : results) {
            assertEquals(perProducer, r.ranked());
            assertEquals(0, r.winnerNode(), "lowest ask (i = 0) wins at equal reputation and latency");
        }
    }

    @Test
    void aFullRingIsBackpressureNotLoss() {
        ManyToOneRingBuffer tiny = MatchingIntake.newRing(1024);
        MatchingIntake in = new MatchingIntake(tiny);
        int accepted = 0;
        while (in.bid(1, 1, 1, 1, 1)) accepted++;
        assertTrue(accepted > 0 && accepted < 1024 / 8);
        assertFalse(in.open(2, 1, 1));
    }
}
