import { Alert, Button, Checkbox, Input, Modal, Select } from 'antd';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  PROVIDER_DATA_TYPES,
  SYNC_STATUSES,
  type ProviderDataType,
  type SyncStatus,
} from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseSyncRunSearch, todayInShanghai, toSyncRunQuery, toSyncRunSearch } from './adminSearch';
import {
  useAdminSyncRunErrorsQuery,
  useAdminSyncRunQuotaSummaryQuery,
  useAdminSyncRunsQuery,
  useAdminSportteryResultSyncAction,
} from './useAdminQueries';

const statusLabels: Record<SyncStatus, string> = {
  RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', PARTIAL: '部分成功',
};

/** 同步运行、错误与业务日额度总览。 */
export default function AdminSyncRunsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseSyncRunSearch(searchParams);
  const runsQuery = useAdminSyncRunsQuery(toSyncRunQuery(filters));
  const errorsQuery = useAdminSyncRunErrorsQuery({
    providerCode: filters.providerCode, dataType: filters.dataType, pageNo: 1, pageSize: 5,
  });
  const quotaQuery = useAdminSyncRunQuotaSummaryQuery(filters.businessDate);
  const yesterday = new Date(`${todayInShanghai()}T00:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  const defaultResultDate = yesterday.toISOString().slice(0, 10);
  const [resultStartDate, setResultStartDate] = useState(defaultResultDate);
  const [resultEndDate, setResultEndDate] = useState(defaultResultDate);
  const [resultConfirmOpen, setResultConfirmOpen] = useState(false);
  const resultSync = useAdminSportteryResultSyncAction();
  const page = runsQuery.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;

  function update(next: Partial<typeof filters>) {
    setSearchParams(toSyncRunSearch({ ...filters, ...next }));
  }

  function refresh() {
    void Promise.all([runsQuery.refetch(), errorsQuery.refetch(), quotaQuery.refetch()]);
  }

  const returnSearch = searchParams.toString();
  const dayCount = resultStartDate && resultEndDate
    ? Math.floor((Date.parse(`${resultEndDate}T00:00:00Z`) - Date.parse(`${resultStartDate}T00:00:00Z`)) / 86_400_000) + 1 : 0;
  const resultRangeValid = dayCount >= 1 && dayCount <= 7 && resultEndDate <= todayInShanghai();
  return (
    <main className="admin-page admin-workspace">
      <section className="admin-page-heading">
        <div>
          <p className="eyebrow">Operations · Sync runs</p>
          <h1>同步运行</h1>
          <p>查看已持久化运行、失败摘要和已消耗额度。预警阈值不是总额度或剩余额度。</p>
        </div>
        <Button loading={runsQuery.isFetching} onClick={refresh}>刷新</Button>
      </section>

      <section className="admin-panel" aria-label="手动同步体彩赛果">
        <header className="admin-panel-heading"><div><h2>手动同步体彩赛果</h2><span>默认上海昨日，最多连续 7 天</span></div></header>
        <p>只写入官方赛果事实，不会自动结算预测。</p>
        <div className="admin-filters"><label><span>开始日期</span><input aria-label="赛果开始日期" type="date" value={resultStartDate} onChange={(event) => setResultStartDate(event.target.value)} /></label>
          <label><span>结束日期</span><input aria-label="赛果结束日期" type="date" value={resultEndDate} onChange={(event) => setResultEndDate(event.target.value)} /></label>
          <Button type="primary" disabled={!resultRangeValid} onClick={() => setResultConfirmOpen(true)}>同步赛果</Button></div>
        {!resultRangeValid && <Alert type="warning" showIcon message="日期范围必须是截至上海今天的连续 1 至 7 天。" />}
        {resultSync.isError && <Alert type="error" showIcon message={`同步请求失败：${resultSync.error.message}`} />}
        {resultSync.data && <Alert type={resultSync.data.syncStatus === 'SUCCESS' ? 'success' : 'warning'} showIcon
          message={`运行 #${resultSync.data.syncRunId} · ${statusLabels[resultSync.data.syncStatus]}`}
          description={<span>拉取 {resultSync.data.fetchedCount}，成功/失败 {resultSync.data.successCount}/{resultSync.data.failureCount}，追加/替代/跳过 {resultSync.data.appendedFactCount}/{resultSync.data.supersededFactCount}/{resultSync.data.unchangedFactCount}。{resultSync.data.errorSummary ? ` ${resultSync.data.errorSummary}` : ' '}<Link to={`/admin/sync-runs/${resultSync.data.syncRunId}`}>查看运行详情</Link></span>} />}
      </section>

      <Modal title="确认同步体彩赛果" open={resultConfirmOpen} onCancel={() => setResultConfirmOpen(false)}
        confirmLoading={resultSync.isPending} okText="确认同步" onOk={() => resultSync.mutate({ startDate: resultStartDate, endDate: resultEndDate }, { onSuccess: () => setResultConfirmOpen(false) })}>
        <p>将同步 {resultStartDate} 至 {resultEndDate} 的体彩官方赛果。此操作不会自动结算预测。</p>
      </Modal>

      <section className="admin-filters" aria-label="同步运行筛选">
        <label><span>Provider</span><Input aria-label="Provider" value={filters.providerCode ?? ''}
          onChange={(event) => update({ providerCode: event.target.value || undefined, pageNo: 1 })} /></label>
        <label><span>数据类型</span><Select aria-label="数据类型" allowClear value={filters.dataType}
          onChange={(value) => update({ dataType: value as ProviderDataType | undefined, pageNo: 1 })}
          options={PROVIDER_DATA_TYPES.map((value) => ({ value, label: value }))} /></label>
        <label><span>额度业务日</span><input aria-label="额度业务日" type="date" value={filters.businessDate}
          onChange={(event) => update({ businessDate: event.target.value })} /></label>
        <fieldset className="admin-status-filter"><legend>运行状态</legend>{SYNC_STATUSES.map((status) => (
          <Checkbox key={status} checked={filters.syncStatuses.includes(status)} onChange={(event) => update({
            syncStatuses: event.target.checked
              ? [...filters.syncStatuses, status]
              : filters.syncStatuses.filter((candidate) => candidate !== status), pageNo: 1,
          })}>{statusLabels[status]}</Checkbox>
        ))}</fieldset>
      </section>

      {runsQuery.isError && <Alert type="error" showIcon message={`同步运行暂不可用：${runsQuery.error.message}`} />}
      {runsQuery.isPending && <section className="admin-state-card">正在读取同步运行……</section>}
      {runsQuery.isSuccess && page && (
        <section className="admin-panel">
          <header className="admin-panel-heading"><div><h2>运行列表</h2><span>共 {page.total} 条</span></div>
            {runsQuery.isStale && <span>缓存数据，正在更新</span>}</header>
          {page.records.length === 0 ? <p className="admin-empty">当前筛选没有同步运行。</p> : <div className="admin-run-list">
            {page.records.map((run) => <Link className="admin-run-card" key={run.syncRunId}
              to={`/admin/sync-runs/${run.syncRunId}${returnSearch ? `?${returnSearch}` : ''}`}>
              <header><strong>{run.providerCode}</strong><span className={`admin-status ${run.syncStatus.toLowerCase()}`}>{statusLabels[run.syncStatus]}</span></header>
              <p>{run.dataType} · 开始于 {formatTimestamp(run.startedAt)}</p>
              <dl><div><dt>成功/失败</dt><dd>{run.successCount}/{run.failureCount}</dd></div>
                <div><dt>重试</dt><dd>{run.retryCount}</dd></div><div><dt>消耗额度</dt><dd>{run.quotaCost}</dd></div></dl>
              {run.errorSummary && <small className="admin-error-text">{run.errorSummary}</small>}
            </Link>)}
          </div>}
          <div className="pagination"><Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
            <span>第 {filters.pageNo} / {pageCount} 页</span><Button disabled={filters.pageNo >= pageCount}
              onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button></div>
        </section>
      )}

      <section className="admin-two-column">
        <section className="admin-panel"><header className="admin-panel-heading"><div><h2>额度消耗</h2><span>{filters.businessDate}（上海）</span></div></header>
          {quotaQuery.isPending && <p className="admin-empty">正在汇总额度……</p>}
          {quotaQuery.isError && <Alert type="error" showIcon message={quotaQuery.error.message} />}
          {quotaQuery.data && (quotaQuery.data.items.length === 0 ? <p className="admin-empty">当日暂无额度消耗记录。</p> :
            <div className="admin-quota-list">{quotaQuery.data.items.map((item) => <article key={`${item.providerCode}-${item.dataType}`}>
              <strong>{item.providerCode}</strong><span>{item.dataType}</span><b>{item.consumedQuota}</b><small>已消耗 · {item.runCount} 次运行</small>
              <p className={item.warningTriggered ? 'admin-warning' : ''}>{item.warningThreshold === null
                ? '未配置预警阈值' : `预警阈值：${item.warningThreshold}${item.warningTriggered ? '（已触发）' : ''}`}</p>
            </article>)}</div>)}</section>
        <section className="admin-panel"><header className="admin-panel-heading"><div><h2>最近错误</h2><span>失败与部分成功</span></div></header>
          {errorsQuery.isPending && <p className="admin-empty">正在读取错误……</p>}
          {errorsQuery.isError && <Alert type="error" showIcon message={errorsQuery.error.message} />}
          {errorsQuery.data && (errorsQuery.data.records.length === 0 ? <p className="admin-empty">当前没有失败或部分成功运行。</p> :
            <ul className="admin-error-list">{errorsQuery.data.records.map((error) => <li key={error.syncRunId}>
              <Link to={`/admin/sync-runs/${error.syncRunId}${returnSearch ? `?${returnSearch}` : ''}`}>{error.providerCode} · {error.dataType}</Link>
              <span>{error.errorSummary || '未记录错误文本'}</span></li>)}</ul>)}</section>
      </section>
    </main>
  );
}
