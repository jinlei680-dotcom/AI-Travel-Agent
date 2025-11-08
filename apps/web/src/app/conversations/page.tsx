"use client";
import { Suspense, useEffect, useState } from "react";
import NavBar from "@/components/NavBar";
import ConversationList from "@/components/ConversationList";
import ConversationChatPanel from "@/components/ConversationChatPanel";
import PlanTextView from "@/components/PlanTextView";
import MapView from "@/components/MapView";
import type { ItineraryPlan } from "@/components/ItineraryInput";

// 该页需在客户端动态渲染以避免 useSearchParams 的预渲染报错
export const dynamic = "force-dynamic";

export default function ConversationsPage() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [plan, setPlan] = useState<ItineraryPlan | null>(null);
  const [userText, setUserText] = useState<string>("");
  const [rawText, setRawText] = useState<string>("");

  useEffect(() => {
    try {
      const sp = new URLSearchParams(typeof window !== "undefined" ? window.location.search : "");
      const id = sp.get("id");
      if (id) setConversationId(id);
    } catch {}
  }, []);

  return (
    <Suspense fallback={<div className="p-6 text-sm text-neutral-600">加载会话中...</div>}>
      <div className="min-h-screen text-neutral-200">
        <NavBar />
        <main className="pb-0 pt-0">
          {/* 全屏两列布局：左侧会话列表贴边，右侧占满其余空间 */}
          <section className="min-h-screen w-full grid grid-cols-[280px_1fr] gap-0">
            <aside className="px-4 py-4 bg-white ring-1 ring-blue-100">
              <ConversationList onSelect={(id) => setConversationId(id)} />
            </aside>
            <section className="px-4 py-4">
              <ConversationChatPanel
                conversationId={conversationId}
                onPlanned={(p) => setPlan(p)}
                onUserText={(t) => setUserText(t)}
                onRawText={(txt, phase) => {
                  if (phase === "final") setRawText(txt);
                  else if (!rawText) setRawText(txt);
                }}
              />
            </section>
          </section>
        </main>
      </div>
    </Suspense>
  );
}
