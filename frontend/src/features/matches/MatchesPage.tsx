import { Link, useSearchParams } from 'react-router-dom';
import {
  MATCH_LIST_SORTS,
  MATCH_STATUSES,
  type MatchListItemVo,
  type MatchListSort,
  type MatchStatus,
} from '../../services/public';
import {
  availabilityLabel,
  asianHandicapPredictionLabel,
  confidenceLabel,
  dataSourceLabel,
  formatHandicap,
  formatNumber,
  formatTimestamp,
  predictionStatusLabel,
  statusLabels,
  totalGoalsPickLabel,
} from './matchPresentation';
import { parseMatchListSearch, todayInShanghai, toMatchListQuery, toMatchListSearchParams, type MatchListSearch } from './matchSearch';
import { useLeagueOptionsQuery, useMatchListQuery } from './useMatchQueries';

const sortLabels: Record<MatchListSort, string> = {
  KICKOFF_ASC: '开赛时间：早到晚',
  KICKOFF_DESC: '开赛时间：晚到早',
  LOTTERY_MATCH_NO_ASC: '竞彩编号：升序',
  LOTTERY_MATCH_NO_DESC: '竞彩编号：降序',
};

/** T501 支持的公开分页比赛列表与筛选。 */
export default function MatchesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseMatchListSearch(searchParams, todayInShanghai());
  const matchesQuery = useMatchListQuery(toMatchListQuery(filters));
  const leagueOptionsQuery = useLeagueOptionsQuery(filters.lotteryDate);
  const page = matchesQuery.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const listSearch = searchParams.toString();

  function updateFilters(next: Partial<MatchListSearch>) {
    setSearchParams(toMatchListSearchParams({ ...filters, ...next }));
  }

  function changeStatus(status: MatchStatus, checked: boolean) {
    const matchStatuses = checked
      ? MATCH_STATUSES.filter((candidate) => candidate === status || filters.matchStatuses.includes(candidate))
      : filters.matchStatuses.filter((candidate) => candidate !== status);
    updateFilters({ matchStatuses, pageNo: 1 });
  }

  function refresh() {
    void matchesQuery.refetch();
    void leagueOptionsQuery.refetch();
  }

  return (
    <main className="page">
      <section className="hero">
        <div>
          <p className="eyebrow">JingCai Compass · Public Matches</p>
          <h1>今日竞彩比赛</h1>
          <p className="summary">按日期、联赛与状态查看已持久化的比赛池和体彩数据状态。</p>
        </div>
        <label className="date-control">
          <span>竞彩日期</span>
          <input
            aria-label="竞彩日期"
            type="date"
            value={filters.lotteryDate}
            onChange={(event) => updateFilters({
              lotteryDate: event.target.value,
              leagueId: undefined,
              pageNo: 1,
            })}
          />
        </label>
      </section>

      <section className="match-filters" aria-label="比赛筛选">
        <label>
          <span>联赛</span>
          <select
            aria-label="联赛"
            value={filters.leagueId ?? ''}
            onChange={(event) => updateFilters({
              leagueId: event.target.value ? Number(event.target.value) : undefined,
              pageNo: 1,
            })}
          >
            <option value="">全部联赛</option>
            {(leagueOptionsQuery.data ?? []).map((league) => (
              <option key={league.id} value={league.id}>{league.name}</option>
            ))}
          </select>
        </label>
        <label>
          <span>排序</span>
          <select
            aria-label="排序"
            value={filters.sort}
            onChange={(event) => updateFilters({
              sort: event.target.value as MatchListSort,
              pageNo: 1,
            })}
          >
            {MATCH_LIST_SORTS.map((sort) => <option key={sort} value={sort}>{sortLabels[sort]}</option>)}
          </select>
        </label>
        <fieldset className="status-filter">
          <legend>比赛状态</legend>
          <div>
            {MATCH_STATUSES.map((status) => (
              <label key={status}>
                <input
                  type="checkbox"
                  checked={filters.matchStatuses.includes(status)}
                  onChange={(event) => changeStatus(status, event.target.checked)}
                />
                {statusLabels[status]}
              </label>
            ))}
          </div>
        </fieldset>
      </section>

      {matchesQuery.isPending && <section className="state-card">正在加载比赛池……</section>}
      {matchesQuery.isError && (
        <section className="state-card error" role="alert">
          后端连接失败：{matchesQuery.error.message}
        </section>
      )}
      {matchesQuery.isSuccess && page && (
        <>
          <section className="summary-strip">
            <div>
              <span>筛选结果</span>
              <strong>{page.total} 场</strong>
            </div>
            <div>
              <span>页面读取时间</span>
              <strong>{formatTimestamp(new Date(matchesQuery.dataUpdatedAt).toISOString())}</strong>
            </div>
            <p className={matchesQuery.isStale ? 'data-stale' : undefined}>
              {matchesQuery.isStale ? '数据可能已过期，请刷新。' : '数据来自已持久化的公开比赛池。'}
            </p>
            <button className="refresh-button" type="button" onClick={refresh} disabled={matchesQuery.isFetching}>
              {matchesQuery.isFetching ? '刷新中…' : '刷新'}
            </button>
          </section>

          {page.records.length === 0 ? (
            <section className="state-card">当前筛选条件下暂无比赛。</section>
          ) : (
            <section className="match-list" aria-label="竞彩比赛列表">
              {page.records.map((match) => <MatchCard key={match.matchId} match={match} listSearch={listSearch} />)}
            </section>
          )}

          <nav className="pagination" aria-label="比赛分页">
            <button
              type="button"
              onClick={() => updateFilters({ pageNo: filters.pageNo - 1 })}
              disabled={filters.pageNo <= 1}
            >
              上一页
            </button>
            <span>第 {page.pageNo} / {pageCount} 页</span>
            <button
              type="button"
              onClick={() => updateFilters({ pageNo: filters.pageNo + 1 })}
              disabled={filters.pageNo >= pageCount}
            >
              下一页
            </button>
          </nav>
        </>
      )}
    </main>
  );
}

function MatchCard({ match, listSearch }: { match: MatchListItemVo; listSearch: string }) {
  return <Link className="match-card match-card-link" to={`/matches/${match.matchId}${listSearch ? `?${listSearch}` : ''}`}>
    <header>
      <span className="match-number">{match.lotteryMatchNo}</span>
      <span>{match.leagueName}</span>
      <time dateTime={match.kickoffTime}>{formatTimestamp(match.kickoffTime)}</time>
    </header>
    <div className="teams">
      <strong>{match.homeTeamName}</strong>
      <span className="versus">VS</span>
      <strong>{match.awayTeamName}</strong>
    </div>
    <div className="match-card-markets">
      <SportterySummary match={match} />
      <AsianMainSummary match={match} />
      <PredictionSummary match={match} />
    </div>
    <footer>
      <span className="handicap">{formatHandicap(match.officialHandicap)}</span>
      <span className="match-status">{statusLabels[match.matchStatus]}</span>
    </footer>
    <div className="match-meta">
      <span>{availabilityLabel(match.sportteryAvailability)}</span>
      <span>{dataSourceLabel(match.sportteryDataSource)}</span>
      <span>采集：{formatTimestamp(match.sportteryCapturedAt)}</span>
    </div>
  </Link>;
}

function SportterySummary({ match }: { match: MatchListItemVo }) {
  const market = match.sportteryMarket;
  return <section className="match-market-summary" aria-label={`${match.lotteryMatchNo} 体彩赔率`}>
    <h2>体彩赔率</h2>
    {market.availability !== 'AVAILABLE' ? <p>胜平负与让球胜平负暂缺</p> : <>
      <OddsTriplet label="胜平负" home={market.hadHomeSp} draw={market.hadDrawSp} away={market.hadAwaySp} />
      <OddsTriplet label={`让球胜平负（${formatHandicap(market.officialHandicap)}）`} home={market.hhadHomeSp} draw={market.hhadDrawSp} away={market.hhadAwaySp} />
    </>}
  </section>;
}

function OddsTriplet({ label, home, draw, away }: {
  label: string;
  home: number | null;
  draw: number | null;
  away: number | null;
}) {
  return <div className="match-odds-triplet"><strong>{label}</strong><span>主 {formatNumber(home)}</span>
    <span>平 {formatNumber(draw)}</span><span>客 {formatNumber(away)}</span></div>;
}

function AsianMainSummary({ match }: { match: MatchListItemVo }) {
  const market = match.asianMainMarket;
  return <section className="match-market-summary" aria-label={`${match.lotteryMatchNo} 亚盘主盘`}>
    <h2>亚盘主盘</h2>
    {!market ? <p>已确认的完整亚盘主盘暂缺</p> : <>
      <p className="market-provider">{market.bookmakerCode} · {market.providerCode}</p>
      <div className="match-market-line"><strong>亚洲{formatHandicap(market.handicapLine)}</strong><span>主 {formatNumber(market.homeOdds)} / 客 {formatNumber(market.awayOdds)}</span></div>
      <div className="match-market-line"><strong>大小球 {formatNumber(market.totalLine)}</strong><span>大 {formatNumber(market.overOdds)} / 小 {formatNumber(market.underOdds)}</span></div>
    </>}
  </section>;
}

function PredictionSummary({ match }: { match: MatchListItemVo }) {
  const asianPredictions = match.publishedPredictions.filter((prediction) => prediction.predictionType === 'ASIAN');
  return <section className="match-market-summary" aria-label={`${match.lotteryMatchNo} 预测结果`}>
    <h2>预测结果</h2>
    {asianPredictions.length === 0 ? <p>暂无已发布亚盘预测</p> : asianPredictions.map((prediction) => <article className="match-prediction-summary" key={prediction.modelVersion}>
      <header><strong>{prediction.modelVersion}</strong><span>{predictionStatusLabel(prediction.predictionStatus)}</span></header>
      {prediction.asianHandicapPick && <p>{asianHandicapPredictionLabel(prediction.asianMarket?.handicapLine, prediction.asianHandicapPick)} · 置信：{confidenceLabel(prediction.asianHandicapConfidenceLevel)}</p>}
      {prediction.totalGoalsPick && <p>亚盘大小球（{formatNumber(prediction.asianMarket?.totalLine)}）：{totalGoalsPickLabel(prediction.totalGoalsPick)} · 置信：{confidenceLabel(prediction.totalGoalsConfidenceLevel)}</p>}
      {prediction.asianMarket && <p className="market-provider">生成快照 #{prediction.asianMarket.asianOddsSnapshotId} · {prediction.asianMarket.bookmakerCode}：亚洲{formatHandicap(prediction.asianMarket.handicapLine)} 主 {formatNumber(prediction.asianMarket.homeOdds)} / 客 {formatNumber(prediction.asianMarket.awayOdds)}；大小球 {formatNumber(prediction.asianMarket.totalLine)} 大 {formatNumber(prediction.asianMarket.overOdds)} / 小 {formatNumber(prediction.asianMarket.underOdds)} · {formatTimestamp(prediction.asianMarket.capturedAt)}</p>}
    </article>)}
  </section>;
}
