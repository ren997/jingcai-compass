import { Link } from 'react-router-dom';

/** 未匹配路由的稳定提示页。 */
export default function NotFoundPage() {
  return (
    <main className="page">
      <section className="state-card">
        <h1>页面不存在</h1>
        <p>你访问的地址不存在或已被移动。</p>
        <Link to="/matches">返回每日比赛</Link>
      </section>
    </main>
  );
}
