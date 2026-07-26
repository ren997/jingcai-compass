package com.jingcaicompass.system.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 管理员 JWT、登录锁定和首次账号引导配置。 */
@Validated
@ConfigurationProperties("app.security")
public record AdminSecurityProperties(
        @Valid @NotNull JwtProperties jwt,
        @Valid @NotNull LoginProperties login,
        @Valid @NotNull BootstrapProperties bootstrap
) {

    @Override
    public String toString() {
        return "AdminSecurityProperties[jwt=" + jwt
                + ", login=" + login
                + ", bootstrap=" + bootstrap + "]";
    }

    public record JwtProperties(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl
    ) {

        @AssertTrue(message = "app.security.jwt.access-token-ttl must be between 1 minute and 24 hours")
        public boolean isAccessTokenTtlValid() {
            return accessTokenTtl != null
                    && accessTokenTtl.compareTo(Duration.ofMinutes(1)) >= 0
                    && accessTokenTtl.compareTo(Duration.ofHours(24)) <= 0;
        }

        @Override
        public String toString() {
            return "JwtProperties[secret=***, issuer=" + issuer
                    + ", audience=" + audience
                    + ", accessTokenTtl=" + accessTokenTtl + "]";
        }
    }

    public record LoginProperties(
            @Min(1) int maxFailedAttempts,
            @NotNull Duration lockDuration
    ) {

        @AssertTrue(message = "app.security.login.lock-duration must be between 1 minute and 24 hours")
        public boolean isLockDurationValid() {
            return lockDuration != null
                    && lockDuration.compareTo(Duration.ofMinutes(1)) >= 0
                    && lockDuration.compareTo(Duration.ofHours(24)) <= 0;
        }
    }

    public record BootstrapProperties(
            String username,
            String password
    ) {

        @Override
        public String toString() {
            return "BootstrapProperties[username=" + username + ", password=***]";
        }
    }
}
