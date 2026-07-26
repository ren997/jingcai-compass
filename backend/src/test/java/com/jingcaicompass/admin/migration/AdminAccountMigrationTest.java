package com.jingcaicompass.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V8 管理员账号、锁定和 Token 撤销约束。 */
class AdminAccountMigrationTest {

    private static final String MIGRATION = "db/migration/V8__init_admin_accounts.sql";

    @Test
    void definesAdministratorSecurityContract() throws IOException {
        String sql = new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE admin_accounts");
        assertThat(sql).contains(
                "username",
                "password_hash",
                "role_code",
                "account_status",
                "failed_login_count",
                "locked_until",
                "token_version",
                "last_login_at",
                "password_updated_at"
        );
        assertThat(sql).contains("username = LOWER(BTRIM(username))");
        assertThat(sql).contains("CHAR_LENGTH(username) BETWEEN 3 AND 64");
        assertThat(sql).contains("role_code IN ('ADMIN')");
        assertThat(sql).contains("account_status IN ('ACTIVE', 'DISABLED')");
        assertThat(sql).contains("failed_login_count >= 0");
        assertThat(sql).contains("token_version >= 0");
        assertThat(sql).contains("CREATE UNIQUE INDEX uk_admin_accounts_username_ci");
        assertThat(sql).contains("ON admin_accounts (LOWER(username))");
        assertThat(sql).contains("CREATE INDEX idx_admin_accounts_status");
        assertThat(sql).contains("COMMENT ON TABLE admin_accounts");
        assertThat(sql).contains("COMMENT ON COLUMN admin_accounts.token_version");
        assertThat(sql).doesNotContain("CREATE TRIGGER");
    }
}
