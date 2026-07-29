-- Provider display names for human mapping review / 人工映射复核的供应商展示名

ALTER TABLE match_source_mappings
    ADD COLUMN external_home_team_name VARCHAR(256),
    ADD COLUMN external_away_team_name VARCHAR(256);

-- The Odds API has no team IDs. Recover only the two display names by its stable event ID;
-- no raw payload, credential, request header or storage location leaves the database.
WITH latest_the_odds_event AS (
    SELECT DISTINCT ON (mapping.id)
        mapping.id AS mapping_id,
        event.value ->> 'home_team' AS external_home_team_name,
        event.value ->> 'away_team' AS external_away_team_name
    FROM match_source_mappings mapping
    INNER JOIN raw_data_payloads payload
        ON payload.provider_code = mapping.provider_code
        AND payload.data_type = 'ASIAN_ODDS'
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(payload.payload -> 'responses') = 'array'
            THEN payload.payload -> 'responses'
            ELSE '[]'::jsonb
        END
    ) response
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(response.value -> 'body') = 'array'
            THEN response.value -> 'body'
            ELSE '[]'::jsonb
        END
    ) event
    WHERE mapping.provider_code = 'THE_ODDS_API'
        AND event.value ->> 'id' = mapping.external_match_id
        AND NULLIF(event.value ->> 'home_team', '') IS NOT NULL
        AND NULLIF(event.value ->> 'away_team', '') IS NOT NULL
    ORDER BY mapping.id, payload.requested_at DESC, payload.id DESC
)
UPDATE match_source_mappings mapping
SET external_home_team_name = latest_the_odds_event.external_home_team_name,
    external_away_team_name = latest_the_odds_event.external_away_team_name
FROM latest_the_odds_event
WHERE mapping.id = latest_the_odds_event.mapping_id
    AND (mapping.external_home_team_name IS NULL OR mapping.external_away_team_name IS NULL);

COMMENT ON COLUMN match_source_mappings.external_home_team_name IS
    'Provider home-team display name for human review only / 人工复核用供应商主队展示名';
COMMENT ON COLUMN match_source_mappings.external_away_team_name IS
    'Provider away-team display name for human review only / 人工复核用供应商客队展示名';
