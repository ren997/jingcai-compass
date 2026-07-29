package com.jingcaicompass.odds.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.odds.dto.AsianOddsLeagueDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpExecutor;
import com.jingcaicompass.system.provider.ProviderHttpRequest;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import com.jingcaicompass.system.provider.ProviderRetryPolicy;
import com.jingcaicompass.system.provider.ProviderException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** The Odds API 真实查询：按受控 sport key 聚合原始赔率响应。 */
@Component
@ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "THE_ODDS_API")
public class TheOddsApiProvider implements AsianOddsProvider {

    static final String PROVIDER_CODE = "THE_ODDS_API";
    private static final String API_KEY_PARAMETER = "apiKey";
    private static final String REGIONS = "eu";
    private static final String MARKETS = "spreads";

    private final RestClient restClient;
    private final ProviderHttpExecutor httpExecutor;
    private final AsianOddsProviderProperties properties;
    private final AsianOddsPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;

    public TheOddsApiProvider(
            @Qualifier(AsianOddsRestClientConfig.ASIAN_ODDS_REST_CLIENT) RestClient restClient,
            ProviderHttpExecutor httpExecutor,
            AsianOddsProviderProperties properties,
            AsianOddsPayloadMapper payloadMapper,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.httpExecutor = httpExecutor;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public List<AsianOddsLeagueDto> fetchLeagues() {
        ProviderHttpResponse response = execute("/v4/sports");
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isArray()) {
                throw parseFailure("sports response is not an array", null);
            }
            List<AsianOddsLeagueDto> leagues = new ArrayList<>();
            for (JsonNode item : root) {
                String sportKey = text(item, "key");
                String title = text(item, "title");
                if (!StringUtils.hasText(sportKey) || !StringUtils.hasText(title)) {
                    continue;
                }
                leagues.add(new AsianOddsLeagueDto(sportKey, title, text(item, "group")));
            }
            return List.copyOf(leagues);
        } catch (JsonProcessingException exception) {
            throw parseFailure("sports response is invalid JSON", exception);
        }
    }

    @Override
    public List<AsianOddsMatchOddsDto> fetchPreMatchOdds(AsianOddsQueryDto query) {
        ProviderFetchResult raw = fetchPreMatchOddsRaw(query);
        return payloadMapper.parseMatches(raw.payloadJson());
    }

    @Override
    public ProviderFetchResult fetchPreMatchOddsRaw(AsianOddsQueryDto query) {
        AsianOddsQueryDto safeQuery = query == null
                ? new AsianOddsQueryDto(null, null, null, null)
                : query;
        List<String> sportKeys = safeQuery.sportKeys().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!sportKeys.isEmpty() && !StringUtils.hasText(properties.apiKey())) {
            throw new ProviderException(
                    PROVIDER_CODE,
                    ProviderErrorCategory.INVALID_PARAMETER,
                    "The Odds API key is required for real odds requests"
            );
        }

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("provider", PROVIDER_CODE);
        envelope.put("regions", REGIONS);
        envelope.put("markets", MARKETS);
        putIfPresent(envelope, "kickoffFrom", safeQuery.kickoffFrom());
        putIfPresent(envelope, "kickoffTo", safeQuery.kickoffTo());
        if (StringUtils.hasText(safeQuery.bookmakerCode())) {
            envelope.put("bookmakerCode", safeQuery.bookmakerCode().trim());
        }
        ArrayNode responses = envelope.putArray("responses");

        int retryCount = 0;
        int quotaCost = 0;
        try {
            for (String sportKey : sportKeys) {
                String path = oddsPath(sportKey, safeQuery);
                ProviderHttpResponse response = execute(path);
                retryCount += response.retryCount();
                quotaCost += response.quotaCost();
                ObjectNode responseNode = responses.addObject();
                responseNode.put("sportKey", sportKey);
                responseNode.put("endpoint", "/v4/sports/" + sportKey + "/odds");
                responseNode.put("status", response.status());
                responseNode.put("retryCount", response.retryCount());
                responseNode.put("quotaCost", response.quotaCost());
                responseNode.set("body", readArray(response.body(), sportKey));
            }
        } catch (ProviderHttpException exception) {
            throw new ProviderHttpException(
                    PROVIDER_CODE,
                    exception.category(),
                    exception.getMessage(),
                    retryCount + exception.retryCount(),
                    quotaCost + exception.quotaCost(),
                    exception.httpStatus(),
                    exception
            );
        }

        try {
            return new ProviderFetchResult(
                    requestKey(sportKeys, safeQuery),
                    objectMapper.writeValueAsString(envelope),
                    200,
                    Instant.now(),
                    retryCount,
                    quotaCost
            );
        } catch (JsonProcessingException exception) {
            throw parseFailure("failed to serialize aggregated odds payload", exception);
        }
    }

    @Override
    public int estimateQuotaCost(AsianOddsQueryDto query) {
        if (query == null || query.sportKeys() == null) {
            return 0;
        }
        return (int) query.sportKeys().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .count();
    }

    private ProviderHttpResponse execute(String path) {
        return httpExecutor.get(
                restClient,
                PROVIDER_CODE,
                ProviderHttpRequest.of(path),
                new ProviderRetryPolicy(properties.retry().maxAttempts(), properties.retry().delay()),
                properties.quotaWarningThreshold()
        );
    }

    private String oddsPath(String sportKey, AsianOddsQueryDto query) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/v4/sports/{sportKey}/odds")
                .queryParam("regions", REGIONS)
                .queryParam("markets", MARKETS)
                .queryParam("oddsFormat", "decimal")
                .queryParam("dateFormat", "iso")
                .queryParam(API_KEY_PARAMETER, properties.apiKey());
        if (query.kickoffFrom() != null) {
            builder.queryParam("commenceTimeFrom", query.kickoffFrom().toInstant().toString());
        }
        if (query.kickoffTo() != null) {
            builder.queryParam("commenceTimeTo", query.kickoffTo().toInstant().toString());
        }
        return builder.buildAndExpand(sportKey).encode().toUriString();
    }

    private ArrayNode readArray(String body, String sportKey) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (parsed == null || !parsed.isArray()) {
                throw parseFailure("odds response is not an array for sport key " + sportKey, null);
            }
            return (ArrayNode) parsed;
        } catch (JsonProcessingException exception) {
            throw parseFailure("odds response is invalid JSON for sport key " + sportKey, exception);
        }
    }

    private String requestKey(List<String> sportKeys, AsianOddsQueryDto query) {
        String from = query.kickoffFrom() == null ? "*" : query.kickoffFrom().toInstant().toString();
        String to = query.kickoffTo() == null ? "*" : query.kickoffTo().toInstant().toString();
        return "the-odds:spreads:" + String.join(",", sportKeys) + ":" + from + ":" + to;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node == null ? null : node.get(fieldName);
        return field == null || field.isNull() ? null : field.asText(null);
    }

    private static void putIfPresent(ObjectNode target, String name, OffsetDateTime value) {
        if (value != null) {
            target.put(name, value.toInstant().toString());
        }
    }

    private ProviderException parseFailure(String message, Throwable cause) {
        return cause == null
                ? new ProviderException(PROVIDER_CODE, ProviderErrorCategory.PARSE_FAILURE, message)
                : new ProviderException(PROVIDER_CODE, ProviderErrorCategory.PARSE_FAILURE, message, cause);
    }
}
