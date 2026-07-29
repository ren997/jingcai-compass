import { Alert, Button, Input, Modal, Radio, Select } from 'antd';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  MAPPING_STATUSES,
  type MappingReviewExternalCandidate,
  type MappingReviewMatchListItem,
  type MappingReviewStatus,
} from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseMappingSearch, toMappingQuery, toMappingSearch } from './adminSearch';
import { useMappingReviewActions, useMappingReviewMatchesQuery } from './useAdminQueries';

const labels: Record<MappingReviewStatus, string> = {
  PENDING: '待复核', AUTO_CONFIRMED: '自动确认', MANUAL_CONFIRMED: '人工确认', REJECTED: '已拒绝',
};

type PendingConfirmation = {
  match: MappingReviewMatchListItem['match'];
  external: MappingReviewExternalCandidate;
};

function externalName(external: MappingReviewExternalCandidate) {
  if (external.externalHomeTeamName || external.externalAwayTeamName) {
    return `${external.externalHomeTeamName ?? '未知主队'} vs ${external.externalAwayTeamName ?? '未知客队'}`;
  }
  return `外部赛事 ${external.externalMatchId}`;
}

/** 以竞彩比赛为主体选择可安全确认的外部比赛。 */
export default function AdminMappingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseMappingSearch(searchParams);
  const query = useMappingReviewMatchesQuery(toMappingQuery(filters));
  const actions = useMappingReviewActions();
  const [selectedExternal, setSelectedExternal] = useState<Record<number, number>>({});
  const [pendingConfirmation, setPendingConfirmation] = useState<PendingConfirmation | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const page = query.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const returnSearch = searchParams.toString();
  const actionError = actions.confirm.error;

  function update(next: Partial<typeof filters>) {
    setSearchParams(toMappingSearch({ ...filters, ...next }));
  }

  function selectedCandidate(item: MappingReviewMatchListItem) {
    return selectedExternal[item.match.matchId] ?? item.externalCandidates[0]?.mappingId;
  }

  async function confirm() {
    if (!pendingConfirmation || confirmation !== '确认关联') return;
    await actions.confirm.mutateAsync({
      mappingId: pendingConfirmation.external.mappingId,
      targetMatchId: pendingConfirmation.match.matchId,
    });
    setPendingConfirmation(null);
    setConfirmation('');
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Match mapping review</p><h1>竞彩比赛映射复核</h1>
      <p>先核对竞彩比赛，再从服务端保留的外部赛事候选中选择关联；不支持手工输入任意比赛 ID。</p></div>
      <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></section>
    <section className="admin-filters" aria-label="映射复核筛选">
      <label><span>Provider</span><Input aria-label="Provider" value={filters.providerCode ?? ''}
        onChange={(event) => update({ providerCode: event.target.value || undefined, pageNo: 1 })} /></label>
      <label><span>映射状态</span><Select aria-label="映射状态" value={filters.mappingStatus}
        onChange={(value) => update({ mappingStatus: value as MappingReviewStatus, pageNo: 1 })}
        options={MAPPING_STATUSES.map((value) => ({ value, label: labels[value] }))} /></label>
    </section>
    {query.isPending && <section className="admin-state-card">正在读取竞彩比赛与外部候选……</section>}
    {query.isError && <Alert type="error" showIcon message={`映射复核不可用：${query.error.message}`} />}
    {actionError && <Alert type="error" showIcon message={`关联未完成：${actionError.message}`} />}
    {query.isSuccess && page && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>{labels[filters.mappingStatus]}竞彩比赛</h2><span>共 {page.total} 场</span></div>
      {query.isStale && <span>缓存数据，正在更新</span>}</header>
      {page.records.length === 0 ? <p className="admin-empty">当前筛选没有可复核的竞彩比赛。</p> : <div className="admin-mapping-list">{page.records.map((item) => {
        const selectedId = selectedCandidate(item);
        const selected = item.externalCandidates.find((external) => external.mappingId === selectedId);
        const canConfirm = filters.mappingStatus === 'PENDING' && selected !== undefined;
        return <article className="admin-mapping-card lottery-mapping-card" key={item.match.matchId}>
          <header><div><strong>{item.match.lotteryMatchNo} · {item.match.homeTeamName} vs {item.match.awayTeamName}</strong>
            <p>{item.match.lotteryDate} · {item.match.leagueName || '联赛待标准化'} · {formatTimestamp(item.match.kickoffTime)}</p></div>
            <span className="admin-status mapping-pending">外部候选 {item.externalCandidates.length}</span></header>
          <Radio.Group className="mapping-external-list" value={selectedId}
            onChange={(event) => setSelectedExternal((current) => ({ ...current, [item.match.matchId]: event.target.value }))}>
            {item.externalCandidates.map((external) => <Radio key={external.mappingId} value={external.mappingId}>
              <div className="mapping-candidate-content"><strong>{externalName(external)}</strong>
                <span>{external.providerCode} · 外部 ID：{external.externalMatchId}</span>
                <small>得分：{external.score ?? '—'} · {external.reasons.join('、') || external.mappingExplanation || '服务端保留的候选'}</small></div>
            </Radio>)}
          </Radio.Group>
          <div className="admin-actions">
            <Button type="primary" disabled={!canConfirm} onClick={() => selected && (setPendingConfirmation({ match: item.match, external: selected }), setConfirmation(''))}>确认关联</Button>
            {selected && <Link to={`/admin/mappings/${selected.mappingId}${returnSearch ? `?${returnSearch}` : ''}`}>查看候选详情</Link>}
          </div>
        </article>;
      })}</div>}
      <div className="pagination"><Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
        <span>第 {filters.pageNo} / {pageCount} 页</span><Button disabled={filters.pageNo >= pageCount} onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button></div>
    </section>}
    <Modal title="确认外部赛事关联" open={pendingConfirmation !== null} onCancel={() => setPendingConfirmation(null)} onOk={() => void confirm()}
      confirmLoading={actions.confirm.isPending} okText="确认关联" okButtonProps={{ disabled: confirmation !== '确认关联' }}>
      {pendingConfirmation && <p>将“{externalName(pendingConfirmation.external)}”关联到竞彩“{pendingConfirmation.match.lotteryMatchNo} · {pendingConfirmation.match.homeTeamName} vs {pendingConfirmation.match.awayTeamName}”。</p>}
      <label className="admin-modal-field">输入“确认关联”以进行二次确认<Input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>
    </Modal>
  </main>;
}
