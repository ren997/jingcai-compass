import { Alert, Button, Modal, Radio, Tag } from 'antd';
import { useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import type { MappingReviewExternalCandidate } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseMappingSearch } from './adminSearch';
import { useMappingReviewActions, useMappingReviewMatchDetailQuery } from './useAdminQueries';

function externalName(candidate: MappingReviewExternalCandidate) {
  if (candidate.externalHomeTeamName || candidate.externalAwayTeamName) {
    return `${candidate.externalHomeTeamName ?? '未知主队'} vs ${candidate.externalAwayTeamName ?? '未知客队'}`;
  }
  return `外部赛事 ${candidate.externalMatchId}`;
}

/** 以竞彩比赛为主体核对并确认服务端保留的外部赛事候选。 */
export default function AdminMappingMatchDetailPage() {
  const { matchId: rawMatchId } = useParams();
  const [searchParams] = useSearchParams();
  const filters = parseMappingSearch(searchParams);
  const matchId = rawMatchId && /^\d+$/.test(rawMatchId) && Number(rawMatchId) > 0 ? Number(rawMatchId) : undefined;
  const detailQuery = useMappingReviewMatchDetailQuery(matchId, {
    providerCode: filters.providerCode,
    mappingStatus: filters.mappingStatus,
  });
  const actions = useMappingReviewActions();
  const [selectedMappingId, setSelectedMappingId] = useState<number | undefined>();
  const [confirmationOpen, setConfirmationOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const backTo = `/admin/mappings${searchParams.toString() ? `?${searchParams}` : ''}`;
  const candidates = detailQuery.data?.externalCandidates ?? [];
  const selected = useMemo(
    () => candidates.find((candidate) => candidate.mappingId === (selectedMappingId ?? candidates[0]?.mappingId)),
    [candidates, selectedMappingId],
  );

  if (matchId === undefined) return <main className="admin-page"><section className="admin-state-card error"><h1>页面不存在</h1><p>竞彩比赛 ID 格式无效。</p></section></main>;
  if (detailQuery.isPending) return <main className="admin-page"><section className="admin-state-card">正在读取竞彩比赛与外部候选……</section></main>;
  if (detailQuery.isError) return <main className="admin-page"><section className="admin-state-card error"><h1>候选详情不可用</h1><p>{detailQuery.error.message}</p><Link to={backTo}>返回映射复核</Link></section></main>;

  const detail = detailQuery.data!;
  const match = detail.match;
  const canConfirm = selected?.mappingStatus === 'PENDING';
  const actionError = actions.confirm.error;

  async function confirm() {
    if (!selected || confirmation !== '确认关联') return;
    await actions.confirm.mutateAsync({ mappingId: selected.mappingId, targetMatchId: match.matchId });
    setConfirmationOpen(false);
    setConfirmation('');
    await detailQuery.refetch();
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Lottery match mapping</p>
      <h1>{match.lotteryMatchNo} · {match.homeTeamName} vs {match.awayTeamName}</h1>
      <p>{match.lotteryDate} · {match.leagueName || '联赛待标准化'} · 官方开赛：{formatTimestamp(match.kickoffTime)}</p></div>
      <div className="admin-actions"><Button loading={detailQuery.isFetching} onClick={() => void detailQuery.refetch()}>刷新</Button><Link to={backTo}>返回队列</Link></div>
    </section>
    {actionError && <Alert type="error" showIcon message={`关联未完成：${actionError.message}`} />}
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>外部赛事候选</h2><span>仅下列服务端保留的候选可被确认</span></div></header>
      {detailQuery.isStale && <p className="admin-metadata-note">缓存数据，正在更新。</p>}
      {candidates.length === 0 ? <p className="admin-empty">当前筛选下没有可复核的外部赛事候选。</p> : <Radio.Group className="mapping-candidate-list" value={selected?.mappingId}
        onChange={(event) => setSelectedMappingId(event.target.value)}>{candidates.map((candidate) => <Radio key={candidate.mappingId} value={candidate.mappingId}>
          <div className="mapping-candidate-content"><strong>{externalName(candidate)}</strong>
            <span>{candidate.providerCode} · 外部赛事 ID：{candidate.externalMatchId}</span>
            <span>外部开赛：{candidate.externalKickoffTime ? formatTimestamp(candidate.externalKickoffTime) : '暂未提供'}</span>
            <small>状态：<Tag>{candidate.mappingStatus}</Tag> · 得分：{candidate.score ?? '—'} · {candidate.reasons.join('、') || candidate.mappingExplanation || '服务端保留的候选'}</small></div>
        </Radio>)}</Radio.Group>}
      <div className="admin-actions">{selected && <>
        <Button type="primary" disabled={!canConfirm} onClick={() => { setConfirmation(''); setConfirmationOpen(true); }}>确认关联</Button>
        <Link to={`/admin/mappings/${selected.mappingId}${searchParams.toString() ? `?${searchParams}` : ''}`}>查看该外部映射的高级详情</Link>
      </>}</div>
    </section>
    <Modal title="确认外部赛事关联" open={confirmationOpen} onCancel={() => setConfirmationOpen(false)} onOk={() => void confirm()}
      confirmLoading={actions.confirm.isPending} okText="确认关联" okButtonProps={{ disabled: confirmation !== '确认关联' }}>
      {selected && <p>将“{externalName(selected)}”关联到竞彩“{match.lotteryMatchNo} · {match.homeTeamName} vs {match.awayTeamName}”。请确认官方和外部开赛时间均合理。</p>}
      <label className="admin-modal-field">输入“确认关联”以进行二次确认<input className="ant-input" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>
    </Modal>
  </main>;
}
