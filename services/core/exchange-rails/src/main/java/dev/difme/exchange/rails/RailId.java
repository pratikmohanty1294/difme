package dev.difme.exchange.rails;

import java.util.Arrays;

/** The rails a vertical pack may name in its {@code rails:} list (ADR-018). The id is the pack's spelling. */
public enum RailId {
    CARD_AGENT_CREDENTIAL("card-agent-credential"),
    MERCHANT_PSP_CHECKOUT("merchant-psp-checkout"),
    AGENT_PA_ESCROW("agent-pa-escrow"),
    UPI_RESERVE_PAY("upi-reserve-pay"),
    STABLECOIN("stablecoin");

    public final String id;

    RailId(String id) { this.id = id; }

    public static RailId of(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown rail '" + id + "'"));
    }
}
