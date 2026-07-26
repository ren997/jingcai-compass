package com.jingcaicompass.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.entity.AdminAccount;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminRoleEnum;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.admin.service.AdminAccountTokenValidator;
import com.jingcaicompass.admin.service.AdminJwtService;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class AdminJwtConfigurationTest {

    private static final String SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void usesBcryptStrengthTwelve() {
        PasswordEncoder encoder = new AdminJwtConfiguration().adminPasswordEncoder();

        String hash = encoder.encode("administrator-password");

        assertThat(hash).startsWith("$2a$12$");
        assertThat(encoder.matches("administrator-password", hash)).isTrue();
    }

    @Test
    void issuesRequiredClaimsAndValidatesDatabaseTokenVersion() {
        Instant now = Instant.now();
        AdminAccount account = account();
        AdminAccountMapper mapper = mock(AdminAccountMapper.class);
        when(mapper.selectById(7L)).thenReturn(account);
        AdminSecurityProperties properties = properties("issuer", "audience");
        AdminJwtConfiguration configuration = new AdminJwtConfiguration();
        JwtEncoder encoder = configuration.adminJwtEncoder(properties);
        JwtDecoder decoder = configuration.adminJwtDecoder(
                properties,
                provider(new AdminAccountTokenValidator(mapper))
        );
        AdminJwtService jwtService = new AdminJwtService(
                encoder,
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        AdminJwtService.IssuedToken issued = jwtService.issue(account);
        Jwt jwt = decoder.decode(issued.accessToken());

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("admin");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.<Number>getClaim("tokenVersion").longValue()).isEqualTo(2L);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("issuer");
        assertThat(jwt.getAudience()).containsExactly("audience");
        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));

        account.setAccountStatus(AdminAccountStatusEnum.DISABLED);
        assertThatThrownBy(() -> decoder.decode(issued.accessToken()))
                .isInstanceOf(JwtException.class);

        account.setAccountStatus(AdminAccountStatusEnum.ACTIVE);
        account.setTokenVersion(3L);
        assertThatThrownBy(() -> decoder.decode(issued.accessToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredTamperedAndWrongIssuerOrAudienceTokens() {
        Instant oldTime = Instant.now().minus(Duration.ofHours(2));
        AdminAccount account = account();
        AdminAccountMapper mapper = mock(AdminAccountMapper.class);
        when(mapper.selectById(7L)).thenReturn(account);
        AdminSecurityProperties properties = properties("issuer", "audience");
        AdminJwtConfiguration configuration = new AdminJwtConfiguration();
        JwtEncoder encoder = configuration.adminJwtEncoder(properties);
        AdminJwtService jwtService = new AdminJwtService(
                encoder,
                properties,
                Clock.fixed(oldTime, ZoneOffset.UTC)
        );
        String expiredToken = jwtService.issue(account).accessToken();

        JwtDecoder decoder = configuration.adminJwtDecoder(
                properties,
                provider(new AdminAccountTokenValidator(mapper))
        );
        assertThatThrownBy(() -> decoder.decode(expiredToken)).isInstanceOf(JwtException.class);

        String validToken = new AdminJwtService(
                encoder,
                properties,
                Clock.fixed(Instant.now(), ZoneOffset.UTC)
        ).issue(account).accessToken();

        String[] parts = validToken.split("\\.");
        parts[2] = (parts[2].startsWith("a") ? "b" : "a") + parts[2].substring(1);
        String tampered = String.join(".", parts);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);

        JwtDecoder wrongIssuer = configuration.adminJwtDecoder(
                properties("another-issuer", "audience"),
                provider(new AdminAccountTokenValidator(mapper))
        );
        assertThatThrownBy(() -> wrongIssuer.decode(validToken)).isInstanceOf(JwtException.class);

        JwtDecoder wrongAudience = configuration.adminJwtDecoder(
                properties("issuer", "another-audience"),
                provider(new AdminAccountTokenValidator(mapper))
        );
        assertThatThrownBy(() -> wrongAudience.decode(validToken)).isInstanceOf(JwtException.class);
    }

    private AdminAccount account() {
        AdminAccount account = new AdminAccount();
        account.setId(7L);
        account.setUsername("admin");
        account.setRoleCode(AdminRoleEnum.ADMIN);
        account.setAccountStatus(AdminAccountStatusEnum.ACTIVE);
        account.setTokenVersion(2L);
        return account;
    }

    private AdminSecurityProperties properties(String issuer, String audience) {
        return new AdminSecurityProperties(
                new AdminSecurityProperties.JwtProperties(
                        SECRET,
                        issuer,
                        audience,
                        Duration.ofMinutes(30)
                ),
                new AdminSecurityProperties.LoginProperties(5, Duration.ofMinutes(15)),
                new AdminSecurityProperties.BootstrapProperties("", "")
        );
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<AdminAccountTokenValidator> provider(
            AdminAccountTokenValidator validator
    ) {
        ObjectProvider<AdminAccountTokenValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);
        return provider;
    }
}
