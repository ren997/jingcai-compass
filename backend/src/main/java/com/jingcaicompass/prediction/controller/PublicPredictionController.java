package com.jingcaicompass.prediction.controller;

import com.jingcaicompass.prediction.dto.PredictionDetailQueryDto;
import com.jingcaicompass.prediction.service.PublicPredictionQueryService;
import com.jingcaicompass.prediction.vo.PredictionDetailVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVerificationVo;
import com.jingcaicompass.snapshot.dto.PublicPredictionSnapshotDownloadDto;
import com.jingcaicompass.system.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 面向公共比赛详情的预测透明信息与快照读取接口。 */
@RestController
@Validated
@RequestMapping("/api/public/predictions")
public class PublicPredictionController {

    private final PublicPredictionQueryService publicPredictionQueryService;

    public PublicPredictionController(PublicPredictionQueryService publicPredictionQueryService) {
        this.publicPredictionQueryService = publicPredictionQueryService;
    }

    /** 返回指定比赛全部模型的当前公开预测与版本替代链。 */
    @PostMapping("/detail")
    public ApiResponse<PredictionDetailVo> detail(
            @Valid @RequestBody PredictionDetailQueryDto query
    ) {
        return ApiResponse.success(publicPredictionQueryService.detail(query));
    }

    /** 流式下载校验通过的已发布快照，不暴露存储地址。 */
    @GetMapping("/snapshots/{snapshotId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable @Positive Long snapshotId
    ) {
        PublicPredictionSnapshotDownloadDto snapshot = publicPredictionQueryService.openSnapshot(snapshotId);
        String filename = "prediction-snapshot-"
                + snapshot.snapshotDate()
                + "-v"
                + String.format(java.util.Locale.ROOT, "%06d", snapshot.snapshotVersion())
                + ".json";
        StreamingResponseBody body = outputStream -> copyAndClose(snapshot.content(), outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(snapshot.contentType()))
                .contentLength(snapshot.contentLength())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(body);
    }

    /** 校验已发布快照当前对象是否仍与数据库记录的哈希和长度一致。 */
    @PostMapping("/snapshots/{snapshotId}/verify")
    public ApiResponse<PredictionSnapshotVerificationVo> verify(
            @PathVariable @Positive Long snapshotId
    ) {
        return ApiResponse.success(publicPredictionQueryService.verifySnapshot(snapshotId));
    }

    private void copyAndClose(InputStream input, java.io.OutputStream output) throws IOException {
        try (input) {
            input.transferTo(output);
        }
    }
}
