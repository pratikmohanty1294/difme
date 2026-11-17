package dev.difme.flink.bulk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelToCsvTest {
    @Test
    void streamsSheetToCsv(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        try (SXSSFWorkbook wb = new SXSSFWorkbook()) {
            var s = wb.createSheet();
            var h = s.createRow(0); h.createCell(0).setCellValue("external_ref"); h.createCell(1).setCellValue("note");
            var r = s.createRow(1); r.createCell(0).setCellValue("R1"); r.createCell(1).setCellValue("a, b");
            wb.write(xlsx);
        }
        Path out = dir.resolve("o.csv");
        assertEquals(2, ExcelToCsv.convert(new ByteArrayInputStream(xlsx.toByteArray()), out, 0));
        assertEquals("external_ref,note\nR1,\"a, b\"\n", Files.readString(out).replace("\r\n", "\n"));
    }
}
