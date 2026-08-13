import { describe, expect, it } from 'vitest';
import {
  asianHandicapPredictionMarketLabel,
  totalGoalsPredictionMarketLabel,
} from './matchPresentation';

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
