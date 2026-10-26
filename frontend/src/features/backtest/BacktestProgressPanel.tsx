import { Alert, Button, Modal } from 'antd';
import { useState } from 'react';
import {
  canCancelBacktestRun,
  canResumeBacktestRun,
  isActiveBacktestStatus,
  type BacktestRunVo,
} from '../../services/backtest';
import { formatTimestamp } from '../matches/matchPresentation';
import {
  backtestProgressText,
  backtestRunStatusLabel,
  backtestRunStatusTone,
  backtestVersionSummary,
} from './backtestPresentation';

type BacktestProgressPanelProps = {
  run: BacktestRunVo;
  cancelling: boolean;
  resuming: boolean;
  onCancel: () => void;
  onResume: () => void;
  actionError?: string;
};

/**
 * run 进度与操作。
 *
 * <p>计数分四组摆：归档（复用/新采/失败）、判定（总/已处理/样本）、额度、时间。
 * 合成一句话会丢掉最关键的对比——「复用 40 / 新采 3 / 失败 5」直接说明还要花多少额度，
 * 而「已处理 48」什么也说明不了。</p>
 */
export default function BacktestProgressPanel({
  run,
  cancelling,
  resuming,
  onCancel,
  onResume,
  actionError,
}: BacktestProgressPanelProps) {
  const [cancelOpen, setCancelOpen] = useState(false);
  const active = isActiveBacktestStatus(run.runStatus);

  return (
    <section className="detail-section">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Run · {run.runKey}</p>
          <h2>{run.runName}</h2>
          <p>{run.dateFrom} 至 {run.dateTo} · 决策时点提前 {run.decisionLeadMinutes} 分钟</p>
        </div>
        <div className="backtest-run-actions">
          <span className={`admin-status ${backtestRunStatusTone(run.runStatus)}`}>
            {backtestRunStatusLabel(run.runStatus)}
          </span>
          {canCancelBacktestRun(run.runStatus) && (
            <Button danger loading={cancelling} onClick={() => setCancelOpen(true)}>请求取消</Button>
          )}
          {canResumeBacktestRun(run.runStatus) && (
            <Button type="primary" loading={resuming} onClick={onResume}>续跑</Button>
          )}
        </div>
      </div>

      <p className="backtest-progress-text">{backtestProgressText(run)}</p>
      {run.totalMatchCount !== null && run.totalMatchCount > 0 && (
        <progress
          className="backtest-progress"
          value={run.processedMatchCount ?? 0}
          max={run.totalMatchCount}
          aria-label="回测处理进度"
        />
      )}

      {run.cancelRequested && active && (
        <Alert type="warning" showIcon message="已请求取消，worker 会在当前批次结束后停止。已写入的样本保留。" />
      )}
      {run.errorCode && (
        <Alert
          type={run.runStatus === 'FAILED' ? 'error' : 'warning'}
          showIcon
          message={`${run.errorCode}${run.errorMessage ? `：${run.errorMessage}` : ''}`}
        />
      )}
      {actionError && <Alert type="error" showIcon message={actionError} />}

      <dl className="backtest-counters">
        <div><dt>总场次</dt><dd>{run.totalMatchCount ?? '—'}</dd></div>
        <div><dt>已处理</dt><dd>{run.processedMatchCount ?? '—'}</dd></div>
        <div><dt>归档复用</dt><dd>{run.archiveReusedCount ?? '—'}</dd></div>
        <div><dt>归档新采</dt><dd>{run.archiveFetchedCount ?? '—'}</dd></div>
        <div><dt>归档失败</dt><dd>{run.archiveFailedCount ?? '—'}</dd></div>
        <div><dt>样本条数</dt><dd>{run.caseCount ?? '—'}</dd></div>
        <div><dt>额度消耗</dt><dd>{run.quotaCost ?? '—'}{run.estimateQuotaCost !== null && ` / 预估 ${run.estimateQuotaCost}`}</dd></div>
        <div><dt>参数组</dt><dd>{run.parameterSets.length}</dd></div>
      </dl>

      <details className="backtest-notes">
        <summary>时间线与口径版本</summary>
        <p>创建 {formatTimestamp(run.createdAt)} · 开始 {formatTimestamp(run.startedAt)}</p>
        <p>最后心跳 {formatTimestamp(run.lastHeartbeatAt)} · 结束 {formatTimestamp(run.finishedAt)}</p>
        <p>{backtestVersionSummary(run)}</p>
        {run.gitSha && <p>部署 {run.gitSha.slice(0, 8)}</p>}
        <p>
          联赛范围：{run.leagueNames.length > 0 ? run.leagueNames.join('、') : '全部'}
          {' · '}provider {run.providerCode}
          {run.resumeCount !== null && run.resumeCount > 0 && ` · 已续跑 ${run.resumeCount} 次`}
        </p>
        {run.parameterSets.length > 0 && (
          <ul>
            {run.parameterSets.map((set) => (
              <li key={String(set.groupKey)}>{String(set.groupKey)}：{JSON.stringify(set)}</li>
            ))}
          </ul>
        )}
      </details>

      <Modal
        title="确认取消回测"
        open={cancelOpen}
        onCancel={() => setCancelOpen(false)}
        confirmLoading={cancelling}
        okText="确认取消"
        okButtonProps={{ danger: true }}
        onOk={() => {
          onCancel();
          setCancelOpen(false);
        }}
      >
        <p>只会置取消标志，worker 在当前批次结束后停止。</p>
        <p>已经写入的场次样本会保留，指标停留在上次汇总时的状态。</p>
      </Modal>
    </section>
  );
}
