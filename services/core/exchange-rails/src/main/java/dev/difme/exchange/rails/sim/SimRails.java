package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.AgentCredentialPort;
import dev.difme.exchange.rails.EscrowPort;
import dev.difme.exchange.rails.MerchantCheckoutPort;
import dev.difme.exchange.rails.PaymentHub;
import dev.difme.exchange.rails.hub.HubCheckout;
import dev.difme.exchange.rails.hub.HubMirror;
import java.util.Map;
import java.util.function.Consumer;
import dev.difme.exchange.rails.RailId;
import dev.difme.exchange.rails.RailRegistry;
import dev.difme.exchange.rails.StablecoinPort;
import dev.difme.exchange.rails.UpiReservePort;

/** A registry with a simulator on every rail: what dev, tests and the verification clusters run. */
public final class SimRails {
    public final SimAgentCredential credentials = new SimAgentCredential();
    public final SimMerchantCheckout checkout = new SimMerchantCheckout(credentials);
    public final SimEscrow escrow = new SimEscrow();
    public final SimUpiReserve upi = new SimUpiReserve();
    public final SimStablecoin stablecoin = new SimStablecoin();
    public final RailRegistry registry = new RailRegistry()
            .register(RailId.CARD_AGENT_CREDENTIAL, AgentCredentialPort.class, credentials)
            .register(RailId.MERCHANT_PSP_CHECKOUT, MerchantCheckoutPort.class, checkout)
            .register(RailId.AGENT_PA_ESCROW, EscrowPort.class, escrow)
            .register(RailId.UPI_RESERVE_PAY, UpiReservePort.class, upi)
            .register(RailId.STABLECOIN, StablecoinPort.class, stablecoin);

    private volatile MerchantCheckoutPort cardCheckout = checkout;
    private volatile HubMirror mirror;

    /** The checkout that charges the winning partner's card: the simulator, or the real rails after {@link #useHub}. */
    public MerchantCheckoutPort cardCheckout() { return cardCheckout; }

    /** Escrow bookings on the core banking system, or null with the simulator only. */
    public HubMirror cbsMirror() { return mirror; }

    /**
     * Switches card payments to the real rails through the payment hub and books every escrow movement on the core
     * banking system. Agent credentials and the escrow ledger stay here.
     */
    public synchronized void useHub(PaymentHub hub, Map<Long, PaymentHub.CardOnFile> partnerCards, Consumer<String> log) {
        if (mirror != null) mirror.close();
        mirror = new HubMirror(hub, log);
        escrow.mirrorTo(mirror);
        cardCheckout = new HubCheckout(credentials, hub, partnerCards);
    }
}
