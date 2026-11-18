package dev.difme.exchange.kernel;

/**
 * Value-weighted asymmetric EWMA reputation (PRD FR-16 §5.3).
 *
 * <pre>
 *   R_k = (1 − α_eff) · R_{k−1} + α_eff · S_k
 *   α_eff = α_base · ln(1 + V_k / V_ref) · δ,   δ = 1.0 if S_k ≥ R_{k−1} (slow gain), 2.5 otherwise (fast drop)
 * </pre>
 *
 * α_eff is capped at 1, so one very large task can at most replace the score, never overshoot it. That cap is an
 * addition to the PRD formula. The log weight stops sub-penny tasks from farming reputation.
 */
public final class Reputation {
    public static final double PRIOR_UNVERIFIED = 80.0;
    public static final double PRIOR_REGULATED = 88.0;
    public static final double DROP_MULTIPLIER = 2.5;

    private Reputation() {}

    public static double update(double previous, double taskScore, long valueMinor, long referenceMinor, double alphaBase) {
        if (taskScore < 0 || taskScore > 100 || previous < 0 || previous > 100 || referenceMinor <= 0 || valueMinor < 0) {
            throw new IllegalArgumentException("R=" + previous + " S=" + taskScore + " V=" + valueMinor + " Vref=" + referenceMinor);
        }
        double weight = Math.log1p((double) valueMinor / referenceMinor);
        double delta = taskScore >= previous ? 1.0 : DROP_MULTIPLIER;
        double alpha = Math.min(1.0, alphaBase * weight * delta);
        return (1 - alpha) * previous + alpha * taskScore;
    }

    /** Global score: weighted aggregate of per-vertical sub-scores (FR-16 §5.3.2). Weights need not sum to 1. */
    public static double global(double[] domainScores, double[] domainWeights) {
        if (domainScores.length != domainWeights.length) throw new IllegalArgumentException("length mismatch");
        double sum = 0, total = 0;
        for (int i = 0; i < domainScores.length; i++) {
            sum += domainScores[i] * domainWeights[i];
            total += domainWeights[i];
        }
        return total == 0 ? PRIOR_UNVERIFIED : sum / total;
    }
}
