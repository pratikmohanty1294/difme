package dev.difme.exchange.matching;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * Producer side: any number of request threads publish auction opens and bids into one off-heap ring. Uses the
 * ring's claim/commit API, so each record is written in place in shared memory (zero copy, no locks, no garbage).
 * A {@code false} return is backpressure: the ring is full and the caller should shed or retry.
 */
public final class MatchingIntake {
    private final RingBuffer ring;

    public MatchingIntake(RingBuffer ring) {
        this.ring = ring;
    }

    /** A many-to-one ring of {@code capacityBytes} (power of two) in direct (off-heap) memory. */
    public static ManyToOneRingBuffer newRing(int capacityBytes) {
        return new ManyToOneRingBuffer(new UnsafeBuffer(
                ByteBuffer.allocateDirect(capacityBytes + RingBufferDescriptor.TRAILER_LENGTH)));
    }

    public boolean open(long auctionId, long maxAcceptableMinor, long windowNanos) {
        int index = ring.tryClaim(Wire.MSG_OPEN, Wire.OPEN_LENGTH);
        if (index <= 0) return false;
        var b = ring.buffer();
        b.putLong(index + Wire.OPEN_AUCTION, auctionId);
        b.putLong(index + Wire.OPEN_MAX, maxAcceptableMinor);
        b.putLong(index + Wire.OPEN_WINDOW, windowNanos);
        ring.commit(index);
        return true;
    }

    public boolean bid(long auctionId, int nodeRef, long askMinor, double reputation, long latencyMicros) {
        int index = ring.tryClaim(Wire.MSG_BID, Wire.BID_LENGTH);
        if (index <= 0) return false;
        var b = ring.buffer();
        b.putLong(index + Wire.BID_AUCTION, auctionId);
        b.putLong(index + Wire.BID_ASK, askMinor);
        b.putLong(index + Wire.BID_LATENCY, latencyMicros);
        b.putDouble(index + Wire.BID_REPUTATION, reputation);
        b.putInt(index + Wire.BID_NODE, nodeRef);
        ring.commit(index);
        return true;
    }
}
