package clre20.dailyQuest.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * SQLite：plugins/DailyQuest/data.db，一張表、一條連線，只在 I/O 執行緒使用。
 * 一律使用 PreparedStatement。Paper 伺服器本身附帶 SQLite 驅動。
 */
public final class SqliteStorage implements Storage {

    private final Path file;
    private Connection conn;

    public SqliteStorage(Path dataFolder) {
        this.file = dataFolder.resolve("data.db");
    }

    @Override
    public void init() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS dq_players ("
                    + "uuid TEXT PRIMARY KEY, data TEXT NOT NULL, last_seen INTEGER NOT NULL)");
        }
    }

    @Override
    public String load(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM dq_players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Override
    public void save(UUID uuid, String data, long lastSeenDay) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO dq_players (uuid, data, last_seen) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET data = excluded.data, last_seen = excluded.last_seen")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, data);
            ps.setLong(3, lastSeenDay);
            ps.executeUpdate();
        }
    }

    @Override
    public int purge(long beforeDay) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM dq_players WHERE last_seen < ?")) {
            ps.setLong(1, beforeDay);
            return ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 關服中
            }
        }
    }
}
