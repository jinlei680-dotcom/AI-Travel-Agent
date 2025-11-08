"use client";
import { useEffect, useMemo, useRef, useState } from "react";
import ItineraryInput, { type ItineraryPlan } from "./ItineraryInput";
import { get, post } from "@/lib/api";
import { getToken, getEmailFromToken } from "@/lib/auth";

type Msg = { role: "user" | "assistant"; content: string };

function planToText(plan: ItineraryPlan): string {
  const days = plan.days || [];
  if (days.length === 0) return "暂未生成行程。";
  const parts: string[] = [];
  parts.push(`共 ${days.length} 天行程`);
  days.forEach((d, i) => {
    const title = `第 ${i + 1} 天`;
    const summary = d.summary ? `：${d.summary}` : "";
    const pois = (d.pois || []).map((p) => p.name).filter(Boolean) as string[];
    const poiLine = pois.length > 0 ? `\n主要地点：${pois.join("、")}` : "";
    parts.push(`${title}${summary}${poiLine}`);
  });
  return parts.join("\n");
}

export default function ConversationChatPanel({ conversationId, onPlanned, showTranscript = true, onUserText, onRawText }: { conversationId?: string | null; onPlanned?: (p: ItineraryPlan) => void; showTranscript?: boolean; onUserText?: (text: string) => void; onRawText?: (text: string, phase: "draft" | "final") => void }) {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [bindStatus, setBindStatus] = useState<string>("");
  const [bindError, setBindError] = useState<string>("");
  const userTextRef = useRef<string>("");
  const [greeting, setGreeting] = useState<string>("");
  const [email, setEmail] = useState<string | null>(null);

  const makeUserMsg = (content: string): Msg => ({ role: "user", content });
  const makeAssistantMsg = (content: string): Msg => ({ role: "assistant", content });

  const addUser = async (text: string) => {
    if (!text?.trim()) return;
    setMsgs((prev) => [...prev, makeUserMsg(text)]);
    userTextRef.current = text;
    onUserText?.(text);
    if (conversationId) {
      try { await post(`/api/v1/conversations/${conversationId}/messages`, { role: "user", content: text }); } catch {}
    }
  };

  const addAssistant = async (text: string) => {
    setMsgs((prev) => [...prev, makeAssistantMsg(text)]);
    if (conversationId) {
      try { await post(`/api/v1/conversations/${conversationId}/messages`, { role: "assistant", content: text }); } catch {}
    }
  };

  // 当选择会话时，拉取历史消息并展示
  useEffect(() => {
    const load = async () => {
      if (!conversationId) { setMsgs([]); return; }
      try {
        const data = await get<{ conversation: any; messages: { role: string; content: string }[] }>(`/api/v1/conversations/${conversationId}`);
        const ms = Array.isArray(data?.messages) ? data.messages : [];
        const mapped: Msg[] = ms.map((m) => ({ role: (m.role === "assistant" ? "assistant" : "user"), content: m.content || "" }));
        setMsgs(mapped);
      } catch (e) {
        // 保持静默，避免打断用户；可考虑外部错误提示
      }
    };
    load();
  }, [conversationId]);

  // 欢迎语与访客ID
  useEffect(() => {
    const hour = new Date().getHours();
    const salutation = hour < 6 ? "凌晨好" : hour < 11 ? "早上好" : hour < 13 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
    setGreeting(salutation);
    try {
      const t = getToken();
      setEmail(getEmailFromToken(t));
    } catch {}
  }, []);

  const onPlanLocal = async (p: ItineraryPlan) => {
    // 保留结构化计划用于后续绑定与地图展示，但不将其转为聊天文本
    onPlanned?.(p);
    try {
      setBindStatus("");
      setBindError("");
      if (!conversationId) {
        setBindError("请先在左侧选择会话，再生成并绑定行程。");
        return;
      }
      const days = Array.isArray(p.days) ? p.days.length : 1;
      const start = new Date();
      const end = new Date(start.getTime() + Math.max(0, days - 1) * 24 * 3600 * 1000);
      const toDateStr = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
      const destination = inferCity(userTextRef.current) || "未命名目的地";
      const created = await post("/api/v1/plans/create-with-conversation", {
        destination,
        startDate: toDateStr(start),
        endDate: toDateStr(end),
        budgetAmount: null,
        budgetCurrency: "CNY",
        tags: [],
        conversationId: conversationId,
      });
      const pid = (created as any)?.id;
      setBindStatus(pid ? `行程已创建并绑定，ID：${pid}` : "行程已创建并绑定到会话。");
    } catch (e: any) {
      const msg = e?.message || "行程绑定失败，请确认已登录并有权限。";
      setBindError(msg);
    }
  };

  const handleRawText = async (text: string, phase: "draft" | "final") => {
    if (text && text.trim()) {
      await addAssistant(text);
      onRawText?.(text, phase);
    }
  };

  // 取消快捷建议按钮与相关逻辑

  const transcript = useMemo(() => (
    <div className="space-y-3 max-h-[70vh] overflow-y-auto pr-1">
      {msgs.map((m, i) => (
        <div key={i} className={`bg-white rounded-sm p-3 ring-1 ring-neutral-300`}>
          <div className="text-xs text-neutral-500 mb-1">{m.role === "user" ? "你" : "AI"}</div>
          <div className="whitespace-pre-wrap text-sm text-neutral-800">{m.content}</div>
        </div>
      ))}
    </div>
  ), [msgs]);

  const containerClass = conversationId ? "space-y-4 min-h-[60vh]" : "min-h-[calc(100vh-96px)] flex flex-col items-center justify-center gap-4";
  return (
    <div className={containerClass}>
      <div className="text-center py-2">
        <div className="text-xl sm:text-2xl font-semibold text-neutral-800">{greeting}，{email || "游客"}</div>
      </div>
      {(bindStatus || bindError) && (
        <div className={`text-xs rounded-xl px-3 py-2 ${bindError ? "bg-red-50 text-red-700 ring-1 ring-red-200" : "bg-blue-50 text-blue-700 ring-1 ring-blue-200"}`}>
          {bindError || bindStatus}
        </div>
      )}
      {conversationId && showTranscript && transcript}
      <div className={conversationId ? "pt-2 w-full" : "w-full max-w-2xl"}>
        <ItineraryInput
          onPlanned={onPlanLocal}
          onUserSubmit={addUser}
          onRawText={handleRawText}
          hideLabel
          rows={conversationId ? 1 : 2}
          variant="chat"
          conversationId={conversationId || undefined}
        />
      </div>
    </div>
  );
}

function inferCity(text: string): string | null {
  try {
    const cities = ["北京","上海","杭州","广州","深圳","成都","重庆","西安","南京","苏州","厦门","青岛","天津","武汉","长春","沈阳","大连","合肥","济南","昆明","拉萨","三亚","海口"];
    for (const c of cities) { if (text.includes(c)) return c; }
    const m = text.match(/([\u4e00-\u9fa5]{2,})[市省]/);
    if (m && m[1]) return m[1];
    return null;
  } catch { return null; }
}
