package dev.difme.exchange.svc;

import dev.difme.exchange.kernel.Canonical;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-01 fulfilment proof (vertical.yaml {@code settlement.trigger}: signed merchant invoice + carrier tracking webhook).
 * Both documents are Ed25519-signed over their canonical JSON (signature field excluded) by keys registered with the
 * exchange. The invoice must be for exactly the principal A the partner paid, at the merchant the exchange paid, and
 * the carrier must report the parcel delivered.
 */
public final class ProofOfDelivery {
    private ProofOfDelivery() {}

    public record Invoice(String invoiceId, String merchantId, long auctionId, long amountMinor, String currency, String signature) {
        Map<String, Object> signed() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", invoiceId); m.put("merchantId", merchantId); m.put("auctionId", auctionId);
            m.put("amountMinor", amountMinor); m.put("currency", currency);
            return m;
        }
    }

    public record Delivery(String carrier, String trackingId, long auctionId, String status, long deliveredAtMs, String signature) {
        Map<String, Object> signed() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("carrier", carrier); m.put("trackingId", trackingId); m.put("auctionId", auctionId);
            m.put("status", status); m.put("deliveredAtMs", deliveredAtMs);
            return m;
        }
    }

    public record Proof(Invoice invoice, Delivery delivery) {}

    /** Problems found; empty means the proof holds. */
    public static List<String> check(Proof p, long auctionId, String paidMerchant, long principalMinor, Map<String, PublicKey> keys) {
        List<String> problems = new ArrayList<>();
        if (p == null || p.invoice() == null || p.delivery() == null) return List.of("invoice and delivery are both required");
        Invoice i = p.invoice();
        Delivery d = p.delivery();
        if (i.auctionId() != auctionId || d.auctionId() != auctionId) problems.add("proof is for another auction");
        if (!paidMerchant.equals(i.merchantId())) problems.add("invoice merchant " + i.merchantId() + " is not the merchant paid (" + paidMerchant + ")");
        if (i.amountMinor() != principalMinor) problems.add("invoice amount " + i.amountMinor() + " differs from the principal A " + principalMinor);
        if (!"INR".equals(i.currency())) problems.add("invoice currency must be INR");
        if (!"DELIVERED".equals(d.status())) problems.add("carrier status is " + d.status() + ", not DELIVERED");
        if (d.trackingId() == null || d.trackingId().isBlank()) problems.add("tracking id missing");
        if (!verifies(keys.get("merchant:" + i.merchantId()), i.signed(), i.signature())) problems.add("invoice signature does not verify against merchant:" + i.merchantId());
        if (!verifies(keys.get("carrier:" + d.carrier()), d.signed(), d.signature())) problems.add("delivery signature does not verify against carrier:" + d.carrier());
        return problems;
    }

    static boolean verifies(PublicKey key, Map<String, Object> payload, String signatureB64) {
        if (key == null || signatureB64 == null) return false;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(Canonical.json(payload).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
