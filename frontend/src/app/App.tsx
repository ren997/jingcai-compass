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
const AdminHomePage = lazy(() => import('../pages/AdminHomePage'));
const AdminSyncRunsPage = lazy(() => import('../features/admin/AdminSyncRunsPage'));
const AdminSyncRunDetailPage = lazy(() => import('../features/admin/AdminSyncRunDetailPage'));
const AdminMappingsPage = lazy(() => import('../features/admin/AdminMappingsPage'));
const AdminMappingDetailPage = lazy(() => import('../features/admin/AdminMappingDetailPage'));
const AdminPredictionLocksPage = lazy(() => import('../features/admin/AdminPredictionLocksPage'));
const AdminSettlementStatusesPage = lazy(() => import('../features/admin/AdminSettlementStatusesPage'));
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
        </Route>
        <Route path="admin/login" element={<AdminLoginPage />} />
        <Route element={<RequireAdmin />}>
          <Route path="admin" element={<AdminLayout />}>
            <Route index element={<AdminHomePage />} />
            <Route path="sync-runs" element={<AdminSyncRunsPage />} />
            <Route path="sync-runs/:syncRunId" element={<AdminSyncRunDetailPage />} />
            <Route path="mappings" element={<AdminMappingsPage />} />
            <Route path="mappings/:mappingId" element={<AdminMappingDetailPage />} />
            <Route path="predictions" element={<AdminPredictionLocksPage />} />
            <Route path="predictions/:predictionId" element={<AdminPredictionStatusDetailPage />} />
            <Route path="settlements" element={<AdminSettlementStatusesPage />} />
            <Route path="settlements/:predictionId" element={<AdminPredictionStatusDetailPage />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Suspense>
  );
}
