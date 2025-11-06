"use client";
import type { ItineraryPlan, DayPlan, Poi } from "./ItineraryInput";

function toSingleBlockText(plan: ItineraryPlan): string {
  const days = plan.days || [];
  if (days.length === 0) return "生成后将在此显示行程文字摘要。";
  const lines: string[] = [];
  lines.push(`共 ${days.length} 天行程`);
  days.forEach((d: DayPlan, i: number) => {
    const title = `第 ${i + 1} 天`;
    const summary = d.summary ? `：${d.summary}` : "";
    const poiNames = (d.pois || []).map((p: Poi) => p.name).filter(Boolean);
    const poiLine = poiNames.length > 0 ? `\n主要地点：${poiNames.join("、")}` : "";
    lines.push(`${title}${summary}${poiLine}`);
  });
  return lines.join("\n\n");
}

export default function PlanTextView({ plan }: { plan?: ItineraryPlan | null }) {
  const hasDays = !!plan && Array.isArray(plan?.days) && (plan?.days?.length || 0) > 0;
  const text = plan ? toSingleBlockText(plan) : "生成后将在此显示行程文字摘要。";
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="text-sm text-neutral-400">旅游计划</div>
        {hasDays && (
          <div className="text-xs text-neutral-500">共 {plan!.days.length} 天</div>
        )}
      </div>
      <div className="rounded-md bg-neutral-900/40 p-4 ring-1 ring-white/10">
        <div className="whitespace-pre-wrap text-sm text-neutral-300">{text}</div>
      </div>
    </div>
  );
}