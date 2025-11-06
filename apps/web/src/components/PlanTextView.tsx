"use client";
import type { ItineraryPlan, DayPlan, Poi, Route } from "./ItineraryInput";

function renderDay(d: DayPlan, index: number) {
  const poiNames = (d.pois || []).map((p: Poi) => p.name).filter(Boolean);
  const routeLen = (d.routes || []).filter((r: Route) => !!r.polyline).length;
  return (
    <div key={index} className="space-y-2 rounded-md bg-neutral-900/40 p-3 ring-1 ring-white/10">
      <div className="text-sm font-medium text-white">第 {index + 1} 天</div>
      {d.summary && <div className="text-sm text-neutral-300">{d.summary}</div>}
      {poiNames.length > 0 && (
        <div className="text-sm text-neutral-400">
          主要地点：{poiNames.join("、")}
        </div>
      )}
      {routeLen > 0 && (
        <div className="text-xs text-neutral-500">已生成 {routeLen} 段步行/路线轨迹</div>
      )}
    </div>
  );
}

export default function PlanTextView({ plan }: { plan?: ItineraryPlan | null }) {
  const hasDays = !!plan && Array.isArray(plan?.days) && (plan?.days?.length || 0) > 0;
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="text-sm text-neutral-400">文字计划</div>
        {hasDays && (
          <div className="text-xs text-neutral-500">共 {plan!.days.length} 天</div>
        )}
      </div>
      {!hasDays && (
        <div className="rounded-md bg-neutral-900/40 p-4 text-sm text-neutral-400 ring-1 ring-white/10">
          生成后将在此显示每日摘要与主要地点。
        </div>
      )}
      {hasDays && (
        <div className="space-y-3">
          {(plan!.days || []).map((d, i) => renderDay(d, i))}
        </div>
      )}
    </div>
  );
}