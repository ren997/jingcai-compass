-- Provider event kickoff for human mapping review / 人工映射复核用供应商赛事开赛时间

ALTER TABLE match_source_mappings
    ADD COLUMN external_kickoff_time TIMESTAMPTZ;

-- The Odds API's event ID is stable. Backfill only that exact event from already stored payloads;
-- no provider request, raw JSON exposure, credential or storage location is introduced.
WITH latest_the_odds_event AS (
    SELECT DISTINCT ON (mapping.id)
        mapping.id AS mapping_id,
        (event.value ->> 'commence_time')::TIMESTAMPTZ AS external_kickoff_time
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
        AND NULLIF(event.value ->> 'commence_time', '') IS NOT NULL
    ORDER BY mapping.id, payload.requested_at DESC, payload.id DESC
)
UPDATE match_source_mappings mapping
SET external_kickoff_time = latest_the_odds_event.external_kickoff_time
FROM latest_the_odds_event
WHERE mapping.id = latest_the_odds_event.mapping_id
    AND mapping.external_kickoff_time IS NULL;

COMMENT ON COLUMN match_source_mappings.external_kickoff_time IS
    'Provider event kickoff for human review only / 人工复核用供应商赛事开赛时间';
