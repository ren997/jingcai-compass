import { Alert, Button, Tag } from 'antd';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { formatTimestamp } from '../matches/matchPresentation';
import { useAdminSyncRunDetailQuery } from './useAdminQueries';

/** 单次同步详情；原始内容只来自后端已脱敏片段。 */
export default function AdminSyncRunDetailPage() {
  const { syncRunId: rawId } = useParams();
  const [searchParams] = useSearchParams();
  const syncRunId = rawId && /^\d+$/.test(rawId) && Number(rawId) > 0 ? Number(rawId) : undefined;
  const query = useAdminSyncRunDetailQuery(syncRunId);
  const backTo = `/admin/sync-runs${searchParams.toString() ? `?${searchParams}` : ''}`;
  if (syncRunId === undefined) return <main className="admin-page"><section className="admin-state-card error"><h1>页面不存在</h1><p>同步运行 ID 格式无效。</p></section></main>;
  if (query.isPending) return <main className="admin-page"><section className="admin-state-card">正在读取同步详情……</section></main>;
  if (query.isError) return <main className="admin-page"><section className="admin-state-card error"><h1>同步详情不可用</h1><p>{query.error.message}</p><Link to={backTo}>返回同步运行</Link></section></main>;
  const detail = query.data!;
  const run = detail.run;
  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Run #{run.syncRunId}</p><h1>{run.providerCode}</h1>
      <p>{run.dataType} · {run.syncStatus} · {formatTimestamp(run.startedAt)}</p></div>
      <div className="admin-actions"><Button onClick={() => void query.refetch()}>刷新</Button><Link to={backTo}>返回列表</Link></div></section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>运行结果</h2><span>持久化事实</span></div></header>
      <dl className="admin-metadata"><div><dt>结束时间</dt><dd>{formatTimestamp(run.finishedAt)}</dd></div><div><dt>抓取/成功/失败</dt><dd>{run.fetchedCount} / {run.successCount} / {run.failureCount}</dd></div>
        <div><dt>重试</dt><dd>{run.retryCount}</dd></div><div><dt>本轮消耗额度</dt><dd>{run.quotaCost}</dd></div></dl>
      {run.errorSummary && <Alert type="error" showIcon message="错误摘要" description={run.errorSummary} />}
    </section>
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>受控原始响应</h2><span>敏感字段已遮蔽，片段可能截断</span></div></header>
      {detail.rawPayloadNotice && <Alert type="info" showIcon message={detail.rawPayloadNotice} />}
      {detail.rawPayloads.map((payload) => <article className="admin-payload" key={payload.payloadId}><header><strong>载荷 #{payload.payloadId}</strong><Tag>{payload.parseStatus}</Tag>
        <span>HTTP {payload.httpStatus ?? '—'} · {formatTimestamp(payload.requestedAt)}</span></header>
        <dl className="admin-metadata compact"><div><dt>请求键</dt><dd>{payload.requestKey || '—'}</dd></div><div><dt>哈希</dt><dd>{payload.payloadHash}</dd></div></dl>
        {payload.parseErrorSummary && <Alert type="warning" showIcon message={payload.parseErrorSummary} />}
        <pre>{payload.maskedJsonFragment}</pre>{payload.truncated && <small>片段已按安全长度截断。</small>}</article>)}
    </section>
  </main>;
}
