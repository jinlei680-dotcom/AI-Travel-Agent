export default function Home() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-neutral-800 via-neutral-900 to-neutral-950 text-neutral-200">
      <header className="sticky top-0 z-40 bg-transparent">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-2">
            <div className="h-8 w-8 rounded bg-gradient-to-br from-violet-500 to-indigo-600" />
            <span className="text-lg font-semibold">AI Travel Planner</span>
          </div>
          <nav className="hidden items-center gap-6 sm:flex">
            <a className="text-sm text-neutral-300 hover:text-violet-300" href="#features">功能</a>
            <a className="text-sm text-neutral-300 hover:text-violet-300" href="#how">原理</a>
            <a className="text-sm text-neutral-300 hover:text-violet-300" href="#contact">联系</a>
          </nav>
          <div className="flex items-center gap-3">
            <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">登录</button>
            <button className="rounded-md bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 px-3 py-2 text-sm text-white shadow-sm hover:from-violet-600 hover:via-fuchsia-600 hover:to-violet-700">注册</button>
          </div>
        </div>
      </header>

      <main>
        {/* Unified content with sticky framed map */}
        <section>
          <div className="mx-auto grid max-w-6xl grid-cols-1 items-start gap-8 px-6 py-16 md:grid-cols-2">
            <div className="space-y-6">
              <h1 className="bg-gradient-to-r from-violet-200 via-fuchsia-200 to-indigo-200 bg-clip-text text-3xl font-bold tracking-tight text-transparent sm:text-5xl">
                语音驱动的 AI 旅行规划师
              </h1>
              <p className="mt-4 text-neutral-300">
                说出你的目的地、日期与预算，AI 即刻生成可执行行程，含交通、住宿、景点与餐厅建议。
              </p>
              <div className="mt-6 flex gap-3">
                <a href="#start" className="rounded-md bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 px-4 py-2 text-white shadow-sm hover:from-violet-600 hover:via-fuchsia-600 hover:to-violet-700">开始规划</a>
                <a href="#demo" className="rounded-md px-4 py-2 text-neutral-200 hover:bg-white/5">查看示例</a>
              </div>
              {/* Inline quick actions for future features (kept minimal, no frames) */}
              <div className="flex flex-wrap gap-3">
                <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">快速创建行程</button>
                <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">导入航班/酒店</button>
                <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">语音记账</button>
              </div>
            </div>
            <div className="md:sticky md:top-24">
              <div className="rounded-xl ring-1 ring-violet-400/25 bg-neutral-900/60 p-4">
                <div className="mb-2 text-sm text-neutral-400">地图预览</div>
                <div className="aspect-[4/3] w-full rounded-lg bg-neutral-800" />
              </div>
            </div>
          </div>
        </section>

        {/* Features */}
        <section id="features" className="mx-auto max-w-6xl px-6 py-14">
          <h2 className="text-2xl font-semibold">核心功能</h2>
          <div className="mt-6 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
            <Feature title="智能行程规划" desc="输入偏好与约束，生成分日行程与动线。" />
            <Feature title="费用预算与管理" desc="自动估算预算，语音记账与分类汇总。" />
            <Feature title="地图为中心" desc="景点、餐厅与住宿在地图联动展示。" />
            <Feature title="语音入口" desc="长按录音，语音新增或修改行程。" />
            <Feature title="云端同步" desc="多设备实时查看与编辑，数据安全可靠。" />
            <Feature title="分享与导出" desc="生成 PDF/日历，分享只读或协作链接。" />
          </div>
        </section>

        {/* How */}
        <section id="how" className="mx-auto max-w-6xl px-6 py-14">
          <div className="mx-auto max-w-6xl">
            <h2 className="text-2xl font-semibold">工作原理</h2>
            <ul className="mt-4 list-disc space-y-2 pl-6 text-neutral-300">
              <li>语音识别解析用户意图与约束</li>
              <li>地图检索与路线规划提供地理数据</li>
              <li>LLM 生成初稿并校验预算与可行性</li>
              <li>增量更新与流式反馈提升体验</li>
            </ul>
          </div>
        </section>
      </main>

      <footer id="contact">
        <div className="mx-auto max-w-6xl px-6 py-8 text-sm text-neutral-400">
          <div className="flex items-center justify-between">
            <div>© {new Date().getFullYear()} AI Travel Planner</div>
            <div className="flex gap-4">
              <a href="#" className="hover:text-white">隐私政策</a>
              <a href="#" className="hover:text-white">使用条款</a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}

function Feature({ title, desc }: { title: string; desc: string }) {
  return (
    <div className="p-2">
      <h3 className="text-lg font-semibold text-white">{title}</h3>
      <p className="mt-1 text-sm text-neutral-300">{desc}</p>
    </div>
  );
}
