import { Alert, Button } from 'antd';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { BacktestCaseQuery } from '../../services/backtest';
import { formatTimestamp } from '../matches/matchPresentation';
import BacktestCasesTable from './BacktestCasesTable';
import BacktestMetricsTable from './BacktestMetricsTable';
import { backtestVersionSummary } from './backtestPresentation';
import {
  usePublicBacktestCasesQuery,
  usePublicBacktestComparisonQuery,
  usePublicBacktestRunQuery,
} from './useBacktestQueries';

/**
 * 公开的回测详情。
 *
 * <p>后端对未定型的 run 返回「不存在」，所以这里把 404 当成一种正常结局来解释，
 * 而不是报「出错了」——用户点进来看到的应该是一句「这次回测还没有公开结论」。</p>
 */
export default function PublicBacktestDetailPage() {
  const { runId: runIdParam } = useParams();
  const runId = Number(runIdParam);
  const validRunId = Number.isFinite(runId) && runId > 0 ? runId : undefined;

  const runQuery = usePublicBacktestRunQuery(validRunId);
  const comparisonQuery = usePublicBacktestComparisonQuery(validRunId);
  const [caseQuery, setCaseQuery] = useState<BacktestCaseQuery>({ page: 1, pageSize: 20 });
  const casesQuery = usePublicBacktestCasesQuery(validRunId, caseQuery);

  if (validRunId === undefined) {
    return <main className="page"><Alert type="error" showIcon message="回测编号无效" /></main>;
  }

  const run = runQuery.data;
  const groupKeys = comparisonQuery.data?.groupKeys ?? [];

  return (
    <main className="page">
      <section className="section-heading">
        <div>
          <p className="eyebrow">Research · Backtest</p>
          <h1>{run?.runName ?? '回测'}</h1>
          <p><Link to="/backtest">← 返回回测列表</Link></p>
        </div>
        <Button loading={runQuery.isFetching} onClick={() => void runQuery.refetch()}>刷新</Button>
      </section>

      {runQuery.isError && (
        <section className="state-card">
          <p>{runQuery.error.message}</p>
          <p>可能是这次回测还没有产出结论，或已被清理。公开端只展示状态为「成功」或「部分成功」的回测。</p>
        </section>
      )}
      {runQuery.isPending && <section className="state-card">正在读取回测……</section>}

      {run && (
        <>
          <section className="detail-section">
            <div className="section-heading">
              <div>
                <h2>{run.dateFrom} 至 {run.dateTo}</h2>
                <p>
                  {run.parameterSets.length} 个参数组 · 样本 {run.caseCount ?? '—'} 条
                  · 决策时点提前 {run.decisionLeadMinutes} 分钟
                </p>
                <p>{backtestVersionSummary(run)}</p>
                <p>完成于 {formatTimestamp(run.finishedAt)}</p>
              </div>
            </div>
            {run.runStatus === 'PARTIAL' && (
              <Alert
                type="warning"
                showIcon
                message="部分成功"
                description={`补采有 ${run.archiveFailedCount ?? 0} 场失败，样本数少于区间场次数。结论可用，但覆盖并不完整。`}
              />
            )}
          </section>

          {comparisonQuery.isError && (
            <Alert type="error" showIcon message={`指标读取失败：${comparisonQuery.error.message}`} />
          )}
          <BacktestMetricsTable comparison={comparisonQuery.data} isReady />

          <BacktestCasesTable
            page={casesQuery.data}
            isPending={casesQuery.isPending}
            isError={casesQuery.isError}
            errorMessage={casesQuery.isError ? casesQuery.error.message : undefined}
            query={caseQuery}
            groupKeys={groupKeys}
            onQueryChange={setCaseQuery}
          />
        </>
      )}
    </main>
  );
}
