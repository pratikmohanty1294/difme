package dev.difme.exchange.rails;

/** Merchant PSP checkout: ISO 8583 0100 authorisation request, 0110 response (jPOS in production, ADR-018). */
public interface MerchantCheckoutPort {
    /** 0100: stan is the systems trace audit number (field 11), unique per attempt. */
    record Authorization(String stan, String token, String merchantId, long amountMinor, String currency) {}

    /** 0110: field 39 response code, field 38 approval code, field 37 retrieval reference number. */
    record Response(String stan, String responseCode, String approvalCode, String rrn) {
        public boolean approved() { return Iso8583.APPROVED.equals(responseCode); }
    }

    Response authorize(Authorization request, long nowMs);

    /** 0400 reversal of an approved authorisation (e.g. a later leg failed); idempotent. */
    void reverse(String rrn);
}
