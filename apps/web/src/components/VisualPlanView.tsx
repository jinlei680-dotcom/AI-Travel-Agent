"use client";
import React, { useMemo } from "react";
import type { ItineraryPlan, DayPlan, Poi, DailySegment } from "./ItineraryInput";

type VisualItem = { name: string; type: "lodging" | "attraction" | "restaurant" | "transport"; note?: string };
type DayVisual = { title: string; items: VisualItem[] };

function seedImageUrl(name: string, type: VisualItem["type"]): string {
  const base = "https://picsum.photos/seed/";
  const category = type === "lodging" ? "hotel" : type === "restaurant" ? "food" : type === "transport" ? "transport" : "sight";
  return `${base}${encodeURIComponent(name + "-" + category)}/280/160`;
}

function classifyByName(name: string): VisualItem["type"] {
  const n = name || "";
  if (/酒店|民宿|旅店|宾馆|入住|住宿/i.test(n)) return "lodging";
  if (/餐厅|午餐|晚餐|早餐|美食|小吃|酒吧|咖啡/i.test(n)) return "restaurant";
  if (/地铁|公交|火车|高铁|航班|出租车|打车|步行|骑行/i.test(n)) return "transport";
  return "attraction";
}

function tokenizeCandidates(line: string): string[] {
  const text = (line || "").replace(/[\*\#>]/g, "");
  const afterColon = text.split(/[：:]/).slice(-1)[0];
  const base = afterColon && afterColon.trim() ? afterColon : text;
  const tokens = base.split(/[、，,；;]/).map((t) => t.trim()).filter(Boolean);
  const valid = tokens.filter((t) => /[\u4e00-\u9fa5A-Za-z·（）()\s]{2,18}/.test(t))
    .filter((t) => !/(预算|约|元|小时|分钟|公里|门票|费用)/.test(t));
  return valid;
}

function parseRawTextToVisual(rawText: string): DayVisual[] {
  const raw = rawText || "";
  if (!raw.trim()) return [];
  // 切分“第X天”，兼容中文数字与阿拉伯数字
  // 放宽匹配：不强制结尾换行，支持“第X天：...”同一行形式
  const dayRe = /(\n|^)[#\s]*第\s*([0-9一二三四五六七八九十]+)\s*天[^\n]*/gi;
  const indices: { idx: number; title: string }[] = [];
  let m: RegExpExecArray | null;
  while ((m = dayRe.exec(raw)) !== null) {
    const idx = m.index + (m[1]?.length || 0);
    const title = `第 ${m[2]} 天`;
    indices.push({ idx, title });
  }
  const days: DayVisual[] = [];
  if (indices.length === 0) {
    const lines = raw.split(/\n+/).slice(0, 60);
    const items: VisualItem[] = [];
    for (const line of lines) {
      if (!line || line.length < 2) continue;
      const names = tokenizeCandidates(line).slice(0, 3);
      for (const nm of names) {
        items.push({ name: nm, type: classifyByName(nm) });
      }
      if (items.length >= 10) break;
    }
    if (items.length > 0) days.push({ title: "行程要点", items });
    return days;
  }
  for (let i = 0; i < indices.length; i++) {
    const start = indices[i].idx;
    const end = i + 1 < indices.length ? indices[i + 1].idx : raw.length;
    const chunk = raw.slice(start, end);
    const title = indices[i].title;
    // 在该天内提取“住宿/景点/餐饮/交通”相关条目（列表、段落关键词）
    const items: VisualItem[] = [];
    const lineSegments = chunk.split(/\n+/);
    for (const ln of lineSegments) {
      const names = tokenizeCandidates(ln).slice(0, 4);
      for (const nm of names) {
        items.push({ name: nm, type: classifyByName(nm) });
      }
    }
    // 去重并限制数量
    const seen = new Set<string>();
    const unique = items.filter((it) => {
      const key = it.type + "::" + it.name;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    days.push({ title, items: unique.slice(0, 12) });
  }
  return days;
}

function fromPlanFallback(plan?: ItineraryPlan | null): DayVisual[] {
  if (!plan || !Array.isArray(plan.days)) return [];
  return (plan.days || []).map((d: DayPlan, i: number) => {
    const items: VisualItem[] = (d.pois || []).map((p: Poi) => ({ name: p.name, type: classifyByName(p.name || "") }));
    return { title: `第 ${i + 1} 天`, items };
  });
}

export default function VisualPlanView({ plan, rawText, daily, variant = "light" }: { plan?: ItineraryPlan | null; rawText?: string; daily?: DailySegment[]; variant?: "light" | "dark" | "icons" }) {
  const visuals = useMemo(() => {
    // 1) 优先使用后端解析好的 daily 段
    if (Array.isArray(daily) && daily.length > 0) {
      const v = daily.map((d: DailySegment, i: number) => {
        const items: VisualItem[] = [];
        (d.attractions || []).forEach((n) => items.push({ name: n, type: "attraction" }));
        (d.lodging || []).forEach((n) => items.push({ name: n, type: "lodging" }));
        (d.restaurants || []).forEach((n) => items.push({ name: n, type: "restaurant" }));
        (d.transport || []).forEach((n) => items.push({ name: n, type: "transport" }));
        return { title: d.title || `第 ${i + 1} 天`, items };
      });
      if (v.length > 0) return v;
    }
    const parsed = parseRawTextToVisual(rawText || "");
    if (variant === "icons") {
      // icons 模式严格使用原文解析；无原文则返回空，显示提示
      return parsed;
    }
    if (parsed.length > 0) return parsed;
    const fallback = fromPlanFallback(plan);
    return fallback.length > 0 ? fallback : [];
  }, [rawText, plan, variant, daily]);

  // icons 模式视为浅色主题，但渲染为“图标+文字”简洁列表
  const isLight = variant !== "dark";
  const typeLabel = (t: VisualItem["type"]) => (t === "lodging" ? "住宿" : t === "restaurant" ? "餐饮" : t === "transport" ? "交通" : "景点");
  const typeIcon = (t: VisualItem["type"]) => (t === "lodging" ? "🏨" : t === "restaurant" ? "🍜" : t === "transport" ? "🚇" : "📍");
  const dayBudget = useMemo(() => {
    try {
      const total = plan?.baseBudget?.amount || 0;
      const days = Array.isArray(plan?.days) ? plan!.days.length : 0;
      if (!total || !days) return null;
      return Math.round(total / days);
    } catch { return null; }
  }, [plan]);
  const badge = (label: string) => (
    <span className={`text-xxs px-2 py-0.5 rounded-full ${isLight ? "bg-neutral-100 text-neutral-700 ring-1 ring-neutral-200" : "bg-neutral-800/60 text-sky-200 ring-1 ring-white/10"}`}>{label}</span>
  );

  const introText = useMemo(() => {
    const raw = (rawText || "").trim();
    // 行程要点仅根据原文提炼；若无原文则返回空，由 UI 提示
    if (!raw) return "";
    const dayIdx = raw.search(/(\n|^)#?\s*第\s*([0-9一二三四五六七八九十]+)\s*天/);
    const head = dayIdx > 0 ? raw.slice(0, dayIdx) : raw;
    const firstLine = head.split(/\n+/).find(l => !!l.trim()) || "";
    const cleaned = firstLine.replace(/^[#>*\s]+/, "").trim();
    const sentence = cleaned.split(/[。.!?]/)[0];
    const s = sentence.length > 90 ? sentence.slice(0, 90) + "…" : sentence;
    return s;
  }, [rawText]);

  return (
    <div className="space-y-4 min-h-[520px]">
      {variant === "icons" ? (
        <div className="space-y-4">
          {/* 顶部简介卡片移除：不再显示原文第一行提示 */}
          {visuals.length === 0 ? (
            <div className={`text-sm ${isLight ? "text-neutral-600" : "text-sky-300"}`}>暂无原文或未解析到“第X天”结构</div>
          ) : (
            visuals.map((day, idx) => {
              const atts = day.items.filter(it => it.type === "attraction");
              const lods = day.items.filter(it => it.type === "lodging");
              const rests = day.items.filter(it => it.type === "restaurant");
              const section = (title: string, icon: string, items: VisualItem[], type: VisualItem["type"]) => (
                <div className={`${isLight ? (type === "attraction" ? "bg-sky-50 ring-1 ring-sky-200" : type === "lodging" ? "bg-amber-50 ring-1 ring-amber-200" : type === "restaurant" ? "bg-pink-50 ring-1 ring-pink-200" : "bg-indigo-50 ring-1 ring-indigo-200") : "bg-neutral-800/60 ring-1 ring-white/10"} rounded-lg p-3`}>
                  <div className="flex items-center justify-between mb-2">
                    <div className={`text-sm font-medium ${isLight ? "text-neutral-900" : "text-sky-100"}`}>{title}</div>
                  </div>
                  {items.length === 0 ? (
                    <div className={`${isLight ? "text-neutral-500" : "text-sky-300"} text-xs`}>暂无</div>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {items.map((it, i) => (
                        <span
                          key={i}
                          className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs whitespace-nowrap ${isLight
                            ? (type === "attraction" ? "bg-sky-100 text-sky-800 ring-1 ring-sky-200" : type === "lodging" ? "bg-amber-100 text-amber-900 ring-1 ring-amber-200" : type === "restaurant" ? "bg-pink-100 text-pink-800 ring-1 ring-pink-200" : "bg-indigo-100 text-indigo-800 ring-1 ring-indigo-200")
                            : "bg-neutral-700/60 text-sky-100 ring-1 ring-white/10"}`}
                        >
                          <span className="text-sm">{icon}</span>
                          <span className="truncate max-w-[140px]">{it.name}</span>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              );
              return (
                <div key={idx} className={`${isLight ? "bg-white" : "bg-neutral-900/40"} rounded-xl p-4 ring-1 ${isLight ? "ring-neutral-200" : "ring-white/10"} flex flex-col`}>
                  <div className="mb-3">
                    <div className={`text-base font-semibold ${isLight ? "text-neutral-900" : "text-sky-100"}`}>{day.title}</div>
                  </div>
                  <div className="grid grid-cols-1 gap-3">
                    {section("景点", "📍", atts, "attraction")}
                    {section("餐饮", "🍜", rests, "restaurant")}
                    {section("住宿", "🏨", lods, "lodging")}
                  </div>
                </div>
              );
            })
          )}
        </div>
      ) : (
        <>
          {visuals.length === 0 && (
            <div className={`text-sm ${isLight ? "text-neutral-600" : "text-sky-300"}`}>暂无可视化内容，请先生成行程。</div>
          )}
          {visuals.map((day, i) => (
            <div key={i} className={`${isLight ? "bg-white" : "bg-neutral-900/40"} rounded-xl p-4 ring-1 ${isLight ? "ring-neutral-200" : "ring-white/10"}`}>
              <div className="flex items-center justify-between mb-3">
                <div className={`text-base font-semibold ${isLight ? "text-neutral-900" : "text-sky-100"}`}>{day.title}</div>
                <div className="flex items-center gap-2">
                  {badge(`节点 ${day.items.length}`)}
                  {dayBudget ? badge(`人均约 ¥${dayBudget}/天`) : null}
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {day.items.map((it, idx) => (
                  <div key={idx} className="relative group">
                    <div className="rounded-lg overflow-hidden ring-1 ring-neutral-200">
                      <div className="aspect-[4/3] w-full bg-neutral-100">
                        <img src={seedImageUrl(it.name, it.type)} alt={it.name} className="w-full h-full object-cover" />
                      </div>
                    </div>
                    <div className="absolute bottom-0 left-0 right-0 p-2 bg-gradient-to-t from-black/50 to-black/0">
                      <div className="flex items-center justify-between">
                        <div className="text-white text-sm font-medium truncate"><span className="mr-1">{typeIcon(it.type)}</span>{it.name}</div>
                        <span className="text-white/90 text-xxs px-2 py-0.5 rounded-full bg-black/40 ring-1 ring-white/20">{typeLabel(it.type)}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
              {day.items.length > 1 && (
                <div className="mt-3 flex items-center gap-1 overflow-x-auto whitespace-nowrap text-xs">
                  {day.items.slice(0, 8).map((it, idx) => (
                    <React.Fragment key={idx}>
                      <span className={`px-2 py-0.5 rounded-full ${isLight ? "bg-neutral-100 text-neutral-700 ring-1 ring-neutral-200" : "bg-neutral-800/60 text-sky-200 ring-1 ring-white/10"}`}>{it.name}</span>
                      {idx < Math.min(day.items.length, 8) - 1 && <span className={`${isLight ? "text-neutral-500" : "text-sky-300"}`}>→</span>}
                    </React.Fragment>
                  ))}
                </div>
              )}
            </div>
          ))}
        </>
      )}
    </div>
  );
}
