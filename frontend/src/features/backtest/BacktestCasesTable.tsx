import { Alert, Button, Select } from 'antd';
import type { BacktestCaseQuery, BacktestCaseVo } from '../../services/backtest';
import type { PageResult } from '../../services/public';
import { formatAsianLine, formatProbability, formatTimestamp } from '../matches/matchPresentation';
import {
  backtestCaseVerdict,
  backtestDecisionStageHints,
  backtestMarketScopeLabels,
  decisionStageLabel,
  formatProfit,
  skipReasonLabel,
} from './backtestPresentation';

type BacktestCasesTableProps = {
  page: PageResult<BacktestCaseVo> | undefined;
  isPending: boolean;
  isError: boolean;
  errorMessage?: string;
  query: BacktestCaseQuery;
  groupKeys: string[];
  onQueryChange: (next: BacktestCaseQuery) => void;
};

const PAGE_SIZE_OPTIONS = [20, 50, 100];

/**
 * 逐场明细。
 *
 * <p>主行只给「结论 + 结算结果」，模型概率、λ、σ、残差全部收进折叠区。原因和首页复盘一致：
 * 主行塞满概率会让人以为那才是结论，而结论是「主 -0.5 中了没有」。</p>
 */
export default function BacktestCasesTable({
  page,
  isPending,
  isError,
  errorMessage,
  query,
  groupKeys,
  onQueryChange,
}: BacktestCasesTableProps) {
  const pageSize = query.pageSize ?? 20;
  const pageCount = page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1;
  const pageNo = query.page ?? 1;

  return (
    <section className="detail-section">
      <div className="section-heading">
        <div><p className="eyebrow">Cases</p><h2>逐场明细</h2></div>
        {page && <span>共 {page.total} 条</span>}
      </div>

      <div className="admin-filters" aria-label="明细筛选">
        <label>
          <span>参数组</span>
          <Select
            aria-label="参数组"
            allowClear
            value={query.groupKey}
            placeholder="全部"
            onChange={(value) => onQueryChange({ ...query, groupKey: value || undefined, page: 1 })}
            options={groupKeys.map((key) => ({ value: key, label: key }))}
          />
        </label>
        <label>
          <span>口径</span>
          <Select
            aria-label="口径"
            allowClear
            value={query.marketScope}
            placeholder="全部"
            onChange={(value) => onQueryChange({ ...query, marketScope: value || undefined, page: 1 })}
            options={Object.entries(backtestMarketScopeLabels).map(([value, label]) => ({ value, label }))}
          />
        </label>
        <label>
          <span>只看有方向</span>
          <input
            aria-label="只看有方向"
            type="checkbox"
            checked={Boolean(query.onlyPicked)}
            onChange={(event) => onQueryChange({ ...query, onlyPicked: event.target.checked, page: 1 })}
          />
        </label>
        <label>
          <span>每页</span>
          <Select
            aria-label="每页条数"
            value={pageSize}
            onChange={(value) => onQueryChange({ ...query, pageSize: value, page: 1 })}
            options={PAGE_SIZE_OPTIONS.map((value) => ({ value, label: `${value} 条` }))}
          />
        </label>
      </div>

      {isError && <Alert type="error" showIcon message={`明细读取失败：${errorMessage ?? '未知错误'}`} />}
      {isPending && <p className="missing-data">正在读取明细……</p>}

      {page && page.records.length === 0 && (
        <p className="missing-data">当前筛选没有样本。回测里「没有样本」本身是结论：放宽筛选或换参数组再看。</p>
      )}

      {page && page.records.length > 0 && (
        <>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>场次</th>
                  <th>联赛</th>
                  <th>决策时点</th>
                  <th>开赛</th>
                  <th>结论</th>
                  <th>置信度</th>
                  <th>结算</th>
                  <th>收益</th>
                </tr>
              </thead>
              <tbody>
                {page.records.map((item) => (
                  <tr key={item.id}>
                    <th scope="row">
                      {item.lotteryMatchNo ?? item.externalMatchId}
                      <small>{item.groupKey}</small>
                    </th>
                    <td>{item.leagueName ?? '—'}</td>
                    <td>{formatTimestamp(item.decisionAt)}</td>
                    <td>{formatTimestamp(item.kickoffTime)}</td>
                    <td>
                      {backtestCaseVerdict(item)}
                      {!item.handicapPick && !item.totalsPick && (
                        <small>{skipReasonLabel(item.skipReason)}</small>
                      )}
                    </td>
                    <td>{confidenceText(item)}</td>
                    <td>{settlementText(item)}</td>
                    <td className={`backtest-profit ${profitTone(item)}`}>
                      {formatProfit(totalProfit(item))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="pagination">
            <Button disabled={pageNo <= 1} onClick={() => onQueryChange({ ...query, page: pageNo - 1 })}>上一页</Button>
            <span>第 {pageNo} / {pageCount} 页</span>
            <Button disabled={pageNo >= pageCount} onClick={() => onQueryChange({ ...query, page: pageNo + 1 })}>下一页</Button>
          </div>

          <details className="backtest-notes">
            <summary>判定推导（λ、σ、残差、市场概率）</summary>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>场次</th>
                    <th>判定阶段</th>
                    <th>冻结行</th>
                    <th>λ 主/客</th>
                    <th>σ 让球/大小</th>
                    <th>模型 / 市场（首边）</th>
                    <th>残差 / 邻线偏离</th>
                  </tr>
                </thead>
                <tbody>
                  {page.records.map((item) => (
                    <tr key={`detail-${item.id}`}>
                      <th scope="row">{item.lotteryMatchNo ?? item.externalMatchId}</th>
                      <td>
                        {decisionStageLabel(item.decisionStage)}
                        {backtestDecisionStageHints[item.decisionStage] && (
                          <small>{backtestDecisionStageHints[item.decisionStage]}</small>
                        )}
                      </td>
                      <td>
                        {item.frozenBookmakerCode ?? '—'}
                        {item.frozenHandicapLine !== null && ` · 让 ${formatAsianLine(item.frozenHandicapLine)}`}
                        {item.frozenTotalLine !== null && ` · 大小 ${formatAsianLine(item.frozenTotalLine)}`}
                      </td>
                      <td>
                        {/* λ 是期望进球数，不是概率：按 formatProbability 渲染会变成「196.1%」。 */}
                        {formatDecimal(item.lambdaHome)} / {formatDecimal(item.lambdaAway)}
                      </td>
                      <td>
                        {formatScore4(item.handicapDispersion)} / {formatScore4(item.totalsDispersion)}
                      </td>
                      <td>
                        让 {formatProbability(item.modelCoverProb)} / {formatProbability(item.marketCoverProb)}
                        {' · '}
                        大 {formatProbability(item.modelOverProb)} / {formatProbability(item.marketOverProb)}
                      </td>
                      <td>
                        {item.maxResidual === null ? '—' : item.maxResidual.toExponential(2)}
                        {' / '}
                        {formatScore4(item.neighborMaxDeviation)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p>
              「模型 / 市场」一律按首边（让球=主队赢盘，大小球=大球）落库。
              edge 是恒等式：λ 由该主盘去水概率反解再投影回同一条线，必然相等，所以不展示。
              残差也几乎恒为 0（恰定方程组），判市场打架只看邻线偏离。
            </p>
          </details>
        </>
      )}
    </section>
  );
}

function formatScore4(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : value.toFixed(4);
}

/** 无量纲小数的直白写法（λ 期望进球数）。两位小数够读，多给反而看不出量级。 */
function formatDecimal(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : value.toFixed(2);
}

/** 两个市场的置信度并列；只有一个方向时只显示那个。 */
function confidenceText(item: BacktestCaseVo) {
  const parts: string[] = [];
  if (item.handicapPick) {
    parts.push(`让 ${confidenceLabel(item.handicapConfidence)}`);
  }
  if (item.totalsPick) {
    parts.push(`大 ${confidenceLabel(item.totalsConfidence)}`);
  }
  return parts.length > 0 ? parts.join(' / ') : '—';
}

function confidenceLabel(value: 'LOW' | 'MEDIUM' | 'HIGH' | null) {
  return value === 'HIGH' ? '高' : value === 'MEDIUM' ? '中' : value === 'LOW' ? '低' : '—';
}

function settlementText(item: BacktestCaseVo) {
  if (item.resultStatus === 'PENDING') {
    return '待结算';
  }
  if (item.homeScore !== null && item.awayScore !== null) {
    return `${item.homeScore}:${item.awayScore} · ${statusLabel(item.handicapStatus)}/${statusLabel(item.totalsStatus)}`;
  }
  return `${statusLabel(item.handicapStatus)}/${statusLabel(item.totalsStatus)}`;
}

function statusLabel(value: string | null) {
  if (!value) {
    return '—';
  }
  const labels: Record<string, string> = {
    HIT: '中', MISS: '不中', PUSH: '走', HALF_WIN: '半赢', HALF_LOSS: '半输', VOID: '无效',
  };
  return labels[value] ?? value;
}

/** 两个市场收益相加；都为空时返回 null，让「无方向」和「打平」分开。 */
function totalProfit(item: BacktestCaseVo) {
  if (item.handicapProfit === null && item.totalsProfit === null) {
    return null;
  }
  return (item.handicapProfit ?? 0) + (item.totalsProfit ?? 0);
}

function profitTone(item: BacktestCaseVo) {
  const profit = totalProfit(item);
  if (profit === null || profit === 0) {
    return 'muted';
  }
  return profit > 0 ? 'positive' : 'negative';
}
