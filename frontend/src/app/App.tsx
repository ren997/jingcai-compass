import { useEffect, useState } from 'react';

type MatchStatus = 'SCHEDULED' | 'LOCKED' | 'FINISHED' | 'POSTPONED' | 'CANCELLED';

type MatchSummary = {
  matchId: string;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  officialHandicap: number;
  matchStatus: MatchStatus;
  dataSource: string;
};

type MatchLoadState =
  | { type: 'loading' }
  | { type: 'ready'; matches: MatchSummary[] }
  | { type: 'error'; message: string };

const statusLabels: Record<MatchStatus, string> = {
  SCHEDULED: '未开赛',
  LOCKED: '已锁定',
  FINISHED: '已结束',
  POSTPONED: '已延期',
  CANCELLED: '已取消',
};

function todayInShanghai() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

function formatKickoff(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatHandicap(value: number) {
  if (value === 0) {
    return '让球 0';
  }
  return `主队 ${value > 0 ? '+' : ''}${value}`;
}

export default function App() {
  const [lotteryDate, setLotteryDate] = useState(todayInShanghai);
  const [loadState, setLoadState] = useState<MatchLoadState>({ type: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    setLoadState({ type: 'loading' });
    fetch(`/api/public/matches?lotteryDate=${lotteryDate}`, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return (await response.json()) as MatchSummary[];
      })
      .then((matches) => setLoadState({ type: 'ready', matches }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return;
        }
        setLoadState({
          type: 'error',
          message: error instanceof Error ? error.message : '未知错误',
        });
      });
    return () => controller.abort();
  }, [lotteryDate]);

  return (
    <main className="page">
      <section className="hero">
        <div>
          <p className="eyebrow">JingCai Compass · Product Demo</p>
          <h1>今日竞彩比赛</h1>
          <p className="summary">先把每日比赛池做清楚，再逐步接入盘口、预测与结算。</p>
        </div>
        <label className="date-control">
          <span>竞彩日期</span>
          <input
            type="date"
            value={lotteryDate}
            onChange={(event) => setLotteryDate(event.target.value)}
          />
        </label>
      </section>

      {loadState.type === 'loading' && <section className="state-card">正在加载比赛池……</section>}
      {loadState.type === 'error' && (
        <section className="state-card error">后端连接失败：{loadState.message}</section>
      )}
      {loadState.type === 'ready' && (
        <>
          <section className="summary-strip">
            <div>
              <span>比赛数量</span>
              <strong>{loadState.matches.length}</strong>
            </div>
            <div>
              <span>当前来源</span>
              <strong>Stub 演示数据</strong>
            </div>
            <p>演示数据不代表真实赛程或推荐结果。</p>
          </section>

          <section className="match-list" aria-label="竞彩比赛列表">
            {loadState.matches.map((match) => (
              <article className="match-card" key={match.matchId}>
                <header>
                  <span className="match-number">{match.lotteryMatchNo}</span>
                  <span>{match.leagueName}</span>
                  <time dateTime={match.kickoffTime}>{formatKickoff(match.kickoffTime)}</time>
                </header>
                <div className="teams">
                  <strong>{match.homeTeamName}</strong>
                  <span className="versus">VS</span>
                  <strong>{match.awayTeamName}</strong>
                </div>
                <footer>
                  <span className="handicap">{formatHandicap(match.officialHandicap)}</span>
                  <span className="match-status">{statusLabels[match.matchStatus]}</span>
                </footer>
              </article>
            ))}
          </section>
        </>
      )}
    </main>
  );
}
