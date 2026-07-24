package com.jingcaicompass.odds.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.odds.dto.AsianOddsLeagueDto;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import com.jingcaicompass.system.stub.StubFixtureLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "stub", matchIfMissing = true)
public class StubAsianOddsProvider implements AsianOddsProvider {

    static final int STUB_QUOTA_COST = 1;

    private final List<AsianOddsLeagueDto> leagues;
    private final List<AsianOddsMatchOddsDto> matchOdds;
    private final ObjectMapper objectMapper;

    public StubAsianOddsProvider() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public StubAsianOddsProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.leagues = List.copyOf(StubFixtureLoader.readList(
                "stub/asianodds/leagues.json", AsianOddsLeagueDto.class));

        List<AsianOddsMatchOddsDto> odds = new ArrayList<>();
        odds.addAll(StubFixtureLoader.readList(
                "stub/asianodds/odds-normal.json", AsianOddsMatchOddsDto.class));
        odds.addAll(StubFixtureLoader.readList(
                "stub/asianodds/odds-missing-line.json", AsianOddsMatchOddsDto.class));
        odds.addAll(StubFixtureLoader.readList(
                "stub/asianodds/odds-alias-conflict.json", AsianOddsMatchOddsDto.class));
        this.matchOdds = List.copyOf(odds);
    }

    @Override
    public String providerCode() {
        return "STUB";
    }

    @Override
    public List<AsianOddsLeagueDto> fetchLeagues() {
        return leagues;
    }

    @Override
    public List<AsianOddsMatchOddsDto> fetchPreMatchOdds(AsianOddsQueryDto query) {
        AsianOddsQueryDto safeQuery = query == null
                ? new AsianOddsQueryDto(null, null, null, null)
                : query;
        return matchOdds.stream()
                .filter(match -> matchesKickoffWindow(match, safeQuery))
                .map(match -> filterLines(match, safeQuery.bookmakerCode()))
                .toList();
    }

    @Override
    public ProviderFetchResult fetchPreMatchOddsRaw(AsianOddsQueryDto query) {
        List<AsianOddsMatchOddsDto> odds = fetchPreMatchOdds(query);
        try {
            // raw_data_payloads.payload 为 JSON 对象 Map；数组需包一层 matches
            String payload = objectMapper.writeValueAsString(Map.of("matches", odds));
            AsianOddsQueryDto safeQuery = query == null
                    ? new AsianOddsQueryDto(null, null, null, null)
                    : query;
            String from = safeQuery.kickoffFrom() == null ? "*" : safeQuery.kickoffFrom().toString();
            String to = safeQuery.kickoffTo() == null ? "*" : safeQuery.kickoffTo().toString();
            return new ProviderFetchResult(
                    "asian-odds:" + from + ":" + to,
                    payload,
                    200,
                    Instant.now(),
                    0,
                    STUB_QUOTA_COST
            );
        } catch (JsonProcessingException exception) {
            throw new ProviderException(
                    "STUB",
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法序列化 Stub 亚盘载荷",
                    exception
            );
        }
    }

    private boolean matchesKickoffWindow(AsianOddsMatchOddsDto match, AsianOddsQueryDto query) {
        if (query.kickoffFrom() != null && match.kickoffTime().isBefore(query.kickoffFrom())) {
            return false;
        }
        if (query.kickoffTo() != null && match.kickoffTime().isAfter(query.kickoffTo())) {
            return false;
        }
        return true;
    }

    private AsianOddsMatchOddsDto filterLines(AsianOddsMatchOddsDto match, String bookmakerCode) {
        if (!StringUtils.hasText(bookmakerCode)) {
            return match;
        }
        List<AsianOddsLineDto> filtered = match.lines().stream()
                .filter(line -> bookmakerCode.equalsIgnoreCase(line.bookmakerCode()))
                .toList();
        return new AsianOddsMatchOddsDto(
                match.providerMatchId(),
                match.homeTeamName(),
                match.awayTeamName(),
                match.kickoffTime(),
                match.live(),
                filtered
        );
    }
}
