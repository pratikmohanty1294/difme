package dev.difme.flink.bulk;

import java.io.Serializable;
import java.util.List;

/**
 * Validation for bulk payment files (columns: external_ref, debit_account, credit_account, amount_minor, currency,
 * channel). Pure: inputs are the record plus looked-up linked records; output is the reasons on the record.
 */
public final class PaymentFileRules implements Serializable {
    public static final List<String> COLUMNS = List.of("external_ref", "debit_account", "credit_account", "amount_minor", "currency", "channel");
    public static final int REF = 0, DEBIT = 1, CREDIT = 2, AMOUNT = 3, CURRENCY = 4, CHANNEL = 5;

    private PaymentFileRules() {}

    /** Field-level checks that need no lookups. */
    public static void schema(BulkRecord r) {
        for (int i = 0; i < COLUMNS.size(); i++) {
            String v = r.get(i);
            if (v == null || v.isEmpty()) r.reject(COLUMNS.get(i) + " is required");
        }
        if (!r.valid()) return;
        try {
            if (Long.parseLong(r.get(AMOUNT)) <= 0) r.reject("amount_minor must be positive");
        } catch (NumberFormatException e) {
            r.reject("amount_minor must be an integer (minor units)");
        }
        String ccy = r.get(CURRENCY);
        if (ccy.length() != 3 || !Character.isUpperCase(ccy.charAt(0)) || !Character.isUpperCase(ccy.charAt(1)) || !Character.isUpperCase(ccy.charAt(2))) {
            r.reject("currency must be ISO-4217");
        }
        if (r.get(DEBIT).equals(r.get(CREDIT))) r.reject("debit and credit account must differ");
    }

    /** Checks against linked records (static snapshot + live) and the DMN limit. */
    public static void linked(BulkRecord r, ReferenceStore.Account debit, ReferenceStore.Account credit,
                              String debitStatus, boolean alreadyProcessed, DmnRules limits) {
        if (alreadyProcessed) r.reject("external_ref already processed in an earlier run");
        if (debit == null) { r.reject("debit account not found"); return; }
        if (credit == null) r.reject("credit account not found");
        if (!"ACTIVE".equals(debitStatus)) r.reject("debit account is " + (debitStatus == null ? "unknown" : debitStatus));
        if (!debit.currency().equals(r.get(CURRENCY))) r.reject("currency does not match debit account");
        Object limit = limits.single("channel", r.get(CHANNEL), "riskLevel", debit.riskLevel());
        if (limit == null) r.reject("no payment limit configured for channel/risk");
        else if (Long.parseLong(r.get(AMOUNT)) > ((Number) limit).longValue()) r.reject("amount exceeds daily limit " + limit);
    }
}
