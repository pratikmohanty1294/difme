package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.Iso8583;
import dev.difme.exchange.rails.MerchantCheckoutPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated PSP + issuer behind ISO 8583 0100/0110. The issuer knows each buying partner's available card limit:
 * over the limit answers 51; an invalid, spent or mismatched token answers 05; a scripted fault (91/96) can be set per
 * partner. Answers are idempotent per STAN, as a real switch replays the original 0110 for a duplicate 0100.
 */
public final class SimMerchantCheckout implements MerchantCheckoutPort {
    private final SimAgentCredential credentials;
    private final Map<Long, Long> availableLimitMinor = new ConcurrentHashMap<>();
    private final Map<Long, String> scriptedCode = new ConcurrentHashMap<>();
    private final Map<String, Response> byStan = Bounded.map();
    private final Map<String, Long> approvedAmountByRrn = Bounded.map();
    private final Map<String, Long> partnerByRrn = Bounded.map();
    private final AtomicLong rrn = new AtomicLong(100_000_000_000L);

    public SimMerchantCheckout(SimAgentCredential credentials) { this.credentials = credentials; }

    public SimMerchantCheckout limit(long partnerId, long availableMinor) { availableLimitMinor.put(partnerId, availableMinor); return this; }

    /** Every later authorisation for this partner answers the given code (e.g. 91 issuer unavailable). */
    public SimMerchantCheckout script(long partnerId, String responseCode) { scriptedCode.put(partnerId, responseCode); return this; }

    @Override
    public Response authorize(Authorization a, long nowMs) {
        return byStan.computeIfAbsent(a.stan(), s -> decide(a, nowMs));
    }

    private Response decide(Authorization a, long nowMs) {
        var instruction = credentials.instructionOf(a.token()).orElse(null);
        if (instruction == null) return new Response(a.stan(), Iso8583.DO_NOT_HONOUR, null, null);
        long partner = instruction.partnerId();
        String scripted = scriptedCode.get(partner);
        if (scripted != null) return new Response(a.stan(), scripted, null, null);
        long limit = availableLimitMinor.getOrDefault(partner, Long.MAX_VALUE);
        if (a.amountMinor() > limit) return new Response(a.stan(), Iso8583.INSUFFICIENT_FUNDS, null, null);
        if (!credentials.redeem(a.token(), a.merchantId(), a.amountMinor(), nowMs)) {
            return new Response(a.stan(), Iso8583.DO_NOT_HONOUR, null, null);
        }
        availableLimitMinor.computeIfPresent(partner, (p, l) -> l - a.amountMinor());
        String ref = Long.toString(rrn.incrementAndGet());
        approvedAmountByRrn.put(ref, a.amountMinor());
        partnerByRrn.put(ref, partner);
        return new Response(a.stan(), Iso8583.APPROVED, ref.substring(ref.length() - 6), ref);
    }

    @Override
    public void reverse(String rrn) {
        Long amount = approvedAmountByRrn.remove(rrn);
        if (amount != null) availableLimitMinor.computeIfPresent(partnerByRrn.remove(rrn), (p, l) -> l + amount);
    }
}
