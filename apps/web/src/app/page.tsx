"use client";
import { useEffect, useRef, useState } from "react";
import MapView from "@/components/MapView";
import HeroModule from "@/components/HeroModule";
import ItineraryInput, { type ItineraryPlan, type BackendBudgetBreakdown } from "@/components/ItineraryInput";
// 移除文本展示组件，左侧卡片改为规划输入
import VisualPlanView from "@/components/VisualPlanView";
import NavBar from "@/components/NavBar";
import { get, post } from "@/lib/api";
import BudgetSummary from "@/components/BudgetSummary";
import { parseBudgetItemsFromRaw, formatBudgetItemsMarkdown, parseTotalBudgetFromRaw, expandDiningByDay, parseChineseNum } from "@/lib/budget";

export default function Home() {
  const [plan, setPlan] = useState<ItineraryPlan | null>(null);
  const [userText, setUserText] = useState<string>("");
  const [rawText, setRawText] = useState<string>("");
  const [daily, setDaily] = useState<any[]>([]);
  const [bindStatus, setBindStatus] = useState<string>("");
  const [bindError, setBindError] = useState<string>("");
  const [convId, setConvId] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [usedText, setUsedText] = useState<string>("");
  const [parsedUsed, setParsedUsed] = useState<{ amount?: number; currency?: string; dayIndex?: number } | null>(null);
  const [adjustStatus, setAdjustStatus] = useState<string>("");
  const [adjustError, setAdjustError] = useState<string>("");
  const planRef = useRef<ItineraryPlan | null>(null);
  const rawRef = useRef<string>("");
  const rightCardRef = useRef<HTMLDivElement | null>(null);
  const [rightCardHeight, setRightCardHeight] = useState<number>(520);
  const rawTextFromState = (rawRef.current || (plan as any)?.rawText || "");
  const breakdownItemsRaw = parseBudgetItemsFromRaw(rawTextFromState);
  const breakdownItems = expandDiningByDay(breakdownItemsRaw, rawTextFromState);
  const parsedTotal = parseTotalBudgetFromRaw(rawTextFromState);
  const [backendBudget, setBackendBudget] = useState<BackendBudgetBreakdown | null>(null);
  const [backendBudgetAligned, setBackendBudgetAligned] = useState<boolean | null>(null);

  // 将后端结构化预算转换为前端概览组件可展示的条目
  const backendBreakdownItems = (() => {
    const items: any[] = [];
    try {
      if (backendBudget && Array.isArray(backendBudget.categories)) {
        for (const cat of backendBudget.categories) {
          const cur = cat.currency || backendBudget.currency || "CNY";
          const total = typeof cat.total === "number" && isFinite(cat.total)
            ? cat.total
            : (Array.isArray(cat.items) ? cat.items.reduce((s, it) => s + (Number(it.amount || 0)), 0) : 0);
          // 分类小计（例如：住宿/餐饮/交通/门票）
          if (cat.name) {
            items.push({ label: String(cat.name), amount: total, currency: cur });
          }
          // 分类内的条目
          if (Array.isArray(cat.items)) {
            for (const it of cat.items) {
              items.push({ label: String(it.name || "-"), amount: Number(it.amount || 0), currency: String(it.currency || cur) });
            }
          }
        }
      }
    } catch {}
    return items;
  })();

  // 提取后端原文中的预算段落用于展示
  const extractBudgetSection = (raw: string): string => {
    const text = (raw || "").trim();
    if (!text) return "";
    const lines = text.split(/\n/);
    const isHeading = (l: string) => /^\s*#{1,6}\s/.test(l);
    let start = lines.findIndex((l) => /^\s*#{1,6}\s*预算\b/.test(l));
    if (start < 0) start = lines.findIndex((l) => /预算依据与汇总/.test(l));
    if (start >= 0) {
      const end = (() => {
        for (let i = start + 1; i < Math.min(lines.length, start + 40); i++) {
          if (isHeading(lines[i])) return i;
        }
        return Math.min(lines.length, start + 40);
      })();
      return lines.slice(start, end).join("\n");
    }
    // 回退：围绕“合计：xxx 货币”截取上下文
    const totalIdx = lines.findIndex((l) => /合计\s*[:：]\s*[0-9]+/.test(l));
    if (totalIdx >= 0) {
      return lines.slice(Math.max(0, totalIdx - 10), Math.min(lines.length, totalIdx + 10)).join("\n");
    }
    return "";
  };

  // 真实测试：不注入任何示例原文，保持为空，待用户或服务端返回填充

  useEffect(() => {
    const el = rightCardRef.current;
    if (!el || typeof window === "undefined") return;
    const measure = () => {
      const h = el.offsetHeight;
      if (Number.isFinite(h) && h > 0) setRightCardHeight(h);
    };
    measure();
    let ro: ResizeObserver | null = null;
    try {
      ro = new ResizeObserver(() => measure());
      ro.observe(el);
    } catch {}
    const onResize = () => measure();
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      try { ro?.disconnect(); } catch {}
    };
  }, []);

  const toDateStr = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const inferCity = (text: string): string | null => {
    try {
      const cities = ["北京","上海","杭州","广州","深圳","成都","重庆","西安","南京","苏州","厦门","青岛","天津","武汉","长春","沈阳","大连","合肥","济南","昆明","拉萨","三亚","海口"];
      for (const c of cities) { if (text.includes(c)) return c; }
      const m = text.match(/([\u4e00-\u9fa5]{2,})[市省]/);
      if (m && m[1]) return m[1];
      return null;
    } catch { return null; }
  };
  // 自然语言解析：已用预算（金额/币种/第X天）
  function parseUsedBudgetFromText(text: string): { amount?: number; currency?: string; dayIndex?: number } {
    const t = (text || "").trim();
    if (!t) return {};
    let dayIndex: number | undefined = undefined;
    const dayMatch = t.match(/第([一二三四五六七八九十百]+|\d+)天/);
    if (dayMatch) {
      const raw = dayMatch[1];
      const num = /\d+/.test(raw) ? Number(raw) : parseChineseNum(raw) || undefined;
      dayIndex = typeof num === "number" && isFinite(num) ? num : undefined;
    }
    let currency: string | undefined = undefined;
    if (/人民币|元|块|CNY/i.test(t)) currency = "CNY";
    else if (/美元|美金|\bUSD\b|\$/i.test(t)) currency = "USD";
    else if (/欧元|\bEUR\b|€/i.test(t)) currency = "EUR";
    else if (/日元|\bJPY\b|¥/i.test(t)) currency = "JPY";
    let amount: number | undefined = undefined;
    const numMatch = t.match(/(\d+[\.,]?\d*)\s*(元|块|人民币|CNY|USD|EUR|JPY|\$|€|¥)?/);
    if (numMatch) {
      amount = Number(numMatch[1].replace(/[,]/g, ""));
    } else {
      const cnMatch = t.match(/([一二三四五六七八九十百千万亿]+)\s*(元|块|人民币)?/);
      if (cnMatch) {
        const val = parseChineseNum(cnMatch[1]);
        if (typeof val === "number" && isFinite(val)) amount = val;
      }
    }
    return { amount, currency, dayIndex };
  }

  const adjustBudget = async () => {
    setAdjustError("");
    setAdjustStatus("");
    if (!convId) {
      setAdjustError("尚未绑定到会话，无法调整预算。");
      return;
    }
    const parsed = parseUsedBudgetFromText(usedText);
    setParsedUsed(parsed);
    const amt = parsed?.amount;
    const cur = parsed?.currency || "CNY";
    if (!(typeof amt === "number" && isFinite(amt) && amt > 0)) {
      setAdjustError("请用自然语言输入已用预算，例如：第一天我已经使用了2000元。");
      return;
    }
    try {
      setAdjustStatus("正在根据已用预算调整后续行程...");
      try { await post(`/api/v1/conversations/${convId}/messages`, { role: "user", content: `已用预算：${usedText}` }); } catch {}
      const res = await post<{ plan: ItineraryPlan; rawText?: string }>(`/api/v1/conversations/${convId}/budget-adjust`, {
        usedAmount: amt,
        currency: cur,
      });
      const newPlan = (res as any)?.plan ?? (res as any);
      if (newPlan) {
        setPlan(newPlan as ItineraryPlan);
        planRef.current = newPlan as ItineraryPlan;
      }
      const newRaw = (res as any)?.rawText;
      if (typeof newRaw === "string") {
        setRawText(newRaw);
        rawRef.current = newRaw;
      }
      setAdjustStatus("已根据已用预算调整后续行程。");
      setTimeout(() => setAdjustStatus(""), 4000);
    } catch (e: any) {
      setAdjustError(e?.message || "调整失败，请稍后重试。");
    }
  };
  const planToText = (p: ItineraryPlan): string => {
    const days = p.days || [];
    if (days.length === 0) return "暂未生成行程。";
    const parts: string[] = [];
    parts.push(`共 ${days.length} 天行程`);
    days.forEach((d, i) => {
      const title = `第 ${i + 1} 天`;
      const summary = d.summary ? `：${d.summary}` : "";
      const pois = (d.pois || []).map((pp) => pp.name).filter(Boolean) as string[];
      const poiLine = pois.length > 0 ? `\n主要地点：${pois.join("、")}` : "";
      parts.push(`${title}${summary}${poiLine}`);
    });
    return parts.join("\n");
  };

  const bindPlanToConversation = async (p: ItineraryPlan, assistantRaw?: string) => {
    setBindError("");
    setBindStatus("正在绑定到会话...");
    try {
      // 计算日期范围
      const days = Array.isArray(p.days) ? p.days.length : 1;
      const start = new Date();
      const end = new Date(start.getTime() + Math.max(0, days - 1) * 24 * 3600 * 1000);

      // 每次规划均新建会话
      let cid: string | null = null;
      try {
        const destination = inferCity(userText) || "首页会话";
        const title = `${destination} 行程会话（${toDateStr(start)} 至 ${toDateStr(end)}）`;
        const created = await post<any>("/api/v1/conversations", { title });
        cid = created?.id ?? null;
      } catch (e: any) {
        const msg = e?.message || "未登录或会话接口不可用，行程未绑定。";
        setBindError(msg);
        setBindStatus("");
        return;
      }
      if (!cid) {
        setBindError("创建会话失败，无法绑定行程。");
        setBindStatus("");
        return;
      }
      setConvId(cid);
      setConversationId(cid);

      // 追加消息：用户输入和行程摘要
      if (userText && userText.trim()) {
        try { await post(`/api/v1/conversations/${cid}/messages`, { role: "user", content: userText }); } catch {}
      }
      // 修改：只写入原文 rawText，不再使用摘要作为回退
      const assistantText = (assistantRaw && assistantRaw.trim())
        ? assistantRaw
        : (rawRef.current && rawRef.current.trim())
          ? rawRef.current
          : "";
      try { await post(`/api/v1/conversations/${cid}/messages`, { role: "assistant", content: assistantText }); } catch {}
      // 追加预算明细（从原文解析）
      try {
        const parsed = parseBudgetItemsFromRaw(assistantText || rawRef.current || "");
        const items = expandDiningByDay(parsed, assistantText || rawRef.current || "");
        if (items && items.length > 0) {
          const md = formatBudgetItemsMarkdown(items);
          await post(`/api/v1/conversations/${cid}/messages`, { role: "assistant", content: md });
        }
      } catch {}

      // 绑定行程到会话
      const destination = inferCity(userText) || "未命名目的地";
      const createdPlan = await post<any>("/api/v1/plans/create-with-conversation", {
        destination,
        startDate: toDateStr(start),
        endDate: toDateStr(end),
        budgetAmount: null,
        budgetCurrency: "CNY",
        tags: [],
        conversationId: cid,
      });
      const pid = createdPlan?.id;
      setBindStatus(pid ? `行程已创建并绑定到会话（ID：${pid}）` : "行程已创建并绑定到会话。");
    } catch (e: any) {
      setBindError(e?.message || "行程绑定失败，请确认已登录并有权限。");
      setBindStatus("");
    }
  };

  return (
    <div className="min-h-screen text-neutral-200">
      <NavBar />

      {/* 主内容 */}
      <main className="pb-0 pt-0">
        {/* 首页大字宣言 + 行程需求输入栏 */}
        <HeroModule>
          <ItineraryInput
            onPlanned={(p) => { setPlan(p); planRef.current = p; }}
            onUserSubmit={(t) => setUserText(t)}
            onRawText={(txt, phase) => {
              if (phase === "final") {
                rawRef.current = txt;
                setRawText(txt);
                const p = planRef.current;
                if (p) { bindPlanToConversation(p, txt); }
              } else if (!rawText) {
                rawRef.current = txt;
                setRawText(txt);
              }
          }}
          onDaily={(days) => setDaily(days as any[])}
          onBudget={(bd, aligned) => { setBackendBudget(bd || null); setBackendBudgetAligned(typeof aligned === "boolean" ? aligned : null); }}
          hideLabel={true}
          rows={2}
          />
        </HeroModule>
        <section
          id="app"
          className="relative min-h-screen bg-luxe text-neutral-800"
        >
          <div className="relative z-10 container-wide space-y-6 py-8">
          {(bindStatus || bindError) && (
            <div className={`text-xs rounded-md px-3 py-2 ${bindError ? "bg-red-50 text-red-700 ring-1 ring-red-200" : "bg-green-50 text-green-700 ring-1 ring-green-200"}`}>
              {bindError || bindStatus}
              {convId && (
                <span className="ml-2 text-violet-700">已绑定到会话</span>
              )}
            </div>
          )}
          {/* 预算展示与调整 */}
          <section className="max-w-7xl mx-auto px-6">
  <div className="rounded-2xl p-6 bg-white/60 ring-1 ring-neutral-200">
    <div className="flex items-center justify-between mb-4">
      <h3 className="text-base font-semibold tracking-tight text-neutral-900">预算与会话</h3>
      <div className="text-xs text-neutral-500">绑定后可调整后续行程</div>
    </div>
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
      <div className="sm:col-span-1">
        <div className="text-sm text-neutral-600 mb-1">预算概览</div>
        <div className="rounded-lg bg-amber-50 ring-1 ring-amber-200 p-4">
          <BudgetSummary
            budget={backendBudget?.grandTotal ? ({ amount: backendBudget!.grandTotal, currency: backendBudget!.currency || "CNY" } as any) : undefined}
            breakdown={(backendBreakdownItems && backendBreakdownItems.length > 0) ? backendBreakdownItems : breakdownItems}
            label="预计支出合计"
          />
        </div>
      </div>
      <div className="sm:col-span-2">
        <div className="text-sm text-neutral-600 mb-1">根据已用预算调整</div>
        <div className="rounded-lg bg-white/60 ring-1 ring-neutral-200 p-4">
          <div>
            <input
              type="text"
              value={usedText}
              onChange={(e) => {
                setUsedText(e.target.value);
                const p = parseUsedBudgetFromText(e.target.value);
                setParsedUsed(p);
              }}
              placeholder="例如：第一天我已经使用了2000元"
              className="w-full rounded-md ring-1 ring-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-violet-300"
            />
            <div className="mt-1 text-xs text-neutral-600">
              {parsedUsed?.amount ? (
                <span>
                  解析为：{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(parsedUsed.amount)} {parsedUsed.currency || "CNY"}
                  {parsedUsed?.dayIndex ? `；第 ${parsedUsed.dayIndex} 天` : ""}
                </span>
              ) : (
                <span>请用自然语言输入：如“第一天我已经使用了2000元”。</span>
              )}
            </div>
            <div className="mt-3">
              <button onClick={adjustBudget} disabled={!convId} className="rounded-md bg-violet-600 text-white px-4 py-2 text-sm hover:bg-violet-700 disabled:opacity-50">调整行程</button>
            </div>
            <div className="mt-2 text-xs">
              {adjustError && <span className="text-red-600">{adjustError}</span>}
              {adjustStatus && <span className="text-green-700">{adjustStatus}</span>}
            </div>
            {/* 详细金额展示（根据原文预算明细） */}
            <div className="mt-4">
              <div className="text-sm font-medium text-neutral-800 mb-2">详细金额</div>
              {backendBudget && Array.isArray(backendBudget.categories) && backendBudget.categories.length > 0 ? (
                <div>
                  <div className="flex items-center justify-between text-xs mb-1">
                    <span className="text-neutral-700">合计</span>
                    <span className="text-neutral-800 whitespace-nowrap">
                      {Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Number(backendBudget.grandTotal || 0))} {String(backendBudget.currency || "CNY")}
                    </span>
                  </div>
                  <ul className="space-y-2 max-h-[260px] overflow-y-auto">
                    {backendBudget.categories.map((cat, idx) => {
                      const catTotal = typeof cat.total === "number" && isFinite(cat.total)
                        ? cat.total
                        : (Array.isArray(cat.items) ? cat.items.reduce((s, it) => s + (Number(it.amount || 0)), 0) : 0);
                      const catCurrency = cat.currency || backendBudget.currency || "CNY";
                      return (
                        <li key={idx}>
                          <div className="flex items-center justify-between text-xs">
                            <span className="font-medium text-neutral-800 truncate">{String(cat.name || "-")}</span>
                            <span className="text-neutral-700 whitespace-nowrap">
                              {Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(catTotal)} {String(catCurrency)} (总计)
                            </span>
                          </div>
                          {Array.isArray(cat.items) && cat.items.length > 0 && (
                            <ul className="mt-1 space-y-0.5">
                              {cat.items.map((it, j) => (
                                <li key={j} className="flex items-center justify-between text-xs leading-tight">
                                  <span className="text-neutral-800 truncate">{String(it.name || "-")}</span>
                                  <span className="text-neutral-700 whitespace-nowrap">
                                    {Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Number(it.amount || 0))} {String(it.currency || catCurrency)}
                                  </span>
                                </li>
                              ))}
                            </ul>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                  {typeof backendBudgetAligned === "boolean" && (
                    <div className={`mt-1 text-xxs ${backendBudgetAligned ? "text-green-700" : "text-neutral-500"}`}>
                      {backendBudgetAligned ? "已对齐并规范化币种与结构" : "币种或结构未完全对齐"}
                    </div>
                  )}
                </div>
              ) : (
                Array.isArray(breakdownItems) && breakdownItems.length > 0 ? (
                  <ul className="space-y-1 max-h-[260px] overflow-y-auto">
                    {breakdownItems.map((it: any, idx: number) => (
                      <li key={idx} className="flex items-center justify-between text-xs leading-tight">
                        <span className="text-neutral-800 truncate">{String(it.label || "-")}</span>
                        <span className="text-neutral-700 whitespace-nowrap">
                          {Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Number(it.amount || 0))} {String(it.currency || parsedTotal.currency || "CNY")}
                          {it.basis === "per_person" ? " (人均)" : " (总计)"}
                        </span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <div className="text-xs text-neutral-500">暂无可用的预算明细</div>
                )
              )}
            </div>
            {/* 已移除：后端预算原文与数据、后端结构化预算（完整）面板 */}
          </div>
        </div>
      </div>
    </div>
  </div>
</section>
          {/* 计划输入模块已移至首页首屏 */}

          {/* 文本与地图两列（不透明卡片，视口全宽） */}
  <section className="grid grid-cols-12 gap-8 max-w-7xl mx-auto px-6">
  <div className="col-span-12 md:col-span-5 rounded-2xl bg-white/60 ring-1 ring-neutral-200 p-7 flex flex-col" style={{ height: rightCardHeight }}>
    <div className="flex items-center justify-between mb-4">
      <h2 className="text-base font-semibold tracking-tight text-neutral-900">行程要点</h2>
    </div>
    <div className="flex-1 h-full min-h-0 overflow-y-auto pr-1 rounded-md bg-white/60 ring-1 ring-neutral-200">
      <VisualPlanView plan={plan ?? undefined} rawText={rawText} daily={daily as any[]} variant="icons" />
    </div>
  </div>
  <div ref={rightCardRef} className="col-span-12 md:col-span-7 rounded-2xl bg-white/60 ring-1 ring-neutral-200 p-7">
    <div className="flex items-center justify-between mb-4">
      <h2 className="text-base font-semibold tracking-tight text-neutral-900">地图预览</h2>
      <div className="text-xs text-neutral-500">可交互</div>
    </div>
    <div className="rounded-md bg-white/60 ring-1 ring-neutral-200">
      <MapView plan={plan ?? undefined} userText={userText} />
    </div>
  </div>
</section>
          {/* 会话列表与聊天面板已迁移至独立会话页（通过导航进入） */}
          </div>
        </section>
      </main>
    </div>
  );
}
