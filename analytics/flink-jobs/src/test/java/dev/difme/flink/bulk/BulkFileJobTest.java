package dev.difme.flink.bulk;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Runs the full bulk pipeline on a Flink MiniCluster with the real payment-limits DMN. */
class BulkFileJobTest {
    @RegisterExtension
    static final MiniClusterExtension FLINK = new MiniClusterExtension();

    @Test
    void validatesEveryRecord(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("payments.csv");
        Files.write(input, List.of(
                "external_ref,debit_account,credit_account,amount_minor,currency,channel",
                "R1,ACC1,ACC2,1000,INR,MOBILE",          // valid
                "R2,ACC1,ACC2,-5,INR,MOBILE",            // schema
                "R3,ACC5000,ACC2,1000,INR,MOBILE",       // unknown debit account (store holds ACC0..ACC999)
                "R1,ACC3,ACC4,1000,INR,MOBILE",          // duplicate ref in file
                "R5,ACC13,ACC2,1000,INR,WEB",            // frozen account (live check)
                "OLD1,ACC1,ACC2,1000,INR,MOBILE",        // processed in an earlier run
                "R7,ACC20,ACC2,2000000,INR,MOBILE",      // HIGH risk → 1,000,000 limit (DMN)
                "R8,ACC21,ACC2,600000,INR,NFC_POS",      // NFC limit 500,000 (DMN)
                "R9,ACC22,ACC23,400000,INR,NFC_POS"));   // valid
        String dmn = Files.readString(Path.of(System.getProperty("user.dir")).resolve("../../processes/dmn/payment-limits.dmn").normalize());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        BulkFileJob.build(env, new BulkFileJob.Options(input.toString(), "csv", PaymentFileRules.COLUMNS, "", true, dmn,
                100, 2, dir.resolve("out").toString(), "file", "", ""), new MapReferenceStore(1000));
        env.execute("bulk-test");

        String report = read(dir.resolve("out/report"));
        assertTrue(report.contains("\"valid\":2"), report);
        assertTrue(report.contains("\"rejected\":7"), report);
        String errors = read(dir.resolve("out/errors"));
        for (String reason : List.of("amount_minor must be positive", "debit account not found", "duplicate external_ref",
                "debit account is FROZEN", "already processed", "exceeds daily limit 1000000", "exceeds daily limit 500000")) {
            assertTrue(errors.contains(reason), "missing rejection: " + reason + "\n" + errors);
        }
    }

    private static String read(Path dir) throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            StringBuilder b = new StringBuilder();
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().startsWith(".")).toList()) {
                b.append(Files.readString(f));
            }
            return b.toString();
        }
    }
}
