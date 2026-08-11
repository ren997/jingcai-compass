package com.jingcaicompass.admin.mapper;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 草稿分页 SQL 的内部平铺投影，避免向接口暴露表结构。 */
@Data
public class AdminDraftPredictionRow {

    private Long predictionId;
    private String modelVersion;
    private String featureVersion;
    private String generationBatchId;
    private String generationBatchHash;
    private Integer predictionVersion;
    private BigDecimal homeWinProb;
    private BigDecimal drawProb;
    private BigDecimal awayWinProb;
    private HandicapPickEnum handicapPick;
    private BigDecimal expectedTotalGoals;
    private ConfidenceLevelEnum confidenceLevel;
    private String analysisSummary;
    private Instant generatedAt;
    private Long matchId;
    private LocalDate lotteryDate;
    private String lotteryMatchNo;
    private String leagueName;
    private String homeTeamName;
    private String awayTeamName;
    private Instant kickoffTime;
}
