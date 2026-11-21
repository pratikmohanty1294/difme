package dev.difme.exchange.svc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable exchange state (decided 2026-11-21: DIFME's own data lives in YugabyteDB). Keeps the latest version of each
 * record, keyed by kind and id, in one table. Writes never block the caller: {@link #put} only records the newest value
 * in memory, and one background writer upserts the pending records in batches, so a record changed ten times between
 * two flushes is written once. A crash loses at most the last flush interval; {@link #close} flushes everything.
 * Open auctions (≈50 ms) are not stored.
 */
public final class StateStore implements AutoCloseable {
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(StateStore.class);
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String url, user, password;
    private final long flushMillis;
    private final Map<String, String> pending = new LinkedHashMap<>();   // "kind\u0000id" -> JSON, newest wins
    private final Object lock = new Object();
    private final Thread writer;
    private volatile boolean closed;
    private Connection conn;
    final AtomicLong written = new AtomicLong(), failures = new AtomicLong();

    /** Disabled store: everything stays in memory (tests, local runs). */
    public static StateStore none() {
        return new StateStore();
    }

    private StateStore() {
        url = user = password = null;
        flushMillis = 0;
        writer = null;
    }

    public StateStore(String url, String user, String password, long flushMillis) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.flushMillis = flushMillis;
        conn = connect();
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS exchange");
            s.execute("CREATE TABLE IF NOT EXISTS exchange.state (kind text NOT NULL, id text NOT NULL, body jsonb NOT NULL,"
                    + " updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (kind, id))");
        }
        writer = Thread.ofVirtual().name("exchange-state-writer").start(this::run);
    }

    public boolean enabled() {
        return writer != null;
    }

    public int pending() {
        synchronized (lock) { return pending.size(); }
    }

    /** Records the newest value of a record; written by the background writer. No-op when disabled. */
    public void put(String kind, Object id, Object value) {
        if (writer == null || closed) return;
        String body;
        try {
            body = JSON.writeValueAsString(value);
        } catch (Exception e) {
            failures.incrementAndGet();
            LOG.errorf(e, "cannot serialise %s %s", kind, id);
            return;
        }
        synchronized (lock) { pending.put(kind + '\u0000' + id, body); }
    }

    /** Every stored record of a kind (startup). Empty when disabled. */
    public List<JsonNode> load(String kind) {
        List<JsonNode> out = new ArrayList<>();
        if (writer == null) return out;
        try (PreparedStatement ps = conn().prepareStatement("SELECT body::text FROM exchange.state WHERE kind = ? ORDER BY updated_at")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(JSON.readTree(rs.getString(1)));
            }
        } catch (Exception e) {
            // Starting without the stored state would hand out wrong budgets and lose orders: fail the start instead.
            throw new IllegalStateException("cannot load exchange state (" + kind + "): " + e.getMessage(), e);
        }
        return out;
    }

    private void run() {
        while (!closed) {
            try {
                TimeUnit.MILLISECONDS.sleep(flushMillis);
            } catch (InterruptedException e) {
                break;
            }
            flush();
        }
    }

    /** Writes everything pending; on failure the records stay pending (newer values still replace them) and retry. */
    public synchronized void flush() {
        if (writer == null) return;
        Map<String, String> batch;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO exchange.state (kind, id, body, updated_at)"
                + " VALUES (?, ?, ?::jsonb, now()) ON CONFLICT (kind, id) DO UPDATE SET body = EXCLUDED.body, updated_at = now()")) {
            for (var e : batch.entrySet()) {
                int cut = e.getKey().indexOf('\u0000');
                ps.setString(1, e.getKey().substring(0, cut));
                ps.setString(2, e.getKey().substring(cut + 1));
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            written.addAndGet(batch.size());
        } catch (SQLException e) {
            failures.incrementAndGet();
            LOG.warnf("exchange state write failed (%d records kept for retry): %s", batch.size(), e.getMessage());
            synchronized (lock) {
                batch.forEach(pending::putIfAbsent);   // a newer value written meanwhile wins
            }
            closeQuietly();
        }
    }

    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) conn = connect();
        return conn;
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        c.setAutoCommit(true);
        return c;
    }

    private void closeQuietly() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // reconnected on the next flush
        }
        conn = null;
    }

    @Override
    public void close() {
        if (writer == null) return;
        closed = true;
        writer.interrupt();
        for (int i = 0; i < 3 && pending() > 0; i++) flush();
        if (pending() > 0) LOG.errorf("exchange state: %d records not written at shutdown", pending());
        closeQuietly();
    }
}
