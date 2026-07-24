package com.jingcaicompass.match.client;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.system.stub.StubFixtureLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "stub")
public class StubSportteryProvider implements SportteryProvider {

    private static final Pattern SEQUENCE_PATTERN = Pattern.compile("(\\d+)$");
    private static final String RAW_TEMPLATE_DATE = "2026-07-22";

    private final List<SportteryMatchDto> poolTemplates;
    private final List<SportteryMatchResultDto> resultTemplates;
    private final String rawPoolTemplate;
    private final String rawEmptyPoolTemplate;

    public StubSportteryProvider() {
        List<SportteryMatchDto> pool = new ArrayList<>();
        pool.addAll(StubFixtureLoader.readList(
                "stub/sporttery/pool-normal.json", SportteryMatchDto.class));
        pool.addAll(StubFixtureLoader.readList(
                "stub/sporttery/pool-postponed-cancelled.json", SportteryMatchDto.class));
        this.poolTemplates = List.copyOf(pool.stream()
                .sorted(Comparator.comparing(this::sequenceOf))
                .toList());

        List<SportteryMatchResultDto> results = new ArrayList<>();
        results.addAll(StubFixtureLoader.readList(
                "stub/sporttery/results-normal.json", SportteryMatchResultDto.class));
        results.addAll(StubFixtureLoader.readList(
                "stub/sporttery/results-amended.json", SportteryMatchResultDto.class));
        this.resultTemplates = List.copyOf(results);
        this.rawPoolTemplate = StubFixtureLoader.readText("stub/sporttery/pool-raw-normal.json");
        this.rawEmptyPoolTemplate = StubFixtureLoader.readText("stub/sporttery/pool-raw-empty.json");
    }

    @Override
    public String providerCode() {
        return "STUB";
    }

    @Override
    public List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate) {
        String weekday = weekdayLabel(lotteryDate.getDayOfWeek());
        return poolTemplates.stream()
                .map(template -> remapMatch(template, lotteryDate, weekday))
                .toList();
    }

    @Override
    public ProviderFetchResult fetchMatchPoolRaw(LocalDate lotteryDate) {
        LocalDate requestDate = lotteryDate == null ? LocalDate.of(2026, 7, 22) : lotteryDate;
        String weekday = weekdayLabel(requestDate.getDayOfWeek());
        String payload = rawPoolTemplate
                .replace(RAW_TEMPLATE_DATE, requestDate.toString())
                .replace("周三", weekday);
        return new ProviderFetchResult(
                requestDate.toString(),
                payload,
                200,
                Instant.now(),
                0,
                0
        );
    }

    /**
     * 返回空比赛池原始响应，供同步空池场景测试。
     */
    public ProviderFetchResult fetchEmptyMatchPoolRaw(LocalDate lotteryDate) {
        LocalDate requestDate = lotteryDate == null ? LocalDate.of(2026, 7, 22) : lotteryDate;
        return new ProviderFetchResult(
                requestDate.toString(),
                rawEmptyPoolTemplate,
                200,
                Instant.now(),
                0,
                0
        );
    }

    @Override
    public List<SportteryMatchResultDto> fetchMatchResults(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return List.of();
        }
        List<SportteryMatchResultDto> results = new ArrayList<>();
        for (LocalDate lotteryDate = startDate; !lotteryDate.isAfter(endDate); lotteryDate = lotteryDate.plusDays(1)) {
            String weekday = weekdayLabel(lotteryDate.getDayOfWeek());
            for (SportteryMatchResultDto template : resultTemplates) {
                results.add(remapResult(template, lotteryDate, weekday));
            }
        }
        return List.copyOf(results);
    }

    private SportteryMatchDto remapMatch(SportteryMatchDto template, LocalDate lotteryDate, String weekday) {
        int sequence = sequenceOf(template);
        LocalTime kickoffTime = template.kickoffTime().toLocalTime();
        ZoneOffset offset = template.kickoffTime().getOffset();
        return new SportteryMatchDto(
                "stub-%s-%03d".formatted(lotteryDate, sequence),
                lotteryDate,
                "%s%03d".formatted(weekday, sequence),
                template.leagueName(),
                template.homeTeamName(),
                template.awayTeamName(),
                lotteryDate.atTime(kickoffTime).atOffset(offset),
                template.officialHandicap(),
                template.matchStatus()
        );
    }

    private SportteryMatchResultDto remapResult(
            SportteryMatchResultDto template,
            LocalDate lotteryDate,
            String weekday
    ) {
        int sequence = sequenceOf(template.matchId());
        LocalTime updatedTime = template.providerUpdatedAt().toLocalTime();
        ZoneOffset offset = template.providerUpdatedAt().getOffset();
        LocalDate updatedDate = template.amended() ? lotteryDate.plusDays(1) : lotteryDate;
        return new SportteryMatchResultDto(
                "stub-%s-%03d".formatted(lotteryDate, sequence),
                lotteryDate,
                "%s%03d".formatted(weekday, sequence),
                template.homeScore(),
                template.awayScore(),
                template.matchStatus(),
                template.amended(),
                OffsetDateTime.of(updatedDate, updatedTime, offset)
        );
    }

    private int sequenceOf(SportteryMatchDto template) {
        return sequenceOf(template.matchId());
    }

    private int sequenceOf(String matchId) {
        Matcher matcher = SEQUENCE_PATTERN.matcher(matchId);
        if (!matcher.find()) {
            throw new IllegalStateException("Stub matchId 缺少序号：" + matchId);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String weekdayLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
