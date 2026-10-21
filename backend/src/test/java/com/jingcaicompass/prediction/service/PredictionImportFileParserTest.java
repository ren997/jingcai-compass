package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.prediction.dto.AsianModelOutputDto;
import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PredictionImportFileParserTest {

    private PredictionImportFileParser parser;

    @BeforeEach
    void setUp() {
        parser = new PredictionImportFileParserImpl(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void parsesStrictUtf8SampleAndHashesExactRawBytes() throws Exception {
        byte[] content = sampleBytes();

        PredictionImportBatchDto batch = parser.parse(content);

        assertThat(batch.generationBatchId()).isEqualTo("t302-offline-batch-001");
        assertThat(batch.generationBatchHash()).isEqualTo(sha256(content));
        assertThat(batch.predictions()).hasSize(2);
        assertThat(batch.predictions().get(0).handicapPick()).isEqualTo(HandicapPickEnum.HOME_WIN);
        assertThat(batch.predictions().get(1).confidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);
    }

    @ParameterizedTest
    @MethodSource("invalidJsonContents")
    void rejectsLooseOrInvalidJsonContract(String content) {
        assertInvalid(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsEmptyAndNonUtf8Files() {
        assertInvalid(new byte[0]);
        assertInvalid(new byte[] {(byte) 0xC3, (byte) 0x28});
    }

    @Test
    void parsesNestedAsianModelProbabilities() {
        PredictionImportBatchDto batch = parser.parse(asianModelSample(VALID_ASIAN_MODEL).getBytes(
                StandardCharsets.UTF_8));

        AsianModelOutputDto asian = batch.predictions().getFirst().asianModel();
        assertThat(asian.lambdaHome()).isEqualByComparingTo("1.250000");
        assertThat(asian.lambdaAway()).isEqualByComparingTo("0.950000");
        assertThat(asian.modelCoverProb()).isEqualByComparingTo("0.512345");
        assertThat(asian.edgeOver()).isEqualByComparingTo("0.037500");
        assertThat(asian.totalsDispersion()).isEqualByComparingTo("0.010500");
    }

    @ParameterizedTest
    @MethodSource("invalidAsianModelJsonContents")
    void rejectsAsianModelProbabilitiesOutsideStrictContract(String content) {
        assertInvalid(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String VALID_ASIAN_MODEL = "\"asianModel\":{"
            + "\"lambdaHome\":1.250000,\"lambdaAway\":0.950000,"
            + "\"modelCoverProb\":0.512345,\"marketCoverProb\":0.512345,\"edgeCover\":0.000000,"
            + "\"modelOverProb\":0.487500,\"marketOverProb\":0.450000,\"edgeOver\":0.037500,"
            + "\"handicapDispersion\":0.011800,\"totalsDispersion\":0.010500}";

    private static Stream<String> invalidAsianModelJsonContents() {
        return Stream.of(
                asianModelSample("\"asianModel\":[1.25,0.95]"),
                asianModelSample("\"asianModel\":\"1.250000\""),
                asianModelSample(VALID_ASIAN_MODEL.replace("\"lambdaHome\":1.250000",
                        "\"lambdaHome\":\"1.250000\"")),
                asianModelSample(VALID_ASIAN_MODEL.replace("\"edgeCover\":0.000000",
                        "\"edgeCover\":true"))
        );
    }

    private static String asianModelSample(String asianModelField) {
        return """
                {"generationBatchId":"asian-model-batch","predictions":[{
                "matchId":1,
                "modelVersion":"t306-asian-baseline-v6",
                "featureVersion":"t306-asian-mainline-v6",
                "analysisSummary":"亚盘分析",
                "generatedAt":"2026-07-26T00:00:00Z",
                "asianOddsSnapshotId":9001,
                "asianHandicapPick":"HOME_COVER",
                "totalGoalsPick":"UNDER",
                "asianHandicapConfidenceLevel":"HIGH",
                "totalGoalsConfidenceLevel":"MEDIUM",
                "predictionType":"ASIAN","""
                + asianModelField
                + "}]}";
    }

    private static Stream<String> invalidJsonContents() throws IOException {
        String valid = new String(sampleBytes(), StandardCharsets.UTF_8);
        return Stream.of(
                valid.replace(
                        "\"generationBatchId\": \"t302-offline-batch-001\",",
                        "\"generationBatchId\": \"t302-offline-batch-001\", \"unexpected\": true,"
                ),
                valid.replace(
                        "\"generationBatchId\": \"t302-offline-batch-001\",",
                        "\"generationBatchId\": \"t302-offline-batch-001\", "
                                + "\"generationBatchHash\": \"" + "a".repeat(64) + "\","
                ),
                valid.replace("\"homeWinProb\": 0.400000", "\"homeWinProb\": \"0.400000\""),
                valid.replace("\"expectedTotalGoals\": 2.50", "\"expectedTotalGoals\": \"25%\""),
                valid.replace("\"confidenceLevel\": \"HIGH\"", "\"confidenceLevel\": \"CERTAIN\""),
                "{\"generationBatchId\":\"empty\",\"predictions\":[]}",
                "{\"generationBatchId\":\"first\",\"generationBatchId\":\"second\",\"predictions\":[]}",
                valid + "{}"
        );
    }

    private void assertInvalid(byte[] content) {
        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
    }

    private static byte[] sampleBytes() throws IOException {
        try (var input = PredictionImportFileParserTest.class.getResourceAsStream(
                "/prediction/prediction-import-valid.json"
        )) {
            if (input == null) {
                throw new IOException("prediction import sample is missing");
            }
            return input.readAllBytes();
        }
    }

    private String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
