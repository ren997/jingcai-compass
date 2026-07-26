package com.jingcaicompass.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.service.AdminSecurityAuditService;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            ObjectProvider<AdminSecurityAuditService> securityAuditServiceProvider
    ) throws Exception {
        JwtAuthenticationConverter authenticationConverter = jwtAuthenticationConverter();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**", "/actuator/info"
                        ).permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            recordDenied(
                                    securityAuditServiceProvider,
                                    request,
                                    null,
                                    ErrorCode.AUTH_UNAUTHORIZED.code()
                            );
                            writeFailure(response, objectMapper, ErrorCode.AUTH_UNAUTHORIZED);
                        }))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) -> {
                            recordDenied(
                                    securityAuditServiceProvider,
                                    request,
                                    null,
                                    ErrorCode.AUTH_UNAUTHORIZED.code()
                            );
                            writeFailure(response, objectMapper, ErrorCode.AUTH_UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            recordDenied(
                                    securityAuditServiceProvider,
                                    request,
                                    org.springframework.security.core.context.SecurityContextHolder
                                            .getContext()
                                            .getAuthentication(),
                                    ErrorCode.ACCESS_DENIED.code()
                            );
                            writeFailure(response, objectMapper, ErrorCode.ACCESS_DENIED);
                        })
                )
                .build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("username");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null || role.isBlank()
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    private void recordDenied(
            ObjectProvider<AdminSecurityAuditService> securityAuditServiceProvider,
            jakarta.servlet.http.HttpServletRequest request,
            org.springframework.security.core.Authentication authentication,
            String reason
    ) {
        AdminSecurityAuditService service = securityAuditServiceProvider.getIfAvailable();
        if (service != null) {
            service.recordAccessDenied(request, authentication, reason);
        }
    }

    private void writeFailure(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ErrorCode errorCode
    ) throws java.io.IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(errorCode)
        );
    }
}
