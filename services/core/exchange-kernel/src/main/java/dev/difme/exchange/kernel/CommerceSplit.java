package dev.difme.exchange.kernel;

/**
 * Conservation identity of a delegated purchase (Buy It For Me, DiscountAI E2E v4.1 §0.4). All amounts in minor units.
 *
 * <pre>
 *   A = P − round(P · d)      charged to the buying partner's credential at the merchant
 *   B = A + Y + F             buyer pays (auth-only hold, captured on shipment)
 *   S = P − B                 buyer saves
 *   payout = A + Y            to the buying partner (A before the card due date, Y after the return window)
 * </pre>
 *
 * The PRD §3.1.1.3 names these letters the other way round ("A = net user payment"). v4.1 and the prototype are newer
 * and are followed here. The guarantee-fund share is taken out of F, so B is unchanged.
 *
 * @param discountBps d in basis points (2000 = 20 %)
 */
public record CommerceSplit(long list, int discountBps, long discount, long a, long yield, long fee, long b, long buyerSaves,
                            long payout, long guaranteeFund) {

    public CommerceSplit {
        if (a + yield + fee != b || list - b != buyerSaves || a + yield != payout || list - discount != a) {
            throw new IllegalStateException("conservation identity violated: " + this);
        }
    }

    /**
     * @param guaranteeFundBps share of F allocated to the Guarantee Fund (1000 = 10 %)
     * @throws IllegalArgumentException when the buyer would not save (Y + F must stay below the discount)
     */
    public static CommerceSplit of(long listMinor, int discountBps, long yieldMinor, long feeMinor, int guaranteeFundBps) {
        if (listMinor <= 0 || discountBps < 0 || discountBps >= 10_000 || yieldMinor < 0 || feeMinor < 0) {
            throw new IllegalArgumentException("P=" + listMinor + " d=" + discountBps + " Y=" + yieldMinor + " F=" + feeMinor);
        }
        long discount = Math.round(listMinor * (discountBps / 10_000.0));
        long a = listMinor - discount;
        long b = a + yieldMinor + feeMinor;
        if (b >= listMinor) throw new IllegalArgumentException("no buyer saving: B=" + b + " ≥ P=" + listMinor);
        long gf = Math.round(feeMinor * (guaranteeFundBps / 10_000.0));
        return new CommerceSplit(listMinor, discountBps, discount, a, yieldMinor, feeMinor, b, listMinor - b, a + yieldMinor, gf);
    }
}
