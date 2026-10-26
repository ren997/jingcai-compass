import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import AdminLayout from './AdminLayout';
import PublicLayout from './PublicLayout';
import RequireAdmin from './RequireAdmin';

const MatchesPage = lazy(() => import('../features/matches/MatchesPage'));
const MatchDetailPage = lazy(() => import('../features/matches/MatchDetailPage'));
const HomePage = lazy(() => import('../features/home/HomePage'));
const HistoryPage = lazy(() => import('../features/history/HistoryPage'));
const StatisticsPage = lazy(() => import('../features/history/StatisticsPage'));
const PublicBacktestListPage = lazy(() => import('../features/backtest/PublicBacktestListPage'));
const PublicBacktestDetailPage = lazy(() => import('../features/backtest/PublicBacktestDetailPage'));
const BacktestRunsPage = lazy(() => import('../features/backtest/BacktestRunsPage'));
const BacktestRunDetailPage = lazy(() => import('../features/backtest/BacktestRunDetailPage'));
const AdminHomePage = lazy(() => import('../pages/AdminHomePage'));
const AdminSyncRunsPage = lazy(() => import('../features/admin/AdminSyncRunsPage'));
const AdminSyncRunDetailPage = lazy(() => import('../features/admin/AdminSyncRunDetailPage'));
const AdminPredictionLocksPage = lazy(() => import('../features/admin/AdminPredictionLocksPage'));
const AdminDraftPredictionsPage = lazy(() => import('../features/admin/AdminDraftPredictionsPage'));
const AdminSettlementStatusesPage = lazy(() => import('../features/admin/AdminSettlementStatusesPage'));
const AdminManualResultsPage = lazy(() => import('../features/admin/AdminManualResultsPage'));
const AdminPredictionStatusDetailPage = lazy(() => import('../features/admin/AdminPredictionStatusDetailPage'));
const AdminLoginPage = lazy(() => import('../pages/AdminLoginPage'));
const NotFoundPage = lazy(() => import('../pages/NotFoundPage'));

/** 应用路由与公共/后台访问边界。 */
export default function App() {
  return (
    <Suspense fallback={<main className="page"><section className="state-card">正在加载页面……</section></main>}>
      <Routes>
        <Route element={<PublicLayout />}>
          <Route index element={<HomePage />} />
          <Route path="matches" element={<MatchesPage />} />
          <Route path="matches/:matchId" element={<MatchDetailPage />} />
          <Route path="history" element={<HistoryPage />} />
          <Route path="statistics" element={<StatisticsPage />} />
          <Route path="backtest" element={<PublicBacktestListPage />} />
          <Route path="backtest/:runId" element={<PublicBacktestDetailPage />} />
        </Route>
        <Route path="admin/login" element={<AdminLoginPage />} />
        <Route element={<RequireAdmin />}>
          <Route path="admin" element={<AdminLayout />}>
            <Route index element={<AdminHomePage />} />
            <Route path="sync-runs" element={<AdminSyncRunsPage />} />
            <Route path="sync-runs/:syncRunId" element={<AdminSyncRunDetailPage />} />
            <Route path="predictions" element={<AdminPredictionLocksPage />} />
            <Route path="predictions/drafts" element={<AdminDraftPredictionsPage />} />
            <Route path="predictions/:predictionId" element={<AdminPredictionStatusDetailPage />} />
            <Route path="settlements" element={<AdminSettlementStatusesPage />} />
            <Route path="settlements/:predictionId" element={<AdminPredictionStatusDetailPage />} />
            <Route path="manual-results" element={<AdminManualResultsPage />} />
            <Route path="backtest" element={<BacktestRunsPage />} />
            <Route path="backtest/:runId" element={<BacktestRunDetailPage />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Suspense>
  );
}
