import { Alert } from 'antd';
import type {
  BacktestComparisonVo,
  BacktestMetricsVo,
  BacktestParameterSet,
} from '../../services/backtest';
import { BACKTEST_MARKET_SCOPES } from '../../services/backtest';
import {
  backtestMarketScopeLabels,
  brierDeltaVerdict,
  formatBrierDelta,
  formatProfit,
  formatRate,
  formatScore,
  parameterSetThresholds,
  skipReasonLabel,
} from './backtestPresentation';

type BacktestMetricsTableProps = {
  comparison: BacktestComparisonVo | undefined;
  /** 只有终态才有指标；中间态显示等待文案而不是空表。 */
  isReady: boolean;
  /**
   * 参数组定义。`groupKey` 是用户自己起的名字（baseline / tight / 随便什么），
   * 光看名字无法知道这一组严在哪，所以四个阈值要一并显示出来。
   */
  parameterSets?: BacktestParameterSet[];
};

/**
 * 参数组 × 口径的指标表。
 *
 * <p>每个参数组固定三行而不是一行：`ALL` 不是「让球 + 大小球」的和，它是把两个市场的样本
 * 放进同一个池子各算一次。合成一行会让人以为合计命中率是两市场命中率的平均，
 * 而实际上两个市场的注数并不相等。</p>
 */
export default function BacktestMetricsTable({
  comparison,
  isReady,
  parameterSets,
}: BacktestMetricsTableProps) {
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

  // 全部参数组都没出方向：这不是「没样本」，而是每一场都卡在判定阶段。
  // 不点明的话，这一页会显示六行全「—」，最容易被读成「那段时间上游没开价」。
  const noDirection = rows.every(({ metrics }) => metrics.pickCount === 0);

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

      {noDirection && (
        <Alert
          type="warning"
          showIcon
          message="这批样本没有任何方向"
          description="样本数与跳过分布见下表展开项：每一场都停在了判定阶段，而不是「这段时间上游没开价」。"
        />
      )}

      <div className="table-scroll">
        <table className="backtest-metrics-table">
          <thead>
            <tr>
              <th>参数组</th>
              <th>口径</th>
              <th>
                样本（场）
                <small className="backtest-th-note">已结算（腿）</small>
              </th>
              <th>方向（腿）</th>
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
                <td title={`样本 ${metrics.caseCount} 场（其中 ${metrics.decidedCount} 场出了方向），已结算 ${metrics.settledCount} 条腿`}>
                  {metrics.caseCount} 场 / {metrics.settledCount} 腿
                </td>
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

      {/*
        参数组定义放在表格外面，不塞进「参数组」列：那一列会被两行阈值撑宽，
        把「口径」「加权命中率」挤到换行（实测截图确认过）。
        组名是用户自己起的，光看名字无法知道这组严在哪，所以必须铺出来。
      */}
      {parameterSets && parameterSets.length > 0 ? (
        <div className="backtest-param-defs">
          <h3>参数组定义</h3>
          <ul>
            {parameterSets.map((set) => (
              <li key={set.groupKey}>
                <strong>{set.groupKey}</strong>
                <span className="backtest-param-thresholds">
                  {parameterSetThresholds(set).map((line) => (
                    <span key={line}>{line}</span>
                  ))}
                </span>
              </li>
            ))}
          </ul>
          <p className="backtest-hint">
            组名是创建回测时自己填的标识，没有固定含义。阈值只决定「发不发方向」，不决定「判什么」——
            同一场比赛，各组给出的方向完全一致，门槛高的那组只是少发几条。
          </p>
        </div>
      ) : null}

      <details className="backtest-notes">
        <summary>口径说明与跳过分布</summary>
        <ul>
          <li>
            <strong>「样本」是场次，「已结算」是腿，两者分母不同，不能相除当完成度。</strong>
            一场同时给出让球与大小球时算两条腿；没出方向的场次进场次、不进腿数。
            所以「80 场 / 40 腿」不是「只算了一半」。
          </li>
          <li>命中率分母不含走盘；走盘按 0.5 计入加权命中率与 Brier，不进命中率分母。</li>
          <li>
            Δ = 市场 Brier − 模型 Brier，<strong>正数才表示模型更好</strong>。负值是合法结论，不取绝对值。
          </li>
          <li>
            但 Δ <strong>在当前模型下恒为 0</strong>：模型概率是由该主盘去水概率反解 λ 再投影回
            同一条线，与市场概率同源，因此两侧 Brier 相等。Δ 列不是「模型优于市场」的证据，
            跨参数组比较请看 ROI 与命中率。
          </li>
          <li>合计口径独立计算，不等于让球与大小球相加：两个市场注数不等。</li>
          <li>样本为 0 时命中率与 ROI 显示「—」而不是 0%：没下注和下了不中不是一回事。</li>
        </ul>
        {rows
          .map(({ groupKey, metrics }) => ({
            groupKey,
            metrics,
            entries: skipEntries(metrics.skipReasonCounts),
          }))
          .filter(({ entries }) => entries.length > 0)
          .map(({ groupKey, metrics, entries }) => (
            <p key={`skip-${groupKey}-${metrics.marketScope}`}>
              {groupKey} · {backtestMarketScopeLabels[metrics.marketScope]} 跳过分布：
              {entries.map(([reason, count]) => (
                <span key={reason}>{skipReasonLabel(reason)} {count}；</span>
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

/**
 * 跳过分布的键值对，缺失时退化成空数组。
 *
 * <p>「跳过分布」是可选信息：它解释样本为什么少，但少一条解释不该让整页打不开。
 * 这一列是 jsonb，后端读取路径一旦绕过 jsonb typeHandler 就整列读回 null，
 * 而直接 `Object.keys(null)` 抛的是 TypeError，会被错误边界接住渲染成整页白屏——
 * 用户看到的只是「页面报错」，看不出问题出在一列附注上。</p>
 */
/**
 * 跳过分布条目。
 *
 * <p>键不再一定是 `BacktestSkipReason`：λ 反解失败会被后端细分成
 * `INCONSISTENT_ASIAN_MARKETS/<原因>`，所以按键原样透传给 `skipReasonLabel`，
 * 由它决定翻译方式。</p>
 */
function skipEntries(counts: Record<string, number> | null | undefined) {
  if (!counts) {
    return [] as Array<[string, number]>;
  }
  return Object.entries(counts);
}
