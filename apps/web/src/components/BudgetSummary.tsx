"use client";
import React, { useMemo, useState } from "react";
import type { Budget } from "./ItineraryInput";
import type { BudgetItem } from "@/lib/budget";

export default function BudgetSummary({ budget, breakdown, label }: { budget?: Budget; breakdown?: BudgetItem[]; label?: string }) {
  const [showDetail, setShowDetail] = useState(false);
  const amount = budget?.amount;
  const currency = budget?.currency || "CNY";
  const hasBudget = typeof amount === "number" && isFinite(amount);
  const items = Array.isArray(breakdown) ? breakdown : [];
  const displayItems = useMemo(() => items || [], [items]);
  // 合计规则：
  // - 若存在类别总额（如“住宿/餐饮/交通/门票”），细项默认不计入合计，防止重复
  // - 若不存在该类别总额，则计入细项金额
  const normalize = (label: string): string => (label || "").replace(/^[\-\*•\s]+/, "").trim();
  const classify = (label: string): "lodging" | "restaurant" | "transport" | "ticket" | "other" => {
    const s = normalize(label);
    if (/住宿|酒店|民宿|宾馆/.test(s)) return "lodging";
    if (/餐饮|餐厅|饭店|酒楼|火锅|小吃|美食|咖啡|面馆|拉面|牛肉面|烤肉|食堂/.test(s)) return "restaurant";
    if (/交通|地铁|高铁|火车|航班|机票|出租车|打车|巴士|公交/.test(s)) return "transport";
    if (/门票|票/.test(s)) return "ticket";
    return "other";
  };
  const groupLabel = (g: ReturnType<typeof classify>): string => (
    g === "lodging" ? "住宿" : g === "restaurant" ? "餐饮" : g === "transport" ? "交通" : "门票"
  );
  const hasCategoryLodging = displayItems.some(it => normalize(it.label) === "住宿");
  const hasCategoryDining = displayItems.some(it => normalize(it.label) === "餐饮" || normalize(it.label) === "餐饮（合计）");
  const hasCategoryTransport = displayItems.some(it => /^交通/.test(normalize(it.label)) || normalize(it.label) === "市内交通");
  const hasCategoryTicket = displayItems.some(it => normalize(it.label) === "门票");
  const computedTotal = displayItems.reduce((sum, it) => {
    const lbl = normalize(it.label);
    if (/（第[0-9]+天）/.test(lbl)) return sum; // 已按天展开的餐饮不在此重复
    const cat = classify(lbl);
    if (cat === "lodging" && hasCategoryLodging && lbl !== "住宿") return sum;
    if (cat === "restaurant" && hasCategoryDining && lbl !== "餐饮" && lbl !== "餐饮（合计）") return sum;
    if (cat === "transport" && hasCategoryTransport && !/^交通/.test(lbl) && lbl !== "市内交通") return sum;
    if (cat === "ticket" && hasCategoryTicket && lbl !== "门票") return sum;
    return sum + (it.amount || 0);
  }, 0);

  // 分组展示：将 displayItems 按类别分块
  const grouped = useMemo(() => {
    const map: Record<"lodging" | "restaurant" | "transport" | "ticket", BudgetItem[]> = { lodging: [], restaurant: [], transport: [], ticket: [] };
    for (const it of displayItems) {
      const key = classify(it.label);
      if (key === "lodging" || key === "restaurant" || key === "transport" || key === "ticket") {
        map[key].push(it);
      }
    }
    return map;
  }, [displayItems]);
  const overBudget = hasBudget && computedTotal > (amount || 0);
  const remaining = hasBudget ? Math.max(0, (amount || 0) - computedTotal) : null;
  const titleLabel = label || "总预算";
  const badgeText = titleLabel === "预计支出合计" ? "结构化明细" : "模型提供";
  return (
    <div className="rounded-xl bg-gradient-to-br from-amber-50 to-orange-50 ring-1 ring-amber-200 p-3">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-sm font-medium text-amber-900">{titleLabel}</h3>
        <span className="text-xxs text-amber-700">{badgeText}</span>
      </div>
      {hasBudget ? (
        <div className="flex items-baseline gap-2">
          <div className="text-xl font-semibold text-amber-900">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(amount!)}</div>
          <div className="text-xs text-amber-800">{currency}</div>
        </div>
      ) : (
        <div className="text-xs text-amber-800">暂未提供预算</div>
      )}
      {displayItems.length > 0 && (
        <div className="mt-2 rounded-lg bg-white/60 ring-1 ring-amber-200 p-2">
          <div className="flex items-center justify-between mb-1">
            <div className="text-xxs font-medium text-amber-900">预算明细</div>
            <button
              type="button"
              className="text-xxs px-2 py-0.5 rounded-md text-amber-800 hover:bg-amber-100"
              onClick={() => setShowDetail(v => !v)}
            >{showDetail ? "收起" : "展开"}</button>
          </div>
          <div className="space-y-2 max-h-[360px] overflow-y-auto">
            {showDetail ? (
              (["lodging","restaurant","transport","ticket"] as const).map((key) => (
                grouped[key].length > 0 ? (
                  (() => {
                    const headerLabels: Record<typeof key, string[]> = {
                      lodging: ["住宿"],
                      restaurant: ["餐饮", "餐饮（合计）"],
                      transport: ["交通"],
                      ticket: ["门票"],
                    } as any;
                    const headerTargets = headerLabels[key].map(normalize);
                    const summaryItem = grouped[key].find(it => headerTargets.includes(normalize(it.label)));
                    const cur = summaryItem?.currency ?? (grouped[key][0]?.currency || currency);
                    const amt = summaryItem?.amount ?? grouped[key].reduce((s, it) => s + (it.amount || 0), 0);
                    const details = grouped[key].filter(it => !headerTargets.includes(normalize(it.label)));
                    return (
                      <div key={key}>
                        <div className="flex items-center justify-between mb-1">
                          <span className="text-sm font-semibold text-amber-900">{groupLabel(key)}</span>
                          <span className="text-xs text-amber-800 whitespace-nowrap">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(amt)} {cur} (总计)</span>
                        </div>
                        {details.length > 0 && (
                          <ul className="space-y-0.5">
                            {details.map((it, idx) => (
                              <li key={idx} className="flex items-center justify-between text-xs leading-tight">
                                <span className="text-amber-900 truncate">{normalize(it.label)}</span>
                                <span className="text-amber-800 whitespace-nowrap">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(it.amount)} {it.currency}{it.basis === "per_person" ? " (人均)" : " (总计)"}</span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    );
                  })()
                ) : null
              ))
            ) : (
              (["lodging","restaurant","transport","ticket"] as const).map((key) => (
                grouped[key].length > 0 ? (
                  (() => {
                    const headerLabels: Record<typeof key, string[]> = {
                      lodging: ["住宿"],
                      restaurant: ["餐饮", "餐饮（合计）"],
                      transport: ["交通"],
                      ticket: ["门票"],
                    } as any;
                    const headerTargets = headerLabels[key].map(normalize);
                    const summaryItem = grouped[key].find(it => headerTargets.includes(normalize(it.label)));
                    const amt = summaryItem?.amount ?? grouped[key].reduce((s, it) => s + (it.amount || 0), 0);
                    const cur = summaryItem?.currency ?? (grouped[key][0]?.currency || currency);
                    return (
                      <div key={key} className="flex items-center justify-between text-xs leading-tight">
                        <span className="text-amber-900 truncate">{groupLabel(key)}</span>
                        <span className="text-amber-800 whitespace-nowrap">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(amt)} {cur} (总计)</span>
                      </div>
                    );
                  })()
                ) : null
              ))
            )}
          </div>
          {hasBudget && titleLabel !== "预计支出合计" && (
            <div className="mt-1">
              {overBudget ? (
                <div className="text-right text-red-700">
                  <span className="text-[11px]">预计支出合计</span>
                  <span className="text-[12px] font-medium">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(computedTotal)} {currency}</span>
                  <span className="text-[11px]">，超出</span>
                  <span className="text-[12px] font-medium">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(computedTotal - (amount || 0))} {currency}</span>
                </div>
              ) : (
                <div className="text-right text-amber-700">
                  <span className="text-[11px]">预计支出合计</span>
                  <span className="text-[12px] font-medium">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(computedTotal)} {currency}</span>
                  {typeof remaining === "number" ? (
                    <>
                      <span className="text-[11px]">，剩余</span>
                      <span className="text-[12px] font-medium">{Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(remaining)} {currency}</span>
                    </>
                  ) : null}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}