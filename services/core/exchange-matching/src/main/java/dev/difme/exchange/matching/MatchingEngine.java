package dev.difme.exchange.matching;

import dev.difme.exchange.kernel.BidBook;
import dev.difme.exchange.kernel.Weights;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * Single-writer sealed-bid auction engine (single-writer principle: only this agent's thread touches auction state,
 * so the hot path needs no locks or volatile writes). Auctions open with a window (PRD: 50 ms); bids stream in
 * through the intake ring; when a window closes the kernel's {@link BidBook} scores and ranks the bids (FR-17) and the
 * result goes to the {@link ResultSink} on this thread.
 *
 * <p>Memory: every BidBook and all auction state are preallocated (struct of arrays plus a primitive open-addressing
 * map), so steady-state operation allocates nothing. Run it under an Agrona {@code AgentRunner} with a backoff idle
 * strategy on small hosts (a pinned busy-spin core is a production option, not a Codespace one).
 */
public final class MatchingEngine implements Agent {
    /** Receives a closed auction; the book is only valid during the call (copy what you keep). */
    @FunctionalInterface
    public interface ResultSink {
        void onClosed(long auctionId, BidBook book, int ranked);
    }

    // Volatile: the operator can retune them while the engine runs; each auction close reads one consistent pair.
    private volatile Weights weights;
    private volatile long latencyMaxMicros;
    private final RingBuffer intake;
    private final ResultSink sink;
    private final NanoClock clock;
    private final int readLimit;

    // Auction slots (struct of arrays) and a free-slot stack.
    private final long[] auctionId, maxAcceptable, closeAt;
    private final boolean[] active;
    private final BidBook[] books;
    private final int[] free;
    private int freeTop;
    private int open;
    private final Long2LongHashMap slotOf;

    private long dropped, rejected;
    private final MessageHandler handler = this::onMessage;

    /** New scoring weights and latency cap, used from the next auction close on (operator settings). */
    public void retune(Weights w, long latencyMaxMicros) {
        if (w == null || latencyMaxMicros <= 0) throw new IllegalArgumentException("weights and a positive latency cap are required");
        this.weights = w;
        this.latencyMaxMicros = latencyMaxMicros;
    }

    public MatchingEngine(Weights weights, long latencyMaxMicros, int maxAuctions, int bidsPerAuction,
                          RingBuffer intake, ResultSink sink, NanoClock clock, int readLimit) {
        this.weights = weights;
        this.latencyMaxMicros = latencyMaxMicros;
        this.intake = intake;
        this.sink = sink;
        this.clock = clock;
        this.readLimit = readLimit;
        auctionId = new long[maxAuctions];
        maxAcceptable = new long[maxAuctions];
        closeAt = new long[maxAuctions];
        active = new boolean[maxAuctions];
        books = new BidBook[maxAuctions];
        free = new int[maxAuctions];
        for (int i = 0; i < maxAuctions; i++) {
            books[i] = new BidBook(bidsPerAuction);
            free[i] = maxAuctions - 1 - i;
        }
        freeTop = maxAuctions;
        slotOf = new Long2LongHashMap(maxAuctions * 2, 0.5f, -1L);
    }

    @Override
    public int doWork() {
        int work = intake.read(handler, readLimit);
        return work + closeExpired(clock.nanoTime());
    }

    @Override
    public String roleName() {
        return "matching-engine";
    }

    /** Messages for unknown/closed auctions (late or duplicate). */
    public long dropped() {
        return dropped;
    }

    /** Opens refused for lack of a free slot, plus bids refused by a full book. */
    public long rejected() {
        return rejected;
    }

    public int openAuctions() {
        return open;
    }

    private void onMessage(int type, DirectBuffer b, int index, int length) {
        if (type == Wire.MSG_BID) {
            long slot = slotOf.get(b.getLong(index + Wire.BID_AUCTION));
            if (slot < 0) { dropped++; return; }
            int added = books[(int) slot].add(b.getInt(index + Wire.BID_NODE), b.getLong(index + Wire.BID_ASK),
                    b.getDouble(index + Wire.BID_REPUTATION), b.getLong(index + Wire.BID_LATENCY));
            if (added < 0) rejected++;
        } else if (type == Wire.MSG_OPEN) {
            long id = b.getLong(index + Wire.OPEN_AUCTION);
            if (slotOf.get(id) >= 0) { dropped++; return; }      // duplicate open
            if (freeTop == 0) { rejected++; return; }
            int s = free[--freeTop];
            auctionId[s] = id;
            maxAcceptable[s] = b.getLong(index + Wire.OPEN_MAX);
            closeAt[s] = clock.nanoTime() + b.getLong(index + Wire.OPEN_WINDOW);
            books[s].reset();
            active[s] = true;
            slotOf.put(id, s);
            open++;
        }
    }

    private int closeExpired(long now) {
        if (open == 0) return 0;
        int closed = 0;
        for (int s = 0; s < books.length; s++) {
            long id = auctionId[s];
            if (!active[s] || now < closeAt[s]) continue;
            BidBook book = books[s];
            int ranked = book.size() == 0 ? 0 : book.rank(maxAcceptable[s], weights, latencyMaxMicros);
            active[s] = false;
            slotOf.remove(id);
            free[freeTop++] = s;
            open--;
            closed++;
            sink.onClosed(id, book, ranked);
        }
        return closed;
    }
}
