import { Alert, Button, Input, Select } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { MAPPING_STATUSES, type MappingReviewStatus } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseMappingSearch, toMappingQuery, toMappingSearch } from './adminSearch';
import { useMappingReviewsQuery } from './useAdminQueries';

const labels: Record<MappingReviewStatus, string> = {
  PENDING: '待复核', AUTO_CONFIRMED: '自动确认', MANUAL_CONFIRMED: '人工确认', REJECTED: '已拒绝',
};

/** 映射复核队列与待处理数量。 */
export default function AdminMappingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseMappingSearch(searchParams);
  const query = useMappingReviewsQuery(toMappingQuery(filters));
  const page = query.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const returnSearch = searchParams.toString();
  function update(next: Partial<typeof filters>) {
    setSearchParams(toMappingSearch({ ...filters, ...next }));
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Mapping review</p><h1>映射复核</h1>
      <p>人工确认只能选择当前关联或服务端保留的候选比赛；所有决定由管理员 JWT 记录到追加式审计。</p></div>
      <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></section>
    <section className="admin-filters" aria-label="映射复核筛选">
      <label><span>Provider</span><Input aria-label="Provider" value={filters.providerCode ?? ''}
        onChange={(event) => update({ providerCode: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>映射状态</span><Select aria-label="映射状态" value={filters.mappingStatus}
        onChange={(value) => update({ mappingStatus: value as MappingReviewStatus, pageNo: 1 })}
        options={MAPPING_STATUSES.map((value) => ({ value, label: labels[value] }))} /></label>
    </section>
    {query.isPending && <section className="admin-state-card">正在读取映射复核队列……</section>}
    {query.isError && <Alert type="error" showIcon message={`映射复核不可用：${query.error.message}`} />}
    {query.isSuccess && page && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>{labels[filters.mappingStatus]}</h2><span>共 {page.total} 条</span></div>
      {query.isStale && <span>缓存数据，正在更新</span>}</header>
      {page.records.length === 0 ? <p className="admin-empty">当前筛选没有映射记录。</p> : <div className="admin-mapping-list">{page.records.map((mapping) => <Link
        className="admin-mapping-card" key={mapping.mappingId}
        to={`/admin/mappings/${mapping.mappingId}${returnSearch ? `?${returnSearch}` : ''}`}><header><strong>{mapping.providerCode}</strong>
          <span className={`admin-status mapping-${mapping.mappingStatus.toLowerCase()}`}>{labels[mapping.mappingStatus]}</span></header>
        <p>外部比赛：{mapping.externalMatchId}</p><dl><div><dt>候选</dt><dd>{mapping.candidateCount}</dd></div><div><dt>置信度</dt><dd>{mapping.mappingConfidence ?? '—'}</dd></div>
          <div><dt>更新时间</dt><dd>{formatTimestamp(mapping.updatedAt)}</dd></div></dl>
        <small>{mapping.mappingExplanation || '暂无映射解释'}</small></Link>)}</div>}
      <div className="pagination"><Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
        <span>第 {filters.pageNo} / {pageCount} 页</span><Button disabled={filters.pageNo >= pageCount} onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button></div>
    </section>}
  </main>;
}
