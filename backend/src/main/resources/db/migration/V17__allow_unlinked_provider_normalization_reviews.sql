-- External-only provider normalization reviews / 仅外部身份的供应商标准化复核

ALTER TABLE provider_league_mappings
    ALTER COLUMN league_id DROP NOT NULL;

ALTER TABLE provider_team_mappings
    ALTER COLUMN team_id DROP NOT NULL;

ALTER TABLE provider_league_mappings
    ADD CONSTRAINT ck_provider_league_mappings_confirmed_entity
        CHECK (
            mapping_status IN ('PENDING', 'REJECTED')
            OR league_id IS NOT NULL
        );

ALTER TABLE provider_team_mappings
    ADD CONSTRAINT ck_provider_team_mappings_confirmed_entity
        CHECK (
            mapping_status IN ('PENDING', 'REJECTED')
            OR team_id IS NOT NULL
        );

-- Detach only legacy The Odds pending candidates before testing whether their temporary entities are orphaned.
WITH detached_leagues AS (
    SELECT id, league_id
    FROM provider_league_mappings
    WHERE provider_code = 'THE_ODDS_API'
      AND mapping_status = 'PENDING'
      AND mapping_method = 'NAME_CANDIDATE'
      AND league_id IS NOT NULL
), cleared_leagues AS (
    UPDATE provider_league_mappings
    SET league_id = NULL,
        mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
    FROM detached_leagues detached
    WHERE provider_league_mappings.id = detached.id
    RETURNING detached.league_id
)
DELETE FROM leagues league
WHERE league.id IN (SELECT league_id FROM cleared_leagues)
  AND NOT EXISTS (SELECT 1 FROM matches match WHERE match.league_id = league.id)
  AND NOT EXISTS (SELECT 1 FROM league_aliases alias WHERE alias.league_id = league.id)
  AND NOT EXISTS (SELECT 1 FROM provider_league_mappings mapping WHERE mapping.league_id = league.id);

WITH detached_teams AS (
    SELECT id, team_id
    FROM provider_team_mappings
    WHERE provider_code = 'THE_ODDS_API'
      AND mapping_status = 'PENDING'
      AND mapping_method = 'NAME_CANDIDATE'
      AND team_id IS NOT NULL
), cleared_teams AS (
    UPDATE provider_team_mappings
    SET team_id = NULL,
        mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
    FROM detached_teams detached
    WHERE provider_team_mappings.id = detached.id
    RETURNING detached.team_id
)
DELETE FROM teams team
WHERE team.id IN (SELECT team_id FROM cleared_teams)
  AND NOT EXISTS (SELECT 1 FROM matches match WHERE match.home_team_id = team.id OR match.away_team_id = team.id)
  AND NOT EXISTS (SELECT 1 FROM team_aliases alias WHERE alias.team_id = team.id)
  AND NOT EXISTS (SELECT 1 FROM provider_team_mappings mapping WHERE mapping.team_id = team.id);

COMMENT ON COLUMN provider_league_mappings.league_id
    IS 'Confirmed internal league; null while external identity is pending or rejected / 已确认内部联赛；外部身份待复核或拒绝时为空';
COMMENT ON COLUMN provider_team_mappings.team_id
    IS 'Confirmed internal team; null while external identity is pending or rejected / 已确认内部球队；外部身份待复核或拒绝时为空';
