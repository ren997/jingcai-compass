export default function App() {
  const highlights = [
    '只聚焦中国体彩每日竞彩足球比赛',
    '围绕发布、锁定、结算、历史透明构建产品闭环',
    '当前优先交付 MVP 需求与基础工程骨架',
  ];

  return (
    <main className="page">
      <section className="hero">
        <p className="eyebrow">JingCai Compass</p>
        <h1>竞彩罗盘</h1>
        <p className="summary">
          一个面向中国体彩竞彩足球每日比赛的数据分析与概率预测工具。
        </p>
      </section>

      <section className="panel">
        <h2>当前范围</h2>
        <ul className="list">
          {highlights.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      </section>

      <section className="panel">
        <h2>文档入口</h2>
        <p>请先查看 docs/requirements-mvp.md，后续再继续细化表结构和接口。</p>
      </section>
    </main>
  );
}
