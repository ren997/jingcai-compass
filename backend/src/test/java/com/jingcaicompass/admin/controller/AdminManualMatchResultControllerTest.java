package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.dto.AdminManualMatchResultDto;
import com.jingcaicompass.admin.service.AdminManualMatchResultService;
import com.jingcaicompass.admin.vo.AdminManualMatchResultVo;
import com.jingcaicompass.admin.vo.AdminManualResultSettlementVo;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminManualMatchResultController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminManualMatchResultControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AdminManualMatchResultService manualMatchResultService;

    @Test
    void acceptsConfirmedResultWithJwtOperatorAndReturnsTraceId() throws Exception {
        AdminManualMatchResultDto request = request(true);
        when(manualMatchResultService.record(any(), eq("admin-1"))).thenReturn(result());
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("1")
                .claim("username", "admin-1").claim("role", "ADMIN").build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/manual-results")
                            .header(TraceIdContext.HEADER_NAME, "manual-result-test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.data.resultSource").value("MANUAL"));
            verify(manualMatchResultService).record(request, "admin-1");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectsMissingSecondConfirmationWithTraceId() throws Exception {
        mockMvc.perform(post("/api/admin/manual-results")
                        .header(TraceIdContext.HEADER_NAME, "manual-confirmation-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private AdminManualMatchResultDto request(boolean confirmed) {
        return new AdminManualMatchResultDto(7L, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED,
                2, 1, "人工来源", "开发补录", confirmed);
    }

    private AdminManualMatchResultVo result() {
        return new AdminManualMatchResultVo(101L, 1, "APPENDED", MatchResultFactSourceEnum.MANUAL,
                MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, 2, 1, "人工来源", "开发补录",
                "admin-1", Instant.parse("2026-08-10T02:00:00Z"), true,
                new AdminManualResultSettlementVo(0, 0, 0, 0, 0, 1, 1, 2, 0, 0));
    }
}
