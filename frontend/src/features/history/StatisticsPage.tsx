import { useSearchParams } from 'react-router-dom';
import type { StatisticsMetricsVo, StatisticsWindowVo } from '../../services/public';
import { formatTimestamp } from '../matches/matchPresentation';
import {
  formatDecimal,
  formatPercent,
  roiUnavailableReasonLabel,
  settlementMarketLabel,
  probabilityUnavailableReasonLabel,
} from './historyPresentation';
import { parseStatisticsSearch, toStatisticsSearchParams, type StatisticsSearch } from './historySearch';
import { useStatisticsSummaryQuery } from './useHistoryQueries';

function WindowMetrics({ title, window }: { title: string; window: StatisticsWindowVo }) {
  const { metrics } = window;
  return (
    <article className="statistics-window-card">
      <header><strong>{title}</strong><span>{window.startDate} 至 {window.endDate}</span></header>
      <dl className="statistics-metrics">
        <div><dt>已锁定预测</dt><dd>{metrics.lockedPredictionCount}</dd></div>
        <div><dt>最终 / 待定 / 作废赛果</dt><dd>{metrics.finalFactCount} / {metrics.pendingFactCount} / {metrics.voidFactCount}</dd></div>
        <div><dt>{settlementMarketLabel('HAD')} 命中率</dt><dd>{formatPercent(metrics.had.hitRate)}（{metrics.had.hitCount} 命中 / {metrics.had.missCount} 未中）</dd></div>
        <div><dt>{settlementMarketLabel('HHAD')} 命中率</dt><dd>{formatPercent(metrics.hhad.hitRate)}（{metrics.hhad.hitCount} 命中 / {metrics.hhad.missCount} 未中）</dd></div>
        <div><dt>Brier Score</dt><dd>{formatDecimal(metrics.probabilityMetrics.brierScore)}</dd></div>
        <div><dt>Log Loss</dt><dd>{formatDecimal(metrics.probabilityMetrics.logLoss)}</dd></div>
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
            <thead><tr><th>分组</th><th>已锁定预测</th><th>HAD 命中率</th><th>HHAD 命中率</th><th>最终赛果</th></tr></thead>
            <tbody>
              {rows.map((row) => <tr key={row.name}><th scope="row">{row.name}</th><td>{row.metrics.lockedPredictionCount}</td><td>{formatPercent(row.metrics.had.hitRate)}</td><td>{formatPercent(row.metrics.hhad.hitRate)}</td><td>{row.metrics.finalFactCount}</td></tr>)}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** T507 公开预测表现统计页。 */
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
