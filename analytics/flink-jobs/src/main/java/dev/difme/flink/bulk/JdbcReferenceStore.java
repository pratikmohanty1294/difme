package dev.difme.flink.bulk;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * YugabyteDB YSQL (PostgreSQL wire) implementation. Static reads hit follower replicas; every query takes a whole
 * batch of keys via {@code = ANY(?)}. One connection per lookup kind, used from its BatchLookup thread only.
 */
public class JdbcReferenceStore implements ReferenceStore {
    private final String url, user, password;
    private transient Connection staticConn, liveConn;

    public JdbcReferenceStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void open() throws Exception {
        staticConn = DriverManager.getConnection(url, user, password);
        try (var s = staticConn.createStatement()) {
            s.execute("SET default_transaction_read_only = true");
            try { s.execute("SET yb_read_from_followers = true"); } catch (Exception notYugabyte) { /* plain PostgreSQL in CI */ }
        }
        liveConn = DriverManager.getConnection(url, user, password);
    }

    @Override
    public synchronized Map<String, Account> accounts(Collection<String> ids) {
        Map<String, Account> out = new HashMap<>();
        query(staticConn, "SELECT account_id, currency, risk_level FROM bulk_ref.account WHERE account_id = ANY(?)", ids,
                rs -> out.put(rs.getString(1), new Account(rs.getString(1), rs.getString(2), rs.getString(3))));
        return out;
    }

    @Override
    public synchronized Map<String, String> accountStatus(Collection<String> ids) {
        Map<String, String> out = new HashMap<>();
        query(liveConn, "SELECT account_id, status FROM bulk_ref.account_status WHERE account_id = ANY(?)", ids,
                rs -> out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    @Override
    public synchronized Map<String, Boolean> processedRefs(Collection<String> refs) {
        Map<String, Boolean> out = new HashMap<>();
        query(liveConn, "SELECT external_ref FROM bulk_ref.processed_ref WHERE external_ref = ANY(?)", refs,
                rs -> out.put(rs.getString(1), Boolean.TRUE));
        return out;
    }

    interface Row { void accept(ResultSet rs) throws Exception; }

    private static void query(Connection c, String sql, Collection<String> keys, Row row) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Array arr = c.createArrayOf("text", keys.toArray());
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
        if (staticConn != null) staticConn.close();
        if (liveConn != null) liveConn.close();
    }
}
