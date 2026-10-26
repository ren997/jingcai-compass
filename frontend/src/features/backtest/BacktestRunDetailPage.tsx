import { Alert, Button } from 'antd';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { BacktestCaseQuery } from '../../services/backtest';
import { isPublishableBacktestStatus } from '../../services/backtest';
import BacktestCasesTable from './BacktestCasesTable';
import BacktestMetricsTable from './BacktestMetricsTable';
import BacktestProgressPanel from './BacktestProgressPanel';
import {
  useAdminBacktestCasesQuery,
  useAdminBacktestComparisonQuery,
  useAdminBacktestRunQuery,
  useCancelBacktestRunMutation,
  useResumeBacktestRunMutation,
} from './useBacktestQueries';

/** 管理端回测详情：进度、参数组指标、逐场明细。 */
export default function BacktestRunDetailPage() {
  const { runId: runIdParam } = useParams();
  const runId = Number(runIdParam);
  const validRunId = Number.isFinite(runId) && runId > 0 ? runId : undefined;

  const runQuery = useAdminBacktestRunQuery(validRunId);
  const run = runQuery.data;
  const metricsReady = run ? isPublishableBacktestStatus(run.runStatus) : false;

  const comparisonQuery = useAdminBacktestComparisonQuery(validRunId, metricsReady);
  const groupKeys = comparisonQuery.data?.groupKeys ?? [];

  const [caseQuery, setCaseQuery] = useState<BacktestCaseQuery>({ page: 1, pageSize: 20 });
  const casesQuery = useAdminBacktestCasesQuery(validRunId, caseQuery);

  const cancel = useCancelBacktestRunMutation();
  const resume = useResumeBacktestRunMutation();

  if (validRunId === undefined) {
    return (
      <main className="admin-page admin-workspace">
        <Alert type="error" showIcon message="回测编号无效" />
      </main>
    );
  }

  return (
    <main className="admin-page admin-workspace">
      <section className="admin-page-heading">
        <div>
          <p className="eyebrow">Research · Backtest run</p>
          <h1>{run?.runName ?? `回测 #${validRunId}`}</h1>
          <p><Link to="/admin/backtest">← 返回回测列表</Link></p>
        </div>
        <Button loading={runQuery.isFetching} onClick={() => void runQuery.refetch()}>刷新</Button>
      </section>

      {runQuery.isError && (
        <Alert
          type="error"
          showIcon
          message={`回测读取失败：${runQuery.error.message}`}
          description="状态为 FAILED 或已被清理的 run 需要回列表确认。"
        />
      )}
      {runQuery.isPending && <section className="admin-state-card">正在读取回测……</section>}

      {run && (
        <>
          {run.runStatus === 'INTERRUPTED' && (
            <Alert
              type="warning"
              showIcon
              message="进程中断"
              description="worker 心跳超时后被标记为中断。已写入的样本保留，续跑会从中断处继续。"
            />
          )}

          <BacktestProgressPanel
            run={run}
            cancelling={cancel.isPending}
            resuming={resume.isPending}
            actionError={
              cancel.isError ? `取消失败：${cancel.error.message}`
                : resume.isError ? `续跑失败：${resume.error.message}` : undefined
            }
            onCancel={() => cancel.mutate(validRunId)}
            onResume={() => resume.mutate(validRunId)}
          />

          {comparisonQuery.isError && (
            <Alert type="error" showIcon message={`指标读取失败：${comparisonQuery.error.message}`} />
          )}
          <BacktestMetricsTable comparison={comparisonQuery.data} isReady={metricsReady} />

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
