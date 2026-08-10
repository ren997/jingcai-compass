-- Repair legacy pending identity cleanup / 修复历史待复核身份清理
--
-- V17 correctly detached legacy The Odds candidates, but its data-modifying CTE
-- evaluated reference checks against the statement snapshot. This follow-up only
-- removes entities whose legacy temporary identity can still be proven from the
-- retained review metadata and the shared creation transaction timestamp.

WITH proven_legacy_leagues AS (
    SELECT DISTINCT league.id
    FROM leagues league
    JOIN provider_league_mappings mapping
        ON mapping.provider_code = 'THE_ODDS_API'
       AND mapping.mapping_status = 'PENDING'
       AND mapping.mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
       AND mapping.external_display_name IS NOT NULL
       AND league.name_zh = mapping.external_display_name
       AND league.created_at = mapping.created_at
)
DELETE FROM leagues league
WHERE league.id IN (SELECT id FROM proven_legacy_leagues)
  AND NOT EXISTS (SELECT 1 FROM matches match WHERE match.league_id = league.id)
  AND NOT EXISTS (SELECT 1 FROM league_aliases alias WHERE alias.league_id = league.id)
  AND NOT EXISTS (SELECT 1 FROM provider_league_mappings mapping WHERE mapping.league_id = league.id);

WITH proven_legacy_teams AS (
    SELECT DISTINCT team.id
    FROM teams team
    JOIN provider_team_mappings mapping
        ON mapping.provider_code = 'THE_ODDS_API'
       AND mapping.mapping_status = 'PENDING'
       AND mapping.mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
       AND mapping.external_display_name IS NOT NULL
       AND team.name_zh = mapping.external_display_name
       AND team.created_at = mapping.created_at
)
DELETE FROM teams team
WHERE team.id IN (SELECT id FROM proven_legacy_teams)
  AND NOT EXISTS (
      SELECT 1
      FROM matches match
      WHERE match.home_team_id = team.id OR match.away_team_id = team.id
  )
  AND NOT EXISTS (SELECT 1 FROM team_aliases alias WHERE alias.team_id = team.id)
  AND NOT EXISTS (SELECT 1 FROM provider_team_mappings mapping WHERE mapping.team_id = team.id);
