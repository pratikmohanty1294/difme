package dev.difme.flink.bulk;

import java.io.Serializable;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation for bulk payment files (columns: external_ref, debit_account, credit_account, amount_minor, currency,
 * channel). Pure: inputs are the record plus looked-up linked records; output is a list of reasons on the record.
 */
public final class PaymentFileRules implements Serializable {
    public static final java.util.List<String> COLUMNS =
            java.util.List.of("external_ref", "debit_account", "credit_account", "amount_minor", "currency", "channel");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private PaymentFileRules() {}

    /** Field-level checks that need no lookups. */
    public static void schema(BulkRecord r) {
        for (String c : COLUMNS) if (r.get(c) == null || r.get(c).isEmpty()) r.reject(c + " is required");
        if (!r.valid()) return;
        try {
            if (Long.parseLong(r.get("amount_minor")) <= 0) r.reject("amount_minor must be positive");
        } catch (NumberFormatException e) {
            r.reject("amount_minor must be an integer (minor units)");
        }
        if (!CURRENCY.matcher(r.get("currency")).matches()) r.reject("currency must be ISO-4217");
        if (r.get("debit_account").equals(r.get("credit_account"))) r.reject("debit and credit account must differ");
    }

    /** Checks against linked records (static + live) and the DMN limit. */
    public static void linked(BulkRecord r, ReferenceStore.Account debit, ReferenceStore.Account credit,
                              String debitStatus, boolean alreadyProcessed, DmnRules limits) {
        if (alreadyProcessed) r.reject("external_ref already processed in an earlier run");
        if (debit == null) { r.reject("debit account not found"); return; }
        if (credit == null) r.reject("credit account not found");
        if (!"ACTIVE".equals(debitStatus)) r.reject("debit account is " + (debitStatus == null ? "unknown" : debitStatus));
        if (!debit.currency().equals(r.get("currency"))) r.reject("currency does not match debit account");
        Object limit = limits.single(Map.of("channel", r.get("channel"), "riskLevel", debit.riskLevel()));
        if (limit == null) r.reject("no payment limit configured for channel/risk");
        else if (Long.parseLong(r.get("amount_minor")) > ((Number) limit).longValue()) r.reject("amount exceeds daily limit " + limit);
    }
}
