import type { MatchDataAvailability, MatchStatus, OddsSnapshotType } from '../../services/public';

export const statusLabels: Record<MatchStatus, string> = {
  SCHEDULED: '未开赛',
  LOCKED: '已锁定',
  IN_PROGRESS: '进行中',
  FINISHED: '已结束',
  POSTPONED: '已延期',
  CANCELLED: '已取消',
  ABANDONED: '已中止',
};

const availabilityLabels: Record<MatchDataAvailability, string> = {
  AVAILABLE: '数据可用',
  NO_SPORTTERY_SNAPSHOT: '暂无体彩快照',
  NO_ASIAN_ODDS_SNAPSHOT: '暂无亚盘快照',
  NO_SOURCE_MAPPING: '暂无来源映射',
  MAPPING_UNCONFIRMED: '映射待确认',
};

const snapshotTypeLabels: Record<OddsSnapshotType, string> = {
  FIRST_SEEN: '首次可见',
  PRE_KICKOFF: '封盘前',
  OTHER: '其他',
};

export function formatTimestamp(value: string | null | undefined) {
  if (!value) {
    return '暂无时间';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

export function formatHandicap(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '让球暂缺';
  }
  return value === 0 ? '让球 0' : `主队 ${value > 0 ? '+' : ''}${value}`;
}

export function formatNumber(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : String(value);
}

export function availabilityLabel(value: MatchDataAvailability) {
  return availabilityLabels[value];
}

export function snapshotTypeLabel(value: OddsSnapshotType) {
  return snapshotTypeLabels[value];
}

export function dataSourceLabel(value: string | null | undefined) {
  if (!value) {
    return '来源暂缺';
  }
  if (value === 'CHINA_SPORTTERY') {
    return '中国体彩网公开数据';
  }
  if (value === 'STUB') {
    return 'Stub 演示数据';
  }
  return value;
}
