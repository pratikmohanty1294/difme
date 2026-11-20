package dev.difme.exchange.rails;

import dev.difme.exchange.kernel.Risk;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Silent failover across the auction winner and its hot backups (ADR-017, FR-15). Each candidate is tried in rank
 * order until one is approved. A candidate whose decline it is answerable for is slashed with the kernel formula
 * (β from the pack's per-code basis points; μ = 1.2 when a backup takes over seamlessly, 1.5 when nobody does and the
 * task must be re-run). Network and issuer faults move on without slashing.
 */
public final class Failover {
    private Failover() {}

    /** One ranked candidate: the partner and the collateral locked for this task. */
    public record Candidate(long partnerId, long lockedMinor) {}

    /** Pack slashing basis points (FR-01: iso_51_bps 200, other_decline_bps 50). */
    public record SlashingBps(int funds, int other) {}

    public record Attempt(long partnerId, String responseCode, Iso8583.Outcome outcome, long slashMinor) {}

    /** winner is -1 when every candidate failed. */
    public record Result(long winner, List<Attempt> attempts) {
        public boolean fulfilled() { return winner >= 0; }
        public long totalSlashMinor() { return attempts.stream().mapToLong(Attempt::slashMinor).sum(); }
    }

    /**
     * @param valueMinor the task value V (FR-01: principal A)
     * @param attempt    executes the rail call for one partner and returns the ISO 8583 response code
     */
    public static Result run(List<Candidate> ranked, long valueMinor, SlashingBps bps, LongFunction<String> attempt) {
        List<Attempt> log = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            Candidate c = ranked.get(i);
            String code = attempt.apply(c.partnerId());
            Iso8583.Outcome outcome = Iso8583.classify(code);
            if (outcome == Iso8583.Outcome.APPROVED) {
                log.add(new Attempt(c.partnerId(), code, outcome, 0));
                return new Result(c.partnerId(), List.copyOf(log));
            }
            int basis = switch (outcome) {
                case DECLINE_FUNDS -> bps.funds();
                case DECLINE_OTHER -> bps.other();
                default -> 0;
            };
            Risk.Failover mu = i + 1 < ranked.size() ? Risk.Failover.SEAMLESS : Risk.Failover.REEXECUTE;
            long slash = basis == 0 ? 0 : Risk.slash(c.lockedMinor(), valueMinor, basis / 10_000.0, 0, 0, mu);
            log.add(new Attempt(c.partnerId(), code, outcome, slash));
        }
        return new Result(-1, List.copyOf(log));
    }
}
