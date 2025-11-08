"use client";
import React from "react";

export default function EnvDebugPage() {
  const apiBase = process.env.NEXT_PUBLIC_API_URL || "";
  const amapKey = process.env.NEXT_PUBLIC_AMAP_API_KEY || "";
  const amapSec = process.env.NEXT_PUBLIC_AMAP_SECURITY_KEY || "";
  return (
    <div style={{ padding: 24, fontFamily: "system-ui, -apple-system" }}>
      <h1 style={{ fontSize: 18, fontWeight: 600 }}>前端环境变量调试</h1>
      <p style={{ color: "#666", marginTop: 8 }}>用于验证 Next.js 是否加载 .env.local 中的键值。</p>
      <div style={{ marginTop: 16, padding: 12, border: "1px solid #ddd", borderRadius: 8 }}>
        <div><strong>NEXT_PUBLIC_API_URL</strong>: {apiBase || "<未设置>"}</div>
        <div style={{ marginTop: 8 }}><strong>NEXT_PUBLIC_AMAP_API_KEY</strong>: {amapKey ? `${amapKey.substring(0, 6)}…(${amapKey.length} chars)` : "<未设置>"}</div>
        <div style={{ marginTop: 8 }}><strong>NEXT_PUBLIC_AMAP_SECURITY_KEY</strong>: {amapSec ? `${amapSec.substring(0, 6)}…(${amapSec.length} chars)` : "<未设置>"}</div>
      </div>
      <p style={{ marginTop: 12, color: "#888" }}>提示：若显示“未设置”，请确认 .env.local 的变量名与前缀 NEXT_PUBLIC_，并重启开发服务器。</p>
    </div>
  );
}

