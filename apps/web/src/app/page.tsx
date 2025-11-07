"use client";
import { useState } from "react";
import MapView from "@/components/MapView";
import ItineraryInput, { type ItineraryPlan } from "@/components/ItineraryInput";
import PlanTextView from "@/components/PlanTextView";

export default function Home() {
  const [plan, setPlan] = useState<ItineraryPlan | null>(null);
  const [userText, setUserText] = useState<string>("");
  const [rawText, setRawText] = useState<string>("");

  return (
    <div className="min-h-screen bg-neutral-950 text-neutral-200">
      <header className="sticky top-0 z-40 bg-neutral-900/40">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <span className="text-lg font-semibold">旅行规划助手</span>
        </div>
      </header>
      <main className="px-6 py-8">
        <div className="mx-auto max-w-6xl space-y-6">
          <ItineraryInput
            onPlanned={(p) => setPlan(p)}
            onUserSubmit={(t) => setUserText(t)}
            onRawText={(txt, phase) => {
              // 优先展示最终原文，草稿作为过渡
              if (phase === "final") setRawText(txt);
              else if (!rawText) setRawText(txt);
            }}
          />

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <PlanTextView plan={plan ?? undefined} rawText={rawText} />
            <div>
              <div className="text-sm text-neutral-400 mb-2">地图预览</div>
              <MapView plan={plan ?? undefined} userText={userText} />
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
