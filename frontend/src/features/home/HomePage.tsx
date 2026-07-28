import { Link } from 'react-router-dom';
import type { StatisticsMetricsVo, StatisticsWindowVo } from '../../services/public';
import { formatTimestamp } from '../matches/matchPresentation';
import {
  formatDecimal,
  formatPercent,
  probabilityUnavailableReasonLabel,
  roiUnavailableReasonLabel,
} from '../history/historyPresentation';
import { useHomeSummaryQuery } from './useHomeSummaryQuery';

function metricValue(value: string) {
  return value === '—' ? '暂无' : value;
}

function formatAge(seconds: number | null) {
  if (seconds === null) {
    return '暂无当天体彩采集';
  }
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} 分钟`;
  }
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`;
}

function PerformanceCard({ title, window, probability }: {
  title: string;
  window: StatisticsWindowVo;
  probability?: boolean;
}) {
  const metrics = window.metrics;
  return (
    <article className="home-performance-card">
      <header>
        <strong>{title}</strong>
        <span>{window.startDate} 至 {window.endDate}</span>
      </header>
      {probability ? <ProbabilityMetrics metrics={metrics} /> : <HitRateMetrics metrics={metrics} />}
    </article>
  );
}

function HitRateMetrics({ metrics }: { metrics: StatisticsMetricsVo }) {
  return (
    <dl className="home-performance-metrics">
      <div><dt>HAD 命中率</dt><dd>{metricValue(formatPercent(metrics.had.hitRate))}</dd><small>已结算样本 {metrics.had.settledSampleSize}</small></div>
      <div><dt>HHAD 命中率</dt><dd>{metricValue(formatPercent(metrics.hhad.hitRate))}</dd><small>已结算样本 {metrics.hhad.settledSampleSize}</small></div>
      <div><dt>当前最终赛果</dt><dd>{metrics.finalFactCount}</dd><small>待定 {metrics.pendingFactCount} · 作废 {metrics.voidFactCount}</small></div>
    </dl>
  );
}

function ProbabilityMetrics({ metrics }: { metrics: StatisticsMetricsVo }) {
  const probability = metrics.probabilityMetrics;
  return (
    <>
      <dl className="home-performance-metrics">
        <div><dt>Brier Score</dt><dd>{metricValue(formatDecimal(probability.brierScore))}</dd><small>最终样本 {probability.sampleSize}</small></div>
        <div><dt>Log Loss</dt><dd>{metricValue(formatDecimal(probability.logLoss))}</dd><small>仅基于当前最终赛果</small></div>
      </dl>
      {probability.unavailableReasons.length > 0 && <p className="metric-unavailable">概率指标暂不可用：{probability.unavailableReasons.map(probabilityUnavailableReasonLabel).join('；')}</p>}
      {metrics.roi.available ? (
        <p className="roi-available">ROI：{metricValue(formatPercent(metrics.roi.roi))} · Yield：{metricValue(formatPercent(metrics.roi.yield))} · 样本 {metrics.roi.sampleSize}</p>
      ) : (
        <p className="metric-unavailable">ROI / Yield 暂不可用：{metrics.roi.unavailableReasons.map(roiUnavailableReasonLabel).join('；')}</p>
      )}
    </>
  );
}

/** 数据库事实驱动的公开首页。 */
export default function HomePage() {
  const homeQuery = useHomeSummaryQuery();
  const summary = homeQuery.data;

  return (
    <main className="page">
      <section className="hero home-hero">
        <div>
          <p className="eyebrow">JingCai Compass · Public Facts</p>
          <h1>公开事实，持续检验每一场预测</h1>
          <p className="summary">面向中国体彩竞彩足球的数据分析工具：公开记录、自动结算和长期表现口径都可追溯。</p>
        </div>
      </section>

      {homeQuery.isPending && <section className="state-card">正在加载首页汇总……</section>}
      {homeQuery.isError && (
        <section className="state-card error" role="alert">
          首页汇总暂不可用：{homeQuery.error.message}
          <button className="refresh-button" type="button" onClick={() => void homeQuery.refetch()} disabled={homeQuery.isFetching}>
            {homeQuery.isFetching ? '刷新中…' : '刷新'}
          </button>
        </section>
      )}
      {homeQuery.isSuccess && summary && (
        <>
          <section className="summary-strip home-summary-strip">
            <div><span>首页截至</span><strong>{summary.asOfDate}</strong></div>
            <div><span>汇总生成时间</span><strong>{formatTimestamp(summary.generatedAt)}</strong></div>
            <p className={homeQuery.isStale ? 'data-stale' : undefined}>{homeQuery.isStale ? '首页数据可能已过期，请刷新。' : '所有指标均从已持久化的公开事实重建。'}</p>
            <button className="refresh-button" type="button" onClick={() => void homeQuery.refetch()} disabled={homeQuery.isFetching}>{homeQuery.isFetching ? '刷新中…' : '刷新'}</button>
          </section>

          <section className="home-metric-grid" aria-label="首页核心指标">
            <article><span>今日竞彩比赛</span><strong>{summary.today.matchCount}</strong><small>{summary.asOfDate} 上海竞彩日</small></article>
            <article><span>今日已发布预测</span><strong>{summary.today.publishedPredictionMatchCount}</strong><small>按比赛去重</small></article>
            <article><span>待结算比赛</span><strong>{summary.pendingSettlementMatchCount}</strong><small>锁定预测的 HAD 尚未终态</small></article>
            <article><span>累计公开预测比赛</span><strong>{summary.historicalPublishedMatchCount}</strong><small>历史版本与多模型不重复计数</small></article>
          </section>

          <section className="home-performance-grid" aria-label="近期表现">
            <PerformanceCard title="近 7 天结算概览" window={summary.trailingSevenDays} />
            <PerformanceCard title="近 30 天概率评估" window={summary.trailingThirtyDays} probability />
          </section>

          <section className="home-data-grid" aria-label="数据新鲜度与快照">
            <article>
              <span>当天体彩最后采集</span>
              <strong>{summary.dataFreshness.sportteryLastCapturedAt ? formatTimestamp(summary.dataFreshness.sportteryLastCapturedAt) : '暂无'}</strong>
              <small>数据年龄：{formatAge(summary.dataFreshness.sportteryDataAgeSeconds)}</small>
            </article>
            <article>
              <span>最近一次发布快照</span>
              <strong>{summary.latestPublishedSnapshotAt ? formatTimestamp(summary.latestPublishedSnapshotAt) : '暂无'}</strong>
              <small>{summary.latestPublishedSnapshotAt ? '成功发布的公开快照时间' : '当前没有已发布快照'}</small>
            </article>
          </section>

          <section className="home-action-list" aria-label="公共功能入口">
            <Link to="/matches">查看每日比赛</Link>
            <Link to="/history">查看公开预测历史</Link>
            <Link to="/statistics">查看完整表现统计</Link>
          </section>

          <section className="home-risk-card">
            <p className="eyebrow">Notice</p>
            <h2>分析工具，不构成投注建议</h2>
            <p>平台仅展示竞彩足球数据分析、概率预测与可追溯的历史结算。历史表现不代表未来结果，请结合数据更新时间、样本量和风险承受能力独立判断。</p>
          </section>
        </>
      )}
    </main>
  );
}
