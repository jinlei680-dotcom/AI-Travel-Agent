"use client";
import { useEffect, useState } from "react";
import { get, post, del } from "@/lib/api";

type Conversation = {
  id: string;
  title?: string | null;
  createdAt?: string | null;
};

export default function ConversationList({ onSelect }: { onSelect: (id: string | null) => void }) {
  const [items, setItems] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await get<Conversation[]>("/api/v1/conversations");
      setItems(Array.isArray(data) ? data : []);
    } catch (e: any) {
      setError(e?.message || "加载会话失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const create = async () => {
    try {
      const created = await post<Conversation>("/api/v1/conversations", { title: "新会话" });
      setItems((prev) => [created, ...prev]);
      onSelect(created.id);
    } catch (e: any) {
      setError(e?.message || "创建会话失败");
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-neutral-800">会话列表</h2>
        <button
          className="px-3 py-1.5 text-xs rounded-xl bg-blue-600 text-white hover:bg-blue-700 shadow-sm"
          onClick={create}
        >新建</button>
      </div>

      {loading && <div className="text-xs text-neutral-500">加载中...</div>}
      {error && <div className="text-xs text-red-600">{error}</div>}

      <ul className="space-y-1">
        {items.map((c) => (
          <li key={c.id} className="flex items-center gap-2">
            <button
              onClick={() => onSelect(c.id)}
              className="flex-1 text-left px-3 py-2 rounded-xl bg-white ring-1 ring-blue-100 hover:bg-blue-50"
            >
              <div className="text-sm font-medium text-neutral-800">{c.title || `会话 ${c.id.slice(0, 6)}`}</div>
              {c.createdAt && <div className="text-xs text-neutral-500">{new Date(c.createdAt).toLocaleString()}</div>}
            </button>
            <button
              title="删除会话"
              className="px-3 py-1.5 text-xs rounded-xl bg-white text-blue-700 ring-1 ring-blue-200 hover:bg-blue-50"
              onClick={async () => {
                if (!confirm(`确定删除会话：${c.title || c.id.slice(0,6)}？该会话的消息也将一并删除。`)) return;
                try {
                  await del(`/api/v1/conversations/${c.id}`);
                  setItems((prev) => prev.filter((x) => x.id !== c.id));
                  onSelect(null);
                } catch (e: any) {
                  setError(e?.message || "删除会话失败");
                }
              }}
            >删除</button>
          </li>
        ))}
        {items.length === 0 && !loading && (
          <li className="text-xs text-neutral-500">暂无会话，点击右上角“新建”创建</li>
        )}
      </ul>
    </div>
  );
}
