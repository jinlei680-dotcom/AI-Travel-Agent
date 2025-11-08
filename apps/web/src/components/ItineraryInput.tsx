"use client";
import { useEffect, useRef, useState } from "react";
import { API_BASE, post } from "@/lib/api";

export type Coord = [number, number];
export type Route = { polyline?: string; color?: string; mode?: string; stops?: { name: string; coord: Coord; line?: string; type?: string }[]; segmentIndex?: number; durationSec?: number };
export type Poi = { name: string; coord: Coord; type?: string; address?: string };
export type DayPlan = { summary?: string; routes: Route[]; pois: Poi[] };
export type Budget = { amount?: number; currency?: string };
export type ItineraryPlan = { cityCenter?: Coord; days: DayPlan[]; baseBudget?: Budget };
export type DailySegment = { title: string; attractions: string[]; lodging: string[]; restaurants: string[]; transport?: string[] };

export type BackendBudgetItem = { name: string; amount?: number; currency?: string };
export type BackendBudgetCategory = { name: string; items: BackendBudgetItem[]; total?: number; currency?: string };
export type BackendBudgetBreakdown = { currency?: string; categories: BackendBudgetCategory[]; grandTotal?: number; aligned?: boolean };

export default function ItineraryInput({ onPlanned, onUserSubmit, onRawText, onDaily, onBudget, hideLabel, rows, variant = "plan", conversationId }: { onPlanned: (p: ItineraryPlan) => void; onUserSubmit?: (text: string) => void; onRawText?: (text: string, phase: "draft" | "final") => void; onDaily?: (days: DailySegment[]) => void; onBudget?: (b: BackendBudgetBreakdown, aligned?: boolean) => void; hideLabel?: boolean; rows?: number; variant?: "plan" | "chat"; conversationId?: string }) {
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stage, setStage] = useState<string | null>(null);
  const [hasDraft, setHasDraft] = useState(false);
  const [bgComputing, setBgComputing] = useState(false);
  const [finalNotice, setFinalNotice] = useState<string | null>(null);
  

  useEffect(() => { return () => {}; }, []);

  async function submit() {
    setError(null);
    if (!text.trim()) {
      setError("请输入旅行需求，例如：‘北京三日游，偏好历史景点’");
      return;
    }
    try { onUserSubmit?.(text); } catch {}
    // 点击开始规划后，平滑跳转到下半页面（#app）
    try {
      const el = document.getElementById("app");
      if (el && typeof el.scrollIntoView === "function") {
        el.scrollIntoView({ behavior: "smooth", block: "start" });
      } else if (typeof window !== "undefined") {
        window.location.hash = "#app";
      }
    } catch {}
    setLoading(true);
    setStage("sync");
    setHasDraft(false);
    setBgComputing(false);
    setFinalNotice(null);
    // 优先走 SSE 流式，如失败则回退到非流式 POST
    // 非流式回退逻辑封装
    const callNonStream = async () => {
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 30000);
        // 简单从用户文本推断城市，提高后端地理编码命中率
        const inferCityFromText = (t: string): string | undefined => {
          const known = [
            "北京","上海","广州","深圳","成都","杭州","西安","重庆","南京","厦门",
            "青岛","苏州","武汉","长沙","昆明","桂林","三亚","大理","天津","郑州"
          ];
          for (const c of known) { if (t.includes(c)) return c; }
          return undefined;
        };
        const city = inferCityFromText(text);
        const endpoint = (variant === "chat" && conversationId)
          ? `/api/v1/conversations/${conversationId}/chat-plan`
          : "/api/v1/itinerary/plan";
        const payload = city ? { text, city } : { text };
        const res = await post<{ plan: ItineraryPlan; rawText?: string; daily?: DailySegment[]; budget?: BackendBudgetBreakdown; budgetAligned?: boolean }>(
          endpoint,
          payload,
          { signal: controller.signal }
        );
        let plan = (res as any).plan ?? (res as any);
        plan = expandPlanDaysByText(plan as ItineraryPlan, text);
        onPlanned(plan as ItineraryPlan);
        // 传递后端解析好的每日结构（若有）
        try {
          const daily = (res as any)?.daily;
          if (Array.isArray(daily)) {
            onDaily?.(daily as DailySegment[]);
          }
        } catch {}
        // 后端结构化预算
        try {
          const bd = (res as any)?.budget;
          const aligned = (res as any)?.budgetAligned;
          if (bd && typeof bd === "object") {
            onBudget?.(bd as BackendBudgetBreakdown, aligned as boolean | undefined);
          }
        } catch {}
        try {
          const raw = (res as any)?.rawText;
          // 聊天模式只显示后端返回的原文，不做摘要回退
          if (variant === "chat") {
            if (typeof raw === "string" && raw.trim()) {
              onRawText?.(raw, "final");
            } else {
              // 原文为空时给出轻提示，避免“无回应”的体验
              onRawText?.("已生成行程，请查看下方卡片。", "final");
            }
          } else {
            // 非聊天模式：若后端未提供原文，则不再用“第1天摘要”覆盖，
            // 让展示组件回退到结构化计划的 Markdown，多日信息更完整。
            if (typeof raw === "string" && raw.trim()) {
              onRawText?.(raw, "final");
            }
          }
        } catch {}
        // 成功生成后标记完成，便于用户感知
        setStage("done");
        setFinalNotice("规划完成");
        try { setTimeout(() => setFinalNotice(null), 4000); } catch {}
        try { clearTimeout(timer); } catch {}
      } catch (err: any) {
        const msg = (err?.name === "AbortError") ? "请求超时，请重试或简化需求" : (err?.message || "规划失败，请稍后重试");
        setError(msg);
        setStage("error");
      } finally {
        setLoading(false);
      }
    };

    await callNonStream();
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center">
        {!hideLabel && (
          <label className="text-sm text-neutral-600">用自然语言描述你的行程需求</label>
        )}
      </div>
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="例如：北京三天家庭亲子游，6月初，预算3000，主要看博物馆和历史景点"
        className={`${variant === "chat" ? "w-full rounded-xl bg-white px-3 py-2 text-neutral-800 ring-1 ring-blue-200 focus:outline-none focus:ring-2 focus:ring-blue-400" : "w-full rounded-md bg-white px-3 py-2 text-neutral-800 ring-1 ring-neutral-300 focus:outline-none focus:ring-2 focus:ring-violet-300"} resize-none placeholder:text-neutral-400`}
        rows={Math.max(1, rows ?? 2)}
      />
      <div className={`flex ${variant === "chat" ? "justify-end" : "justify-center"}`}>
        <button
          onClick={submit}
          disabled={loading && !hasDraft}
          className={`${variant === "chat" ? "rounded-xl bg-blue-600 text-white px-4 py-2 text-sm hover:bg-blue-700 shadow-sm" : "rounded-full border border-white/20 bg-white/10 px-5 py-2.5 text-white hover:bg-white/20 transition"} disabled:opacity-60`}
        >{variant === "chat" ? (loading && !hasDraft ? "发送中..." : "发送") : (loading && !hasDraft ? "规划中..." : "开始规划")}</button>
      </div>
      <div className="flex items-center gap-3">
        {stage && !error && (
          <span className="text-sm text-sky-300">阶段：{stage}</span>
        )}
        {error && <span className="text-sm text-red-400">{error}</span>}
      </div>
      {hasDraft && bgComputing && (
        <div className="text-xs text-sky-400">已生成草稿，可先查看；路线优化将在后台完成…</div>
      )}
      {finalNotice && (
        <div className="text-xs text-green-400">{finalNotice}</div>
      )}
    </div>
  );
}

// 根据输入文本推断天数，并扩展计划的 days；用于后端返回天数不足时的回退。
function expandPlanDaysByText(plan: ItineraryPlan, text: string): ItineraryPlan {
  try {
    const targetDays = inferDays(text);
    const currentDays = Array.isArray(plan.days) ? plan.days.length : 0;
    if (targetDays > currentDays) {
      const extended = Array.from({ length: targetDays }, (_, i) => {
        if (i < currentDays) return plan.days[i];
        return { summary: `第 ${i + 1} 天`, routes: [], pois: [] } as DayPlan;
      });
      return { ...plan, days: extended };
    }
  } catch {}
  return plan;
}

function inferDays(text: string): number {
  if (!text) return 1;
  // 1) 数字形式：3天、3日、3天2晚、3天一夜、3日游 等
  const numDay = text.match(/(\d+)\s*(天|日)/);
  if (numDay) {
    const n = parseInt(numDay[1], 10);
    if (Number.isFinite(n)) return Math.max(1, Math.min(n, 10));
  }
  // 2) 中文数字：三天、三日、两天一夜、十日游 等（上限 10 天）
  const zhDigits: Record<string, number> = { "零": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9 };
  const zhWord = text.match(/([零一二两三四五六七八九十]+)\s*(天|日)/);
  if (zhWord) {
    const s = zhWord[1];
    let val = 0;
    if (s.includes("十")) {
      // 简化：出现“十”按 10 处理
      val = 10;
    } else {
      for (const ch of s.split("")) {
        if (ch in zhDigits) { val = zhDigits[ch]; break; }
      }
    }
    if (val > 0) return Math.max(1, Math.min(val, 10));
  }
  // 3) 宽松匹配：包含“X天Y晚/一夜”优先取 X
  const dayNight = text.match(/(\d+)\s*天\s*(\d+)\s*(晚|夜)?/);
  if (dayNight) {
    const n = parseInt(dayNight[1], 10);
    if (Number.isFinite(n)) return Math.max(1, Math.min(n, 10));
  }
  return 1;
}
