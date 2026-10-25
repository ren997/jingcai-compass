import { describe, expect, it } from 'vitest';
import {
  asianHandicapPredictionMarketLabel,
  asianHandicapVerdictText,
  formatAsianLine,
  totalGoalsPredictionMarketLabel,
  totalGoalsVerdictText,
} from './matchPresentation';

describe('prediction verdict text', () => {
  it('reads the handicap conclusion as side plus line', () => {
    expect(asianHandicapVerdictText(-0.5, 'HOME_COVER')).toBe('主 -0.5');
    expect(asianHandicapVerdictText(-1.5, 'AWAY_COVER')).toBe('客 +1.5');
    expect(asianHandicapVerdictText(1, 'HOME_COVER')).toBe('主 +1');
  });

  it('names the level line instead of writing a meaningless zero', () => {
    expect(asianHandicapVerdictText(0, 'HOME_COVER')).toBe('主 平手');
    expect(asianHandicapVerdictText(0, 'AWAY_COVER')).toBe('客 平手');
  });

  it('keeps the side when the frozen row has no line yet', () => {
    expect(asianHandicapVerdictText(null, 'AWAY_COVER')).toBe('客');
    expect(asianHandicapVerdictText(-0.5, null)).toBeNull();
  });

  it('reads the totals conclusion as side plus line', () => {
    expect(totalGoalsVerdictText(2.5, 'OVER')).toBe('大 2.5');
    expect(totalGoalsVerdictText(3.25, 'UNDER')).toBe('小 3.25');
    expect(totalGoalsVerdictText(null, 'UNDER')).toBe('小');
    expect(totalGoalsVerdictText(2.5, null)).toBeNull();
  });

  it('formats a signed Asian line', () => {
    expect(formatAsianLine(-0.5)).toBe('-0.5');
    expect(formatAsianLine(1)).toBe('+1');
    expect(formatAsianLine(0)).toBe('0');
    expect(formatAsianLine(null)).toBe('—');
  });
});

describe('prediction market presentation', () => {
  it('shows the selected home handicap side with its line and odds', () => {
    expect(asianHandicapPredictionMarketLabel(-0.5, 'HOME_COVER', 1.73, 2.17))
      .toBe('主 -0.5（赔率 1.73）');
  });

  it('converts the selected away handicap side and uses its odds', () => {
    expect(asianHandicapPredictionMarketLabel(-0.5, 'AWAY_COVER', 1.73, 2.17))
      .toBe('客 +0.5（赔率 2.17）');
  });

  it('shows the selected totals side with its line and odds', () => {
    expect(totalGoalsPredictionMarketLabel(2.5, 'OVER', 1.71, 2.21))
      .toBe('大 2.5（赔率 1.71）');
    expect(totalGoalsPredictionMarketLabel(2, 'UNDER', 1.71, 2.21))
      .toBe('小 2（赔率 2.21）');
  });
});
