package dev.difme.flink.bulk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecordParserTest {
    @Test
    void csvWithQuotes() {
        BulkRecord r = new BulkRecord("f:1", 1, "R1,\"ACC, 1\",\"say \"\"hi\"\"\"");
        RecordParser.csv(List.of("ref", "acc", "note"), ',').parse(r);
        assertTrue(r.valid(), r.errors.toString());
        assertEquals("ACC, 1", r.get("acc"));
        assertEquals("say \"hi\"", r.get("note"));
    }

    @Test
    void csvWrongColumnCount() {
        BulkRecord r = new BulkRecord("f:1", 1, "a,b");
        RecordParser.csv(List.of("x", "y", "z"), ',').parse(r);
        assertFalse(r.valid());
    }

    @Test
    void fixedWidth() {
        BulkRecord r = new BulkRecord("f:1", 1, "REF0001ACC00012   1500INR");
        RecordParser.fixedWidth("ref:0:7,acc:7:8,amount:15:7,ccy:22:3").parse(r);
        assertEquals("ACC00012", r.get("acc"));
        assertEquals("1500", r.get("amount"));
        assertEquals("INR", r.get("ccy"));
    }

    @Test
    void jsonLines() {
        BulkRecord r = new BulkRecord("f:1", 1, "{\"external_ref\":\"R9\",\"amount_minor\":42}");
        RecordParser.json().parse(r);
        assertEquals("42", r.get("amount_minor"));
    }
}
