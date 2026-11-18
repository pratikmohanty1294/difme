package dev.difme.exchange.kernel;

/**
 * Domain weight vector of the universal auction score (PRD FR-17, §5.4.1): price utility, reputation, latency.
 * Each weight is in [0, 1] and they sum to 1.
 */
public record Weights(double price, double reputation, double latency) {
    private static final double EPS = 1e-9;

    public Weights {
        if (price < 0 || reputation < 0 || latency < 0 || price > 1 || reputation > 1 || latency > 1) {
            throw new IllegalArgumentException("weights must be in [0, 1]: " + price + ", " + reputation + ", " + latency);
        }
        if (Math.abs(price + reputation + latency - 1.0) > EPS) {
            throw new IllegalArgumentException("weights must sum to 1: " + (price + reputation + latency));
        }
    }
}
