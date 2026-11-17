package dev.difme.flink.bulk;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One record from an uploaded file. {@code id} = sha256(file):byteOffset — stable across re-runs and unique even when
 * two lines are identical, so re-processing a file never double-applies (ADR-011).
 */
public class BulkRecord implements Serializable {
    public String id;
    public long offset;
    public String raw;
    public Map<String, String> fields = new LinkedHashMap<>();
    public List<String> errors = new ArrayList<>();

    public BulkRecord() {}

    public BulkRecord(String id, long offset, String raw) {
        this.id = id;
        this.offset = offset;
        this.raw = raw;
    }

    public String get(String k) { return fields.get(k); }
    public boolean valid() { return errors.isEmpty(); }
    public BulkRecord reject(String reason) { errors.add(reason); return this; }
}
