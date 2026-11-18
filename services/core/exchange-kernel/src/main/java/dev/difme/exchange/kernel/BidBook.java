package dev.difme.exchange.kernel;

/**
 * One SBBA batch (a single 50 ms window for one intent): sealed bids in, ranked winner plus hot backups out.
 *
 * <p>Universal score (PRD FR-17): {@code Score_i = w1 · U_i + w2 · R_i/100 − w3 · min(1, L_i / Lmax)}, where
 * {@code U_i = (maxAcceptable − ask_i) / maxAcceptable}. The vertical pack chooses what "ask" and "maxAcceptable"
 * mean. Buy It For Me uses the bidder's yield ask Y against the discount pool d·P, so a lower ask scores higher.
 *
 * <p>Ranking: highest score wins. Ties go to the lower ask, then the earlier bid. Asks above {@code maxAcceptable}
 * are rejected: they would break the vertical's price guarantee, e.g. a buyer paying more than list.
 *
 * <p>Hot path: struct-of-arrays sized once and reused through {@link #reset()}. {@link #add} and {@link #rank}
 * allocate nothing. Insertion sort suits the small n of one window (tens to a few hundred bids). Not thread-safe;
 * one batch belongs to one matching thread (the single-writer principle).
 */
public final class BidBook {
    private final int[] node;
    private final long[] ask;
    private final double[] reputation;
    private final long[] latencyMicros;
    private final double[] score;
    private final int[] order;
    private int size;
    private int ranked;

    public BidBook(int capacity) {
        node = new int[capacity];
        ask = new long[capacity];
        reputation = new double[capacity];
        latencyMicros = new long[capacity];
        score = new double[capacity];
        order = new int[capacity];
    }

    public void reset() {
        size = 0;
        ranked = 0;
    }

    /**
     * Adds a sealed bid; arrival order is the tie-breaker of last resort.
     *
     * @param nodeRef       caller's handle for the bidding node
     * @param askMinor      the bid's ask in minor currency units
     * @param reputation    the node's current reputation R_i in [0, 100] (FR-16)
     * @param latencyMicros proposed fulfilment / response latency
     * @return the bid's index, or -1 when the window is full
     */
    public int add(int nodeRef, long askMinor, double reputation, long latencyMicros) {
        if (size == node.length) return -1;
        if (askMinor < 0 || latencyMicros < 0 || reputation < 0 || reputation > 100) {
            throw new IllegalArgumentException("bad bid: ask=" + askMinor + " R=" + reputation + " L=" + latencyMicros);
        }
        int i = size++;
        node[i] = nodeRef;
        ask[i] = askMinor;
        this.reputation[i] = reputation;
        this.latencyMicros[i] = latencyMicros;
        return i;
    }

    /**
     * Scores and ranks the batch.
     *
     * @return the number of eligible (ranked) bids
     */
    public int rank(long maxAcceptableMinor, Weights w, long latencyMaxMicros) {
        if (maxAcceptableMinor <= 0 || latencyMaxMicros <= 0) throw new IllegalArgumentException("maxAcceptable and Lmax must be > 0");
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (ask[i] > maxAcceptableMinor) continue;
            score[i] = score(ask[i], maxAcceptableMinor, reputation[i], latencyMicros[i], latencyMaxMicros, w);
            int j = n++;
            while (j > 0 && better(i, order[j - 1])) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
        ranked = n;
        return n;
    }

    /** The universal score for one bid; pure function, also used for explanations. */
    public static double score(long ask, long maxAcceptable, double reputation, long latencyMicros, long latencyMax, Weights w) {
        double utility = Math.max(0.0, (double) (maxAcceptable - ask) / maxAcceptable);
        double lat = Math.min(1.0, (double) latencyMicros / latencyMax);
        return w.price() * utility + w.reputation() * (reputation / 100.0) - w.latency() * lat;
    }

    private boolean better(int a, int b) {
        if (score[a] != score[b]) return score[a] > score[b];
        if (ask[a] != ask[b]) return ask[a] < ask[b];
        return a < b;
    }

    public int size() { return size; }

    public int ranked() { return ranked; }

    /** Bid index at a rank: 0 is the winner, 1.. are the hot backups for silent failover (FR-15 §5.2.2). */
    public int atRank(int rank) {
        if (rank < 0 || rank >= ranked) throw new IndexOutOfBoundsException(rank);
        return order[rank];
    }

    public int node(int bid) { return node[bid]; }

    public long ask(int bid) { return ask[bid]; }

    public double score(int bid) { return score[bid]; }
}
