import type { BacktestComparisonVo, BacktestMetricsVo } from '../../services/backtest';
import { BACKTEST_MARKET_SCOPES } from '../../services/backtest';
import {
  backtestMarketScopeLabels,
  brierDeltaVerdict,
  formatBrierDelta,
  formatProfit,
  formatRate,
  formatScore,
  skipReasonLabel,
} from './backtestPresentation';

type BacktestMetricsTableProps = {
  comparison: BacktestComparisonVo | undefined;
  /** 只有终态才有指标；中间态显示等待文案而不是空表。 */
  isReady: boolean;
};

/**
 * 参数组 × 口径的指标表。
 *
 * <p>每个参数组固定三行而不是一行：`ALL` 不是「让球 + 大小球」的和，它是把两个市场的样本
 * 放进同一个池子各算一次。合成一行会让人以为合计命中率是两市场命中率的平均，
 * 而实际上两个市场的注数并不相等。</p>
 */
export default function BacktestMetricsTable({ comparison, isReady }: BacktestMetricsTableProps) {
  if (!isReady) {
    return (
      <section className="detail-section">
        <div className="section-heading"><div><p className="eyebrow">Metrics</p><h2>参数组指标</h2></div></div>
        <p className="missing-data">回测尚未产出结论，指标在汇总阶段生成。</p>
      </section>
    );
  }

  if (!comparison || comparison.metrics.length === 0) {
    return (
      <section className="detail-section">
        <div className="section-heading"><div><p className="eyebrow">Metrics</p><h2>参数组指标</h2></div></div>
        <p className="missing-data">这个 run 没有可结算样本，指标为空。</p>
      </section>
    );
  }

  const rows: Array<{ groupKey: string; metrics: BacktestMetricsVo }> = [];
  comparison.groupKeys.forEach((groupKey) => {
    BACKTEST_MARKET_SCOPES.forEach((scope) => {
      const found = comparison.metrics.find(
        (item) => item.groupKey === groupKey && item.marketScope === scope,
      );
      if (found) {
        rows.push({ groupKey, metrics: found });
      }
    });
  });

  return (
    <section className="detail-section">
      <div className="section-heading">
        <div><p className="eyebrow">Metrics</p><h2>参数组指标</h2></div>
        {comparison.bestGroupKey && (
          <div>
            <strong>最优组：{comparison.bestGroupKey}</strong>
            {/* 理由必须显示：不写理由的「最优」是无法反驳也无法复核的断言。 */}
            {comparison.bestGroupReason && <p>{comparison.bestGroupReason}</p>}
          </div>
        )}
      </div>

      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>参数组</th>
              <th>口径</th>
              <th>样本 / 已结算</th>
              <th>方向数</th>
              <th>命中率</th>
              <th>加权命中率</th>
              <th>ROI</th>
              <th>收益</th>
              <th>模型 / 市场 Brier</th>
              <th>Δ（市场−模型）</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ groupKey, metrics }) => (
              <tr key={`${groupKey}-${metrics.marketScope}`}
                className={groupKey === comparison.bestGroupKey ? 'is-best-group' : undefined}>
                <th scope="row">{groupKey}</th>
                <td>{backtestMarketScopeLabels[metrics.marketScope]}</td>
                <td>{metrics.caseCount} / {metrics.settledCount}</td>
                <td>{metrics.pickCount}</td>
                <td>{formatRate(metrics.hitRate)}</td>
                <td>{formatRate(metrics.weightedHitRate)}</td>
                <td>{formatRate(metrics.roi)}</td>
                <td className={`backtest-profit ${profitTone(metrics.roi)}`}>
                  {formatProfit(metrics.profitSum)}
                </td>
                <td>{formatScore(metrics.brierModel)} / {formatScore(metrics.brierMarket)}</td>
                <td>
                  {formatBrierDelta(metrics.brierDelta)}
                  <small>{brierDeltaVerdict(metrics.brierDelta)}</small>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <details className="backtest-notes">
        <summary>口径说明与跳过分布</summary>
        <ul>
          <li>命中率分母不含走盘；走盘按 0.5 计入加权命中率与 Brier，不进命中率分母。</li>
          <li>Δ = 市场 Brier − 模型 Brier，<strong>正数才表示模型更好</strong>。负值是合法结论，不取绝对值。</li>
          <li>合计口径独立计算，不等于让球与大小球相加：两个市场注数不等。</li>
          <li>样本为 0 时命中率与 ROI 显示「—」而不是 0%：没下注和下了不中不是一回事。</li>
        </ul>
        {rows.filter(({ metrics }) => Object.keys(metrics.skipReasonCounts).length > 0).map(({ groupKey, metrics }) => (
          <p key={`skip-${groupKey}-${metrics.marketScope}`}>
            {groupKey} · {backtestMarketScopeLabels[metrics.marketScope]} 跳过分布：
            {Object.entries(metrics.skipReasonCounts).map(([reason, count]) => (
              <span key={reason}>{skipReasonLabel(reason as never)} {count}；</span>
            ))}
          </p>
        ))}
      </details>
    </section>
  );
}

/** 收益正红负绿（A 股口径）；null 与 0 不着色。 */
function profitTone(roi: number | null | undefined) {
  if (roi === null || roi === undefined || roi === 0) {
    return 'muted';
  }
  return roi > 0 ? 'positive' : 'negative';
}
