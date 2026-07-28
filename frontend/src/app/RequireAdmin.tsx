import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAdminSession } from '../hooks/useAdminSession';

/** 未登录访问后台时，保留原地址并跳转管理员登录页。 */
export default function RequireAdmin() {
  const session = useAdminSession();
  const location = useLocation();
  if (session) {
    return <Outlet />;
  }
  return (
    <Navigate
      replace
      state={{ from: `${location.pathname}${location.search}` }}
      to="/admin/login"
    />
  );
}
