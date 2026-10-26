import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** 一次性工具：把一份迁移 SQL 在单事务里跑一遍再回滚，用来在无 Docker 时验证语法与约束行为。 */
public final class MigrateDryRun {

    private static final String URL = "jdbc:postgresql://110.40.155.140:35432/jingcai_compass";
    private static final String USER = "postgres";
    private static final String PASSWORD = "CHANGE_ME_VIA_DB_PASSWORD";

    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                boolean hasResult = statement.execute(sql);
                int guard = 0;
                while (true) {
                    if (hasResult) {
                        try (ResultSet rs = statement.getResultSet()) {
                            print(rs);
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    hasResult = statement.getMoreResults();
                    if (++guard > 5000) {
                        break;
                    }
                }
            } catch (Exception exception) {
                System.out.println("FAILED: " + exception.getMessage());
                connection.rollback();
                return;
            }
            connection.rollback();
            System.out.println("OK: 全程成功并已回滚，未落库");
        }
    }

    private static void print(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        while (rs.next()) {
            StringBuilder line = new StringBuilder("  ");
            for (int i = 1; i <= columns; i++) {
                line.append(meta.getColumnLabel(i)).append('=').append(rs.getString(i));
                if (i < columns) {
                    line.append("  |  ");
                }
            }
            System.out.println(line);
        }
    }

    private MigrateDryRun() {
    }
}
