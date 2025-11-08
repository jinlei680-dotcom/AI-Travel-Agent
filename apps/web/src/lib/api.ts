// 兼容环境变量为空字符串的情况；为空则回退到后端默认地址
const ENV_BASE = (process.env.NEXT_PUBLIC_API_URL || "").trim();
export const API_BASE = ENV_BASE.length > 0 ? ENV_BASE : "http://localhost:8080";

import { getToken } from "./auth";

export async function post<T>(path: string, body: unknown, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    },
    body: JSON.stringify(body),
    mode: "cors",
    credentials: "omit",
    cache: "no-store",
    ...init,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = (data && (data.error || data.message)) ?? `请求失败 (${res.status})`;
    throw new Error(msg);
  }
  return data as T;
}

export async function get<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    mode: "cors",
    credentials: "omit",
    cache: "no-store",
    headers: {
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
      ...(init?.headers || {}),
    },
    ...init,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = (data && (data.error || data.message)) ?? `请求失败 (${res.status})`;
    throw new Error(msg);
  }
  return data as T;
}

export async function del<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "DELETE",
    mode: "cors",
    credentials: "omit",
    cache: "no-store",
    headers: {
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
      ...(init?.headers || {}),
    },
    ...init,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = (data && (data.error || data.message)) ?? `请求失败 (${res.status})`;
    throw new Error(msg);
  }
  return data as T;
}
