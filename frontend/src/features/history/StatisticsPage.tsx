import { useSearchParams } from 'react-router-dom';
import type {
  MarketHitRateVo,
  PredictionType,
  StatisticsMetricsVo,
  StatisticsWindowVo,
} from '../../services/public';
import { formatTimestamp } from '../matches/matchPresentation';
import {
  formatDecimal,
  formatPercent,
  formatSigned,
  predictionTypeLabels,
  probabilityUnavailableReasonLabel,
  roiUnavailableReasonLabel,
  settlementMarketLabel,
} from './historyPresentation';
import {
  parseStatisticsSearch,
  toStatisticsSearchParams,
  type StatisticsSearch,
} from './historySearch';
import { useStatisticsSummaryQuery } from './useHistoryQueries';

const PREDICTION_TYPE_TABS: PredictionType[] = ['ASIAN', 'SPORTTERY'];

/** 市场命中单元：把走盘与半赢半输单独列出来，否则"命中率"会因为分母口径不同而对不上账。 */
function MarketHitRate({ market }: { market: MarketHitRateVo }) {
  const hasSplit = market.pushCount > 0 || market.halfWinCount > 0 || market.halfLossCount > 0;
  return (
    <div>
      <dt>{settlementMarketLabel(market.marketType)} 命中率</dt>
      <dd>
        {formatPercent(market.hitRate)}
        <small>
          {market.hitCount} 命中 / {market.missCount} 未中
          {hasSplit && ` · ${market.pushCount} 走盘 · ${market.halfWinCount} 半赢 · ${market.halfLossCount} 半输`}
        </small>
      </dd>
      {hasSplit && (
        <dd className="metric-secondary">
          加权命中率（走盘 0.5、半赢 0.75、半输 0.25）：{formatPercent(market.weightedHitRate)}
        </dd>
      )}
    </div>
  );
}

/** 双 Brier：模型与市场共用同一批样本，只有配对比较才有意义。 */
function ProbabilityMetrics({ metrics }: { metrics: StatisticsMetricsVo }) {
  const { probabilityMetrics } = metrics;
  return (
    <>
      <div>
        <dt>模型 Brier Score</dt>
        <dd>
          {formatDecimal(probabilityMetrics.brierScore)}
          <small>样本 {probabilityMetrics.sampleSize}</small>
        </dd>
      </div>
      <div>
        <dt>市场 Brier Score</dt>
        <dd>{formatDecimal(probabilityMetrics.marketBrierScore)}</dd>
      </div>
      <div>
        <dt>Brier 优势（市场 − 模型）</dt>
        <dd>
          {formatSigned(probabilityMetrics.brierAdvantage)}
          {probabilityMetrics.modelOutperformsMarket === null && <small>无配对样本</small>}
          {probabilityMetrics.modelOutperformsMarket === true && <small>模型优于市场</small>}
          {probabilityMetrics.modelOutperformsMarket === false && <small>模型未跑赢市场</small>}
        </dd>
      </div>
      <div>
        <dt>Log Loss</dt>
        <dd>{formatDecimal(probabilityMetrics.logLoss)}</dd>
      </div>
    </>
  );
}

function WindowMetrics({ title, window }: { title: string; window: StatisticsWindowVo }) {
  const { metrics } = window;
  return (
    <article className="statistics-window-card">
      <header><strong>{title}</strong><span>{window.startDate} 至 {window.endDate}</span></header>
      <dl className="statistics-metrics">
        <div><dt>已锁定预测</dt><dd>{metrics.lockedPredictionCount}</dd></div>
        <div><dt>最终 / 待定 / 作废赛果</dt><dd>{metrics.finalFactCount} / {metrics.pendingFactCount} / {metrics.voidFactCount}</dd></div>
        {metrics.markets.map((market) => <MarketHitRate key={market.marketType} market={market} />)}
        <ProbabilityMetrics metrics={metrics} />
      </dl>
      {metrics.probabilityMetrics.unavailableReasons.length > 0 && (
        <p className="metric-unavailable">概率指标暂不可用：{metrics.probabilityMetrics.unavailableReasons.map(probabilityUnavailableReasonLabel).join('；')}</p>
      )}
      <RoiMetrics metrics={metrics} />
    </article>
  );
}

function RoiMetrics({ metrics }: { metrics: StatisticsMetricsVo }) {
  return metrics.roi.available ? (
    <p className="roi-available">ROI：{formatPercent(metrics.roi.roi)} · Yield：{formatPercent(metrics.roi.yield)} · 样本 {metrics.roi.sampleSize}</p>
  ) : (
    <p className="metric-unavailable">ROI / Yield 暂不可用：{metrics.roi.unavailableReasons.map(roiUnavailableReasonLabel).join('；')}</p>
  );
}

function BreakdownTable({ title, rows }: { title: string; rows: Array<{ name: string; metrics: StatisticsMetricsVo }> }) {
  return (
    <section className="detail-section statistics-breakdown">
      <div className="section-heading"><div><p className="eyebrow">Breakdown</p><h2>{title}</h2></div></div>
      {rows.length === 0 ? <p className="missing-data">当前筛选范围没有可分组样本。</p> : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>分组</th>
                <th>已锁定预测</th>
                <th>命中率</th>
                <th>加权命中率</th>
                <th>模型 / 市场 Brier</th>
                <th>最终赛果</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                // 分组行按该类预测的市场逐行取数；多市场时并列展示，不做加权合并——
                // 两个市场是各自独立的二选一，合并出一个"总命中率"没有对应的可下注对象。
                const [primary] = row.metrics.markets;
                return (
                  <tr key={row.name}>
                    <th scope="row">{row.name}</th>
                    <td>{row.metrics.lockedPredictionCount}</td>
                    <td>{formatPercent(primary?.hitRate)}</td>
                    <td>{formatPercent(primary?.weightedHitRate)}</td>
                    <td>
                      {formatDecimal(row.metrics.probabilityMetrics.brierScore)}
                      {' / '}
                      {formatDecimal(row.metrics.probabilityMetrics.marketBrierScore)}
                    </td>
                    <td>{row.metrics.finalFactCount}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** T507 公开预测表现统计页；两条产品线口径不同，用 tab 切换而不是一次算完。 */
export default function StatisticsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseStatisticsSearch(searchParams);
  const statisticsQuery = useStatisticsSummaryQuery(filters);
  const summary = statisticsQuery.data;

  function updateFilters(next: Partial<StatisticsSearch>) {
    setSearchParams(toStatisticsSearchParams({ ...filters, ...next }));
  }

  return (
    <main className="page">
      <section className="hero history-hero">
        <div>
          <p className="eyebrow">JingCai Compass · Public Statistics</p>
          <h1>预测表现统计</h1>
          <p className="summary">以当前最终赛果和当前结算计算表现；缺少冻结赔率或下注规则时不会伪造 ROI。</p>
        </div>
      </section>

      <section className="history-filters" aria-label="统计筛选">
        <div className="prediction-type-switch" role="tablist" aria-label="产品线">
          {PREDICTION_TYPE_TABS.map((type) => (
            <button
              key={type}
              type="button"
              role="tab"
              aria-selected={filters.predictionType === type}
              onClick={() => updateFilters({ predictionType: type })}
            >
              {predictionTypeLabels[type]}
            </button>
          ))}
        </div>
        <label><span>开始日期</span><input aria-label="统计开始日期" type="date" value={filters.startDate ?? ''} onChange={(event) => updateFilters({ startDate: event.target.value || undefined })} /></label>
        <label><span>结束日期</span><input aria-label="统计结束日期" type="date" value={filters.endDate ?? ''} onChange={(event) => updateFilters({ endDate: event.target.value || undefined })} /></label>
        <label>
          <span>联赛 ID</span>
          <input
            aria-label="统计联赛 ID"
            type="number"
            min="1"
            inputMode="numeric"
            value={filters.leagueId ?? ''}
            onChange={(event) => {
              const value = Number(event.target.value);
              updateFilters({ leagueId: Number.isSafeInteger(value) && value > 0 ? value : undefined });
            }}
          />
        </label>
        <label><span>模型版本</span><input aria-label="统计模型版本" type="text" value={filters.modelVersion ?? ''} onChange={(event) => updateFilters({ modelVersion: event.target.value.trim() || undefined })} /></label>
      </section>

      {statisticsQuery.isPending && <section className="state-card">正在加载表现统计……</section>}
      {statisticsQuery.isError && <section className="state-card error" role="alert">公开统计暂不可用：{statisticsQuery.error.message}</section>}
      {statisticsQuery.isSuccess && summary && (
        <>
          <section className="summary-strip">
            <div><span>统计截至</span><strong>{summary.asOfDate}</strong></div>
            <div><span>口径</span><strong>{predictionTypeLabels[filters.predictionType]}</strong></div>
            <div><span>页面读取时间</span><strong>{formatTimestamp(new Date(statisticsQuery.dataUpdatedAt).toISOString())}</strong></div>
            <p className={statisticsQuery.isStale ? 'data-stale' : undefined}>{statisticsQuery.isStale ? '统计可能已过期，请刷新。' : '统计仅使用当前事实与当前结算口径。'}</p>
            <button className="refresh-button" type="button" onClick={() => void statisticsQuery.refetch()} disabled={statisticsQuery.isFetching}>{statisticsQuery.isFetching ? '刷新中…' : '刷新'}</button>
          </section>
          {summary.requestedWindow.metrics.lockedPredictionCount === 0 && <section className="state-card statistics-empty">当前筛选范围暂无已锁定预测，指标按无样本口径展示。</section>}
          <section className="statistics-window-list" aria-label="统计窗口">
            <WindowMetrics title="请求范围" window={summary.requestedWindow} />
            <WindowMetrics title="近 7 天" window={summary.trailingSevenDays} />
            <WindowMetrics title="近 30 天" window={summary.trailingThirtyDays} />
          </section>
          <BreakdownTable title="按联赛分布" rows={summary.byLeague.map((item) => ({ name: item.leagueName ?? `联赛 #${item.leagueId ?? '未知'}`, metrics: item.metrics }))} />
          <BreakdownTable title="按模型版本分布" rows={summary.byModelVersion.map((item) => ({ name: item.modelVersion, metrics: item.metrics }))} />
        </>
      )}
    </main>
  );
}
