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

export default function PlanTextView({ plan, rawText }: { plan?: ItineraryPlan | null; rawText?: string }) {
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

  const components = {
    h1: ({ children }: any) => (
      <h1 className="text-2xl md:text-3xl font-semibold text-neutral-100 tracking-tight mb-4">{children}</h1>
    ),
    h2: ({ children }: any) => (
      <h2 className="text-xl md:text-2xl font-semibold text-neutral-100 mt-6 mb-3">{children}</h2>
    ),
    h3: ({ children }: any) => (
      <h3 className="text-lg md:text-xl font-semibold text-neutral-200 mt-4 mb-2">{children}</h3>
    ),
    p: ({ children }: any) => (
      <p className="leading-7 md:leading-8 text-neutral-200">{children}</p>
    ),
    ul: ({ children }: any) => (
      <ul className="list-disc pl-6 space-y-1 text-neutral-200">{children}</ul>
    ),
    ol: ({ children }: any) => (
      <ol className="list-decimal pl-6 space-y-1 text-neutral-200">{children}</ol>
    ),
    li: ({ children }: any) => (
      <li className="text-neutral-200">{children}</li>
    ),
    blockquote: ({ children }: any) => (
      <blockquote className="border-l-2 border-white/20 pl-4 text-neutral-300 italic my-3">{children}</blockquote>
    ),
    code: ({ children }: any) => (
      <code className="font-mono bg-neutral-800/70 px-1.5 py-0.5 rounded text-violet-200">{children}</code>
    ),
    pre: ({ children }: any) => (
      <pre className="font-mono bg-neutral-900/60 p-3 rounded-md overflow-x-auto text-sm">{children}</pre>
    ),
    a: ({ href, children }: any) => (
      <a href={href} target="_blank" rel="noreferrer" className="text-violet-300 hover:text-violet-200 underline">{children}</a>
    ),
    table: ({ children }: any) => (
      <table className="w-full text-left border-collapse my-3">{children}</table>
    ),
    th: ({ children }: any) => (
      <th className="border-b border-white/10 py-2 font-medium text-neutral-200">{children}</th>
    ),
    td: ({ children }: any) => (
      <td className="border-b border-white/5 py-2 text-neutral-300">{children}</td>
    ),
  } as any;
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="text-sm text-neutral-400">旅游计划</div>
        {hasDays && (
          <div className="text-xs text-neutral-500">共 {plan!.days.length} 天</div>
        )}
      </div>
      <div className="max-w-none rounded-md bg-neutral-900/40 p-5 md:p-6 ring-1 ring-white/10 text-sm md:text-base text-neutral-200 leading-7 h-[68vh] md:h-[75vh] overflow-y-auto">
        <div className="mb-3 flex items-center gap-2 text-xs">
          <span className={`inline-flex items-center rounded-full px-2 py-0.5 ${sourceLabel.startsWith("原文") ? "bg-green-700/50 text-green-200" : "bg-blue-700/50 text-blue-200"}`}>{sourceLabel}</span>
          <span className="text-neutral-400">{hintText}</span>
        </div>
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{markdownSource}</ReactMarkdown>
      </div>
    </div>
  );
}
