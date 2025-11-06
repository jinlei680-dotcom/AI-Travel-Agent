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

export default function AiChatPanel({ onPlanned }: { onPlanned: (p: ItineraryPlan) => void }) {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const lastAssistantIndex = useRef<number | null>(null);

  // Helpers to construct strictly typed messages
  const makeUserMsg = (content: string): Msg => ({ role: "user", content } as Msg);
  const makeAssistantMsg = (content: string): Msg => ({ role: "assistant", content } as Msg);

  const addUser = (text: string) => {
    setMsgs((prev: Msg[]) => [...prev, makeUserMsg(text)]);
    lastAssistantIndex.current = null; // reset for new assistant reply
  };

  const addOrUpdateAssistant = (text: string) => {
    setMsgs((prev: Msg[]) => {
      if (lastAssistantIndex.current == null) {
        const next: Msg[] = [...prev, makeAssistantMsg(text)];
        lastAssistantIndex.current = next.length - 1;
        return next;
      } else {
        const idx = lastAssistantIndex.current!;
        const next: Msg[] = [...prev];
        next[idx] = makeAssistantMsg(text);
        return next;
      }
    });
  };

  const onPlan = (p: ItineraryPlan) => {
    onPlanned(p);
    const text = planToText(p);
    addOrUpdateAssistant(text);
  };

  const transcript = useMemo(() => (
    <div className="space-y-3">
      {msgs.map((m, i) => (
        <div
          key={i}
          className={`${m.role === "user" ? "bg-neutral-800/70" : "bg-violet-900/40"} rounded-md p-4 ring-1 ring-white/10`}
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
      {transcript}
      <div className="pt-2">
        <ItineraryInput onPlanned={onPlan} onUserSubmit={addUser} hideLabel />
      </div>
    </div>
  );
}