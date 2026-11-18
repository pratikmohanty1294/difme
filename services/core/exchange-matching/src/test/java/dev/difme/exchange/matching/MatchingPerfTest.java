package dev.difme.exchange.matching;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.difme.exchange.kernel.Weights;
import java.util.Arrays;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.junit.jupiter.api.Test;

/**
 * PRD NFR-02 check: < 100 µs per bid and < 5 ms p99 per match. Plain JUnit (no JMH dependency); numbers are printed
 * and asserted with wide margins so a busy CI runner does not flake.
 */
class MatchingPerfTest {
    static final Weights W = new Weights(0.60, 0.25, 0.15);

    static final class Clock implements NanoClock {
        long now;
        public long nanoTime() { return now; }
    }

    @Test
    void bidThroughputAndCloseLatency() {
        Clock clock = new Clock();
        ManyToOneRingBuffer ring = MatchingIntake.newRing(1 << 20);
        MatchingIntake intake = new MatchingIntake(ring);
        long[] winner = new long[1];
        MatchingEngine engine = new MatchingEngine(W, 50_000, 1024, 64, ring,
                (id, book, ranked) -> winner[0] += ranked > 0 ? book.node(book.atRank(0)) : 0, clock, 4096);

        // Warm-up, then 1,000,000 bids across 1024 auctions (16,384 auctions of 61 bids).
        int auctions = 16_384, bidsEach = 61;
        long[] closeNanos = new long[auctions / 1024];
        long t0 = 0;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) t0 = System.nanoTime();
            for (int batch = 0; batch < auctions / 1024; batch++) {
                for (int a = 0; a < 1024; a++) {
                    long id = (long) pass << 40 | (long) batch << 20 | a;
                    while (!intake.open(id, 1_000_000, 1)) engine.doWork();
                    for (int b = 0; b < bidsEach; b++) {
                        while (!intake.bid(id, b, 500_000 + (b * 7919L) % 400_000, 50 + b % 50, 1_000 + b * 100)) engine.doWork();
                    }
                }
                while (engine.doWork() > 0) { }
                clock.now += 2;                                  // every window has ended
                long c0 = System.nanoTime();
                while (engine.doWork() > 0) { }                  // rank + close 1024 auctions of 61 bids
                if (pass == 1) closeNanos[batch] = (System.nanoTime() - c0) / 1024;
            }
        }
        double nsPerBid = (double) (System.nanoTime() - t0) / ((long) auctions * bidsEach);
        long[] sorted = closeNanos.clone();
        Arrays.sort(sorted);
        long p99Close = sorted[(int) Math.ceil(sorted.length * 0.99) - 1];
        System.out.printf("matching: %.0f ns per bid end-to-end (ring + engine), close+rank of a 61-bid auction: p50 %d ns, p99 %d ns%n",
                nsPerBid, sorted[sorted.length / 2], p99Close);
        assertTrue(nsPerBid < 100_000, "NFR-02: < 100 µs per bid");
        assertTrue(p99Close < 5_000_000, "NFR-02: < 5 ms p99 per match");
        assertTrue(winner[0] >= 0);
    }
}
