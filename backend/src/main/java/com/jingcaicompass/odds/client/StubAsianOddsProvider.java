package com.jingcaicompass.odds.client;

import com.jingcaicompass.odds.dto.AsianOddsLeagueDto;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.system.stub.StubFixtureLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "stub", matchIfMissing = true)
public class StubAsianOddsProvider implements AsianOddsProvider {

    private final List<AsianOddsLeagueDto> leagues;
    private final List<AsianOddsMatchOddsDto> matchOdds;

    public StubAsianOddsProvider() {
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
