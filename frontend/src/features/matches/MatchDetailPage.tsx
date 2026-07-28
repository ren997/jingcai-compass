import { Link, useLocation, useParams } from 'react-router-dom';
import { ApiClientError } from '../../services/http';
import {
  predictionSnapshotDownloadUrl,
  type MatchSourceMappingVo,
  type PredictionVersionVo,
} from '../../services/public';
import NotFoundPage from '../../pages/NotFoundPage';
import {
  availabilityLabel,
  confidenceLabel,
  dataSourceLabel,
  formatProbability,
  formatHandicap,
  formatNumber,
  formatTimestamp,
  handicapPickLabel,
  predictionStatusLabel,
  publicSnapshotAvailabilityLabel,
  snapshotTypeLabel,
  statusLabels,
} from './matchPresentation';
import {
  useMatchDetailQuery,
  useMatchPredictionDetailQuery,
  usePredictionSnapshotVerification,
} from './useMatchQueries';

function parseMatchId(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) {
    return undefined;
  }
  const matchId = Number(value);
  return Number.isSafeInteger(matchId) && matchId > 0 ? matchId : undefined;
}

function mappingStatusLabel(mapping: MatchSourceMappingVo) {
  const labels = {
    PENDING: '待确认',
    AUTO_CONFIRMED: '自动确认',
    MANUAL_CONFIRMED: '人工确认',
    REJECTED: '已拒绝',
  } as const;
  return labels[mapping.mappingStatus];
}

function predictionHashLabel(prediction: PredictionVersionVo) {
  return prediction.predictionHash.length > 20
    ? `${prediction.predictionHash.slice(0, 20)}…`
    : prediction.predictionHash;
}

/** T501 市场和来源透明信息的公共详情页。 */
export default function MatchDetailPage() {
  const { matchId: rawMatchId } = useParams();
  const location = useLocation();
  const matchId = parseMatchId(rawMatchId);
  const detailQuery = useMatchDetailQuery(matchId);
  const predictionQuery = useMatchPredictionDetailQuery(matchId);
  const snapshotVerification = usePredictionSnapshotVerification();
  const backToList = `/matches${location.search}`;

  if (matchId === undefined) {
    return <NotFoundPage />;
  }

  if (detailQuery.isPending) {
    return <main className="page"><section className="state-card">正在加载比赛详情……</section></main>;
  }

  if (detailQuery.isError) {
    const isNotFound = detailQuery.error instanceof ApiClientError && detailQuery.error.code === 'MATCH_NOT_FOUND';
    return (
      <main className="page">
        <section className="state-card error" role="alert">
          <h1>{isNotFound ? '比赛不存在' : '比赛详情暂不可用'}</h1>
          <p>{detailQuery.error.message}</p>
          <Link to={backToList}>返回比赛列表</Link>
        </section>
      </main>
    );
  }

  const detail = detailQuery.data;
  if (!detail) {
    return null;
  }

  return (
    <main className="page">
      <section className="hero detail-hero">
        <div>
          <p className="eyebrow">Match Detail · Public Data</p>
          <h1>{detail.homeTeamName} VS {detail.awayTeamName}</h1>
          <p className="summary">{detail.leagueName} · {detail.lotteryMatchNo} · {formatTimestamp(detail.kickoffTime)}</p>
        </div>
        <Link className="back-link" to={backToList}>返回比赛列表</Link>
      </section>

      <section className="summary-strip detail-summary">
        <div><span>比赛状态</span><strong>{statusLabels[detail.matchStatus]}</strong></div>
        <div><span>当前比分</span><strong>{detail.homeScore === null || detail.awayScore === null ? '比分待更新' : `${detail.homeScore} : ${detail.awayScore}`}</strong></div>
        <div><span>竞彩日期</span><strong>{detail.lotteryDate}</strong></div>
        <p className={detailQuery.isStale ? 'data-stale' : undefined}>
          {detailQuery.isStale
            ? '详情数据可能已过期，请刷新。'
            : `页面读取时间：${formatTimestamp(new Date(detailQuery.dataUpdatedAt).toISOString())}`}
        </p>
        <button
          className="refresh-button"
          type="button"
          onClick={() => {
            void detailQuery.refetch();
            void predictionQuery.refetch();
          }}
          disabled={detailQuery.isFetching || predictionQuery.isFetching}
        >
          {detailQuery.isFetching || predictionQuery.isFetching ? '刷新中…' : '刷新'}
        </button>
      </section>

      <section className="detail-section" aria-labelledby="prediction-detail-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Model Analysis</p>
            <h2 id="prediction-detail-title">模型分析与透明信息</h2>
          </div>
          {predictionQuery.isSuccess && (
            <span className="availability-badge">
              {predictionQuery.data?.modelPredictions.length ?? 0} 个当前模型
            </span>
          )}
        </div>
        {predictionQuery.isPending && <p className="missing-data">正在加载公开预测……</p>}
        {predictionQuery.isError && (
          <p className="missing-data" role="alert">公开预测暂不可用：{predictionQuery.error.message}</p>
        )}
        {predictionQuery.isSuccess && (
          <>
            {predictionQuery.isStale && <p className="data-stale">预测数据可能已过期，请刷新。</p>}
            {(predictionQuery.data?.modelPredictions ?? []).length === 0 ? (
              <p className="missing-data">当前没有可公开展示的模型预测。</p>
            ) : (
              <div className="prediction-model-list">
                {(predictionQuery.data?.modelPredictions ?? []).map((model) => {
                  const prediction = model.currentPrediction;
                  const snapshot = prediction.snapshot;
                  const verification = snapshotVerification.data?.snapshotId === snapshot?.snapshotId
                    ? snapshotVerification.data
                    : undefined;
                  const verificationError = snapshotVerification.error
                    && snapshotVerification.variables === snapshot?.snapshotId
                    ? snapshotVerification.error
                    : undefined;
                  return (
                    <article className="prediction-model-card" key={model.modelVersion}>
                      <header>
                        <strong>{model.modelVersion}</strong>
                        <span>{predictionStatusLabel(prediction.predictionStatus)} · 第 {prediction.predictionVersion} 版</span>
                      </header>
                      <dl className="metadata-list prediction-metadata">
                        <div><dt>主胜 / 平局 / 客胜</dt><dd>{formatProbability(prediction.homeWinProb)} / {formatProbability(prediction.drawProb)} / {formatProbability(prediction.awayWinProb)}</dd></div>
                        <div><dt>让球倾向</dt><dd>{handicapPickLabel(prediction.handicapPick)}</dd></div>
                        <div><dt>预期总进球</dt><dd>{formatNumber(prediction.expectedTotalGoals)}</dd></div>
                        <div><dt>置信等级</dt><dd>{confidenceLabel(prediction.confidenceLevel)}</dd></div>
                        <div><dt>生成 / 发布时间</dt><dd>{formatTimestamp(prediction.generatedAt)} / {formatTimestamp(prediction.publishTime)}</dd></div>
                        <div><dt>锁定时间</dt><dd>{formatTimestamp(prediction.lockTime)}</dd></div>
                        <div><dt>特征版本</dt><dd>{prediction.featureVersion}</dd></div>
                        <div><dt>预测哈希</dt><dd title={prediction.predictionHash}>{predictionHashLabel(prediction)}</dd></div>
                      </dl>
                      <p className="prediction-summary">{prediction.analysisSummary}</p>
                      <div className="snapshot-panel">
                        <strong>{publicSnapshotAvailabilityLabel(prediction.snapshotAvailability)}</strong>
                        {snapshot ? (
                          <>
                            <span>快照 #{snapshot.snapshotId} · 版本 {snapshot.snapshotVersion} · {formatTimestamp(snapshot.publishedAt)}</span>
                            <span title={snapshot.snapshotHash}>SHA-256：{snapshot.snapshotHash}</span>
                            <div className="snapshot-actions">
                              <a className="back-link" href={predictionSnapshotDownloadUrl(snapshot.snapshotId)}>下载快照</a>
                              <button
                                className="refresh-button"
                                type="button"
                                onClick={() => snapshotVerification.mutate(snapshot.snapshotId)}
                                disabled={snapshotVerification.isPending && snapshotVerification.variables === snapshot.snapshotId}
                              >
                                {snapshotVerification.isPending && snapshotVerification.variables === snapshot.snapshotId
                                  ? '校验中…'
                                  : '校验快照'}
                              </button>
                            </div>
                            {verification && <small className={verification.verified ? 'verification-success' : 'verification-failure'}>{verification.verified ? '当前对象与记录的哈希和长度一致。' : '当前对象未通过哈希或长度校验。'}</small>}
                            {verificationError && <small className="verification-failure">校验失败：{verificationError.message}</small>}
                          </>
                        ) : (
                          <span>当前版本暂无已验证快照，仍可查看公开预测内容。</span>
                        )}
                        <small>哈希用于内容完整性校验，不构成独立的防篡改证明。</small>
                      </div>
                      {model.historicalPredictions.length > 0 && (
                        <details className="prediction-history">
                          <summary>查看 {model.historicalPredictions.length} 个历史公开版本</summary>
                          <ul>
                            {model.historicalPredictions.map((history) => (
                              <li key={history.predictionId}>
                                第 {history.predictionVersion} 版 · {predictionStatusLabel(history.predictionStatus)} · 发布于 {formatTimestamp(history.publishTime)}
                                {history.replacesPredictionId ? ` · 替代预测 #${history.replacesPredictionId}` : ' · 首个公开版本'}
                              </li>
                            ))}
                          </ul>
                        </details>
                      )}
                    </article>
                  );
                })}
              </div>
            )}
          </>
        )}
      </section>

      <section className="detail-section" aria-labelledby="sporttery-market-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Sporttery</p>
            <h2 id="sporttery-market-title">体彩市场</h2>
          </div>
          <span className="availability-badge">{availabilityLabel(detail.sportteryMarket.availability)}</span>
        </div>
        {detail.sportteryMarket.availability !== 'AVAILABLE' ? (
          <p className="missing-data">{availabilityLabel(detail.sportteryMarket.availability)}，暂不展示 SP 或官方让球。</p>
        ) : (
          <>
            <dl className="metadata-list">
              <div><dt>体彩官方让球</dt><dd>{formatHandicap(detail.sportteryMarket.officialHandicap)}</dd></div>
              <div><dt>来源</dt><dd>{dataSourceLabel(detail.sportteryMarket.dataSource)}</dd></div>
              <div><dt>采集时间</dt><dd>{formatTimestamp(detail.sportteryMarket.capturedAt)}</dd></div>
              <div><dt>供应商更新时间</dt><dd>{formatTimestamp(detail.sportteryMarket.providerUpdatedAt)}</dd></div>
              <div><dt>销售状态</dt><dd>{detail.sportteryMarket.sellStatus ?? '暂缺'}</dd></div>
            </dl>
            <div className="market-tables">
              <table>
                <caption>胜平负 SP（HAD）</caption>
                <thead><tr><th>主胜</th><th>平</th><th>客胜</th></tr></thead>
                <tbody><tr><td>{formatNumber(detail.sportteryMarket.hadHomeSp)}</td><td>{formatNumber(detail.sportteryMarket.hadDrawSp)}</td><td>{formatNumber(detail.sportteryMarket.hadAwaySp)}</td></tr></tbody>
              </table>
              <table>
                <caption>让球胜平负 SP（HHAD）</caption>
                <thead><tr><th>主胜</th><th>平</th><th>客胜</th></tr></thead>
                <tbody><tr><td>{formatNumber(detail.sportteryMarket.hhadHomeSp)}</td><td>{formatNumber(detail.sportteryMarket.hhadDrawSp)}</td><td>{formatNumber(detail.sportteryMarket.hhadAwaySp)}</td></tr></tbody>
              </table>
            </div>
          </>
        )}
      </section>

      <section className="detail-section" aria-labelledby="asian-market-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Asian Odds</p>
            <h2 id="asian-market-title">亚洲盘快照</h2>
          </div>
          <span className="availability-badge">{availabilityLabel(detail.asianOddsAvailability)}</span>
        </div>
        {detail.asianOddsMarkets.length === 0 ? (
          <p className="missing-data">{availabilityLabel(detail.asianOddsAvailability)}。</p>
        ) : (
          <div className="asian-market-list">
            {detail.asianOddsMarkets.map((market, index) => (
              <article className="asian-market-card" key={`${market.providerCode}-${market.bookmakerCode}-${market.handicapLine}-${index}`}>
                <header><strong>{market.bookmakerCode}</strong><span>{market.providerCode}</span></header>
                <dl className="metadata-list compact">
                  <div><dt>亚洲让球线</dt><dd>{formatNumber(market.handicapLine)}</dd></div>
                  <div><dt>主 / 客赔率</dt><dd>{formatNumber(market.homeOdds)} / {formatNumber(market.awayOdds)}</dd></div>
                  {market.totalLine !== null && market.overOdds !== null && market.underOdds !== null && (
                    <div><dt>大小球</dt><dd>{market.totalLine}（大 {market.overOdds} / 小 {market.underOdds}）</dd></div>
                  )}
                  <div><dt>快照类型</dt><dd>{snapshotTypeLabel(market.snapshotType)}</dd></div>
                  <div><dt>采集 / 更新</dt><dd>{formatTimestamp(market.capturedAt)} / {formatTimestamp(market.providerUpdatedAt)}</dd></div>
                </dl>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="detail-section" aria-labelledby="mapping-title">
        <div className="section-heading">
          <div><p className="eyebrow">Source Mapping</p><h2 id="mapping-title">来源映射</h2></div>
          <span className="availability-badge">{availabilityLabel(detail.mappingAvailability)}</span>
        </div>
        {detail.sourceMappings.length === 0 ? (
          <p className="missing-data">{availabilityLabel(detail.mappingAvailability)}。</p>
        ) : (
          <div className="mapping-list">
            {detail.sourceMappings.map((mapping) => (
              <article className="mapping-card" key={`${mapping.providerCode}-${mapping.externalMatchId}`}>
                <strong>{mapping.providerCode}</strong>
                <span>{mappingStatusLabel(mapping)} · {mapping.mappingMethod ?? '方法暂缺'}</span>
                <p>{mapping.mappingExplanation ?? '暂无映射解释。'}</p>
                <small>更新时间：{formatTimestamp(mapping.mappingUpdatedAt)}</small>
              </article>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
