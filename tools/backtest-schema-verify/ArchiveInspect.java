import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** 一次性排查：odds-history 的 markets 节点到底用什么标识玩法。 */
public final class ArchiveInspect {

    private static final String URL = require("DB_URL");
    private static final String USER = require("DB_USER");
    private static final String PASSWORD = require("DB_PASSWORD");

    private static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + key + "（例：DB_URL=jdbc:postgresql://host:port/db）");
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FILTER (WHERE payload::text LIKE '%marketId%'), count(*) "
                            + "FROM odds_history_archive")) {
                while (rs.next()) {
                    System.out.println("含 marketId 的归档行: " + rs.getInt(1) + " / " + rs.getInt(2));
                }
            }

            System.out.println("=== 第一个 market 节点（去掉 options）===");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT (jsonb_path_query_first(payload, '$.data.providers[0].markets[0]') - 'options')::text "
                            + "FROM odds_history_archive ORDER BY external_match_id LIMIT 2")) {
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1));
                }
            }

            System.out.println("=== market 节点出现过的键名 ===");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT DISTINCT jsonb_object_keys(m) "
                            + "FROM odds_history_archive, "
                            + "jsonb_array_elements(payload -> 'data' -> 'providers') p, "
                            + "jsonb_array_elements(p -> 'markets') m")) {
                while (rs.next()) {
                    System.out.println("  key: " + rs.getString(1));
                }
            }

            System.out.println("=== market.name 取值 ===");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT DISTINCT m ->> 'name' "
                            + "FROM odds_history_archive, "
                            + "jsonb_array_elements(payload -> 'data' -> 'providers') p, "
                            + "jsonb_array_elements(p -> 'markets') m LIMIT 10")) {
                while (rs.next()) {
                    System.out.println("  name: " + rs.getString(1));
                }
            }

            System.out.println("=== provider 节点键名 ===");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT DISTINCT jsonb_object_keys(p) "
                            + "FROM odds_history_archive, jsonb_array_elements(payload -> 'data' -> 'providers') p")) {
                while (rs.next()) {
                    System.out.println("  provider key: " + rs.getString(1));
                }
            }
        }
    }
}
