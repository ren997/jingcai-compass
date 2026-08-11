import { Alert, Button, Checkbox, Input, Modal } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { AdminDraftPredictionListItem } from '../../services/admin';
import {
  asianHandicapPickLabel,
  confidenceLabel,
  formatNumber,
  formatProbability,
  formatTimestamp,
  handicapPickLabel,
  totalGoalsPickLabel,
} from '../matches/matchPresentation';
import {
  parseDraftPredictionSearch,
  toDraftPredictionQuery,
  toDraftPredictionSearch,
} from './adminSearch';
import {
  useAdminDraftPredictionPublishAction,
  useAdminDraftPredictionsQuery,
} from './useAdminQueries';

type PublishFeedback = {
  predictionId: number;
  matchLabel: string;
  success: boolean;
  message: string;
};

/** 管理员复核并通过既有发布规则逐条发布草稿预测。 */
export default function AdminDraftPredictionsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseDraftPredictionSearch(searchParams);
  const query = useAdminDraftPredictionsQuery(toDraftPredictionQuery(filters));
  const publish = useAdminDraftPredictionPublishAction();
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [feedback, setFeedback] = useState<PublishFeedback[]>([]);
  const records = query.data?.records ?? [];
  const selectedRecords = useMemo(
    () => records.filter((item) => selectedIds.has(item.predictionId)),
    [records, selectedIds],
  );
  const pageCount = query.data ? Math.max(1, Math.ceil(query.data.total / query.data.pageSize)) : 1;
  const allCurrentSelected = records.length > 0 && records.every((item) => selectedIds.has(item.predictionId));

  useEffect(() => {
    setSelectedIds(new Set());
  }, [filters.lotteryDate, filters.modelVersion, filters.pageNo]);

  function update(next: Partial<typeof filters>) {
    setSearchParams(toDraftPredictionSearch({ ...filters, ...next }));
  }

  function toggle(predictionId: number, checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (checked) next.add(predictionId);
      else next.delete(predictionId);
      return next;
    });
  }

  function toggleCurrentPage(checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current);
      records.forEach((item) => checked ? next.add(item.predictionId) : next.delete(item.predictionId));
      return next;
    });
  }

  async function confirmPublish() {
    const outcomes = await publish.mutateAsync(selectedRecords);
    setFeedback(outcomes);
    setSelectedIds(new Set());
    setConfirmOpen(false);
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Draft predictions</p><h1>草稿预测管理</h1>
      <p>草稿不会进入公共页面。请先复核比赛、模型和概率，再逐条经过既有的时效、版本、哈希与审计规则发布。</p></div>
      <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></section>
    <section className="admin-filters" aria-label="草稿预测筛选">
      <label><span>比赛日期</span><input aria-label="草稿比赛日期" type="date" value={filters.lotteryDate}
        onChange={(event) => update({ lotteryDate: event.target.value || filters.lotteryDate, pageNo: 1 })} /></label>
      <label><span>模型版本</span><Input aria-label="草稿模型版本" value={filters.modelVersion ?? ''}
        onChange={(event) => update({ modelVersion: event.target.value || undefined, pageNo: 1 })} /></label>
      <div className="admin-draft-selection"><span>已选 {selectedRecords.length} 条</span>
        <Button type="primary" disabled={selectedRecords.length === 0} onClick={() => setConfirmOpen(true)}>发布所选</Button></div>
    </section>
    {query.isPending && <section className="admin-state-card">正在读取草稿预测……</section>}
    {query.isError && <Alert type="error" showIcon title={`草稿预测暂不可用：${query.error.message}`} />}
    {feedback.length > 0 && <section className="admin-panel" aria-label="发布结果"><header className="admin-panel-heading"><div><h2>本次发布结果</h2>
      <span>成功 {feedback.filter((item) => item.success).length} 条 · 失败 {feedback.filter((item) => !item.success).length} 条</span></div>
      <Button onClick={() => setFeedback([])}>清除结果</Button></header><ul className="admin-publish-feedback">{feedback.map((item) => <li key={item.predictionId} className={item.success ? 'success' : 'failure'}>
        <strong>{item.matchLabel}</strong><span>{item.message}</span></li>)}</ul></section>}
    {query.isSuccess && query.data && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>待发布草稿</h2>
      <span>共 {query.data.total} 条 · 草稿仅在此后台可见</span></div>{query.isStale && <span>缓存数据，正在更新</span>}</header>
      {records.length === 0 ? <p className="admin-empty">当前筛选没有待发布草稿。</p> : <><div className="admin-draft-toolbar">
        <Checkbox checked={allCurrentSelected} indeterminate={!allCurrentSelected && selectedRecords.length > 0} onChange={(event) => toggleCurrentPage(event.target.checked)}>全选当前页</Checkbox>
        <span>发布后成功项会从草稿列表移除；失败项继续保留为草稿。</span></div><div className="admin-draft-list">{records.map((item) => <DraftCard key={item.predictionId} item={item}
          selected={selectedIds.has(item.predictionId)} onToggle={toggle} />)}</div></>}
    </section>}
    {query.isSuccess && query.data && query.data.total > 0 && <nav className="admin-pagination" aria-label="草稿预测分页">
      <Button disabled={filters.pageNo <= 1} onClick={() => update({ pageNo: filters.pageNo - 1 })}>上一页</Button>
      <span>第 {filters.pageNo} / {pageCount} 页</span>
      <Button disabled={filters.pageNo >= pageCount} onClick={() => update({ pageNo: filters.pageNo + 1 })}>下一页</Button>
    </nav>}
    <Modal title="确认发布所选草稿" open={confirmOpen} onCancel={() => setConfirmOpen(false)}
      confirmLoading={publish.isPending} okText="确认逐条发布" onOk={() => void confirmPublish()}>
      <p>将发布 {selectedRecords.length} 条已选草稿。每条都会重新经过开赛时效、版本、内容哈希和审计校验。</p>
      <p>若某条在发布时校验失败，它会继续保留为草稿，并在本页显示失败原因。</p>
      <ul>{selectedRecords.map((item) => <li key={item.predictionId}>{matchLabel(item)}</li>)}</ul>
    </Modal>
  </main>;
}

function DraftCard({
  item,
  selected,
  onToggle,
}: {
  item: AdminDraftPredictionListItem;
  selected: boolean;
  onToggle: (predictionId: number, checked: boolean) => void;
}) {
  return <article className="admin-draft-card">
    <header><Checkbox aria-label={`选择 ${matchLabel(item)}`} checked={selected}
      onChange={(event) => onToggle(item.predictionId, event.target.checked)} />
      <div><strong>{item.match.lotteryMatchNo} · {item.match.homeTeamName} vs {item.match.awayTeamName}</strong>
        <span>{item.match.leagueName} · {formatTimestamp(item.match.kickoffTime)}</span></div><span className="admin-status">草稿</span></header>
    <dl><div><dt>模型 / 特征</dt><dd>{item.modelVersion} / {item.featureVersion}</dd></div>
      <div><dt>生成时间</dt><dd>{formatTimestamp(item.generatedAt)}</dd></div><div><dt>生成批次</dt><dd title={item.generationBatchId}>{item.generationBatchId}</dd></div>
      <div><dt>主胜 / 平 / 客胜</dt><dd>{formatProbability(item.homeWinProb)} / {formatProbability(item.drawProb)} / {formatProbability(item.awayWinProb)}</dd></div>
      <div><dt>竞彩让球胜平负</dt><dd>{handicapPickLabel(item.handicapPick)}</dd></div><div><dt>预期总进球 / 置信度</dt><dd>{formatNumber(item.expectedTotalGoals)} / {confidenceLabel(item.confidenceLevel)}</dd></div>
      {item.asianHandicapPick && <div><dt>亚盘让球</dt><dd>{asianHandicapPickLabel(item.asianHandicapPick)}</dd></div>}{item.totalGoalsPick && <div><dt>亚盘大小球</dt><dd>{totalGoalsPickLabel(item.totalGoalsPick)}</dd></div>}
      {item.asianMarket && <div><dt>生成亚盘快照</dt><dd>#{item.asianMarket.asianOddsSnapshotId} · {item.asianMarket.bookmakerCode} · 亚洲{formatNumber(item.asianMarket.handicapLine)} 主 {formatNumber(item.asianMarket.homeOdds)} / 客 {formatNumber(item.asianMarket.awayOdds)}；大小球 {formatNumber(item.asianMarket.totalLine)} 大 {formatNumber(item.asianMarket.overOdds)} / 小 {formatNumber(item.asianMarket.underOdds)} · {formatTimestamp(item.asianMarket.capturedAt)}</dd></div>}
    </dl><p>{item.analysisSummary}</p>
  </article>;
}

function matchLabel(item: AdminDraftPredictionListItem) {
  return `${item.match.lotteryMatchNo} · ${item.match.homeTeamName} vs ${item.match.awayTeamName}`;
}
