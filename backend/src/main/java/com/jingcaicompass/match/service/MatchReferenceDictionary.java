package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.entity.League;
import com.jingcaicompass.match.entity.Team;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import java.util.HashMap;
import java.util.Map;

/**
 * 单次同步内的联赛 / 球队字典缓存。
 *
 * <p>单源之后字典只由 {@code matches.league_name} / {@code home_team_name} 派生，
 * 没有别名表、没有跨源映射，所以「按名字取 id，取不到就建」就是完整的解析逻辑——
 * 这不是归一化层的回归，而是它不再存在的证明：名字本身就是身份。</p>
 *
 * <p>缓存生命周期刻意限制在<b>一次写库调用</b>内，而不是应用生命周期：
 * 长期缓存会把其它进程或其它任务新建的字典行挡在缓存之外，导致同一名字被重复建行。</p>
 *
 * <p>本类不做并发保护，由调用方按单线程使用（{@code writeAll} 是串行循环）。</p>
 */
final class MatchReferenceDictionary {

    private final LeagueMapper leagueMapper;
    private final TeamMapper teamMapper;
    private final Map<String, Long> leagueIds = new HashMap<>();
    private final Map<String, Long> teamIds = new HashMap<>();

    MatchReferenceDictionary(LeagueMapper leagueMapper, TeamMapper teamMapper) {
        this.leagueMapper = leagueMapper;
        this.teamMapper = teamMapper;
    }

    /**
     * 联赛名 → 标准联赛 ID。
     *
     * @return 名字为空时返回 {@code null}，调用方据此保留既有归属而不清空
     */
    Long leagueId(String name) {
        String key = trimToNull(name);
        if (key == null) {
            return null;
        }
        Long cached = leagueIds.get(key);
        if (cached != null) {
            return cached;
        }
        League existing = leagueMapper.selectOne(new LambdaQueryWrapper<League>()
                .eq(League::getNameZh, key)
                .last("LIMIT 1"));
        Long id;
        if (existing != null) {
            id = existing.getId();
        } else {
            League created = new League();
            created.setNameZh(key);
            leagueMapper.insert(created);
            id = created.getId();
        }
        leagueIds.put(key, id);
        return id;
    }

    /**
     * 球队名 → 标准球队 ID。
     *
     * @return 名字为空时返回 {@code null}，调用方据此保留既有归属而不清空
     */
    Long teamId(String name) {
        String key = trimToNull(name);
        if (key == null) {
            return null;
        }
        Long cached = teamIds.get(key);
        if (cached != null) {
            return cached;
        }
        Team existing = teamMapper.selectOne(new LambdaQueryWrapper<Team>()
                .eq(Team::getNameZh, key)
                .last("LIMIT 1"));
        Long id;
        if (existing != null) {
            id = existing.getId();
        } else {
            Team created = new Team();
            created.setNameZh(key);
            teamMapper.insert(created);
            id = created.getId();
        }
        teamIds.put(key, id);
        return id;
    }

    private String trimToNull(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
