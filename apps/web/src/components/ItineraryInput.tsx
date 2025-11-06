"use client";
import { useEffect, useRef, useState } from "react";
import { API_BASE, post } from "@/lib/api";

export type Coord = [number, number];
export type Route = { polyline?: string; color?: string };
export type Poi = { name: string; coord: Coord; type?: string };
export type DayPlan = { summary?: string; routes: Route[]; pois: Poi[] };
export type ItineraryPlan = { cityCenter?: Coord; days: DayPlan[] };

export default function ItineraryInput({ onPlanned, onUserSubmit, hideLabel }: { onPlanned: (p: ItineraryPlan) => void; onUserSubmit?: (text: string) => void; hideLabel?: boolean }) {
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stage, setStage] = useState<string | null>(null);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    return () => { try { esRef.current?.close?.(); } catch {} };
  }, []);

  async function submit() {
    setError(null);
    if (!text.trim()) {
      setError("请输入旅行需求，例如：‘北京三日游，偏好历史景点’");
      return;
    }
    try { onUserSubmit?.(text); } catch {}
    setLoading(true);
    setStage("connecting");
    // 优先走 SSE 流式，如失败则回退到非流式 POST
    try {
      // 关闭可能存在的旧连接
      try { esRef.current?.close?.(); } catch {}
      const url = `${API_BASE}/api/v1/itinerary/plan/stream?text=${encodeURIComponent(text)}`;
      const es = new EventSource(url);
      esRef.current = es;
      es.addEventListener("progress", (ev: MessageEvent) => {
        try { const data = JSON.parse(ev.data); setStage(data.stage || "progress"); } catch { setStage("progress"); }
      });
      es.addEventListener("draft", (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data);
          const plan: ItineraryPlan = (data.plan ?? data) as ItineraryPlan;
          onPlanned(plan);
        } catch {}
      });
      es.addEventListener("final", (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data);
          const plan: ItineraryPlan = (data.plan ?? data) as ItineraryPlan;
          onPlanned(plan);
        } catch {}
        setStage("done");
        setLoading(false);
        try { es.close(); } catch {}
      });
      es.addEventListener("error", (ev: MessageEvent) => {
        setError("流式连接错误");
        setLoading(false);
        try { es.close(); } catch {}
      });
    } catch (e) {
      // 回退到非流式
      try {
        const res = await post<{ plan: ItineraryPlan }>("/api/v1/itinerary/plan", { text });
        const plan = (res as any).plan ?? (res as any);
        onPlanned(plan as ItineraryPlan);
      } catch (err: any) {
        setError(err?.message || "规划失败，请稍后重试");
      } finally {
        setLoading(false);
      }
    }
  }

  return (
    <div className="space-y-3">
      {!hideLabel && (
        <label className="text-sm text-neutral-300">用自然语言描述你的行程需求</label>
      )}
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="例如：北京三天家庭亲子游，6月初，预算3000，主要看博物馆和历史景点"
        className="w-full rounded-md bg-neutral-900/70 p-4 text-neutral-200 ring-2 ring-white/15 focus:outline-none focus:ring-2 focus:ring-violet-400/60"
        rows={6}
      />
      <div className="flex items-center gap-3">
        <button
          onClick={submit}
          disabled={loading}
          className="rounded-md bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 px-5 py-3 text-white shadow-sm hover:from-violet-600 hover:via-fuchsia-600 hover:to-violet-700 disabled:opacity-60"
        >{loading ? "规划中..." : "开始规划"}</button>
        {stage && !error && <span className="text-sm text-neutral-300">阶段：{stage}</span>}
        {error && <span className="text-sm text-red-400">{error}</span>}
      </div>
    </div>
  );
}