import { Alert, Button, Select } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import {
  BACKTEST_RUN_STATUSES,
  type BacktestRunListQuery,
  type BacktestRunStatus,
  type BacktestRunVo,
} from '../../services/backtest';
import { formatTimestamp } from '../matches/matchPresentation';
import BacktestCreateForm from './BacktestCreateForm';
import {
  backtestProgressText,
  backtestRunStatusLabel,
  backtestRunStatusTone,
} from './backtestPresentation';
import {
  useAdminBacktestRunsQuery,
  useCreateBacktestRunMutation,
  useEstimateBacktestMutation,
} from './useBacktestQueries';

const PAGE_SIZE = 10;

/** 管理端回测列表与创建入口。 */
export default function BacktestRunsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Number(searchParams.get('page') ?? '1') || 1;
  const statusFilter = (searchParams.get('status') ?? '') as BacktestRunStatus | '';

  const query: BacktestRunListQuery = {
    runStatus: statusFilter || undefined,
    page,
    pageSize: PAGE_SIZE,
  };
  const runsQuery = useAdminBacktestRunsQuery(query);
  const estimate = useEstimateBacktestMutation();
  const create = useCreateBacktestRunMutation();

  function update(next: { page?: number; status?: string }) {
    const params = new URLSearchParams();
    const nextStatus = next.status === undefined ? statusFilter : next.status;
    if (nextStatus) {
      params.set('status', nextStatus);
    }
    const nextPage = next.page ?? page;
    if (nextPage > 1) {
      params.set('page', String(nextPage));
    }
    setSearchParams(params);
  }

  const data = runsQuery.data;
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <main className="admin-page admin-workspace">
      <section className="admin-page-heading">
        <div>
          <p className="eyebrow">Research · Backtest</p>
          <h1>历史回测</h1>
          <p>
            用归档的历史赔率按指定决策时点重放模型判定，再用生产同一批结算函数核对结果。
            结论只在参数组之间、同一批样本内可比。
          </p>
        </div>
        <Button loading={runsQuery.isFetching} onClick={() => void runsQuery.refetch()}>刷新</Button>
      </section>

      <BacktestCreateForm
        estimate={estimate.data}
        estimating={estimate.isPending}
        creating={create.isPending}
        estimateError={estimate.isError ? estimate.error.message : undefined}
        createError={create.isError ? create.error.message : undefined}
        onEstimate={(request) => estimate.mutate(request)}
        onCreate={(request) => create.mutate(request)}
      />

      {create.data && (
        <Alert
          type="success"
          showIcon
          message={`已入队：${create.data.runName}`}
          description={<Link to={`/admin/backtest/${create.data.id}`}>查看进度与指标</Link>}
        />
      )}

      <section className="admin-filters" aria-label="回测筛选">
        {/* 单选而不是多选：状态是线性的推进序列，勾选多个会让人以为「排队中 + 失败」是并列关系。 */}
        <label>
          <span>运行状态</span>
          <Select
            aria-label="运行状态"
            allowClear
            placeholder="全部"
            value={statusFilter || undefined}
            onChange={(value) => update({ status: value ?? '', page: 1 })}
            options={BACKTEST_RUN_STATUSES.map((status) => ({
              value: status,
              label: backtestRunStatusLabel(status),
            }))}
          />
        </label>
      </section>

      {runsQuery.isError && <Alert type="error" showIcon message={`回测列表读取失败：${runsQuery.error.message}`} />}
      {runsQuery.isPending && <section className="admin-state-card">正在读取回测……</section>}

      {data && (
        <section className="admin-panel">
          <header className="admin-panel-heading">
            <div><h2>回测列表</h2><span>共 {data.total} 条</span></div>
            {runsQuery.isStale && <span>缓存数据，正在更新</span>}
          </header>
          {data.records.length === 0 ? (
            <p className="admin-empty">当前筛选没有回测。先在上方创建一次。</p>
          ) : (
            <div className="admin-run-list">
              {data.records.map((run) => <RunCard key={run.id} run={run} />)}
            </div>
          )}
          <div className="pagination">
            <Button disabled={page <= 1} onClick={() => update({ page: page - 1 })}>上一页</Button>
            <span>第 {page} / {pageCount} 页</span>
            <Button disabled={page >= pageCount} onClick={() => update({ page: page + 1 })}>下一页</Button>
          </div>
        </section>
      )}
    </main>
  );
}

function RunCard({ run }: { run: BacktestRunVo }) {
  return (
    <Link className="admin-run-card" to={`/admin/backtest/${run.id}`}>
      <header>
        <strong>{run.runName}</strong>
        <span className={`admin-status ${backtestRunStatusTone(run.runStatus)}`}>
          {backtestRunStatusLabel(run.runStatus)}
        </span>
      </header>
      <p>{run.dateFrom} 至 {run.dateTo} · {backtestProgressText(run)}</p>
      <dl>
        <div><dt>样本</dt><dd>{run.caseCount ?? '—'}</dd></div>
        <div><dt>参数组</dt><dd>{run.parameterSets.length}</dd></div>
        <div><dt>额度</dt><dd>{run.quotaCost ?? '—'}</dd></div>
        <div><dt>创建</dt><dd>{formatTimestamp(run.createdAt)}</dd></div>
      </dl>
      {run.errorCode && <small className="admin-error-text">{run.errorCode}</small>}
    </Link>
  );
}
