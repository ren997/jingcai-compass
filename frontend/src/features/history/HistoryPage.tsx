import { useSearchParams } from 'react-router-dom';
import {
  settlementMarketsFor,
  settlementStatusesFor,
  type PredictionType,
  type SettlementStatus,
} from '../../services/public';
import {
  handicapPickLabel,
  predictionStatusLabel,
  formatProbability,
  formatTimestamp,
  pickedProbability,
  statusLabels,
} from '../matches/matchPresentation';
import {
  asianMarketReference,
  asianPickSummary,
  currentFact,
  defaultMarketFor,
  factScore,
  factSourceLabel,
  factStatusLabel,
  formatSigned,
  predictionTypeLabels,
  settlementMarketLabel,
  settlementStatusLabel,
} from './historyPresentation';
import {
  parseHistorySearch,
  toHistoryListQuery,
  toHistorySearchParams,
  type HistorySearch,
} from './historySearch';
import { useHistoryListQuery } from './useHistoryQueries';

/** T507 全量公开预测、赛果与结算版本历史页。 */
export default function HistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = parseHistorySearch(searchParams);
  const historyQuery = useHistoryListQuery(toHistoryListQuery(filters));
  const page = historyQuery.data;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;

  function updateFilters(next: Partial<HistorySearch>) {
    setSearchParams(toHistorySearchParams({ ...filters, ...next }));
  }

  function updateFilterAndResetPage(next: Partial<HistorySearch>) {
    updateFilters({ ...next, pageNo: 1 });
  }

  function changeSettlementStatus(status: SettlementStatus, checked: boolean) {
    const settlementStatuses = checked
      ? settlementStatusesFor(filters.predictionType).filter(
          (candidate) => candidate === status || filters.settlementStatuses.includes(candidate),
        )
      : filters.settlementStatuses.filter((candidate) => candidate !== status);
    updateFilterAndResetPage({ settlementStatuses });
  }

  /**
   * 切换产品线时必须同时把市场与状态重置。
   *
   * <p>只改 type 会让 URL 停留在「亚盘 + HAD」这种组合上，后端会以参数错误拒绝整个请求。
   * 状态同理：竞彩下勾着的"走盘"在新类型里没有意义，留着只会得到空结果。
   */
  function changePredictionType(predictionType: PredictionType) {
    updateFilterAndResetPage({
      predictionType,
      settlementMarket: defaultMarketFor(predictionType),
      settlementStatuses: [],
    });
  }

  return (
    <main className="page">
      <section className="hero history-hero">
        <div>
          <p className="eyebrow">JingCai Compass · Public History</p>
          <h1>公开预测历史</h1>
          <p className="summary">保留命中、未中、待结算与作废记录，并可追溯赛果和结算修正版本。</p>
        </div>
      </section>

      <section className="history-filters" aria-label="历史筛选">
        <div className="prediction-type-switch" role="tablist" aria-label="产品线">
          {(['ASIAN', 'SPORTTERY'] as PredictionType[]).map((type) => (
            <button
              key={type}
              type="button"
              role="tab"
              aria-selected={filters.predictionType === type}
              onClick={() => changePredictionType(type)}
            >
              {predictionTypeLabels[type]}
            </button>
          ))}
        </div>
        <label>
          <span>开始日期</span>
          <input
            aria-label="开始日期"
            type="date"
            value={filters.startDate ?? ''}
            onChange={(event) => updateFilterAndResetPage({ startDate: event.target.value || undefined })}
          />
        </label>
        <label>
          <span>结束日期</span>
          <input
            aria-label="结束日期"
            type="date"
            value={filters.endDate ?? ''}
            onChange={(event) => updateFilterAndResetPage({ endDate: event.target.value || undefined })}
          />
        </label>
        <label>
          <span>联赛 ID</span>
          <input
            aria-label="联赛 ID"
            type="number"
            min="1"
            inputMode="numeric"
            value={filters.leagueId ?? ''}
            onChange={(event) => {
              const value = Number(event.target.value);
              updateFilterAndResetPage({ leagueId: Number.isSafeInteger(value) && value > 0 ? value : undefined });
            }}
          />
        </label>
        <label>
          <span>模型版本</span>
          <input
            aria-label="模型版本"
            type="text"
            value={filters.modelVersion ?? ''}
            onChange={(event) => updateFilterAndResetPage({ modelVersion: event.target.value.trim() || undefined })}
          />
        </label>
        <label>
          <span>结算市场</span>
          <select
            aria-label="结算市场"
            value={filters.settlementMarket}
            onChange={(event) => updateFilterAndResetPage({ settlementMarket: event.target.value as HistorySearch['settlementMarket'] })}
          >
            {settlementMarketsFor(filters.predictionType).map((market) => (
              <option key={market} value={market}>{settlementMarketLabel(market)}</option>
            ))}
          </select>
        </label>
        <label className="check-filter">
          <input
            type="checkbox"
            checked={filters.lockedOnly}
            onChange={(event) => updateFilterAndResetPage({ lockedOnly: event.target.checked })}
          />
          仅已锁定
        </label>
        <fieldset className="status-filter">
          <legend>结算状态</legend>
          <div>
            {settlementStatusesFor(filters.predictionType).map((status) => (
              <label key={status}>
                <input
                  type="checkbox"
                  checked={filters.settlementStatuses.includes(status)}
                  onChange={(event) => changeSettlementStatus(status, event.target.checked)}
                />
                {settlementStatusLabel(status)}
              </label>
            ))}
          </div>
        </fieldset>
      </section>

      {historyQuery.isPending && <section className="state-card">正在加载公开历史……</section>}
      {historyQuery.isError && (
        <section className="state-card error" role="alert">公开历史暂不可用：{historyQuery.error.message}</section>
      )}
      {historyQuery.isSuccess && page && (
        <>
          <section className="summary-strip">
            <div><span>筛选结果</span><strong>{page.total} 条预测版本</strong></div>
            <div><span>页面读取时间</span><strong>{formatTimestamp(new Date(historyQuery.dataUpdatedAt).toISOString())}</strong></div>
            <p className={historyQuery.isStale ? 'data-stale' : undefined}>
              {historyQuery.isStale ? '数据可能已过期，请刷新。' : '历史不会因未命中、待结算或作废而被隐藏。'}
            </p>
            <button className="refresh-button" type="button" onClick={() => void historyQuery.refetch()} disabled={historyQuery.isFetching}>
              {historyQuery.isFetching ? '刷新中…' : '刷新'}
            </button>
          </section>

          {page.records.length === 0 ? (
            <section className="state-card">当前筛选条件下暂无公开预测历史。</section>
          ) : (
            <section className="history-list" aria-label="公开预测历史列表">
              {page.records.map((record) => {
                const fact = currentFact(record);
                return (
                  <article className="history-card" key={record.predictionId}>
                    <header>
                      <div>
                        <span className="match-number">{record.match.lotteryMatchNo}</span>
                        <span>{record.match.leagueName}</span>
                      </div>
                      <span>{record.match.lotteryDate}</span>
                    </header>
                    <div className="history-card-main">
                      <div className="history-teams"><strong>{record.match.homeTeamName}</strong><span>VS</span><strong>{record.match.awayTeamName}</strong></div>
                      <div className="history-badges">
                        <span>{predictionTypeLabels[record.predictionType]}</span>
                        <span>{record.modelVersion} · 第 {record.predictionVersion} 版</span>
                        <span>{predictionStatusLabel(record.predictionStatus)}</span>
                        {record.recalculatedAfterFactCorrection && <span className="correction-badge">赛果修正后重算</span>}
                      </div>
                      <dl className="metadata-list history-metadata">
                        <div><dt>发布时间</dt><dd>{formatTimestamp(record.publishTime)}</dd></div>
                        <div><dt>锁定时间</dt><dd>{formatTimestamp(record.lockTime)}</dd></div>
                        {record.predictionType === 'SPORTTERY' ? (
                          <>
                            <div><dt>主胜 / 平局 / 客胜</dt><dd>{formatProbability(record.homeWinProb)} / {formatProbability(record.drawProb)} / {formatProbability(record.awayWinProb)}</dd></div>
                            <div><dt>竞彩让球胜平负</dt><dd>{record.handicapPick ? handicapPickLabel(record.handicapPick) : '暂缺'}</dd></div>
                          </>
                        ) : (
                          <>
                            <div><dt>亚盘方向</dt><dd>{asianPickSummary(record)}</dd></div>
                            <div>
                              <dt>所选方向模型概率</dt>
                              <dd>
                                让球 {formatProbability(pickedProbability(record.asianModel, record.asianHandicapPick, 'HANDICAP'))}
                                {' / '}
                                大小球 {formatProbability(pickedProbability(record.asianModel, record.totalGoalsPick, 'TOTALS'))}
                              </dd>
                            </div>
                            <div><dt>市场共识（首边）</dt><dd>{asianMarketReference(record)}</dd></div>
                          </>
                        )}
                        <div><dt>当前赛果</dt><dd>{factScore(fact)}{fact ? ` · ${factStatusLabel(fact.factStatus)} · ${factSourceLabel(fact.resultSource)}` : ''}</dd></div>
                        <div><dt>比赛状态</dt><dd>{fact ? statusLabels[fact.matchStatus] : '待赛果'}</dd></div>
                      </dl>
                      <div className="settlement-summary" aria-label="当前结算状态">
                        {record.settlementMarkets.map((market) => (
                          <span key={market.marketType}>
                            {settlementMarketLabel(market.marketType)}：<strong>{settlementStatusLabel(market.currentStatus)}</strong>
                          </span>
                        ))}
                      </div>
                      <details className="history-versions">
                        <summary>查看赛果与结算版本</summary>
                        <div className="version-columns">
                          <section>
                            <h3>赛果事实</h3>
                            {record.resultFacts.length === 0 ? <p>尚无赛果事实。</p> : (
                              <ul>
                                {record.resultFacts.map((item) => <li key={item.factId}>第 {item.factVersion} 版 · {factScore(item)} · {factStatusLabel(item.factStatus)}{item.current ? ' · 当前' : ''} · {factSourceLabel(item.resultSource)}</li>)}
                              </ul>
                            )}
                          </section>
                          {record.settlementMarkets.map((market) => (
                            <section key={market.marketType}>
                              <h3>{settlementMarketLabel(market.marketType)}</h3>
                              {market.versions.length === 0 ? <p>当前待结算。</p> : (
                                <ul>
                                  {market.versions.map((item) => (
                                    <li key={item.settlementId}>
                                      第 {item.settlementVersion} 版 · {settlementStatusLabel(item.settlementStatus)}
                                      {item.profit !== null && ` · 收益 ${formatSigned(item.profit)}`}
                                      {item.current ? ' · 当前' : ''}
                                    </li>
                                  ))}
                                </ul>
                              )}
                            </section>
                          ))}
                        </div>
                      </details>
                    </div>
                  </article>
                );
              })}
            </section>
          )}

          <nav className="pagination" aria-label="历史分页">
            <button type="button" onClick={() => updateFilters({ pageNo: filters.pageNo - 1 })} disabled={filters.pageNo <= 1}>上一页</button>
            <span>第 {page.pageNo} / {pageCount} 页</span>
            <button type="button" onClick={() => updateFilters({ pageNo: filters.pageNo + 1 })} disabled={filters.pageNo >= pageCount}>下一页</button>
          </nav>
        </>
      )}
    </main>
  );
}
