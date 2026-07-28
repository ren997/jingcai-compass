import { Link, useLocation, useParams } from 'react-router-dom';
import { ApiClientError } from '../../services/http';
import type { MatchSourceMappingVo } from '../../services/public';
import NotFoundPage from '../../pages/NotFoundPage';
import {
  availabilityLabel,
  dataSourceLabel,
  formatHandicap,
  formatNumber,
  formatTimestamp,
  snapshotTypeLabel,
  statusLabels,
} from './matchPresentation';
import { useMatchDetailQuery } from './useMatchQueries';

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

/** T501 市场和来源透明信息的公共详情页。 */
export default function MatchDetailPage() {
  const { matchId: rawMatchId } = useParams();
  const location = useLocation();
  const matchId = parseMatchId(rawMatchId);
  const detailQuery = useMatchDetailQuery(matchId);
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
        <button className="refresh-button" type="button" onClick={() => void detailQuery.refetch()} disabled={detailQuery.isFetching}>
          {detailQuery.isFetching ? '刷新中…' : '刷新'}
        </button>
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
