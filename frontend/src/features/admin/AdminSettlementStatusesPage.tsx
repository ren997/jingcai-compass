import { Alert, Button, Input, Select } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { SETTLEMENT_DIAGNOSTICS, type SettlementDiagnostic } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { diagnosticsText, factText, marketText, settlementLabels } from './adminPredictionPresentation';
import { parseSettlementStatusSearch, toSettlementStatusQuery, toSettlementStatusSearch } from './adminSearch';
import { useAdminSettlementStatusesQuery } from './useAdminQueries';

/** 已锁定预测的待赛果、待结算和重算状态只读页面。 */
export default function AdminSettlementStatusesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseSettlementStatusSearch(searchParams);
  const query = useAdminSettlementStatusesQuery(toSettlementStatusQuery(filters));
  const page = query.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const returnSearch = searchParams.toString();
  function update(next: Partial<typeof filters>) {
    setSearchParams(toSettlementStatusSearch({ ...filters, ...next }));
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Settlement status</p><h1>结算状态</h1>
      <p>显示等待官方赛果、当前市场结算缺失或引用已替代赛果的锁定预测；此处只读，不可人工改写结算。</p></div>
      <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></section>
    <section className="admin-filters" aria-label="结算状态筛选">
      <label><span>比赛日期</span><input aria-label="比赛日期" type="date" value={filters.lotteryDate ?? ''}
        onChange={(event) => update({ lotteryDate: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>模型版本</span><Input aria-label="模型版本" value={filters.modelVersion ?? ''}
        onChange={(event) => update({ modelVersion: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>结算诊断</span><Select aria-label="结算诊断" mode="multiple" allowClear value={filters.diagnostics}
        onChange={(value) => update({ diagnostics: value as SettlementDiagnostic[], pageNo: 1 })}
        options={SETTLEMENT_DIAGNOSTICS.map((value) => ({ value, label: settlementLabels[value] }))} /></label>
    </section>
    {query.isPending && <section className="admin-state-card">正在读取结算状态……</section>}
    {query.isError && <Alert type="error" showIcon message={`结算状态暂不可用：${query.error.message}`} />}
    {query.isSuccess && page && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>结算运营队列</h2>
      <span>共 {page.total} 条 · 待人工处理 {page.manualAttentionCount} 条</span></div>{query.isStale && <span>缓存数据，正在更新</span>}</header>
      {page.records.length === 0 ? <p className="admin-empty">当前筛选没有待赛果、待结算或需重算记录。</p> : <div className="admin-operation-list">{page.records.map((item) => <Link className="admin-operation-card" key={item.predictionId}
        to={`/admin/settlements/${item.predictionId}${returnSearch ? `?${returnSearch}` : ''}`}><header><strong>{item.match.homeTeamName} vs {item.match.awayTeamName}</strong>
          <span className="admin-status settlement-attention">{diagnosticsText(item.settlementDiagnostics, settlementLabels)}</span></header>
        <p>{item.match.lotteryDate} · {item.modelVersion} V{item.predictionVersion} · {formatTimestamp(item.match.kickoffTime)}</p>
        <dl><div><dt>当前赛果</dt><dd>{factText(item.currentResultFact)}</dd></div><div><dt>HAD</dt><dd>{marketText(item.hadSettlement)}</dd></div>
          <div><dt>HHAD</dt><dd>{marketText(item.hhadSettlement)}</dd></div></dl></Link>)}</div>}
      <div className="pagination"><Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
        <span>第 {filters.pageNo} / {pageCount} 页</span><Button disabled={filters.pageNo >= pageCount} onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button></div>
    </section>}
  </main>;
}
