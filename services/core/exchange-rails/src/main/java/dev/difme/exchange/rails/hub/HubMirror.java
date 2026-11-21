package dev.difme.exchange.rails.hub;

import dev.difme.exchange.rails.PaymentHub;
import dev.difme.exchange.rails.sim.SimEscrow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Books the escrow's money movements on the core banking system, through the hub, in the background: the exchange's
 * escrow ledger decides (conservation, idempotency), the CBS records. A hold is money arriving into the escrow account;
 * a release moves escrow → payee; a refund moves escrow → payer. Ids are derived from the hold and operation ids, so a
 * movement sent twice is booked once; failed sends are retried with backoff until they go through. Not yet durable:
 * movements still queued when the process stops are lost (logged), and the CBS then lags the exchange's ledger.
 */
public final class HubMirror implements SimEscrow.Mirror, AutoCloseable {
    public static final String ESCROW = "escrow";

    private record Move(String id, String from, String to, long amountMinor, String note) {}

    private final PaymentHub hub;
    private final LinkedBlockingQueue<Move> queue = new LinkedBlockingQueue<>();
    private final Consumer<String> log;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile long booked, refusedCount;
    private volatile boolean inFlight;

    public HubMirror(PaymentHub hub, Consumer<String> log) {
        this.hub = hub;
        this.log = log;
        this.worker = Thread.ofPlatform().name("escrow-cbs-mirror").daemon().start(this::loop);
    }

    @Override public void hold(String holdId, String payer, long amountMinor) {
        queue.add(new Move("hold-" + holdId, null, ESCROW, amountMinor, "escrow hold " + holdId + " from " + payer));
    }

    @Override public void release(String holdId, String releaseId, String payee, long amountMinor) {
        queue.add(new Move("rel-" + holdId + "-" + releaseId, ESCROW, payee, amountMinor, "escrow release " + holdId + "/" + releaseId));
    }

    @Override public void refund(String holdId, String refundId, String payer, long amountMinor) {
        queue.add(new Move("ref-" + holdId + "-" + refundId, ESCROW, payer, amountMinor, "escrow refund " + holdId + "/" + refundId));
    }

    /** Movements not yet booked on the CBS. */
    public int pending() { return queue.size(); }

    public long booked() { return booked; }

    /** Movements the CBS refused for good (e.g. insufficient funds): the books disagree and an operator must look. */
    public long refused() { return refusedCount; }

    private void loop() {
        long backoff = 200;
        while (running) {
            Move m;
            try {
                m = queue.take();
                inFlight = true;
            } catch (InterruptedException e) {
                return;
            }
            while (running) {
                try {
                    PaymentHub.Outcome o = m.from() == null ? hub.deposit(m.id(), m.to(), m.amountMinor())
                            : hub.transfer(m.id(), m.from(), m.to(), m.amountMinor(), m.note());
                    if ("DONE".equals(o.status())) booked++;
                    else { refusedCount++; log.accept("CBS refused " + m.id() + ": " + o.status() + " " + o.detail()); }
                    backoff = 200;
                    inFlight = false;
                    break;
                } catch (RuntimeException e) {
                    log.accept("CBS booking " + m.id() + " failed, retrying in " + backoff + " ms: " + e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
        }
    }

    /** Waits up to timeoutMs for the queue to drain (tests, shutdown). */
    public boolean drain(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (queue.isEmpty() && !inFlight) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return queue.isEmpty() && !inFlight;
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        if (!queue.isEmpty()) log.accept(queue.size() + " escrow movements not booked on the CBS at shutdown");
    }
}
