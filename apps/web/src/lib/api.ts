export const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = (data && (data.error || data.message)) ?? `请求失败 (${res.status})`;
    throw new Error(msg);
  }
  return data as T;
}

export async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = (data && (data.error || data.message)) ?? `请求失败 (${res.status})`;
    throw new Error(msg);
  }
  return data as T;
}