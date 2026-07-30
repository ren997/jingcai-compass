import { Alert, Button, Input, Select } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import {
  MAPPING_STATUSES,
  type MappingReviewStatus,
  type NormalizationEntityType,
  type ProviderNormalizationReviewListItem,
} from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseNormalizationSearch, toNormalizationQuery, toNormalizationSearch } from './adminSearch';
import { useProviderNormalizationsQuery } from './useAdminQueries';

type Props = { entityType: NormalizationEntityType };

const statusLabels: Record<MappingReviewStatus, string> = {
  PENDING: '待复核', AUTO_CONFIRMED: '自动确认', MANUAL_CONFIRMED: '人工确认', REJECTED: '已拒绝',
};

function title(type: NormalizationEntityType) {
  return type === 'LEAGUE' ? '联赛标准化复核' : '球队标准化复核';
}

function entityName(item: ProviderNormalizationReviewListItem) {
  const entity = item.currentEntity;
  if (!entity) return '暂无内部暂存实体';
  return entity.nameZh || entity.nameEn || `内部实体 #${entity.entityId}`;
}

/** 供应商联赛或球队标准化映射的后台列表。 */
export default function AdminNormalizationsPage({ entityType }: Props) {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseNormalizationSearch(searchParams);
  const query = useProviderNormalizationsQuery(toNormalizationQuery(entityType, filters));
  const page = query.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const listPath = entityType === 'LEAGUE' ? '/admin/normalizations/leagues' : '/admin/normalizations/teams';

  function update(next: Partial<typeof filters>) {
    setSearchParams(toNormalizationSearch({ ...filters, ...next }));
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Provider normalization</p><h1>{title(entityType)}</h1>
      <p>仅单独确认 Provider {entityType === 'LEAGUE' ? '联赛' : '球队'}身份与内部标准实体；赛事映射、开赛时间和相似名称不产生别名。</p></div>
      <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></section>
    <section className="admin-filters" aria-label={`${title(entityType)}筛选`}>
      <label><span>Provider</span><Input aria-label="Provider" value={filters.providerCode ?? ''}
        onChange={(event) => update({ providerCode: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>映射状态</span><Select aria-label="映射状态" value={filters.mappingStatus}
        onChange={(value) => update({ mappingStatus: value as MappingReviewStatus, pageNo: 1 })}
        options={MAPPING_STATUSES.map((value) => ({ value, label: statusLabels[value] }))} /></label>
    </section>
    {query.isPending && <section className="admin-state-card">正在读取标准化复核项……</section>}
    {query.isError && <Alert type="error" showIcon message={`${title(entityType)}不可用：${query.error.message}`} />}
    {query.isSuccess && page && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>{statusLabels[filters.mappingStatus]}项</h2><span>共 {page.total} 项</span></div>{query.isStale && <span>缓存数据，正在更新</span>}</header>
      {page.records.length === 0 ? <p className="admin-empty">当前筛选没有可复核的 Provider {entityType === 'LEAGUE' ? '联赛' : '球队'}。</p> : <div className="admin-mapping-list">{page.records.map((item) => <article className="admin-mapping-card" key={item.mappingId}>
        <header><strong>{item.externalDisplayName || item.externalId}</strong><span className={`admin-status mapping-${item.mappingStatus.toLowerCase()}`}>{statusLabels[item.mappingStatus]}</span></header>
        <p>{item.providerCode} · 外部 ID：{item.externalId}</p>
        <dl><div><dt>作用域</dt><dd>{item.externalScope || '无'}</dd></div><div><dt>规范化键</dt><dd>{item.externalNormalizedKey || '历史记录未采集'}</dd></div><div><dt>暂存实体</dt><dd>{entityName(item)}</dd></div></dl>
        <div className="admin-actions"><Link to={`${listPath}/${item.mappingId}${searchParams.toString() ? `?${searchParams}` : ''}`}>复核详情</Link><small>更新于 {formatTimestamp(item.updatedAt)}</small></div>
      </article>)}</div>}
      <div className="pagination"><Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
        <span>第 {filters.pageNo} / {pageCount} 页</span><Button disabled={filters.pageNo >= pageCount} onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button></div>
    </section>}
  </main>;
}
