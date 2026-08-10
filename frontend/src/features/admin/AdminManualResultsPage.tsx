import { Alert, Button, Input, InputNumber, Modal, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { fetchMatchList, type MatchListItemVo } from '../../services/public';
import { formatTimestamp } from '../matches/matchPresentation';
import { todayInShanghai } from './adminSearch';
import { useAdminManualMatchResultAction } from './useAdminQueries';

type ManualStatus = 'FINAL' | 'VOID';
type VoidStatus = 'CANCELLED' | 'ABANDONED';

function previousShanghaiDate() {
  const yesterday = new Date(`${todayInShanghai()}T00:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  return yesterday.toISOString().slice(0, 10);
}

/** 仅用于开发验证与模型评估样本的受控人工赛果补录页面。 */
export default function AdminManualResultsPage() {
  const [lotteryDate, setLotteryDate] = useState(previousShanghaiDate);
  const [selected, setSelected] = useState<MatchListItemVo | null>(null);
  const [factStatus, setFactStatus] = useState<ManualStatus>('FINAL');
  const [voidStatus, setVoidStatus] = useState<VoidStatus>('CANCELLED');
  const [homeScore, setHomeScore] = useState<number | null>(null);
  const [awayScore, setAwayScore] = useState<number | null>(null);
  const [sourceNote, setSourceNote] = useState('');
  const [entryReason, setEntryReason] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const mutation = useAdminManualMatchResultAction();
  const matchesQuery = useQuery({
    queryKey: ['admin', 'manual-results', 'matches', lotteryDate],
    queryFn: ({ signal }) => fetchMatchList({
      lotteryDate, sort: 'KICKOFF_ASC', pageNo: 1, pageSize: 100,
    }, signal),
    enabled: /^\d{4}-\d{2}-\d{2}$/.test(lotteryDate),
  });
  const eligibleMatches = useMemo(
    () => (matchesQuery.data?.records ?? []).filter((match) => Date.parse(match.kickoffTime) <= Date.now()),
    [matchesQuery.data],
  );
  const formValid = selected !== null
    && sourceNote.trim().length > 0
    && entryReason.trim().length > 0
    && (factStatus === 'VOID' || (homeScore !== null && awayScore !== null));

  function chooseMatch(match: MatchListItemVo) {
    setSelected(match);
    setFactStatus('FINAL');
    setVoidStatus('CANCELLED');
    setHomeScore(null);
    setAwayScore(null);
    setSourceNote('');
    setEntryReason('');
    mutation.reset();
  }

  function submit() {
    if (!selected) return;
    mutation.mutate({
      matchId: selected.matchId,
      factStatus,
      matchStatus: factStatus === 'FINAL' ? 'FINISHED' : voidStatus,
      homeScore: factStatus === 'FINAL' ? homeScore : null,
      awayScore: factStatus === 'FINAL' ? awayScore : null,
      sourceNote: sourceNote.trim(),
      entryReason: entryReason.trim(),
      confirmed: true,
    }, { onSuccess: () => setConfirmOpen(false) });
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Manual result</p><h1>人工赛果补录</h1>
      <p>仅用于开发验证、模型评估样本和结算链路演练；不是官方或合规赛果数据源，不构成生产展示或 T108 证据。</p></div></section>
    <Alert type="warning" showIcon title="人工补录，非官方源" description="提交会追加不可变赛果事实，并只结算或重算当前选择的比赛；无法直接编辑任何结算结果。" />
    <section className="admin-panel">
      <header className="admin-panel-heading"><div><h2>选择已开赛比赛</h2><span>服务端会再次校验开赛时间</span></div>
        <label><span>竞彩日期</span><input aria-label="人工赛果比赛日期" type="date" value={lotteryDate} onChange={(event) => setLotteryDate(event.target.value)} /></label></header>
      {matchesQuery.isPending && <p className="admin-empty">正在读取比赛……</p>}
      {matchesQuery.isError && <Alert type="error" showIcon title={`比赛列表不可用：${matchesQuery.error.message}`} />}
      {matchesQuery.isSuccess && (eligibleMatches.length === 0 ? <p className="admin-empty">该日期没有已开赛且可补录的比赛。</p> : <div className="admin-operation-list">{eligibleMatches.map((match) => <button type="button" className="admin-operation-card" key={match.matchId} onClick={() => chooseMatch(match)}>
        <header><strong>{match.lotteryMatchNo} · {match.homeTeamName} vs {match.awayTeamName}</strong><span className="admin-status">{match.matchStatus}</span></header>
        <p>{match.leagueName} · 开赛 {formatTimestamp(match.kickoffTime)}</p>
      </button>)}</div>)}
    </section>
    {selected && <section className="admin-panel" aria-label="人工赛果录入表单">
      <header className="admin-panel-heading"><div><h2>补录 {selected.homeTeamName} vs {selected.awayTeamName}</h2><span>{selected.lotteryMatchNo} · 已开赛 {formatTimestamp(selected.kickoffTime)}</span></div></header>
      <div className="admin-filters"><label><span>赛果状态</span><Select aria-label="人工赛果状态" value={factStatus} onChange={(value) => setFactStatus(value as ManualStatus)} options={[
        { value: 'FINAL', label: '正常完赛（FINAL）' }, { value: 'VOID', label: '取消或中止（VOID）' },
      ]} /></label>
        {factStatus === 'FINAL' ? <><label><span>主队比分</span><InputNumber aria-label="主队比分" min={0} max={99} value={homeScore} onChange={(value) => setHomeScore(typeof value === 'number' ? value : null)} /></label>
          <label><span>客队比分</span><InputNumber aria-label="客队比分" min={0} max={99} value={awayScore} onChange={(value) => setAwayScore(typeof value === 'number' ? value : null)} /></label></>
          : <label><span>比赛状态</span><Select aria-label="作废比赛状态" value={voidStatus} onChange={(value) => setVoidStatus(value as VoidStatus)} options={[
            { value: 'CANCELLED', label: '取消' }, { value: 'ABANDONED', label: '中止' },
          ]} /></label>}
      </div>
      <div className="admin-filters"><label><span>来源说明</span><Input.TextArea aria-label="来源说明" maxLength={500} value={sourceNote} onChange={(event) => setSourceNote(event.target.value)} placeholder="例如：已核实的现场记录或可信资料链接说明" /></label>
        <label><span>补录原因</span><Input.TextArea aria-label="补录原因" maxLength={500} value={entryReason} onChange={(event) => setEntryReason(event.target.value)} placeholder="例如：开发验证样本补全" /></label></div>
      {!formValid && <Alert type="info" showIcon title="请填写来源说明、补录原因，以及 FINAL 的双方非负比分。" />}
      {mutation.isError && <Alert type="error" showIcon title={`补录失败：${mutation.error.message}`} />}
      {mutation.data && <Alert type="success" showIcon title={`赛果事实 V${mutation.data.factVersion} ${mutation.data.writeOutcome === 'UNCHANGED' ? '已幂等复用' : '已追加'}`}
        description={mutation.data.settlementTriggered
          ? `本场重算 ${mutation.data.settlement.recalculatedPredictionCount} 条预测/${mutation.data.settlement.recalculatedMarketCount} 个市场；结算 ${mutation.data.settlement.settledPredictionCount} 条预测/${mutation.data.settlement.settledMarketCount} 个市场。`
          : '相同输入已幂等复用，本次未重复触发结算。'} />}
      <div className="admin-actions"><Button type="primary" disabled={!formValid} onClick={() => setConfirmOpen(true)}>提交人工赛果</Button></div>
    </section>}
    <Modal title="确认补录人工赛果" open={confirmOpen} onCancel={() => setConfirmOpen(false)} confirmLoading={mutation.isPending} okText="确认补录" onOk={submit}>
      <p>将以“人工补录，非官方源”追加 {selected?.homeTeamName} vs {selected?.awayTeamName} 的不可变赛果事实。</p>
      <p>系统只会对该比赛关联预测执行自动结算或重算，不能直接修改结算结果。</p>
    </Modal>
  </main>;
}
