import { Alert, Button, Checkbox, Drawer, Input, Modal, Tag } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type {
  AdminBaselinePredictionGeneration,
  AdminDraftPredictionListItem,
  AdminOddsLadder,
  AdminOddsLadderMarket,
} from '../../services/admin';
import {
  asianHandicapPredictionLabel,
  confidenceLabel,
  formatNumber,
  formatProbability,
  formatTimestamp,
  totalGoalsPickLabel,
} from '../matches/matchPresentation';
import {
  parseDraftPredictionSearch,
  toDraftPredictionQuery,
  toDraftPredictionSearch,
} from './adminSearch';
import {
  useAdminDraftPredictionPublishAction,
  useAdminBaselinePredictionGenerationAction,
  useAdminDraftPredictionsQuery,
  useAdminOddsLadderQuery,
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
  const generate = useAdminBaselinePredictionGenerationAction();
  const publish = useAdminDraftPredictionPublishAction();
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [generateConfirmOpen, setGenerateConfirmOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [feedback, setFeedback] = useState<PublishFeedback[]>([]);
  const [generationResult, setGenerationResult] = useState<AdminBaselinePredictionGeneration | null>(null);
  // 阶梯只在打开抽屉时才请求：它是排障视图，不是列表的常规内容
  const [ladderPredictionId, setLadderPredictionId] = useState<number | null>(null);
  const ladder = useAdminOddsLadderQuery(ladderPredictionId ?? undefined);
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

  async function confirmGeneration() {
    const result = await generate.mutateAsync(filters.lotteryDate);
    setGenerationResult(result);
    setGenerateConfirmOpen(false);
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Draft predictions</p><h1>草稿预测管理</h1>
      <p>草稿不会进入公共页面。请复核亚盘方向、对应分项置信度与生成快照，再逐条经过既有的时效、版本、哈希与审计规则发布。</p></div>
      <div className="admin-page-actions"><Button onClick={() => setGenerateConfirmOpen(true)}>生成 V3 亚盘草稿</Button>
        <Button loading={query.isFetching} onClick={() => void query.refetch()}>刷新</Button></div></section>
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
    {generationResult && <Alert type="success" showIcon title="V3 亚盘草稿生成完成"
      description={`候选 ${generationResult.candidateCount} 场 · 已生成 ${generationResult.generatedCount} 条 · 新增 ${generationResult.insertedCount} 条 · 复用 ${generationResult.reusedCount} 条${generationResult.generationBatchId ? ` · 批次 ${generationResult.generationBatchId}` : ''}`} />}
    {feedback.length > 0 && <section className="admin-panel" aria-label="发布结果"><header className="admin-panel-heading"><div><h2>本次发布结果</h2>
      <span>成功 {feedback.filter((item) => item.success).length} 条 · 失败 {feedback.filter((item) => !item.success).length} 条</span></div>
      <Button onClick={() => setFeedback([])}>清除结果</Button></header><ul className="admin-publish-feedback">{feedback.map((item) => <li key={item.predictionId} className={item.success ? 'success' : 'failure'}>
        <strong>{item.matchLabel}</strong><span>{item.message}</span></li>)}</ul></section>}
    {query.isSuccess && query.data && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>待发布草稿</h2>
      <span>共 {query.data.total} 条 · 草稿仅在此后台可见</span></div>{query.isStale && <span>缓存数据，正在更新</span>}</header>
      {records.length === 0 ? <p className="admin-empty">当前筛选没有待发布草稿。</p> : <><div className="admin-draft-toolbar">
        <Checkbox checked={allCurrentSelected} indeterminate={!allCurrentSelected && selectedRecords.length > 0} onChange={(event) => toggleCurrentPage(event.target.checked)}>全选当前页</Checkbox>
        <span>发布后成功项会从草稿列表移除；失败项继续保留为草稿。</span></div><div className="admin-draft-list">{records.map((item) => <DraftCard key={item.predictionId} item={item}
          selected={selectedIds.has(item.predictionId)} onToggle={toggle} onShowLadder={setLadderPredictionId} />)}</div></>}
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
    <Drawer title="盘口阶梯" width={720} open={ladderPredictionId !== null} destroyOnClose
      onClose={() => setLadderPredictionId(null)}>
      <OddsLadderPanel ladder={ladder.data} pending={ladder.isPending} error={ladder.error} />
    </Drawer>
    <Modal title="确认生成 V3 亚盘草稿" open={generateConfirmOpen} onCancel={() => setGenerateConfirmOpen(false)}
      confirmLoading={generate.isPending} okText="确认生成草稿" onOk={() => void confirmGeneration()}>
      <p>将为 {filters.lotteryDate} 仅使用已持久化、已确认且让球/大小球完整的亚盘主盘生成 V3 草稿。</p>
      <p>让球与大小球分别判定置信度；低置信方向不会进入可发布草稿，生成结果不会自动发布。</p>
    </Modal>
  </main>;
}

function DraftCard({
  item,
  selected,
  onToggle,
  onShowLadder,
}: {
  item: AdminDraftPredictionListItem;
  selected: boolean;
  onToggle: (predictionId: number, checked: boolean) => void;
  onShowLadder: (predictionId: number) => void;
}) {
  return <article className="admin-draft-card">
    <header><Checkbox aria-label={`选择 ${matchLabel(item)}`} checked={selected}
      onChange={(event) => onToggle(item.predictionId, event.target.checked)} />
      <div><strong>{item.match.lotteryMatchNo} · {item.match.homeTeamName} vs {item.match.awayTeamName}</strong>
        <span>{item.match.leagueName} · {formatTimestamp(item.match.kickoffTime)}</span></div>
      <div className="admin-draft-card-actions"><Button size="small" onClick={() => onShowLadder(item.predictionId)}>盘口阶梯</Button>
        <span className="admin-status">草稿</span></div></header>
    <dl><div><dt>模型 / 特征</dt><dd>{item.modelVersion} / {item.featureVersion}</dd></div>
      <div><dt>生成时间</dt><dd>{formatTimestamp(item.generatedAt)}</dd></div><div><dt>生成批次</dt><dd title={item.generationBatchId}>{item.generationBatchId}</dd></div>
      {item.asianHandicapPick && <div><dt>亚盘让球</dt><dd>{asianHandicapPredictionLabel(item.asianMarket?.handicapLine, item.asianHandicapPick)} · 置信：{confidenceLabel(item.asianHandicapConfidenceLevel)}</dd></div>}{item.totalGoalsPick && <div><dt>亚盘大小球</dt><dd>{formatNumber(item.asianMarket?.totalLine)}：{totalGoalsPickLabel(item.totalGoalsPick)} · 置信：{confidenceLabel(item.totalGoalsConfidenceLevel)}</dd></div>}
      {item.asianMarket && <div><dt>生成亚盘快照</dt><dd>#{item.asianMarket.asianOddsSnapshotId} · {item.asianMarket.bookmakerCode} · 亚洲{formatNumber(item.asianMarket.handicapLine)} 主 {formatNumber(item.asianMarket.homeOdds)} / 客 {formatNumber(item.asianMarket.awayOdds)}；大小球 {formatNumber(item.asianMarket.totalLine)} 大 {formatNumber(item.asianMarket.overOdds)} / 小 {formatNumber(item.asianMarket.underOdds)} · {formatTimestamp(item.asianMarket.capturedAt)}</dd></div>}
    </dl><p>{item.analysisSummary}</p>
  </article>;
}

/**
 * 盘口阶梯只读视图。
 *
 * <p>概率、σ 与主盘全部由服务端给出，这里只负责排版：前端若自己算去水，
 * 运营看到的「共识」会与模型使用的数慢慢分叉，而这个页面存在的唯一意义就是核对它们。
 */
function OddsLadderPanel({
  ladder,
  pending,
  error,
}: {
  ladder: AdminOddsLadder | undefined;
  pending: boolean;
  error: Error | null;
}) {
  if (pending) return <p className="admin-empty">正在读取盘口阶梯……</p>;
  if (error) return <Alert type="error" showIcon title={`盘口阶梯暂不可用：${error.message}`} />;
  if (!ladder) return null;
  return <div className="admin-ladder">
    <dl className="admin-metadata compact">
      <div><dt>比赛</dt><dd>#{ladder.matchId}</dd></div>
      <div><dt>冻结行</dt><dd>{ladder.frozenSnapshotId === null ? '未生成' : `#${ladder.frozenSnapshotId}`}</dd></div>
      <div><dt>报价条数</dt><dd>{ladder.rows.length}</dd></div>
    </dl>
    <MarketLadder title="让球" firstLabel="主队赢盘" secondLabel="客队受让" market={ladder.handicap} />
    <MarketLadder title="大小球" firstLabel="大球" secondLabel="小球" market={ladder.totals} />
    <section className="admin-ladder-market"><header><h3>逐家庄报价</h3>
      <p>冻结行是该预测结算与摘要实际引用的那一条；其余行只用于判断共识是否可靠。</p></header>
      {ladder.rows.length === 0
        ? <p className="admin-empty">当前没有已确认映射的完整报价。</p>
        : <table className="admin-ladder-table">
          <thead><tr><th>庄家</th><th>让球线</th><th>主 / 客</th><th>大小球线</th><th>大 / 小</th><th>抓取时间</th><th>标记</th></tr></thead>
          <tbody>{ladder.rows.map((row) => <tr key={row.snapshotId} className={row.frozen ? 'frozen' : undefined}>
            <td>{row.bookmakerCode}<br /><small>{row.providerCode}</small></td>
            <td>{formatNumber(row.handicapLine)}</td>
            <td>{formatNumber(row.homeOdds)} / {formatNumber(row.awayOdds)}</td>
            <td>{formatNumber(row.totalLine)}</td>
            <td>{formatNumber(row.overOdds)} / {formatNumber(row.underOdds)}</td>
            <td>{formatTimestamp(row.capturedAt)}</td>
            <td>{row.frozen ? <Tag color="blue">冻结行 #{row.snapshotId}</Tag> : `#${row.snapshotId}`}</td>
          </tr>)}</tbody>
        </table>}
    </section>
  </div>;
}

/** 单个市场的共识阶梯：主盘摘要 + 全部达标档位。 */
function MarketLadder({
  title,
  firstLabel,
  secondLabel,
  market,
}: {
  title: string;
  firstLabel: string;
  secondLabel: string;
  market: AdminOddsLadderMarket;
}) {
  return <section className="admin-ladder-market">
    <header><h3>{title}</h3>
      {market.bestLine === null
        ? <p>没有达到最少家数的档位，模型不会使用这个市场。</p>
        : <p>
          主盘 {formatNumber(market.bestLine)} · {firstLabel} {formatProbability(market.bestFirstProbability)}
          {' / '}{secondLabel} {formatProbability(market.bestSecondProbability)}
          {' · σ '}{market.bestDispersion === null ? '—' : market.bestDispersion.toFixed(4)}
          {' · '}{market.bestSampleCount} 家 · {market.bestSampleSource}
        </p>}
    </header>
    {market.lines.length === 0
      ? <p className="admin-empty">没有合格档位。</p>
      : <table className="admin-ladder-table">
        <thead><tr><th>档位</th><th>家数</th><th>{firstLabel}</th><th>{secondLabel}</th><th>σ</th><th>公平赔率</th><th></th></tr></thead>
        <tbody>{market.lines.map((line) => <tr key={`${line.line}`} className={line.best ? 'frozen' : undefined}>
          <td>{formatNumber(line.line)}</td>
          <td>{line.sampleCount}{line.whitelistApplied ? ' · 白名单' : ''}</td>
          <td>{formatProbability(line.firstProbability)}</td>
          <td>{formatProbability(line.secondProbability)}</td>
          <td>{line.dispersion.toFixed(4)}</td>
          <td>{formatNumber(line.fairFirstPrice)} / {formatNumber(line.fairSecondPrice)}</td>
          <td>{line.best ? <Tag color="green">主盘</Tag> : null}</td>
        </tr>)}</tbody>
      </table>}
  </section>;
}

function matchLabel(item: AdminDraftPredictionListItem) {
  return `${item.match.lotteryMatchNo} · ${item.match.homeTeamName} vs ${item.match.awayTeamName}`;
}
