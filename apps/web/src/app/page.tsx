"use client";
import { useState } from "react";
import AuthModal from "@/components/AuthModal";
import { ItineraryPlan } from "@/components/ItineraryInput";
import MapView from "@/components/MapView";
import AiChatPanel from "@/components/AiChatPanel";
import HeroModule from "@/components/HeroModule";
import PlanTextView from "@/components/PlanTextView";

export default function Home() {
  const [modalOpen, setModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState<"login" | "register">("login");
  const [token, setToken] = useState<string | null>(null);
  const [plan, setPlan] = useState<ItineraryPlan | null>(null);
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
            {token ? (
              <div className="text-sm text-neutral-300">已登录</div>
            ) : (
              <>
                <button
                  className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5"
                  onClick={() => { setModalMode("login"); setModalOpen(true); }}
                >登录</button>
                <button
                  className="rounded-md bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 px-3 py-2 text-sm text-white shadow-sm hover:from-violet-600 hover:via-fuchsia-600 hover:to-violet-700"
                  onClick={() => { setModalMode("register"); setModalOpen(true); }}
                >注册</button>
              </>
            )}
          </div>
        </div>
      </header>

      <main>
        {/* Centered hero module */}
        <HeroModule />

        {/* AI 对话模块：紧跟在 Hero 文案下方，且不使用卡片边框 */}
        <section className="px-6 py-8">
          <div className="mx-auto max-w-2xl">
            <AiChatPanel onPlanned={(p) => setPlan(p)} showTranscript={false} />
          </div>
        </section>

        {/* Unified content with sticky framed map */}
        <section>
          <div className="mx-auto grid max-w-6xl grid-cols-1 items-start gap-12 px-6 py-16 md:grid-cols-2">
            <div className="space-y-10">
              {/* 旅游计划文案：展示模型输出的文字结果 */}
              <div className="rounded-2xl ring-1 ring-white/10 bg-neutral-900/70 p-6 shadow-sm">
                <PlanTextView plan={plan} />
              </div>
              {/* 地图预览卡片已删除，仅保留 AI 对话面板 */}
              {/* Inline quick actions for future features (kept minimal, no frames) */}
              <div className="flex flex-wrap gap-3">
                <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">快速创建行程</button>
                <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">导入航班/酒店</button>
                <button className="rounded-md px-3 py-2 text-sm text-neutral-200 hover:bg-white/5">语音记账</button>
              </div>
            </div>
            <div className="md:sticky md:top-24">
              <div className="rounded-2xl ring-1 ring-white/10 bg-neutral-900/70 p-6 shadow-sm">
                <div className="mb-2 text-sm text-neutral-400">地图预览</div>
                <MapView plan={plan ?? undefined} />
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
      <AuthModal
        open={modalOpen}
        mode={modalMode}
        onClose={() => setModalOpen(false)}
        onAuthed={(t) => { setToken(t); localStorage.setItem("accessToken", t); }}
      />
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
