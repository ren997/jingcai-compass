import { NavLink, Outlet } from 'react-router-dom';

/** 匿名可访问的公共产品布局。 */
export default function PublicLayout() {
  return (
    <div className="app-shell public-shell">
      <header className="site-header">
        <NavLink className="brand" to="/">
          竞彩罗盘
        </NavLink>
        <nav aria-label="公共导航">
          <NavLink end to="/">首页</NavLink>
          <NavLink to="/matches">每日比赛</NavLink>
          <NavLink to="/history">预测历史</NavLink>
          <NavLink to="/statistics">表现统计</NavLink>
          <NavLink to="/admin/login">后台登录</NavLink>
        </nav>
      </header>
      <Outlet />
    </div>
  );
}
