package dev.difme.exchange.rails;

/**
 * ISO 8583 field 39 response codes used on the merchant checkout rail (MTI 0100 authorisation request, 0110 response),
 * and how the exchange reacts to each (v4.1 slashing table, FR-15).
 */
public final class Iso8583 {
    private Iso8583() {}

    public static final String APPROVED = "00";
    public static final String DO_NOT_HONOUR = "05";
    public static final String INSUFFICIENT_FUNDS = "51";
    public static final String EXPIRED_CARD = "54";
    public static final String EXCEEDS_LIMIT = "61";
    public static final String ISSUER_UNAVAILABLE = "91";
    public static final String SYSTEM_MALFUNCTION = "96";

    /** What a response means for the buying partner who attempted it. */
    public enum Outcome {
        /** The purchase went through. */
        APPROVED,
        /** Over-commitment: the partner bid without the funds or limit (pack {@code slashing.iso_51_bps}). */
        DECLINE_FUNDS,
        /** Any other decline the partner is answerable for (pack {@code slashing.other_decline_bps}). */
        DECLINE_OTHER,
        /** Network or issuer fault: fail over, no slashing (the partner did nothing wrong). */
        RETRYABLE
    }

    public static Outcome classify(String responseCode) {
        return switch (responseCode) {
            case APPROVED -> Outcome.APPROVED;
            case INSUFFICIENT_FUNDS, EXCEEDS_LIMIT -> Outcome.DECLINE_FUNDS;
            case ISSUER_UNAVAILABLE, SYSTEM_MALFUNCTION -> Outcome.RETRYABLE;
            default -> Outcome.DECLINE_OTHER;
        };
    }
}
