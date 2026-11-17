package dev.difme.flink.bulk;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * YugabyteDB YSQL (PostgreSQL wire). Static data: one streamed scan from follower replicas. Live data: every query
 * takes a whole batch of keys via {@code = ANY(?)}; nothing is queried per record.
 */
public class JdbcReferenceStore implements ReferenceStore {
    private final String url, user, password;
    private transient Connection liveConn;

    public JdbcReferenceStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void open() throws Exception {
        liveConn = DriverManager.getConnection(url, user, password);
    }

    @Override
    public void loadAccounts(BiConsumer<String, Account> sink) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setAutoCommit(false);   // required for a streaming cursor
            try (var s = c.createStatement()) {
                s.execute("SET TRANSACTION READ ONLY");
                try { s.execute("SET yb_read_from_followers = true"); } catch (Exception notYugabyte) { c.rollback(); c.setAutoCommit(false); }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT account_id, currency, risk_level FROM bulk_ref.account")) {
                ps.setFetchSize(50_000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) sink.accept(rs.getString(1), new Account(rs.getString(2).trim(), rs.getString(3)));
                }
            }
            c.commit();
        }
    }

    @Override
    public synchronized Map<String, String> accountStatus(Collection<String> ids) {
        Map<String, String> out = new HashMap<>(ids.size() * 2);
        query("SELECT account_id, status FROM bulk_ref.account_status WHERE account_id = ANY(?)", ids,
                rs -> out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    @Override
    public synchronized Map<String, Boolean> processedRefs(Collection<String> refs) {
        Map<String, Boolean> out = new HashMap<>();
        query("SELECT external_ref FROM bulk_ref.processed_ref WHERE external_ref = ANY(?)", refs, rs -> out.put(rs.getString(1), Boolean.TRUE));
        return out;
    }

    interface Row { void accept(ResultSet rs) throws Exception; }

    private void query(String sql, Collection<String> keys, Row row) {
        try (PreparedStatement ps = liveConn.prepareStatement(sql)) {
            Array arr = liveConn.createArrayOf("text", keys.toArray());
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) row.accept(rs);
            }
        } catch (Exception e) {
            throw new IllegalStateException("reference lookup failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws Exception {
        if (liveConn != null) liveConn.close();
    }
}
