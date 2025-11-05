"use client";
import { useState } from "react";
import { post } from "@/lib/api";

type Mode = "login" | "register";

export default function AuthModal({ open, mode, onClose, onAuthed }: {
  open: boolean;
  mode: Mode;
  onClose: () => void;
  onAuthed: (token: string) => void;
}) {
  const [current, setCurrent] = useState<Mode>(mode);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const submit = async () => {
    setLoading(true);
    setError(null);
    try {
      const path = current === "login" ? "/auth/login" : "/auth/register";
      const res = await post<{ accessToken: string }>(path, { email, password });
      onAuthed(res.accessToken);
      onClose();
    } catch (e: any) {
      setError(e.message ?? "提交失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md rounded-2xl bg-neutral-900/90 p-6 text-neutral-100 ring-1 ring-white/10">
        <div className="mb-4 flex items-center justify-between">
          <div className="flex gap-2 rounded bg-neutral-800 p-1">
            <button
              className={`rounded px-3 py-1 text-sm ${current === "login" ? "bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 text-white" : "text-neutral-200 hover:bg-white/5"}`}
              onClick={() => setCurrent("login")}
            >登录</button>
            <button
              className={`rounded px-3 py-1 text-sm ${current === "register" ? "bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 text-white" : "text-neutral-200 hover:bg-white/5"}`}
              onClick={() => setCurrent("register")}
            >注册</button>
          </div>
          <button className="rounded px-2 py-1 text-sm text-neutral-300 hover:bg-white/5" onClick={onClose}>关闭</button>
        </div>

        <div className="space-y-4">
          <div>
            <label className="block text-sm text-neutral-300">邮箱</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-800 px-3 py-2 text-sm text-neutral-100 placeholder-neutral-500 focus:outline-none focus:ring-2 focus:ring-violet-500"
              placeholder="you@example.com"
            />
          </div>
          <div>
            <label className="block text-sm text-neutral-300">密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-800 px-3 py-2 text-sm text-neutral-100 placeholder-neutral-500 focus:outline-none focus:ring-2 focus:ring-violet-500"
              placeholder="不少于 6 位"
            />
          </div>
          {error && <div className="text-sm text-red-400">{error}</div>}
          <button
            disabled={loading || !email || !password}
            onClick={submit}
            className="w-full rounded-md bg-gradient-to-r from-violet-500 via-fuchsia-500 to-violet-600 px-4 py-2 text-white disabled:opacity-50"
          >{loading ? "提交中…" : (current === "login" ? "登录" : "注册并登录")}</button>
        </div>
      </div>
    </div>
  );
}