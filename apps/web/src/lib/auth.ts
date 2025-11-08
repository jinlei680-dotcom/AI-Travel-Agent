export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  try { return localStorage.getItem("accessToken"); } catch { return null; }
}

export function setToken(token: string) {
  if (typeof window === "undefined") return;
  try { localStorage.setItem("accessToken", token); } catch {}
}

export function clearToken() {
  if (typeof window === "undefined") return;
  try { localStorage.removeItem("accessToken"); } catch {}
}

export function getEmailFromToken(token: string | null): string | null {
  if (!token) return null;
  try {
    const parts = token.split(".");
    if (parts.length < 2) return null;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
    return payload?.email ?? null;
  } catch { return null; }
}

