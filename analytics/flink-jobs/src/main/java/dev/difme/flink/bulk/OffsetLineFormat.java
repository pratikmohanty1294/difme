package dev.difme.flink.bulk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.file.src.reader.SimpleStreamFormat;
import org.apache.flink.connector.file.src.reader.StreamFormat;
import org.apache.flink.core.fs.FSDataInputStream;

/**
 * Line reader that remembers each line's byte offset, giving every record a stable id across parallel splits.
 * Assumes '\n' line endings (CRLF is tolerated; the CR is stripped). Header line (offset 0) is skipped when requested.
 */
public class OffsetLineFormat extends SimpleStreamFormat<BulkRecord> {
    private final String fileId;
    private final boolean skipHeader;

    public OffsetLineFormat(String fileId, boolean skipHeader) {
        this.fileId = fileId;
        this.skipHeader = skipHeader;
    }

    @Override
    public StreamFormat.Reader<BulkRecord> createReader(Configuration config, FSDataInputStream stream) throws IOException {
        long start = stream.getPos();
        BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16);
        return new StreamFormat.Reader<>() {
            long pos = start;

            @Override
            public BulkRecord read() throws IOException {
                while (true) {
                    String line = in.readLine();
                    if (line == null) return null;
                    long offset = pos;
                    pos += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (line.isBlank() || (skipHeader && offset == 0)) continue;
                    return new BulkRecord(fileId + ":" + offset, offset, line);
                }
            }

            @Override
            public void close() throws IOException { in.close(); }
        };
    }

    @Override
    public TypeInformation<BulkRecord> getProducedType() {
        return TypeInformation.of(BulkRecord.class);
    }
}
