"use client";
import { useMemo, useRef, useState } from "react";
import ItineraryInput, { ItineraryPlan, DayPlan, Poi } from "./ItineraryInput";

type Msg = { role: "user" | "assistant"; content: string };

function planToText(plan: ItineraryPlan): string {
  const days = plan.days || [];
  if (days.length === 0) return "暂未生成行程。";
  const parts: string[] = [];
  parts.push(`共 ${days.length} 天行程`);
  days.forEach((d: DayPlan, i: number) => {
    const title = `第 ${i + 1} 天`;
    const summary = d.summary ? `：${d.summary}` : "";
    const pois = (d.pois || []).map((p: Poi) => p.name).filter(Boolean);
    const poiLine = pois.length > 0 ? `\n主要地点：${pois.join("、")}` : "";
    parts.push(`${title}${summary}${poiLine}`);
  });
  return parts.join("\n");
}

export default function AiChatPanel({ onPlanned, showTranscript = true, onUserText, onRawText }: { onPlanned: (p: ItineraryPlan) => void; showTranscript?: boolean; onUserText?: (text: string) => void; onRawText?: (text: string, phase: "draft" | "final") => void }) {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const lastAssistantIndex = useRef<number | null>(null);

  // Helpers to construct strictly typed messages
  const makeUserMsg = (content: string): Msg => ({ role: "user", content } as Msg);
  const makeAssistantMsg = (content: string): Msg => ({ role: "assistant", content } as Msg);

  const addUser = (text: string) => {
    setMsgs((prev: Msg[]) => [...prev, makeUserMsg(text)]);
    lastAssistantIndex.current = null; // reset for new assistant reply
    try { onUserText?.(text); } catch {}
  };

  const addAssistant = (text: string) => {
    setMsgs((prev: Msg[]) => [...prev, makeAssistantMsg(text)]);
    lastAssistantIndex.current = null;
  };

  const onPlan = (p: ItineraryPlan) => {
    onPlanned(p);
    const text = planToText(p);
    // 结构化摘要作为单独消息保留
    addAssistant(text);
  };

  const handleRawText = (text: string, phase: "draft" | "final") => {
    if (text && text.trim()) {
      // 原文优先显示，draft/final 都追加，便于查看差异
      addAssistant(text);
      try { onRawText?.(text, phase); } catch {}
    }
  };

  const transcript = useMemo(() => (
    <div className="space-y-3 max-h-24 overflow-y-auto">
      {msgs.map((m, i) => (
        <div
          key={i}
          className={`${m.role === "user" ? "bg-neutral-800/70" : "bg-violet-900/40"} rounded-md p-3 ring-1 ring-white/10`}
        >
          <div className="text-xs text-neutral-400 mb-1">{m.role === "user" ? "你" : "AI"}</div>
          <div className="whitespace-pre-wrap text-sm text-neutral-200">{m.content}</div>
        </div>
      ))}
    </div>
  ), [msgs]);

  return (
    <div className="space-y-4">
      <div className="text-sm text-neutral-400">AI 对话</div>
      {showTranscript && transcript}
      <div className="pt-2">
        <ItineraryInput onPlanned={onPlan} onUserSubmit={addUser} onRawText={handleRawText} hideLabel rows={3} />
      </div>
    </div>
  );
}
