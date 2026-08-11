package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.admin.service.AdminDraftPredictionQueryService;
import com.jingcaicompass.admin.vo.AdminDraftPredictionPageVo;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminDraftPredictionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminDraftPredictionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminDraftPredictionQueryService queryService;

    @Test
    void listReturnsTraceableDraftPageAndValidatesPagination() throws Exception {
        when(queryService.list(any())).thenReturn(new AdminDraftPredictionPageVo(List.of(), 1, 20, 0));

        mockMvc.perform(post("/api/admin/predictions/drafts/list")
                        .header("X-Trace-Id", "draft-list-trace")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.records").isArray());
        mockMvc.perform(post("/api/admin/predictions/drafts/list")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pageNo\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
    }
}
