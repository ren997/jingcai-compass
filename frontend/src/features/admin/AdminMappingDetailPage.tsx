import { Alert, Button, Input, Modal, Radio, Tag } from 'antd';
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import type { MappingReviewCandidate } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseMappingSearch } from './adminSearch';
import { useMappingReviewActions, useMappingReviewDetailQuery } from './useAdminQueries';

type Action = 'confirm' | 'reject' | 'reopen' | null;
const actionLabels: Record<Exclude<Action, null>, string> = { confirm: '确认关联', reject: '确认拒绝', reopen: '确认重新打开' };

function MatchBrief({ candidate }: { candidate: MappingReviewCandidate }) {
  const match = candidate.match;
  return <div className="mapping-candidate-content"><strong>{match ? `${match.homeTeamName} vs ${match.awayTeamName}` : `比赛 #${candidate.matchId}`}</strong>
    {match && <span>{match.lotteryDate} · {match.lotteryMatchNo} · {match.leagueName} · {formatTimestamp(match.kickoffTime)}</span>}
    <small>得分：{candidate.score ?? '当前关联'} · {candidate.reasons.join('、') || '当前关联比赛'}</small></div>;
}

/** 候选比赛比较与二次确认操作页。 */
export default function AdminMappingDetailPage() {
  const { mappingId: rawId } = useParams();
  const [searchParams] = useSearchParams();
  const filters = parseMappingSearch(searchParams);
  const mappingId = rawId && /^\d+$/.test(rawId) && Number(rawId) > 0 ? Number(rawId) : undefined;
  const detailQuery = useMappingReviewDetailQuery(mappingId);
  const actions = useMappingReviewActions();
  const [action, setAction] = useState<Action>(null);
  const [reason, setReason] = useState('');
  const [chosenTarget, setChosenTarget] = useState<number | undefined>();
  const backTo = `/admin/mappings${searchParams.toString() ? `?${searchParams}` : ''}`;
  if (mappingId === undefined) return <main className="admin-page"><section className="admin-state-card error"><h1>页面不存在</h1><p>映射 ID 格式无效。</p></section></main>;
  if (detailQuery.isPending) return <main className="admin-page"><section className="admin-state-card">正在读取映射详情……</section></main>;
  if (detailQuery.isError) return <main className="admin-page"><section className="admin-state-card error"><h1>映射详情不可用</h1><p>{detailQuery.error.message}</p><Link to={backTo}>返回映射复核</Link></section></main>;
  const detail = detailQuery.data!;
  const currentCandidate: MappingReviewCandidate | undefined = detail.matchId === null ? undefined : {
    matchId: detail.matchId, score: null, reasons: ['当前关联'], match: detail.match,
  };
  const candidates = [...(currentCandidate ? [currentCandidate] : []), ...detail.candidates.filter((candidate) => candidate.matchId !== detail.matchId)];
  const targetMatchId = chosenTarget ?? candidates[0]?.matchId;
  const selectedTarget = candidates.find((candidate) => candidate.matchId === targetMatchId);
  const targetKickoffTime = selectedTarget?.match?.kickoffTime;
  const targetHasStarted = targetKickoffTime !== null
    && targetKickoffTime !== undefined
    && !Number.isNaN(Date.parse(targetKickoffTime))
    && Date.parse(targetKickoffTime) <= Date.now();
  const pending = detail.mappingStatus === 'PENDING';
  const readOnlyHistory = filters.reviewScope === 'HISTORY' || targetHasStarted;
  const canConfirm = pending && targetMatchId !== undefined && !readOnlyHistory;
  const isMutating = actions.confirm.isPending || actions.reject.isPending || actions.reopen.isPending;
  const actionError = actions.confirm.error || actions.reject.error || actions.reopen.error;

  function open(next: Exclude<Action, null>) { setAction(next); }
  async function submit() {
    if (!action) return;
    if (action === 'confirm' && targetMatchId !== undefined) await actions.confirm.mutateAsync({ mappingId: mappingId!, targetMatchId });
    if (action === 'reject') await actions.reject.mutateAsync({ mappingId: mappingId!, reason });
    if (action === 'reopen') await actions.reopen.mutateAsync(mappingId!);
    setAction(null); setReason('');
  }

  return <main className="admin-page admin-workspace"><section className="admin-page-heading"><div><p className="eyebrow">Operations · Mapping #{detail.mappingId}</p>
    <h1>{detail.providerCode}</h1><p>外部比赛：{detail.externalMatchId} · 状态：<Tag>{detail.mappingStatus}</Tag></p></div>
    <div className="admin-actions"><Button onClick={() => void detailQuery.refetch()}>刷新</Button><Link to={backTo}>返回队列</Link></div></section>
    {readOnlyHistory && <Alert type="info" showIcon message="候选竞彩比赛已开赛，仅保留历史证据，不可确认关联。" />}
    {actionError && <Alert type="error" showIcon message={actionError.message} />}
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>外部映射信息</h2><span>更新时间 {formatTimestamp(detail.updatedAt)}</span></div></header>
      <dl className="admin-metadata"><div><dt>外部联赛 ID</dt><dd>{detail.externalLeagueId || '—'}</dd></div><div><dt>外部主队</dt><dd>{detail.externalHomeTeamName || '暂未恢复'}</dd></div>
        <div><dt>外部客队</dt><dd>{detail.externalAwayTeamName || '暂未恢复'}</dd></div><div><dt>外部开赛</dt><dd>{detail.externalKickoffTime ? formatTimestamp(detail.externalKickoffTime) : '暂未提供'}</dd></div><div><dt>置信度</dt><dd>{detail.mappingConfidence ?? '—'}</dd></div></dl>
      <p className="admin-metadata-note">稳定来源键：主队 {detail.externalHomeTeamId || '—'} · 客队 {detail.externalAwayTeamId || '—'}</p>
      <p className="admin-explanation">{detail.mappingExplanation || '暂无映射解释。'}</p></section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>候选比赛对比</h2><span>仅下列比赛可被人工确认</span></div></header>
      {candidates.length === 0 ? <p className="admin-empty">没有可确认的内部比赛候选；可拒绝后等待后续映射。</p> : <Radio.Group className="mapping-candidate-list" value={targetMatchId}
        onChange={(event) => setChosenTarget(event.target.value)}>{candidates.map((candidate) => <Radio key={candidate.matchId} value={candidate.matchId}><MatchBrief candidate={candidate} /></Radio>)}</Radio.Group>}
      <div className="admin-actions">{pending && <><Button type="primary" disabled={!canConfirm} onClick={() => open('confirm')}>确认所选比赛</Button>
        <Button danger onClick={() => open('reject')}>拒绝映射</Button></>}{detail.mappingStatus === 'REJECTED' && <Button onClick={() => open('reopen')}>重新打开</Button>}</div>
    </section>
    <Modal title={action === 'confirm' ? '确认映射' : action === 'reject' ? '拒绝映射' : '重新打开映射'} open={action !== null}
      onCancel={() => setAction(null)} onOk={() => void submit()} confirmLoading={isMutating}
      okButtonProps={{ disabled: action === null }} okText={action ? actionLabels[action] : '执行操作'}>
      {action === 'confirm' && <p>将外部比赛关联到内部比赛 #{targetMatchId}。确认前请核对候选资料。</p>}
      {action === 'reject' && <label className="admin-modal-field">拒绝原因（可选）<Input.TextArea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={200} /></label>}
      {action === 'reopen' && <p>此操作会将已拒绝映射恢复到待复核队列。</p>}
    </Modal>
  </main>;
}
