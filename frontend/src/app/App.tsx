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
          </Route>
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Suspense>
  );
}
