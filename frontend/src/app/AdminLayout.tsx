import { useState } from 'react';
import { Button } from 'antd';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAdminSession } from '../hooks/useAdminSession';
import { clearAdminSession } from '../services/adminSession';
import { logoutAdmin } from '../services/admin';

/** 仅管理员可访问的后台布局及退出入口。 */
export default function AdminLayout() {
  const session = useAdminSession();
  const navigate = useNavigate();
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  async function handleLogout() {
    setIsLoggingOut(true);
    try {
      await logoutAdmin();
    } finally {
      clearAdminSession();
      navigate('/admin/login', { replace: true });
    }
  }

  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <NavLink className="brand" to="/admin">
          竞彩罗盘后台
        </NavLink>
        <nav aria-label="后台导航">
          <NavLink to="/admin" end>
            概览
          </NavLink>
          <NavLink to="/admin/sync-runs">
            同步运行
          </NavLink>
          <NavLink to="/admin/mappings">
            映射复核
          </NavLink>
        </nav>
      </aside>
      <div className="admin-content">
        <header className="admin-header">
          <span>管理员：{session?.username}</span>
          <Button loading={isLoggingOut} onClick={() => void handleLogout()}>
            退出登录
          </Button>
        </header>
        <Outlet />
      </div>
    </div>
  );
}
