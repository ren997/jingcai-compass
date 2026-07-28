package com.jingcaicompass.snapshot.dto;

import java.io.InputStream;
import java.time.LocalDate;

/** 已校验、可由公开 Controller 流式输出的快照对象。 */
public record PublicPredictionSnapshotDownloadDto(
        Long snapshotId,
        LocalDate snapshotDate,
        Integer snapshotVersion,
        String contentType,
        Long contentLength,
        InputStream content
) {
}
