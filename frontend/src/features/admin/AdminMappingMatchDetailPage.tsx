import { Alert, Button, Checkbox, Modal, Radio, Tag } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import type { MappingReviewExternalCandidate, MappingReviewNormalizationProposal } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { parseMappingSearch } from './adminSearch';
import { useMappingReviewActions, useMappingReviewMatchDetailQuery } from './useAdminQueries';

function externalName(candidate: MappingReviewExternalCandidate) {
  if (candidate.externalHomeTeamName || candidate.externalAwayTeamName) {
    return `${candidate.externalHomeTeamName ?? '未知主队'} vs ${candidate.externalAwayTeamName ?? '未知客队'}`;
  }
  return `外部赛事 ${candidate.externalMatchId}`;
}

const normalizationLabels: Record<MappingReviewNormalizationProposal['role'], string> = {
  LEAGUE: '联赛',
  HOME_TEAM: '主队',
  AWAY_TEAM: '客队',
};

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
  const [confirmLeague, setConfirmLeague] = useState(false);
  const [confirmHomeTeam, setConfirmHomeTeam] = useState(false);
  const [confirmAwayTeam, setConfirmAwayTeam] = useState(false);
  const backTo = `/admin/mappings${searchParams.toString() ? `?${searchParams}` : ''}`;
  const candidates = detailQuery.data?.externalCandidates ?? [];
  const selected = useMemo(
    () => candidates.find((candidate) => candidate.mappingId === (selectedMappingId ?? candidates[0]?.mappingId)),
    [candidates, selectedMappingId],
  );
  const proposals = useMemo(
    () => detailQuery.data?.normalizationProposals?.filter((proposal) => proposal.sourceMappingId === selected?.mappingId) ?? [],
    [detailQuery.data?.normalizationProposals, selected?.mappingId],
  );

  useEffect(() => {
    setConfirmLeague(false);
    setConfirmHomeTeam(false);
    setConfirmAwayTeam(false);
  }, [selected?.mappingId]);

  if (matchId === undefined) return <main className="admin-page"><section className="admin-state-card error"><h1>页面不存在</h1><p>竞彩比赛 ID 格式无效。</p></section></main>;
  if (detailQuery.isPending) return <main className="admin-page"><section className="admin-state-card">正在读取竞彩比赛与外部候选……</section></main>;
  if (detailQuery.isError) return <main className="admin-page"><section className="admin-state-card error"><h1>候选详情不可用</h1><p>{detailQuery.error.message}</p><Link to={backTo}>返回映射复核</Link></section></main>;

  const detail = detailQuery.data!;
  const match = detail.match;
  const matchHasStarted = !Number.isNaN(Date.parse(match.kickoffTime)) && Date.parse(match.kickoffTime) <= Date.now();
  const canConfirm = filters.reviewScope === 'ACTIVE' && !matchHasStarted && selected?.mappingStatus === 'PENDING';
  const actionError = actions.confirmBundle.error ?? actions.confirm.error;

  async function confirm() {
    if (!selected) return;
    await actions.confirmBundle.mutateAsync({
      mappingId: selected.mappingId,
      targetMatchId: match.matchId,
      confirmLeague,
      confirmHomeTeam,
      confirmAwayTeam,
    });
    setConfirmationOpen(false);
    await detailQuery.refetch();
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Lottery match mapping</p>
      <h1>{match.lotteryMatchNo} · {match.homeTeamName} vs {match.awayTeamName}</h1>
      <p>{match.lotteryDate} · {match.leagueName || '联赛待标准化'} · 官方开赛：{formatTimestamp(match.kickoffTime)}</p></div>
      <div className="admin-actions"><Button loading={detailQuery.isFetching} onClick={() => void detailQuery.refetch()}>刷新</Button><Link to={backTo}>返回队列</Link></div>
    </section>
    {(filters.reviewScope === 'HISTORY' || matchHasStarted) && <Alert type="info" showIcon message="该场已开赛，仅保留历史证据，不可确认关联。" />}
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
        <Button type="primary" disabled={!canConfirm} onClick={() => setConfirmationOpen(true)}>确认关联</Button>
        <Link to={`/admin/mappings/${selected.mappingId}${searchParams.toString() ? `?${searchParams}` : ''}`}>查看该外部映射的高级详情</Link>
      </>}</div>
    </section>
    <Modal title="确认外部赛事关联" open={confirmationOpen} onCancel={() => setConfirmationOpen(false)} onOk={() => void confirm()}
      confirmLoading={actions.confirmBundle.isPending} okText="确认关联">
      {selected && <p>将“{externalName(selected)}”关联到竞彩“{match.lotteryMatchNo} · {match.homeTeamName} vs {match.awayTeamName}”。请确认官方和外部开赛时间均合理。</p>}
      <p className="admin-metadata-note">默认只确认本场赛事。以下选项必须由管理员显式勾选，且会与赛事确认作为一个原子操作提交。</p>
      <div className="mapping-normalization-options">
        {(['LEAGUE', 'HOME_TEAM', 'AWAY_TEAM'] as const).map((role) => {
          const proposal = proposals.find((item) => item.role === role);
          const checked = role === 'LEAGUE' ? confirmLeague : role === 'HOME_TEAM' ? confirmHomeTeam : confirmAwayTeam;
          const setChecked = role === 'LEAGUE' ? setConfirmLeague : role === 'HOME_TEAM' ? setConfirmHomeTeam : setConfirmAwayTeam;
          if (!proposal) return <p key={role} className="admin-metadata-note">{normalizationLabels[role]}：尚无可确认的外部身份。</p>;
          return <div key={role} className="mapping-normalization-option">
            <Checkbox checked={checked} disabled={!proposal.selectable} onChange={(event) => setChecked(event.target.checked)}>
              同时确认{normalizationLabels[role]}：{proposal.externalDisplayName ?? '外部名称缺失'} → {proposal.targetEntityName ?? '竞彩内部实体缺失'}
            </Checkbox>
            {!proposal.selectable && <small>{proposal.unavailableReason ?? '当前不可确认'}</small>}
          </div>;
        })}
      </div>
    </Modal>
  </main>;
}
