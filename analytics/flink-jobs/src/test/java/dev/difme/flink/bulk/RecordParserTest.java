package dev.difme.flink.bulk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecordParserTest {
    @Test
    void csvWithQuotes() {
        BulkRecord r = new BulkRecord("f:1", 1, "R1,\"ACC, 1\",\"say \"\"hi\"\"\"");
        RecordParser.csv(List.of("ref", "acc", "note"), ',').parse(r);
        assertTrue(r.valid(), r.errors);
        assertEquals("ACC, 1", r.get(1));
        assertEquals("say \"hi\"", r.get(2));
        assertNull(r.raw, "raw line is dropped after a successful parse");
    }

    @Test
    void csvWrongColumnCount() {
        BulkRecord r = new BulkRecord("f:1", 1, "a,b");
        RecordParser.csv(List.of("x", "y", "z"), ',').parse(r);
        assertFalse(r.valid());
        assertEquals("a,b", r.line());
    }

    @Test
    void fixedWidth() {
        BulkRecord r = new BulkRecord("f:1", 1, "REF0001ACC00012   1500INR");
        RecordParser.fixedWidth("ref:0:7,acc:7:8,amount:15:7,ccy:22:3").parse(r);
        assertEquals("ACC00012", r.get(1));
        assertEquals("1500", r.get(2));
        assertEquals("INR", r.get(3));
    }

    @Test
    void jsonLines() {
        BulkRecord r = new BulkRecord("f:1", 1, "{\"external_ref\":\"R9\",\"amount_minor\":42}");
        RecordParser.json(List.of("external_ref", "amount_minor")).parse(r);
        assertEquals("42", r.get(1));
    }
}
