package dev.difme.flink.bulk;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/**
 * Benchmark data: N payment rows against K accounts, ~2% deliberately invalid (bad amount, unknown account,
 * duplicate ref, over limit). Usage: GenerateFile output.csv 10000000 1000000
 */
public final class GenerateFile {
    private GenerateFile() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        long n = Long.parseLong(args[1]);
        int accounts = Integer.parseInt(args[2]);
        SplittableRandom rnd = new SplittableRandom(42);
        String[] channels = {"MOBILE", "WEB", "NFC_POS"};
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(String.join(",", PaymentFileRules.COLUMNS));
            w.newLine();
            for (long i = 0; i < n; i++) {
                int d = rnd.nextInt(accounts), c = rnd.nextInt(accounts);
                if (c == d) c = (c + 1) % accounts;
                long amount = 100 + rnd.nextInt(400_000);
                String ref = "REF" + i;
                int fault = rnd.nextInt(100);
                if (fault == 0) amount = -5;                              // schema error
                else if (fault == 1) d = accounts + 7;                    // unknown account
                else if (fault == 2 && i > 0) ref = "REF" + (i - 1);      // duplicate ref in file
                else if (fault == 3) amount = 999_999_999;                // over DMN limit
                w.write(ref + ",ACC" + d + ",ACC" + c + "," + amount + ",INR," + channels[rnd.nextInt(3)]);
                w.newLine();
            }
        }
        System.out.println("generated " + n + " rows into " + out);
    }
}
