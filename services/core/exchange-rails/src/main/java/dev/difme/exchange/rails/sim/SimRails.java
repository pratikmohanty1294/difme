package dev.difme.exchange.rails.sim;

import dev.difme.exchange.rails.AgentCredentialPort;
import dev.difme.exchange.rails.EscrowPort;
import dev.difme.exchange.rails.MerchantCheckoutPort;
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
}
