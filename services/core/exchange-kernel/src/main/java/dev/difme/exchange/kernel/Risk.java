package dev.difme.exchange.kernel;

/**
 * Collateral sizing (PRD FR-14) and slashing (FR-15). Money is in minor units. Results round half-up to whole
 * minor units, so a slash is never a fraction of a paisa.
 */
public final class Risk {
    private Risk() {}

    /** Reputation tier and its collateral multiplier φ (FR-14 §5.1.1). */
    public enum Tier {
        COLD_START(2.00), ESTABLISHED(1.00), ENTERPRISE(0.60);

        public final double phi;

        Tier(double phi) { this.phi = phi; }
    }

    /** Fault severity base factor β_fault (FR-15 §5.2.1). Fraud's range is 0.15–0.25; the pack may override it. */
    public enum Fault {
        TRANSIENT(0.005), MISREPRESENTATION(0.020), FRAUD(0.20);

        public final double beta;

        Fault(double beta) { this.beta = beta; }
    }

    /** Failover friction μ: 1.2 when a runner-up takes over seamlessly, 1.5 when the task must be fully re-executed. */
    public enum Failover {
        NONE(1.0), SEAMLESS(1.2), REEXECUTE(1.5);

        public final double mu;

        Failover(double mu) { this.mu = mu; }
    }

    /**
     * D_req = max(D_min, V · α_D · γ_rev · φ).
     *
     * @param valueMinor task value V_k
     * @param alphaD     domain risk factor in (0, 1]
     * @param gammaRev   irreversibility multiplier, ≥ 1
     */
    public static long requiredCollateral(long valueMinor, double alphaD, double gammaRev, Tier tier, long minMinor) {
        if (valueMinor < 0 || alphaD <= 0 || alphaD > 1 || gammaRev < 1) {
            throw new IllegalArgumentException("V=" + valueMinor + " alpha=" + alphaD + " gamma=" + gammaRev);
        }
        return Math.max(minMinor, Math.round(valueMinor * alphaD * gammaRev * tier.phi));
    }

    /** Λ = min(D_locked, V · (β_fault + β_time · τ_overrun) · μ_failover), τ in minutes past the SLA target. */
    public static long slash(long lockedMinor, long valueMinor, double betaFault, double betaTimePerMinute, double overrunMinutes,
                             Failover failover) {
        double tau = Math.max(0.0, overrunMinutes);
        return Math.min(lockedMinor, Math.round(valueMinor * (betaFault + betaTimePerMinute * tau) * failover.mu));
    }
}
