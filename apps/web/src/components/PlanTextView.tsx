"use client";
import React from "react";
import type { ItineraryPlan, DayPlan, Poi } from "./ItineraryInput";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

function toMarkdown(plan: ItineraryPlan): string {
  const days = plan.days || [];
  if (days.length === 0) return "生成后将在此显示行程文字摘要。";
  const lines: string[] = [];
  lines.push(`# 行程规划`);
  lines.push(`共 **${days.length}** 天`);
  days.forEach((d: DayPlan, i: number) => {
    const dayTitle = `## 第 ${i + 1} 天`;
    const summary = d.summary ? `> ${d.summary}` : "";
    const poiNames = (d.pois || []).map((p: Poi) => p.name).filter(Boolean);
    lines.push(dayTitle);
    if (summary) lines.push(summary);
    if (poiNames.length > 0) {
      lines.push("### 主要地点");
      for (const name of poiNames) {
        lines.push(`- ${name}`);
      }
    }
  });
  return lines.join("\n\n");
}

export default function PlanTextView({ plan, rawText, variant = "dark" }: { plan?: ItineraryPlan | null; rawText?: string; variant?: "dark" | "light" }) {
  const hasDays = !!plan && Array.isArray(plan?.days) && (plan?.days?.length || 0) > 0;
  const raw = rawText ?? "";
  // 若原文看起来不完整（如只有“第一段”、或某段几乎为空），优先展示结构化计划的 Markdown
  const markerRe = /第\s*([0-9]+|[一二三四五六七八九十])\s*(天|段)/g;
  const parts: Array<{ label: string; start: number; end: number }> = [];
  let m: RegExpExecArray | null;
  while ((m = markerRe.exec(raw)) !== null) {
    const label = m[0];
    const start = m.index;
    const end = markerRe.lastIndex;
    parts.push({ label, start, end });
  }
  // 估算每段内容长度；最后一段内容长度过短也视为不完整
  const segmentLengths: number[] = [];
  if (parts.length > 0) {
    for (let i = 0; i < parts.length; i++) {
      const segStart = parts[i].end;
      const segEnd = (i + 1 < parts.length) ? parts[i + 1].start : raw.length;
      const len = Math.max(0, segEnd - segStart);
      segmentLengths.push(len);
    }
  }
  const tooShortLast = segmentLengths.length > 0 && segmentLengths[segmentLengths.length - 1] < 50;
  const notEnoughSegments = hasDays && plan!.days.length > 1 && parts.length < Math.min(plan!.days.length, 2);
  const looksIncomplete = raw.length < 80 || tooShortLast || notEnoughSegments || raw.startsWith("{");
  // 始终优先显示原文；仅在没有原文时才回退到结构化摘要
  const markdownSource = raw
    ? raw
    : (plan ? toMarkdown(plan) : "生成后将在此显示行程文字摘要。");

  // 标签与提示：当原文存在但检测到不完整时，也继续显示原文并提示
  const sourceLabel = raw ? (looksIncomplete ? "原文（可能不完整）" : "原文") : "结构化摘要";
  const hintText = raw
    ? (looksIncomplete ? "原文可能不完整，仍按原文显示" : "来自后端返回的原始文本")
    : "未提供原文，显示结构化计划概要";

  const isLight = variant === "light";
  const components = {
    h1: ({ children }: any) => (
      <h1 className={`${isLight ? "text-neutral-900" : "text-sky-100"} text-2xl md:text-3xl font-semibold tracking-tight mb-4`}>{children}</h1>
    ),
    h2: ({ children }: any) => (
      <h2 className={`${isLight ? "text-neutral-900" : "text-sky-100"} text-xl md:text-2xl font-semibold mt-6 mb-3`}>{children}</h2>
    ),
    h3: ({ children }: any) => (
      <h3 className={`${isLight ? "text-neutral-800" : "text-sky-200"} text-lg md:text-xl font-semibold mt-4 mb-2`}>{children}</h3>
    ),
    p: ({ children }: any) => (
      <p className={`${isLight ? "text-neutral-800" : "text-sky-100"} leading-7 md:leading-8`}>{children}</p>
    ),
    ul: ({ children }: any) => (
      <ul className={`list-disc pl-6 space-y-1 ${isLight ? "text-neutral-800" : "text-sky-100"}`}>{children}</ul>
    ),
    ol: ({ children }: any) => (
      <ol className={`list-decimal pl-6 space-y-1 ${isLight ? "text-neutral-800" : "text-sky-100"}`}>{children}</ol>
    ),
    li: ({ children }: any) => (
      <li className={`${isLight ? "text-neutral-800" : "text-sky-100"}`}>{children}</li>
    ),
    blockquote: ({ children }: any) => (
      <blockquote className={`border-l-2 ${isLight ? "border-neutral-300 text-neutral-700" : "border-white/20 text-sky-300"} pl-4 italic my-3`}>{children}</blockquote>
    ),
    code: ({ children }: any) => (
      <code className={`font-mono ${isLight ? "bg-neutral-100 text-violet-700" : "bg-neutral-800/70 text-violet-200"} px-1.5 py-0.5 rounded`}>{children}</code>
    ),
    pre: ({ children }: any) => (
      <pre className={`font-mono ${isLight ? "bg-neutral-100 text-neutral-800" : "bg-neutral-900/60"} p-3 rounded-md overflow-x-auto text-sm`}>{children}</pre>
    ),
    a: ({ href, children }: any) => (
      <a href={href} target="_blank" rel="noreferrer" className={`${isLight ? "text-violet-700 hover:text-violet-900" : "text-violet-300 hover:text-violet-200"} underline`}>{children}</a>
    ),
    table: ({ children }: any) => (
      <table className="w-full text-left border-collapse my-3">{children}</table>
    ),
    th: ({ children }: any) => (
      <th className={`${isLight ? "border-b border-neutral-300 text-neutral-800" : "border-b border-white/10 text-sky-200"} py-2 font-medium`}>{children}</th>
    ),
    td: ({ children }: any) => (
      <td className={`${isLight ? "border-b border-neutral-200 text-neutral-700" : "border-b border-white/5 text-sky-300"} py-2`}>{children}</td>
    ),
  } as any;
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className={`text-sm ${isLight ? "text-neutral-600" : "text-sky-400"}`}>旅游计划</div>
        {hasDays && (
          <div className={`text-xs ${isLight ? "text-neutral-500" : "text-sky-400"}`}>共 {plan!.days.length} 天</div>
        )}
      </div>
      <div className={`max-w-none rounded-md ${isLight ? "bg-transparent ring-0 text-neutral-800" : "bg-neutral-900/40 ring-1 ring-white/10 text-sky-100"} p-5 md:p-6 text-sm md:text-base leading-7 h-[68vh] md:h-[75vh] overflow-y-auto`}
      >
        {/* 按要求移除“结构化摘要”等标识与提示 */}
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{markdownSource}</ReactMarkdown>
      </div>
    </div>
  );
}
