import { NavLink, Outlet } from 'react-router-dom';

/** 匿名可访问的公共产品布局。 */
export default function PublicLayout() {
  return (
    <div className="app-shell public-shell">
      <header className="site-header">
        <NavLink className="brand" to="/matches">
          竞彩罗盘
        </NavLink>
        <nav aria-label="公共导航">
          <NavLink to="/matches">每日比赛</NavLink>
        </nav>
      </header>
      <Outlet />
    </div>
  );
}
