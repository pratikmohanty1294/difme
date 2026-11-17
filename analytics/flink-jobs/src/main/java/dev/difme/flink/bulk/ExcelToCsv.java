package dev.difme.flink.bulk;

import com.github.pjfanning.xlsx.StreamingReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Streams an .xlsx sheet to CSV with constant memory (Apache POI streaming reader). Excel can't be split for parallel
 * reading, so it is converted first; the CSV then runs through {@link BulkFileJob} in parallel (ADR-011).
 * Usage: ExcelToCsv input.xlsx output.csv [sheetIndex]
 */
public final class ExcelToCsv {
    private ExcelToCsv() {}

    public static long convert(InputStream xlsx, Path out, int sheetIndex) throws Exception {
        DataFormatter fmt = new DataFormatter();
        long rows = 0;
        try (Workbook wb = StreamingReader.builder().rowCacheSize(200).bufferSize(1 << 16).open(xlsx);
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            Sheet sheet = wb.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                StringBuilder line = new StringBuilder();
                int last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    if (c > 0) line.append(',');
                    Cell cell = row.getCell(c);
                    String v = cell == null ? "" : fmt.formatCellValue(cell);
                    line.append(v.contains(",") || v.contains("\"") || v.contains("\n") ? BulkFileJob.csv(v) : v);
                }
                w.write(line.toString());
                w.newLine();
                rows++;
            }
        }
        return rows;
    }

    public static void main(String[] args) throws Exception {
        try (InputStream in = new FileInputStream(args[0])) {
            long n = convert(in, Path.of(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 0);
            System.out.println("converted " + n + " rows");
        }
    }
}
