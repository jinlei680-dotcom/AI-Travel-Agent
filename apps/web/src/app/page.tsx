"use client";
import { useState } from "react";
import MapView from "@/components/MapView";
import HeroModule from "@/components/HeroModule";
import ItineraryInput, { type ItineraryPlan } from "@/components/ItineraryInput";
import PlanTextView from "@/components/PlanTextView";

export default function Home() {
  const [plan, setPlan] = useState<ItineraryPlan | null>(null);
  const [userText, setUserText] = useState<string>("");
  const [rawText, setRawText] = useState<string>("");

  return (
    <div className="min-h-screen text-neutral-200">
      {/* 顶部导航已移除 */}

      {/* 主内容 */}
      <main className="pb-0 pt-0">
        {/* 首页大字宣言 + 行程需求输入栏 */}
        <HeroModule>
          <ItineraryInput
            onPlanned={(p) => setPlan(p)}
            onUserSubmit={(t) => setUserText(t)}
            onRawText={(txt, phase) => {
              if (phase === "final") setRawText(txt);
              else if (!rawText) setRawText(txt);
            }}
            hideLabel={true}
            rows={2}
          />
        </HeroModule>
        <section
          id="app"
          className="relative min-h-screen bg-gradient-to-b from-indigo-50 via-sky-50 to-teal-50 text-neutral-800"
        >
          <div className="relative z-10 container-wide space-y-6 py-8">
          {/* 计划输入模块已移至首页首屏 */}

          {/* 文本与地图两列（不透明卡片） */}
          <section className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="p-4 sm:p-6 rounded-2xl bg-gradient-to-br from-white to-neutral-50 ring-1 ring-neutral-200 shadow-md">
              <PlanTextView plan={plan ?? undefined} rawText={rawText} variant="light" />
            </div>
            <div className="p-4 sm:p-6 rounded-2xl bg-gradient-to-br from-white to-neutral-50 ring-1 ring-neutral-200 shadow-md">
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-medium text-neutral-800">地图预览</h2>
                <div className="text-xs text-neutral-500">可交互</div>
              </div>
              <MapView plan={plan ?? undefined} userText={userText} />
          </div>
          </section>
          </div>
        </section>
      </main>
    </div>
  );
}
