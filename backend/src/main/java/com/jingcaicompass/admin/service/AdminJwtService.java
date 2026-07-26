package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** 为已完成账号认证的管理员签发短期 HS256 JWT。 */
@Component
@ConditionalOnBean(DataSource.class)
public class AdminJwtService {

    public static final String TOKEN_TYPE = "Bearer";

    private final JwtEncoder jwtEncoder;
    private final AdminSecurityProperties properties;
    private final Clock clock;

    public AdminJwtService(
            JwtEncoder jwtEncoder,
            AdminSecurityProperties properties,
            Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** 签发包含账号版本、角色和标准时间声明的管理员访问令牌。 */
    public IssuedToken issue(AdminAccount account) {
        // 1) 使用注入 Clock 固定签发与过期时间，便于验证时间边界
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.jwt().accessTokenTtl());

        // 2) 写入最小身份声明，不包含密码、哈希或其他敏感配置
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(String.valueOf(account.getId()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("username", account.getUsername())
                .claim("role", account.getRoleCode().getCode())
                .claim("tokenVersion", account.getTokenVersion())
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** 内部签发结果，不作为 HTTP 模型暴露。 */
    public record IssuedToken(String accessToken, Instant expiresAt) {
    }
}
