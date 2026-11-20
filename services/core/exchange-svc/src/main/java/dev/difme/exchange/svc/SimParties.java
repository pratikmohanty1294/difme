package dev.difme.exchange.svc;

import dev.difme.exchange.kernel.Canonical;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated merchant and carrier for dev and verification clusters, the counterpart of the simulated rails (ADR-018).
 * Each party has its own Ed25519 key, registered with the exchange exactly as a real merchant or carrier registers at
 * {@code POST /v1/exchange/parties}, and signs its own document. The exchange checks the proof the same way as a real
 * one; nothing here bypasses {@link ProofOfDelivery#check}. Real deployments turn this off
 * ({@code difme.exchange.sim-parties=false}) and the documents arrive from the merchant and carrier themselves.
 */
final class SimParties {
    static final String CARRIER = "sim-courier";

    private final Exchange exchange;
    private final Map<String, PrivateKey> keys = new ConcurrentHashMap<>();

    SimParties(Exchange exchange) {
        this.exchange = exchange;
    }

    /** The signed invoice for what the merchant charged and the carrier's signed DELIVERED event. */
    ProofOfDelivery.Proof deliver(long auctionId, String merchantId, long amountMinor, long deliveredAtMs) {
        var unsignedInvoice = new ProofOfDelivery.Invoice("INV-" + auctionId, merchantId, auctionId, amountMinor, "INR", null);
        var invoice = new ProofOfDelivery.Invoice(unsignedInvoice.invoiceId(), merchantId, auctionId, amountMinor, "INR",
                sign("merchant:" + merchantId, unsignedInvoice.signed()));
        var unsignedDelivery = new ProofOfDelivery.Delivery(CARRIER, "TRK-" + auctionId, auctionId, "DELIVERED", deliveredAtMs, null);
        var delivery = new ProofOfDelivery.Delivery(CARRIER, unsignedDelivery.trackingId(), auctionId, "DELIVERED", deliveredAtMs,
                sign("carrier:" + CARRIER, unsignedDelivery.signed()));
        return new ProofOfDelivery.Proof(invoice, delivery);
    }

    private String sign(String party, Map<String, Object> payload) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(keys.computeIfAbsent(party, this::enrol));
            s.update(Canonical.json(payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** New key pair for the party; only the public half goes to the exchange. */
    private PrivateKey enrol(String party) {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            exchange.registerPartyKey(party, kp.getPublic());
            return kp.getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
