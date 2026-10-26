import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.zip.CRC32;

/**
 * 一次性工具：核对生产库已应用的 V29 与本地文件是否仍是同一份。
 *
 * <p>存在理由：V29 落库之后又改过列类型（CHAR → VARCHAR）。若改动发生在应用之后，
 * {@code flyway_schema_history.checksum} 与文件就对不上，下次启动会在 validate 阶段直接失败——
 * 而这个失败发生在 Web 服务器启动之前，看起来像「应用起不来」，实际是迁移历史被篡改。</p>
 */
public final class V29ChecksumCheck {

    private static final String URL = "jdbc:postgresql://110.40.155.140:35432/jingcai_compass";
    private static final String USER = "postgres";
    private static final String PASSWORD = "CHANGE_ME_VIA_DB_PASSWORD";

    public static void main(String[] args) throws Exception {
        // Flyway ChecksumCalculator：按行读取（行尾被 readLine 吃掉），逐行 update，最后取 CRC32 值的 int。
        CRC32 crc = new CRC32();
        try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                first = false;
                crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        int local = (int) crc.getValue();
        System.out.println("local file version = " + args[0]);
        System.out.println("local computed checksum = " + local);

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT version, checksum, success, installed_on "
                            + "FROM flyway_schema_history WHERE version = '29'")) {
                while (rs.next()) {
                    int db = rs.getInt("checksum");
                    System.out.println("db checksum = " + db + ", success = " + rs.getBoolean("success")
                            + ", installed_on = " + rs.getTimestamp("installed_on"));
                    System.out.println(db == local ? "MATCH -> 文件与已应用版本一致，无需 repair"
                            : "MISMATCH -> 需要 flyway repair 或把文件回滚到已应用版本");
                }
            }

            System.out.println("--- 三列实际类型（应为 character varying，若是 character 则库里是旧版）---");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT table_name, column_name, data_type, character_maximum_length "
                            + "FROM information_schema.columns "
                            + "WHERE table_name IN ('odds_history_archive','backtest_runs','backtest_cases','backtest_metrics') "
                            + "AND column_name IN ('run_key','group_key','payload_hash') "
                            + "ORDER BY table_name, column_name")) {
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1) + "." + rs.getString(2)
                            + " -> " + rs.getString(3) + "(" + rs.getInt(4) + ")");
                }
            }
        }
    }
}
