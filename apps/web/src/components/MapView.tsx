"use client";
import { useEffect, useRef, useState } from "react";
import type { ItineraryPlan } from "./ItineraryInput";

declare global {
  interface Window {
    AMap: any;
    _AMapSecurityConfig?: { securityJsCode: string };
  }
}

const AMAP_KEY = process.env.NEXT_PUBLIC_AMAP_API_KEY;
const AMAP_SECURITY = process.env.NEXT_PUBLIC_AMAP_SECURITY_KEY;

async function loadAmap(): Promise<any> {
  if (typeof window === "undefined") return Promise.reject(new Error("window unavailable"));
  if ((window as any).AMap) return (window as any).AMap;
  if (!AMAP_KEY) throw new Error("未配置 NEXT_PUBLIC_AMAP_API_KEY");
  if (AMAP_SECURITY) {
    window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY };
  }
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${AMAP_KEY}&plugin=AMap.Driving,AMap.PlaceSearch`;
    script.async = true;
    script.onload = () => resolve((window as any).AMap);
    script.onerror = () => reject(new Error("AMap JS 加载失败"));
    document.head.appendChild(script);
  });
}

export default function MapView({ plan }: { plan?: ItineraryPlan }) {
  const mapEl = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const overlaysRef = useRef<any[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const defaultCenter: [number, number] = [116.397477, 39.908692]; // 北京市中心附近

  useEffect(() => {
    let disposed = false;
    loadAmap()
      .then((AMap) => {
        if (disposed || !mapEl.current) return;
        const map = new AMap.Map(mapEl.current, {
          viewMode: "2D",
          zoom: 12,
          center: defaultCenter,
        });
        mapRef.current = map;
        setReady(true);
      })
      .catch((e: any) => setError(e?.message || String(e)));
    return () => {
      disposed = true;
      try { mapRef.current?.destroy?.(); } catch {}
    };
  }, []);

  // 渲染行程：清理旧图层，绘制 polyline 与 marker，并尽量自适应视图
  useEffect(() => {
    if (!ready || !mapRef.current) return;
    const map = mapRef.current;
    // 清理旧覆盖物
    overlaysRef.current.forEach((ov) => {
      try { map.remove(ov); } catch {}
    });
    overlaysRef.current = [];

    if (!plan || !plan.days || plan.days.length === 0) {
      // 无计划时，使用默认/城市中心
      const center = plan?.cityCenter ?? defaultCenter;
      try { map.setZoomAndCenter(12, center); } catch {}
      return;
    }

    const day = plan.days[0]; // 初版渲染第1天，后续支持切换
    const created: any[] = [];
    const AMap = (window as any).AMap;
    // routes
    for (const r of day.routes || []) {
      if (!r.polyline) continue;
      // 期望格式："lng,lat;lng,lat;..."；AMap v2 的 Polyline 需要 [lng, lat] 数组
      const parts = r.polyline
        .split(";")
        .map((p) => p.split(",").map(Number))
        .filter((xy) => xy.length === 2);
      const path = parts
        .filter((xy) => Number.isFinite(xy[0]) && Number.isFinite(xy[1]))
        .map((xy) => [xy[0], xy[1]] as [number, number]);
      if (path.length < 2) continue; // 少于两点不绘制折线，避免 Polyline path 报错
      const pl = new AMap.Polyline({
        path,
        isOutline: true,
        outlineColor: "#000000",
        borderWeight: 1,
        strokeColor: r.color || "#3b82f6",
        strokeOpacity: 0.9,
        strokeWeight: 4,
        lineJoin: "round",
        lineCap: "round",
      });
      map.add(pl);
      created.push(pl);
    }
    // pois
    for (const poi of day.pois || []) {
      const mk = new AMap.Marker({ position: poi.coord, title: poi.name });
      map.add(mk);
      created.push(mk);
    }
    overlaysRef.current = created;
    if (created.length > 0) {
      try { map.setFitView(created, true, [60, 60, 60, 60], 14); } catch {}
    } else {
      const center = plan.cityCenter ?? defaultCenter;
      try { map.setZoomAndCenter(12, center); } catch {}
    }
  }, [plan, ready]);
  return (
    <div>
      <div ref={mapEl} className="w-full rounded-lg bg-neutral-800 h-[420px] md:h-[520px]" />
    </div>
  );
}