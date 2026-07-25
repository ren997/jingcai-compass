package com.jingcaicompass.admin.controller;

import com.jingcaicompass.match.dto.NormalizationBackfillRequestDto;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.match.vo.NormalizationBackfillResultVo;
import com.jingcaicompass.system.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双源流水线管理入口；T601 前仍由全局 Security 拒绝外部访问。
 */
@RestController
@RequestMapping("/api/admin/provider/pipeline")
public class DataPipelineAdminController {

    private final ObjectProvider<MatchNormalizationBackfillService> normalizationBackfillServiceProvider;

    public DataPipelineAdminController(
            ObjectProvider<MatchNormalizationBackfillService> normalizationBackfillServiceProvider
    ) {
        this.normalizationBackfillServiceProvider = normalizationBackfillServiceProvider;
    }

    /** 只回填已有比赛，不拉取 Provider。 */
    @PostMapping("/backfill")
    public ApiResponse<NormalizationBackfillResultVo> backfill(
            @Valid @RequestBody NormalizationBackfillRequestDto request
    ) {
        return ApiResponse.success(NormalizationBackfillResultVo.from(
                normalizationBackfillService().backfill(request.businessDate())
        ));
    }

    private MatchNormalizationBackfillService normalizationBackfillService() {
        return normalizationBackfillServiceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("normalization backfill requires a configured DataSource");
        });
    }
}
