import { Alert, Button, Checkbox, Input, Select } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import {
  LOCK_DIAGNOSTICS,
  PREDICTION_STATUSES,
  type LockDiagnostic,
  type PredictionStatus,
} from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { diagnosticsText, lockLabels } from './adminPredictionPresentation';
import { parsePredictionLockSearch, toPredictionLockQuery, toPredictionLockSearch } from './adminSearch';
import { useAdminPredictionLocksQuery } from './useAdminQueries';

/** 已发布预测的锁定状态和到期滞后只读页面。 */
export default function AdminPredictionLocksPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parsePredictionLockSearch(searchParams);
  const query = useAdminPredictionLocksQuery(toPredictionLockQuery(filters));
  const page = query.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const returnSearch = searchParams.toString();
  function update(next: Partial<typeof filters>) {
    setSearchParams(toPredictionLockSearch({ ...filters, ...next }));
  }
  function toggleStatus(status: PredictionStatus, checked: boolean) {
    update({ predictionStatuses: checked
      ? [...filters.predictionStatuses, status]
      : filters.predictionStatuses.filter((value) => value !== status), pageNo: 1 });
  }
  function toggleDiagnostic(diagnostic: LockDiagnostic, checked: boolean) {
    update({ lockDiagnostics: checked
      ? [...filters.lockDiagnostics, diagnostic]
      : filters.lockDiagnostics.filter((value) => value !== diagnostic), pageNo: 1 });
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Prediction locks</p><h1>预测锁定</h1>
      <p>诊断使用数据库时间判断；只展示已发布或已锁定预测，草稿不会进入运营队列。</p></div>
      <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></section>
    <section className="admin-filters" aria-label="预测锁定筛选">
      <label><span>比赛日期</span><input aria-label="比赛日期" type="date" value={filters.lotteryDate ?? ''}
        onChange={(event) => update({ lotteryDate: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>模型版本</span><Input aria-label="模型版本" value={filters.modelVersion ?? ''}
        onChange={(event) => update({ modelVersion: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>锁定诊断</span><Select aria-label="锁定诊断" mode="multiple" allowClear value={filters.lockDiagnostics}
        onChange={(value) => update({ lockDiagnostics: value as LockDiagnostic[], pageNo: 1 })}
        options={LOCK_DIAGNOSTICS.map((value) => ({ value, label: lockLabels[value] }))} /></label>
      <fieldset className="admin-status-filter"><legend>预测状态</legend>{PREDICTION_STATUSES.map((status) => <Checkbox key={status}
        checked={filters.predictionStatuses.includes(status)} onChange={(event) => toggleStatus(status, event.target.checked)}>
        {status === 'PUBLISHED' ? '已发布' : '已锁定'}
      </Checkbox>)}</fieldset>
    </section>
    {query.isPending && <section className="admin-state-card">正在读取预测锁定状态……</section>}
    {query.isError && <Alert type="error" showIcon message={`预测锁定暂不可用：${query.error.message}`} />}
    {query.isSuccess && page && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>锁定运营队列</h2>
      <span>共 {page.total} 条 · 待人工处理 {page.manualAttentionCount} 条</span></div>{query.isStale && <span>缓存数据，正在更新</span>}</header>
      {page.records.length === 0 ? <p className="admin-empty">当前筛选没有已发布或已锁定预测。</p> : <div className="admin-operation-list">{page.records.map((item) => <Link className="admin-operation-card" key={item.predictionId}
        to={`/admin/predictions/${item.predictionId}${returnSearch ? `?${returnSearch}` : ''}`}><header><strong>{item.match.homeTeamName} vs {item.match.awayTeamName}</strong>
          <span className={`admin-status lock-${item.lockDiagnostics[0]?.code.toLowerCase()}`}>{diagnosticsText(item.lockDiagnostics, lockLabels)}</span></header>
        <p>{item.match.lotteryDate} · {item.match.leagueName} · {formatTimestamp(item.match.kickoffTime)}</p>
        <dl><div><dt>模型 / 版本</dt><dd>{item.modelVersion} · V{item.predictionVersion}</dd></div><div><dt>预测状态</dt><dd>{item.predictionStatus === 'PUBLISHED' ? '已发布' : '已锁定'}</dd></div>
          <div><dt>锁定时间</dt><dd>{formatTimestamp(item.lockTime)}</dd></div></dl></Link>)}</div>}
      <div className="pagination"><Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
        <span>第 {filters.pageNo} / {pageCount} 页</span><Button disabled={filters.pageNo >= pageCount} onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button></div>
    </section>}
  </main>;
}
