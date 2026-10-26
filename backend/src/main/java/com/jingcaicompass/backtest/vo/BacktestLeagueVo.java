package com.jingcaicompass.backtest.vo;

/**
 * 联赛候选。
 *
 * @param archivedCount 该联赛已归档成功的场次数；为 0 说明这个联赛的历史赔率还没采过，
 *                      选它会让整批场次走到 {@code NO_HISTORY}
 */
public record BacktestLeagueVo(
        String leagueName,
        Integer matchCount,
        Integer archivedCount
) {
}
