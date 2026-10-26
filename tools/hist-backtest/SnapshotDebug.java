import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.odds.service.OddsHistorySnapshotBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

/**
 * 一次性排查：直接拿生产 {@link OddsHistorySnapshotBuilder} 跑一条已归档载荷。
 *
 * <p>为什么不用单测：切片为空的三种原因（市场没被识别、两侧不成对、价格不可用）
 * 在真实上游载荷里长得一模一样，只有把真实字节喂进去才能分辨。</p>
 */
public final class SnapshotDebug {

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
        String decisionAtText = args.length > 0 ? args[0] : "2026-10-20T15:00:00Z";
        Instant decisionAt = Instant.parse(decisionAtText);
        ObjectMapper mapper = new ObjectMapper();
        OddsHistorySnapshotBuilder builder = new OddsHistorySnapshotBuilder();

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT external_match_id, match_id, kickoff_time, payload::text, payload_hash "
                            + "FROM odds_history_archive ORDER BY external_match_id LIMIT 3")) {
                while (rs.next()) {
                    String externalId = rs.getString(1);
                    Long matchId = rs.getLong(2);
                    JsonNode payload = mapper.readTree(rs.getString(4));
                    System.out.println("=== " + externalId + " kickoff=" + rs.getTimestamp(3)
                            + " matchId=" + matchId + " decisionAt=" + decisionAt);
                    System.out.println("    顶层键: " + names(payload));

                    JsonNode data = payload.path("data").isObject() ? payload.path("data") : payload;
                    System.out.println("    data 键: " + names(data));
                    JsonNode providers = data.path("providers");
                    System.out.println("    providers 数: " + providers.size());
                    if (providers.size() > 0) {
                        JsonNode first = providers.get(0);
                        System.out.println("    provider[0].name=" + first.path("name").asText()
                                + " markets=" + first.path("markets").size());
                        JsonNode market = first.path("markets").get(0);
                        System.out.println("    market[0].marketId=" + market.path("marketId").asInt(-1)
                                + " name=" + market.path("name").asText()
                                + " options=" + market.path("options").size());
                        JsonNode option = market.path("options").get(0);
                        System.out.println("    option[0] line=" + option.path("line").asText()
                                + " option=" + option.path("option").asText()
                                + " points=" + option.path("points").size()
                                + " 首点=" + option.path("points").get(0));
                    }

                    var snapshot = builder.build(payload, decisionAt, matchId, "NOVA_API", rs.getString(5));
                    System.out.println("    -> rows=" + snapshot.rows().size()
                            + " handicapQuotes=" + snapshot.handicapQuotes().size()
                            + " totalsQuotes=" + snapshot.totalsQuotes().size()
                            + " bookmakers=" + snapshot.bookmakerCount()
                            + " points=" + snapshot.pointCount()
                            + " lastChangeAt=" + snapshot.lastChangeAt()
                            + " empty=" + snapshot.isEmpty());
                    System.out.println();
                }
            }
        }
    }

    private static String names(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        node.fieldNames().forEachRemaining(name -> builder.append(name).append(' '));
        return builder.toString().trim();
    }
}
