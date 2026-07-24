package com.jingcaicompass.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProviderMappingReviewController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class ProviderMappingReviewControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MatchMappingReviewService matchMappingReviewService;

    @Test
    void adminMappingPathsAreDeniedBeforeAuth() throws Exception {
        mockMvc.perform(post("/api/admin/provider/mappings/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()));
    }
}
