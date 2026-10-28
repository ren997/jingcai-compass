-- ---------------------------------------------------------------------------
-- V30：回测样本记录 λ 反解的不可行原因
-- ---------------------------------------------------------------------------
-- 为什么要单开一列：skip_reason 上的 INCONSISTENT_ASIAN_MARKETS 一个枚举值背后
-- 挂着两个完全不同的出口——「λ 反解无解」与「残差/邻线超限」。前者又有四种原因，
-- 而四种原因的处置方向相反：
--   TOTALS_PROBABILITY_OUT_OF_RANGE / HANDICAP_PROBABILITY_OUT_OF_RANGE
--       → 搜索域不够宽，放宽 MIN/MAX_LAMBDA 或换反解目标即可救回
--   LAMBDA_SPLIT_IMPOSSIBLE
--       → 总进球定得太低、主客又分不开，属模型形状与市场冲突
--   INVALID_INPUT
--       → 数据缺失（概率或线为空），是上游问题
-- 混在一起统计会得出「市场在打架」的错误结论，而实际上一场都没打架。
--
-- 口径约定：
--   NULL  = 反解可行。注意这里包含「反解可行但被残差/邻线超限拒掉」的场次，
--           判别方法是看 max_residual / neighbor_max_deviation 是否非空。
--   非空  = ImpliedPoissonModel.Infeasibility 的枚举名（NONE 不落库）。
--
-- 历史行不回填：那些 case 的 Solution 在判定结束时即丢弃，没有任何可复原的
-- 依据，回填只能猜。要拿细分分布就得重跑——归档按场次复用，重跑不花额度。

ALTER TABLE backtest_cases
    ADD COLUMN infeasibility_reason VARCHAR(48);

ALTER TABLE backtest_cases
    ADD CONSTRAINT ck_backtest_cases_infeasibility CHECK (
        infeasibility_reason IS NULL OR infeasibility_reason IN (
            'INVALID_INPUT',
            'TOTALS_PROBABILITY_OUT_OF_RANGE',
            'HANDICAP_PROBABILITY_OUT_OF_RANGE',
            'LAMBDA_SPLIT_IMPOSSIBLE'
        )
    );

COMMENT ON COLUMN backtest_cases.infeasibility_reason IS
    'λ 反解不可行原因（ImpliedPoissonModel.Infeasibility 枚举名）；NULL 表示反解可行，'
    '此时若仍需区分「残差/邻线超限」请看 max_residual 与 neighbor_max_deviation。'
    ' Bilingual: why the implied-Poisson solve failed; NULL means the solve succeeded.';
