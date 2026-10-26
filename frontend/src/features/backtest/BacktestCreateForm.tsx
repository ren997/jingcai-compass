import { Alert, Button, Checkbox, Input, Select } from 'antd';
import { useMemo, useState } from 'react';
import type {
  BacktestEstimateVo,
  BacktestParameterSet,
  BacktestRunCreateRequest,
} from '../../services/backtest';
import { useAdminBacktestLeaguesQuery } from './useBacktestQueries';
import { todayInShanghai } from '../admin/adminSearch';

/** 历史赔率接口的硬回溯边界：`odds-history` 只给到约 4 个月前，再早的回测全是无样本。 */
const MIN_LOTTERY_DATE = '2026-06-20';
const MAX_SPAN_DAYS = 120;
const MAX_PARAMETER_SETS = 8;

/** 默认区间长度。一个月足够看出结论，也不会一进页面就撞跨度上限。 */
const DEFAULT_SPAN_DAYS = 30;

/** 与生产 `AsianModelProperties` 默认阈值逐字相同的基线组，作为新增组的起点。 */
const BASELINE_PARAMETER_SET: BacktestParameterSet = {
  groupKey: 'baseline',
  highConfidenceMargin: 0.1,
  mediumConfidenceMargin: 0.16,
  highDirectionSignal: 0.075,
  mediumDirectionSignal: 0.025,
};

/** 四个阈值字段的顺序即表格列顺序。 */
const THRESHOLD_FIELDS = [
  'highConfidenceMargin',
  'mediumConfidenceMargin',
  'highDirectionSignal',
  'mediumDirectionSignal',
] as const;

type ThresholdField = (typeof THRESHOLD_FIELDS)[number];

/** 按 UTC 做日期加减，避免本地时区把日期推到前后一天。 */
function shiftDate(date: string, days: number) {
  const base = new Date(`${date}T00:00:00Z`);
  base.setUTCDate(base.getUTCDate() + days);
  return base.toISOString().slice(0, 10);
}

type BacktestCreateFormProps = {
  estimate: BacktestEstimateVo | undefined;
  estimating: boolean;
  creating: boolean;
  estimateError?: string;
  createError?: string;
  onEstimate: (request: BacktestRunCreateRequest) => void;
  onCreate: (request: BacktestRunCreateRequest) => void;
};

/**
 * 回测创建表单。
 *
 * <p>顺序是刻意的：填范围 → 看预估 → 确认预算 → 入队。预估不是可选的辅助信息，
 * 它是唯一能让「这次要花多少额度、要跑多久」在花钱之前被看见的东西——
 * 直接创建再在列表里看额度消耗，等于先付费后看价。</p>
 */
export default function BacktestCreateForm({
  estimate,
  estimating,
  creating,
  estimateError,
  createError,
  onEstimate,
  onCreate,
}: BacktestCreateFormProps) {
  const today = todayInShanghai();
  // 默认区间取最近 DEFAULT_SPAN_DAYS 天，而不是「从最早可回溯日到今天」：
  // 后者跨度超过上限，一进页面预估按钮就是灰的，用户得先自己改日期才知道能跑。
  const defaultFrom = shiftDate(today, -(DEFAULT_SPAN_DAYS - 1));
  const [runName, setRunName] = useState('');
  const [dateFrom, setDateFrom] = useState(defaultFrom < MIN_LOTTERY_DATE ? MIN_LOTTERY_DATE : defaultFrom);
  const [dateTo, setDateTo] = useState(today);
  const [leagueNames, setLeagueNames] = useState<string[]>([]);
  const [decisionLeadMinutes, setDecisionLeadMinutes] = useState(105);
  const [parameterSets, setParameterSets] = useState<BacktestParameterSet[]>([BASELINE_PARAMETER_SET]);
  const [confirmBudget, setConfirmBudget] = useState(false);

  const leaguesQuery = useAdminBacktestLeaguesQuery(dateFrom, dateTo);

  const spanDays = useMemo(() => {
    const from = Date.parse(`${dateFrom}T00:00:00Z`);
    const to = Date.parse(`${dateTo}T00:00:00Z`);
    if (Number.isNaN(from) || Number.isNaN(to)) {
      return 0;
    }
    return Math.floor((to - from) / 86_400_000) + 1;
  }, [dateFrom, dateTo]);

  const rangeError = dateFrom < MIN_LOTTERY_DATE
    ? `开始日期不能早于 ${MIN_LOTTERY_DATE}：历史赔率接口仅回溯约 4 个月，更早的日期采不到任何报价。`
    : dateTo < dateFrom
      ? '结束日期不能早于开始日期。'
      : spanDays > MAX_SPAN_DAYS
        ? `跨度 ${spanDays} 天超过上限 ${MAX_SPAN_DAYS} 天，请拆成多次回测。`
        : null;

  const duplicateGroupKey = hasDuplicateGroupKey(parameterSets);

  const rangeValid = rangeError === null;
  const nameValid = runName.trim().length > 0 && runName.trim().length <= 64;
  const groupsValid = parameterSets.length > 0
    && parameterSets.length <= MAX_PARAMETER_SETS
    && !duplicateGroupKey
    && parameterSets.every((set) => set.groupKey.trim().length > 0 && set.groupKey.length <= 16);

  const canEstimate = rangeValid && groupsValid;
  const canCreate = canEstimate && nameValid
    && (!estimate?.budgetConfirmRequired || confirmBudget);

  function buildRequest(confirm = false): BacktestRunCreateRequest {
    return {
      runName: runName.trim(),
      dateFrom,
      dateTo,
      leagueNames,
      decisionLeadMinutes,
      parameterSets,
      confirmBudget: confirm,
    };
  }

  function updateParameterSet(index: number, patch: Partial<BacktestParameterSet>) {
    setParameterSets((current) => current.map((set, i) => (i === index ? { ...set, ...patch } : set)));
  }

  function updateThreshold(index: number, field: ThresholdField, value: number) {
    setParameterSets((current) => current.map((set, i) => (i === index ? { ...set, [field]: value } : set)));
    setConfirmBudget(false);
  }

  return (
    <section className="admin-panel" aria-label="创建回测">
      <header className="admin-panel-heading">
        <div><h2>创建回测</h2><span>先预估，再确认预算，最后入队</span></div>
      </header>

      <div className="admin-filters">
        <label>
          <span>名称</span>
          <Input
            aria-label="回测名称"
            value={runName}
            maxLength={64}
            placeholder="例如 基线阈值 · 六月复盘"
            onChange={(event) => setRunName(event.target.value)}
          />
        </label>
        <label>
          <span>开始日期</span>
          <input
            aria-label="开始日期"
            type="date"
            value={dateFrom}
            min={MIN_LOTTERY_DATE}
            onChange={(event) => {
              setDateFrom(event.target.value);
              setConfirmBudget(false);
            }}
          />
        </label>
        <label>
          <span>结束日期</span>
          <input
            aria-label="结束日期"
            type="date"
            value={dateTo}
            onChange={(event) => {
              setDateTo(event.target.value);
              setConfirmBudget(false);
            }}
          />
        </label>
        <label>
          <span>决策时点（赛前分钟）</span>
          <Input
            aria-label="决策时点提前分钟数"
            value={String(decisionLeadMinutes)}
            onChange={(event) => {
              const parsed = Number(event.target.value);
              if (Number.isFinite(parsed) && parsed >= 0 && parsed <= 1440) {
                setDecisionLeadMinutes(Math.trunc(parsed));
                setConfirmBudget(false);
              }
            }}
          />
        </label>
        <label>
          <span>联赛</span>
          <Select
            aria-label="联赛"
            mode="multiple"
            allowClear
            placeholder="全部联赛"
            value={leagueNames}
            loading={leaguesQuery.isPending}
            onChange={(value) => {
              setLeagueNames(value);
              setConfirmBudget(false);
            }}
            options={(leaguesQuery.data ?? []).map((league) => ({
              value: league.leagueName,
              label: `${league.leagueName}（${league.archivedCount}/${league.matchCount} 已归档）`,
            }))}
          />
        </label>
      </div>

      <p className="backtest-hint">
        决策时点取「开赛前 N 分钟」的那一版报价。历史赔率接口在临场约 1h45m 停更，
        所以把 N 设得比 105 更小不会拿到更临场的价，只会拿到同一份旧数据。
      </p>

      {rangeError && <Alert type="warning" showIcon message={rangeError} />}
      {leaguesQuery.isError && (
        <Alert type="warning" showIcon message={`联赛候选读取失败：${leaguesQuery.error.message}`} />
      )}

      <section className="backtest-parameter-sets" aria-label="参数组">
        <div className="section-heading">
          <div><h3>参数组（最多 {MAX_PARAMETER_SETS} 组）</h3><span>方向由模型概率决定，这四个阈值只决定发不发</span></div>
          <Button
            disabled={parameterSets.length >= MAX_PARAMETER_SETS}
            onClick={() => setParameterSets((current) => [
              ...current,
              { ...BASELINE_PARAMETER_SET, groupKey: `g${current.length + 1}` },
            ])}
          >
            新增组
          </Button>
        </div>

        {duplicateGroupKey && <Alert type="error" showIcon message="参数组标识重复，组标识是唯一键。" />}

        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>组标识</th>
                <th>HIGH 让球裕度</th>
                <th>MEDIUM 让球裕度</th>
                <th>HIGH 方向信号</th>
                <th>MEDIUM 方向信号</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {parameterSets.map((set, index) => (
                <tr key={index}>
                  <td>
                    <Input
                      aria-label={`第 ${index + 1} 组标识`}
                      value={set.groupKey}
                      maxLength={16}
                      onChange={(event) => updateParameterSet(index, { groupKey: event.target.value })}
                    />
                  </td>
                  {THRESHOLD_FIELDS.map((field) => (
                    <td key={field}>
                      <Input
                        aria-label={`第 ${index + 1} 组 ${field}`}
                        value={String(set[field])}
                        onChange={(event) => {
                          const parsed = Number(event.target.value);
                          if (Number.isFinite(parsed) && parsed >= 0 && parsed <= 1) {
                            updateThreshold(index, field, parsed);
                          }
                        }}
                      />
                    </td>
                  ))}
                  <td>
                    <Button
                      danger
                      disabled={parameterSets.length <= 1}
                      onClick={() => setParameterSets((current) => current.filter((_, i) => i !== index))}
                    >
                      移除
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <div className="admin-filters">
        <Button loading={estimating} disabled={!canEstimate} onClick={() => onEstimate(buildRequest(false))}>
          预估成本
        </Button>
        <Button
          type="primary"
          loading={creating}
          disabled={!canCreate}
          onClick={() => onCreate(buildRequest(confirmBudget))}
        >
          创建并入队
        </Button>
      </div>

      {estimateError && <Alert type="error" showIcon message={`预估失败：${estimateError}`} />}
      {createError && <Alert type="error" showIcon message={`创建失败：${createError}`} />}

      {estimate && (
        <div className="backtest-estimate">
          <dl className="backtest-counters">
            <div><dt>区间场次</dt><dd>{estimate.matchCount}</dd></div>
            <div><dt>可复用归档</dt><dd>{estimate.reusableArchiveCount}</dd></div>
            <div><dt>预计额度</dt><dd>{estimate.estimatedQuotaCost}</dd></div>
            <div><dt>预计耗时</dt><dd>{Math.max(1, Math.round(estimate.estimatedSeconds / 60))} 分钟</dd></div>
            <div><dt>参数组</dt><dd>{estimate.parameterGroupCount}</dd></div>
          </dl>
          {estimate.warnings.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message="预估提示"
              description={<ul>{estimate.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>}
            />
          )}
          {estimate.budgetConfirmRequired && (
            <Checkbox checked={confirmBudget} onChange={(event) => setConfirmBudget(event.target.checked)}>
              我确认消耗约 {estimate.estimatedQuotaCost} 次接口额度
            </Checkbox>
          )}
          {!estimate.budgetConfirmRequired && (
            <p className="backtest-hint">该区间额度消耗在确认阈值以下，不需要额外确认。</p>
          )}
        </div>
      )}
    </section>
  );
}

function hasDuplicateGroupKey(sets: BacktestParameterSet[]) {
  const keys = sets.map((set) => set.groupKey.trim()).filter((key) => key.length > 0);
  return new Set(keys).size !== keys.length;
}
