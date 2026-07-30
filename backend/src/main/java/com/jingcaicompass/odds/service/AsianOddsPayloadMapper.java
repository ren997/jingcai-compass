package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 亚盘原始 JSON → 比赛盘口列表。 */
@Component
public class AsianOddsPayloadMapper {

    private final ObjectMapper objectMapper;

    public AsianOddsPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<AsianOddsMatchOddsDto> parseMatches(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root == null || root.isNull()) {
                return List.of();
            }
            if (root.isArray()) {
                return readList(root);
            }
            if (root.isObject() && root.has("matches")) {
                return readList(root.get("matches"));
            }
            if (root.isObject() && "THE_ODDS_API".equals(root.path("provider").asText())
                    && root.has("responses")) {
                return parseTheOddsResponses(root);
            }
            // RawDataPayloadService 对非对象 JSON 的兜底包装
            if (root.isObject() && root.has("raw") && root.get("raw").isTextual()) {
                return parseMatches(root.get("raw").asText());
            }
            return List.of();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_PARSE_FAILED, "亚盘载荷解析失败", exception);
        }
    }

    private List<AsianOddsMatchOddsDto> readList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<AsianOddsMatchOddsDto> matches = objectMapper.convertValue(node, new TypeReference<>() {
        });
        return matches == null ? List.of() : List.copyOf(matches);
    }

    private List<AsianOddsMatchOddsDto> parseTheOddsResponses(JsonNode root) {
        OffsetDateTime from = parseTime(root.path("kickoffFrom"));
        OffsetDateTime to = parseTime(root.path("kickoffTo"));
        String bookmakerCode = text(root, "bookmakerCode");
        List<AsianOddsMatchOddsDto> matches = new ArrayList<>();
        JsonNode responses = root.path("responses");
        if (!responses.isArray()) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_PARSE_FAILED, "The Odds API 响应聚合格式无效");
        }
        for (JsonNode response : responses) {
            String sportKey = text(response, "sportKey");
            JsonNode events = response.path("body");
            if (!events.isArray()) {
                throw new BusinessException(ErrorCode.DATA_SOURCE_PARSE_FAILED, "The Odds API 赛事响应不是数组");
            }
            for (JsonNode event : events) {
                AsianOddsMatchOddsDto match = toTheOddsMatch(event, sportKey, bookmakerCode);
                if (isWithinWindow(match.kickoffTime(), from, to)) {
                    matches.add(match);
                }
            }
        }
        return List.copyOf(matches);
    }

    private AsianOddsMatchOddsDto toTheOddsMatch(
            JsonNode event,
            String fallbackSportKey,
            String requestedBookmaker
    ) {
        Set<String> errors = new LinkedHashSet<>();
        String providerMatchId = text(event, "id");
        String homeTeamName = text(event, "home_team");
        String awayTeamName = text(event, "away_team");
        OffsetDateTime kickoffTime = parseTime(event.path("commence_time"));
        String sportKey = text(event, "sport_key");
        if (!StringUtils.hasText(sportKey)) {
            sportKey = fallbackSportKey;
        }
        if (!StringUtils.hasText(providerMatchId)) {
            errors.add("MISSING_EVENT_ID");
        }
        if (!StringUtils.hasText(sportKey)) {
            errors.add("MISSING_SPORT_KEY");
        }
        if (!StringUtils.hasText(homeTeamName) || !StringUtils.hasText(awayTeamName)) {
            errors.add("MISSING_TEAM_NAME");
        }
        if (kickoffTime == null) {
            errors.add("MISSING_KICKOFF_TIME");
        }

        List<AsianOddsLineDto> lines = new ArrayList<>();
        JsonNode bookmakers = event.path("bookmakers");
        if (bookmakers.isArray()) {
            for (JsonNode bookmaker : bookmakers) {
                String bookmakerCode = text(bookmaker, "key");
                if (!StringUtils.hasText(bookmakerCode)
                        || (StringUtils.hasText(requestedBookmaker)
                        && !requestedBookmaker.equalsIgnoreCase(bookmakerCode))) {
                    continue;
                }
                OffsetDateTime bookmakerUpdatedAt = parseTime(bookmaker.path("last_update"));
                JsonNode markets = bookmaker.path("markets");
                if (!markets.isArray()) {
                    errors.add("INVALID_MARKETS");
                    continue;
                }
                for (JsonNode market : markets) {
                    if (!"spreads".equals(market.path("key").asText())) {
                        continue;
                    }
                    OffsetDateTime providerUpdatedAt = parseTime(market.path("last_update"));
                    if (providerUpdatedAt == null) {
                        providerUpdatedAt = bookmakerUpdatedAt;
                    }
                    AsianOddsLineDto line = toSpreadLine(
                            market.path("outcomes"),
                            bookmakerCode,
                            homeTeamName,
                            awayTeamName,
                            providerUpdatedAt,
                            errors
                    );
                    if (line != null) {
                        lines.add(line);
                    }
                }
            }
        }
        boolean live = event.path("live").asBoolean(false);
        return new AsianOddsMatchOddsDto(
                providerMatchId,
                homeTeamName,
                awayTeamName,
                kickoffTime,
                live,
                List.copyOf(lines),
                sportKey,
                errors.isEmpty() ? null : String.join(",", errors)
        );
    }

    private AsianOddsLineDto toSpreadLine(
            JsonNode outcomes,
            String bookmakerCode,
            String homeTeamName,
            String awayTeamName,
            OffsetDateTime providerUpdatedAt,
            Set<String> errors
    ) {
        if (!outcomes.isArray()) {
            errors.add("INVALID_SPREAD_OUTCOMES");
            return null;
        }
        JsonNode home = findOutcome(outcomes, homeTeamName);
        JsonNode away = findOutcome(outcomes, awayTeamName);
        BigDecimal homePoint = decimal(home, "point");
        BigDecimal awayPoint = decimal(away, "point");
        BigDecimal homePrice = decimal(home, "price");
        BigDecimal awayPrice = decimal(away, "price");
        if (homePoint == null || awayPoint == null || homePrice == null || awayPrice == null) {
            errors.add("INCOMPLETE_SPREAD_PAIR");
            return null;
        }
        if (homePoint.add(awayPoint).compareTo(BigDecimal.ZERO) != 0) {
            errors.add("INCONSISTENT_SPREAD_PAIR");
            return null;
        }
        return new AsianOddsLineDto(
                bookmakerCode,
                homePoint,
                homePrice,
                awayPrice,
                null,
                null,
                null,
                providerUpdatedAt
        );
    }

    private JsonNode findOutcome(JsonNode outcomes, String teamName) {
        if (!StringUtils.hasText(teamName)) {
            return null;
        }
        for (JsonNode outcome : outcomes) {
            if (teamName.equals(outcome.path("name").asText())) {
                return outcome;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String name) {
        if (node == null || node.isNull() || !node.hasNonNull(name)) {
            return null;
        }
        try {
            return new BigDecimal(node.get(name).asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static OffsetDateTime parseTime(JsonNode node) {
        if (node == null || node.isNull() || !StringUtils.hasText(node.asText())) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static boolean isWithinWindow(OffsetDateTime kickoff, OffsetDateTime from, OffsetDateTime to) {
        return kickoff == null
                || ((from == null || !kickoff.isBefore(from)) && (to == null || !kickoff.isAfter(to)));
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node == null ? null : node.get(fieldName);
        return field == null || field.isNull() ? null : field.asText(null);
    }
}
