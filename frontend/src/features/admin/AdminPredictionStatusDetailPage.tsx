import { Alert, Button, Tag } from 'antd';
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom';
import type { AdminSettlementMarketHistory } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import { diagnosticsText, factText, lockLabels, marketText, settlementLabels } from './adminPredictionPresentation';
import { useAdminPredictionStatusDetailQuery } from './useAdminQueries';

function MarketHistory({ market }: { market: AdminSettlementMarketHistory }) {
  return <article className="admin-version-market"><header><div><h3>{market.marketType}</h3><p>当前：{market.currentStatus}{market.currentSettlementStale ? '（引用已替代赛果）' : ''}</p></div>
    <Tag color={market.currentSettlementPersisted ? 'green' : 'gold'}>{market.currentSettlementPersisted ? '已落库' : 'PENDING'}</Tag></header>
    {market.versions.length === 0 ? <p className="admin-empty">当前没有持久化结算版本。</p> : <div className="admin-version-list">{market.versions.map((version) => <article key={version.settlementId} className={version.current ? 'current' : ''}>
      <strong>V{version.settlementVersion} · {version.settlementStatus}</strong><span>{version.current ? '当前' : '历史'} · 赛果事实 #{version.matchFactId}</span>
      <small>规则 {version.ruleVersion} · {formatTimestamp(version.createdAt)}</small>{version.supersedesSettlementVersion !== null && <small>替代 V{version.supersedesSettlementVersion}</small>}
    </article>)}</div>}
  </article>;
}

/** 在两组后台入口中复用的预测状态与版本追溯详情页。 */
export default function AdminPredictionStatusDetailPage() {
  const { predictionId: rawId } = useParams();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const predictionId = rawId && /^\d+$/.test(rawId) && Number(rawId) > 0 ? Number(rawId) : undefined;
  const query = useAdminPredictionStatusDetailQuery(predictionId);
  const settlementEntry = location.pathname.startsWith('/admin/settlements/');
  const listPath = settlementEntry ? '/admin/settlements' : '/admin/predictions';
  const listLabel = settlementEntry ? '结算状态' : '预测锁定';
  const backTo = `${listPath}${searchParams.toString() ? `?${searchParams}` : ''}`;
  if (predictionId === undefined) return <main className="admin-page"><section className="admin-state-card error"><h1>页面不存在</h1><p>预测 ID 格式无效。</p></section></main>;
  if (query.isPending) return <main className="admin-page"><section className="admin-state-card">正在读取预测状态详情……</section></main>;
  if (query.isError) return <main className="admin-page"><section className="admin-state-card error"><h1>预测状态不可用</h1><p>{query.error.message}</p><Link to={backTo}>返回{listLabel}</Link></section></main>;
  const detail = query.data!;
  const item = detail.prediction;
  return <main className="admin-page admin-workspace"><section className="admin-page-heading"><div><p className="eyebrow">Operations · Prediction #{item.predictionId}</p>
    <h1>{item.match.homeTeamName} vs {item.match.awayTeamName}</h1><p>{item.match.lotteryDate} · {item.match.leagueName} · {item.modelVersion} V{item.predictionVersion}</p></div>
    <div className="admin-actions"><Button onClick={() => void query.refetch()}>刷新</Button><Link to={backTo}>返回{listLabel}</Link></div></section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>当前运营状态</h2><span>仅由持久化事实派生</span></div></header>
      <dl className="admin-metadata"><div><dt>预测状态</dt><dd>{item.predictionStatus === 'PUBLISHED' ? '已发布' : '已锁定'}</dd></div><div><dt>锁定诊断</dt><dd>{diagnosticsText(item.lockDiagnostics, lockLabels)}</dd></div>
        <div><dt>发布时间</dt><dd>{formatTimestamp(item.publishTime)}</dd></div><div><dt>锁定时间</dt><dd>{formatTimestamp(item.lockTime)}</dd></div>
        <div><dt>特征版本</dt><dd>{item.featureVersion}</dd></div><div><dt>预测哈希</dt><dd>{item.predictionHash}</dd></div></dl>
      <p className="admin-explanation">结算诊断：{diagnosticsText(item.settlementDiagnostics, settlementLabels)}。系统不在此页面提供人工结算、重试或直接修改入口。</p>
    </section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>当前赛果与市场结算</h2><span>{factText(item.currentResultFact)}</span></div></header>
      <dl className="admin-metadata compact"><div><dt>HAD</dt><dd>{marketText(item.hadSettlement)}</dd></div><div><dt>HHAD</dt><dd>{marketText(item.hhadSettlement)}</dd></div>
        <div><dt>当前事实版本</dt><dd>{item.currentResultFact ? `V${item.currentResultFact.factVersion}` : '暂无'}</dd></div><div><dt>供应商更新时间</dt><dd>{formatTimestamp(item.currentResultFact?.providerUpdatedAt ?? null)}</dd></div></dl>
    </section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>官方赛果版本链</h2><span>当前与历史明确区分</span></div></header>
      {detail.resultFactHistory.length === 0 ? <p className="admin-empty">尚未持久化官方赛果事实。</p> : <div className="admin-version-list">{detail.resultFactHistory.map((fact) => <article key={fact.factId} className={fact.current ? 'current' : ''}>
        <strong>事实 V{fact.factVersion} · {fact.factStatus}</strong><span>{fact.current ? '当前权威事实' : '历史事实'} · {factText(fact)}</span>
        <small>供应商更新 {formatTimestamp(fact.providerUpdatedAt)} · 写入 {formatTimestamp(fact.createdAt)}</small>{fact.supersedesFactVersion !== null && <small>替代 V{fact.supersedesFactVersion}</small>}
      </article>)}</div>}
    </section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>市场结算版本链</h2><span>不会将历史结算冒充为当前结果</span></div></header>
      <div className="admin-version-markets">{detail.settlementMarkets.map((market) => <MarketHistory key={market.marketType} market={market} />)}</div>
    </section>
  </main>;
}
