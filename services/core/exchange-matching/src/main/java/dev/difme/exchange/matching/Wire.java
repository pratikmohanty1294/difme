package dev.difme.exchange.matching;

/** Fixed-size record layouts on the intake ring (little-endian, native Agrona encoding). */
final class Wire {
    static final int MSG_OPEN = 1;
    static final int MSG_BID = 2;

    // OPEN: auctionId, maxAcceptableMinor, windowNanos
    static final int OPEN_AUCTION = 0, OPEN_MAX = 8, OPEN_WINDOW = 16, OPEN_LENGTH = 24;
    // BID: auctionId, askMinor, latencyMicros, reputation, nodeRef
    static final int BID_AUCTION = 0, BID_ASK = 8, BID_LATENCY = 16, BID_REPUTATION = 24, BID_NODE = 32, BID_LENGTH = 36;

    private Wire() {}
}
