package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.ChinaSportteryResponseDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 体彩比赛池载荷解析：原始 JSON → 同步写库条目（含 HAD/HHAD SP）。
 */
@Component
public class SportteryPoolPayloadMapper {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper;

    public SportteryPoolPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析指定业务日的比赛条目；接口失败抛业务异常，无分组则返回空列表。
     */
    public List<SportteryPoolSyncItemDto> parseItems(String payloadJson, LocalDate businessDate) {
        // 1) 反序列化并校验 success
        ChinaSportteryResponseDto response = readResponse(payloadJson);
        if (!response.success()) {
            throw new SportteryDataAccessException(
                    "中国体彩网比赛池接口返回失败：%s %s".formatted(
                            response.errorCode(), response.errorMessage()
                    )
            );
        }
        if (response.value() == null || response.value().matchInfoList() == null) {
            return List.of();
        }

        // 2) 只保留目标 businessDate 分组，逐场映射为 SyncItem
        List<SportteryPoolSyncItemDto> items = new ArrayList<>();
        for (ChinaSportteryResponseDto.MatchGroupDto group : response.value().matchInfoList()) {
            if (group == null || !businessDate.toString().equals(group.businessDate())) {
                continue;
            }
            List<ChinaSportteryResponseDto.MatchDto> matches =
                    group.subMatchList() == null ? List.of() : group.subMatchList();
            for (ChinaSportteryResponseDto.MatchDto match : matches) {
                items.add(toSyncItem(businessDate, match));
            }
        }
        return List.copyOf(items);
    }

    private ChinaSportteryResponseDto readResponse(String payloadJson) {
        try {
            ChinaSportteryResponseDto response = objectMapper.readValue(
                    payloadJson, ChinaSportteryResponseDto.class
            );
            if (response == null) {
                throw new SportteryDataAccessException("中国体彩网比赛池接口返回空响应");
            }
            return response;
        } catch (SportteryDataAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析中国体彩网比赛池响应",
                    exception
            );
        }
    }

    private SportteryPoolSyncItemDto toSyncItem(
            LocalDate lotteryDate,
            ChinaSportteryResponseDto.MatchDto match
    ) {
        LocalDate matchDate = LocalDate.parse(match.matchDate());
        LocalTime matchTime = LocalTime.parse(match.matchTime());
        return new SportteryPoolSyncItemDto(
                String.valueOf(match.matchId()),
                lotteryDate,
                match.matchNumStr(),
                match.leagueAbbName(),
                match.homeTeamAbbName(),
                match.awayTeamAbbName(),
                matchDate.atTime(matchTime).atZone(SHANGHAI).toOffsetDateTime(),
                parseHandicap(match),
                toMatchStatus(match.matchStatus()),
                match.matchStatus(),
                parseSp(match.had(), "h"),
                parseSp(match.had(), "d"),
                parseSp(match.had(), "a"),
                parseSp(match.hhad(), "h"),
                parseSp(match.hhad(), "d"),
                parseSp(match.hhad(), "a")
        );
    }

    private BigDecimal parseHandicap(ChinaSportteryResponseDto.MatchDto match) {
        if (match.hhad() == null || !StringUtils.hasText(match.hhad().goalLine())) {
            return null;
        }
        try {
            return new BigDecimal(match.hhad().goalLine());
        } catch (NumberFormatException exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析比赛 %d 的体彩让球值：%s".formatted(
                            match.matchId(), match.hhad().goalLine()
                    ),
                    exception
            );
        }
    }

    private BigDecimal parseSp(ChinaSportteryResponseDto.OddsDto odds, String side) {
        if (odds == null) {
            return null;
        }
        String raw = switch (side) {
            case "h" -> odds.h();
            case "d" -> odds.d();
            case "a" -> odds.a();
            default -> null;
        };
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析 SP 值：" + raw,
                    exception
            );
        }
    }

    private MatchStatusEnum toMatchStatus(String matchStatus) {
        if (!StringUtils.hasText(matchStatus)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "比赛状态缺失"
            );
        }
        if ("Selling".equalsIgnoreCase(matchStatus)) {
            return MatchStatusEnum.SCHEDULED;
        }
        if ("Closed".equalsIgnoreCase(matchStatus) || "Lock".equalsIgnoreCase(matchStatus)) {
            return MatchStatusEnum.LOCKED;
        }
        return MatchStatusEnum.LOCKED;
    }
}
