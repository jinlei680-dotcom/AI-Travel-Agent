"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import AuthModal from "@/components/AuthModal";
import { getToken, setToken, clearToken, getEmailFromToken } from "@/lib/auth";

export default function NavBar() {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [token, setTokenState] = useState<string | null>(null);
  const [email, setEmail] = useState<string | null>(null);

  useEffect(() => {
    const t = getToken();
    setTokenState(t);
    setEmail(t ? getEmailFromToken(t) : null);
  }, []);

  const onAuthed = (t: string) => {
    setToken(t);
    setTokenState(t);
    setEmail(getEmailFromToken(t));
  };

  const logout = () => {
    clearToken();
    setTokenState(null);
    setEmail(null);
  };

  return (
    <header className="sticky top-0 z-40 bg-neutral-900/80 backdrop-blur supports-[backdrop-filter]:bg-neutral-900/60">
      <div className="mx-auto max-w-7xl px-4 py-2 flex items-center justify-between">
        <Link href="/" className="flex items-center gap-3 group cursor-pointer" title="返回首页">
          <div className="h-8 w-8 rounded bg-gradient-to-br from-violet-500 to-fuchsia-600 group-hover:opacity-90 transition" />
          <span className="text-sm font-semibold text-white group-hover:text-white/90">AI 旅行规划</span>
        </Link>
        <div className="flex items-center gap-2">
          {/* 登录后显示“我的旅游计划”入口 */}
          {!!token && (
            <Link href="/conversations" className="rounded px-3 py-1 text-sm text-white/90 hover:bg-white/10">
              我的旅游计划
            </Link>
          )}
          {!token ? (
            <>
              <button
                className="rounded px-3 py-1 text-sm text-white/90 hover:bg-white/10"
                onClick={() => { setMode("login"); setOpen(true); }}
              >登录</button>
              <button
                className="rounded px-3 py-1 text-sm text-white/90 hover:bg-white/10"
                onClick={() => { setMode("register"); setOpen(true); }}
              >注册</button>
            </>
          ) : (
            <div className="flex items-center gap-3">
              <span className="text-xs text-white/70">{email ?? "已登录"}</span>
              <button
                className="rounded px-3 py-1 text-sm text-white/90 hover:bg-white/10"
                onClick={logout}
              >注销</button>
            </div>
          )}
        </div>
      </div>
      <AuthModal open={open} mode={mode} onClose={() => setOpen(false)} onAuthed={onAuthed} />
    </header>
  );
}
