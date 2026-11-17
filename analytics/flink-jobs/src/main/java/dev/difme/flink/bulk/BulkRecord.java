package dev.difme.flink.bulk;

import java.util.List;

/**
 * One record from an uploaded file, as a flat Flink POJO (String[] values, no Map/List) so it travels through the
 * batch sort/shuffle with Flink's fast POJO serializer instead of generic Kryo (ADR-012).
 * {@code id} = sha256(file):byteOffset — stable across re-runs and unique even for identical lines (ADR-011).
 * The raw line is dropped after parsing; error output rebuilds it from the values.
 */
public class BulkRecord {
    public String id;
    public long offset;
    public String raw;          // only until parsed
    public String[] values;     // positional, aligned with the job's column list
    public String errors;       // null = valid; "; "-joined reasons otherwise

    public BulkRecord() {}

    public BulkRecord(String id, long offset, String raw) {
        this.id = id;
        this.offset = offset;
        this.raw = raw;
    }

    public String get(int column) { return values == null || column >= values.length ? null : values[column]; }
    public boolean valid() { return errors == null; }

    public BulkRecord reject(String reason) {
        errors = errors == null ? reason : errors + "; " + reason;
        return this;
    }

    public List<String> errorList() { return errors == null ? List.of() : List.of(errors.split("; ")); }

    /** Original line for the error file (values re-joined as CSV when the raw text was already dropped). */
    public String line() {
        if (raw != null) return raw;
        if (values == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(',');
            String v = values[i] == null ? "" : values[i];
            b.append(v.contains(",") || v.contains("\"") ? BulkFileJob.csv(v) : v);
        }
        return b.toString();
    }
}
