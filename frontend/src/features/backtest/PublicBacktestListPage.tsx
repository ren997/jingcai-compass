import { Alert, Button } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import {
  PUBLISHABLE_BACKTEST_STATUSES,
  type BacktestRunListQuery,
} from '../../services/backtest';
import { formatTimestamp } from '../matches/matchPresentation';
import {
  backtestRunStatusLabel,
  backtestRunStatusTone,
} from './backtestPresentation';
import { usePublicBacktestRunsQuery } from './useBacktestQueries';

const PAGE_SIZE = 10;

/**
 * 公开的历史回测列表。
 *
 * <p>只列已产出结论的 run。这里不传状态筛选：后端已经把白名单下到 SQL，
 * 前端再放一个筛选器只会让人以为「可以筛出排队中的回测」。</p>
 */
export default function PublicBacktestListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Number(searchParams.get('page') ?? '1') || 1;
  const query: BacktestRunListQuery = { page, pageSize: PAGE_SIZE };
  const runsQuery = usePublicBacktestRunsQuery(query);

  function goTo(next: number) {
    setSearchParams(next > 1 ? { page: String(next) } : {});
  }

  const data = runsQuery.data;
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <main className="page">
      <section className="section-heading">
        <div>
          <p className="eyebrow">Research · Backtest</p>
          <h1>历史回测</h1>
          <p>
            用归档的历史赔率在固定决策时点重放模型，再用生产同一批结算函数核对结果。
            每个参数组各自一组指标，结论只在参数组之间、同一批样本内可比。
          </p>
        </div>
      </section>

      {runsQuery.isError && (
        <Alert type="error" showIcon message={`回测列表读取失败：${runsQuery.error.message}`} />
      )}
      {runsQuery.isPending && <section className="state-card">正在读取回测……</section>}

      {data && data.records.length === 0 && (
        <section className="state-card">还没有公开的回测结论。</section>
      )}

      {data && data.records.length > 0 && (
        <section className="detail-section">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>回测</th>
                  <th>区间</th>
                  <th>参数组</th>
                  <th>样本</th>
                  <th>状态</th>
                  <th>完成</th>
                </tr>
              </thead>
              <tbody>
                {data.records.map((run) => (
                  <tr key={run.id}>
                    <th scope="row"><Link to={`/backtest/${run.id}`}>{run.runName}</Link></th>
                    <td>{run.dateFrom} 至 {run.dateTo}</td>
                    <td>{run.parameterSets.length}</td>
                    <td>{run.caseCount ?? '—'}</td>
                    <td>
                      <span className={`admin-status ${backtestRunStatusTone(run.runStatus)}`}>
                        {backtestRunStatusLabel(run.runStatus)}
                      </span>
                    </td>
                    <td>{formatTimestamp(run.finishedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="pagination">
            <Button disabled={page <= 1} onClick={() => goTo(page - 1)}>上一页</Button>
            <span>第 {page} / {pageCount} 页</span>
            <Button disabled={page >= pageCount} onClick={() => goTo(page + 1)}>下一页</Button>
          </div>
        </section>
      )}

      <details className="backtest-notes">
        <summary>公开端能看到什么</summary>
        <p>
          只公开状态为「成功」或「部分成功」的回测。排队中、补采中、失败与已取消的 run
          不对外可见——它们要么没有完整样本、要么口径还没定型。
        </p>
        <p>
          部分成功表示样本可用但补采有失败，样本数会少于区间场次数。命中率分母不含走盘，
          Brier 差额为正才表示模型优于市场。
        </p>
        <p>公开可见状态：{PUBLISHABLE_BACKTEST_STATUSES.map(backtestRunStatusLabel).join('、')}。</p>
      </details>
    </main>
  );
}
