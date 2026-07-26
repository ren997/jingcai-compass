package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.service.AdminAuthService;
import com.jingcaicompass.admin.service.AdminAuthServiceImpl;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 16 验证管理员引导、登录、访问、锁定、审计和即时撤销闭环。 */
@Testcontainers
@ActiveProfiles("integration")
@AutoConfigureMockMvc
@SpringBootTest
class AdminAuthApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String USERNAME = "integration-admin";
    private static final String PASSWORD = "integration-password-123";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_admin_integration")
                    .withUsername("jingcai_test")
                    .withPassword("jingcai_test");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminAuthService adminAuthService;

    @Test
    void completesAdministratorAuthenticationAndRevocationLifecycle() throws Exception {
        // 1) PostgreSQL 上下文必须装配真实认证实现，禁止无数据库占位服务抢占
        assertThat(adminAuthService).isInstanceOf(AdminAuthServiceImpl.class);

        // 2) 首次引导只落 BCrypt 12 哈希，不保存明文
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM admin_accounts WHERE username = ?",
                String.class,
                USERNAME
        );
        assertThat(passwordHash)
                .isNotEqualTo(PASSWORD)
                .startsWith("$2a$12$");

        // 3) 登录后可访问后台，退出立即撤销旧 Token
        String firstToken = login(PASSWORD);
        mockMvc.perform(post("/api/admin/provider/mappings/list")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/auth/logout")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/provider/mappings/list")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));

        // 4) 新登录取得新版本 Token；连续五次错误密码触发 15 分钟锁定
        String secondToken = login(PASSWORD);
        assertThat(secondToken).isNotEqualTo(firstToken);
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/admin/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }

        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT failed_login_count FROM admin_accounts WHERE username = ?",
                Integer.class,
                USERNAME
        );
        OffsetDateTime lockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM admin_accounts WHERE username = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class),
                USERNAME
        );
        assertThat(failedCount).isEqualTo(5);
        assertThat(lockedUntil).isAfter(OffsetDateTime.now());

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        // 5) 成功、失败、退出和旧 Token 拒绝均留下追加式审计
        Integer auditCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE target_type IN ('ADMIN_ACCOUNT', 'SECURITY_REQUEST')
                  AND action_type IN (
                    'ADMIN_BOOTSTRAP',
                    'LOGIN_SUCCESS',
                    'LOGIN_FAILED',
                    'LOGOUT',
                    'ACCESS_DENIED'
                  )
                """,
                Integer.class
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(10);
    }

    private String login(String password) throws Exception {
        String response = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("accessToken").asText();
    }

    private String loginJson(String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(USERNAME, password));
    }

    private record LoginRequest(String username, String password) {
    }
}
