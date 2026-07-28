import { Link } from 'react-router-dom';

/** 后台受保护区域的功能入口。 */
export default function AdminHomePage() {
  return (
    <main className="admin-page">
      <section className="admin-card">
        <p className="eyebrow">Admin workspace</p>
        <h1>后台运营入口</h1>
        <p>查看同步运行、额度与错误，或处理需要人工复核的来源映射。</p>
        <div className="admin-home-links">
          <Link to="/admin/sync-runs">查看同步运行</Link>
          <Link to="/admin/mappings">处理映射复核</Link>
        </div>
      </section>
    </main>
  );
}
