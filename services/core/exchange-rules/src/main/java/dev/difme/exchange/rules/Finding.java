package dev.difme.exchange.rules;

/** One matched rule row of a vertical pack's decision table: a machine code, its severity and the user-facing text. */
public record Finding(String code, Severity severity, String message) {
    public enum Severity { BLOCK, WARN, INFO }

    public boolean blocks() {
        return severity == Severity.BLOCK;
    }
}
