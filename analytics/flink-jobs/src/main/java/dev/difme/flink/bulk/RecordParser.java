package dev.difme.flink.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Parses CSV (RFC 4180 quoting), fixed-width (layout "name:start:length,...") and JSON-lines into positional values. */
public final class RecordParser implements Serializable {
    public enum Format { CSV, FIXED, JSON }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Format format;
    private final List<String> columns;
    private final List<int[]> fixed;
    private final char delimiter;

    private RecordParser(Format format, List<String> columns, List<int[]> fixed, char delimiter) {
        this.format = format;
        this.columns = columns;
        this.fixed = fixed;
        this.delimiter = delimiter;
    }

    public static RecordParser csv(List<String> columns, char delimiter) { return new RecordParser(Format.CSV, columns, List.of(), delimiter); }
    public static RecordParser json(List<String> columns) { return new RecordParser(Format.JSON, columns, List.of(), ','); }

    public static RecordParser fixedWidth(String layout) {
        List<String> cols = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        for (String part : layout.split(",")) {
            String[] p = part.trim().split(":");
            cols.add(p[0]);
            spans.add(new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])});
        }
        return new RecordParser(Format.FIXED, cols, spans, ',');
    }

    public List<String> columns() { return columns; }

    public void parse(BulkRecord r) {
        try {
            String[] v = new String[columns.size()];
            switch (format) {
                case CSV -> {
                    List<String> values = splitCsv(r.raw, delimiter);
                    if (values.size() != columns.size()) { r.reject("expected " + columns.size() + " columns, got " + values.size()); return; }
                    for (int i = 0; i < v.length; i++) v[i] = values.get(i).trim();
                }
                case FIXED -> {
                    for (int i = 0; i < v.length; i++) {
                        int s = fixed.get(i)[0], len = fixed.get(i)[1];
                        if (r.raw.length() < s + len) { r.reject("line shorter than layout at " + columns.get(i)); return; }
                        v[i] = r.raw.substring(s, s + len).trim();
                    }
                }
                case JSON -> {
                    JsonNode n = JSON.readTree(r.raw);
                    for (int i = 0; i < v.length; i++) {
                        JsonNode f = n.get(columns.get(i));
                        v[i] = f == null || f.isNull() ? null : f.isValueNode() ? f.asText() : f.toString();
                    }
                }
            }
            r.values = v;
            if (r.valid()) r.raw = null;   // shrink the record before the shuffle
        } catch (Exception e) {
            r.reject("unparseable: " + e.getMessage());
        }
    }

    static List<String> splitCsv(String line, char d) {
        List<String> out = new ArrayList<>(8);
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == d) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
