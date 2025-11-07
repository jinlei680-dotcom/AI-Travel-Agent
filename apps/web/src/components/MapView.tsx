"use client";
import { useEffect, useRef, useState } from "react";
import type { ItineraryPlan, Route } from "./ItineraryInput";
import { get, post } from "@/lib/api";

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
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${AMAP_KEY}&plugin=AMap.Driving,AMap.PlaceSearch,AMap.Geocoder,AMap.Transfer,AMap.Walking,AMap.Riding`;
    script.async = true;
    script.onload = () => resolve((window as any).AMap);
    script.onerror = () => reject(new Error("AMap JS 加载失败"));
    document.head.appendChild(script);
  });
}

export default function MapView({ plan, userText }: { plan?: ItineraryPlan; userText?: string }) {
  const mapEl = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const overlaysRef = useRef<any[]>([]);
  const geocoderRef = useRef<any>(null);
  const addressCacheRef = useRef<Record<string, string>>({});
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dayIndex, setDayIndex] = useState(0);
  const [fetching, setFetching] = useState(false);
  const [overrideRoutes, setOverrideRoutes] = useState<Record<number, Route[]>>({});
  const [routeMode, setRouteMode] = useState<"drive"|"walk"|"ride"|"transit">("drive");
  const [showAlternatives, setShowAlternatives] = useState<boolean>(true);
  const [routeStats, setRouteStats] = useState<{ distanceMeters: number; durationSec: number } | null>(null);
  const [onlyOneSegment, setOnlyOneSegment] = useState<boolean>(false);
  const [selectedSegmentIndex, setSelectedSegmentIndex] = useState<number>(0);
  const reqIdRef = useRef<number>(0);
  const fetchTimeoutRef = useRef<any>(null);
  const defaultCenter: [number, number] = [116.397477, 39.908692]; // 北京市中心附近
  const refinedPoisRef = useRef<Record<number, { name: string; coord: [number, number]; address?: string; type?: string; city?: string }[]>>({});
  const segmentsByDayRef = useRef<Record<number, Route[][]>>({});

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
        try { geocoderRef.current = new AMap.Geocoder(); } catch {}
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
    const safeIndex = Math.min(Math.max(dayIndex, 0), plan.days.length - 1);
    const day = plan.days[safeIndex];
    const created: any[] = [];
    const AMap = (window as any).AMap;
    let routesToDraw: Route[] = [];
    const hasSegments = segmentsByDayRef.current[safeIndex] && segmentsByDayRef.current[safeIndex].length > 0;
    if (onlyOneSegment && hasSegments) {
      const segIdx = Math.min(Math.max(selectedSegmentIndex, 0), segmentsByDayRef.current[safeIndex].length - 1);
      routesToDraw = segmentsByDayRef.current[safeIndex][segIdx] || [];
    } else {
      routesToDraw = (overrideRoutes[safeIndex] && overrideRoutes[safeIndex].length > 0)
        ? overrideRoutes[safeIndex]
        : (day.routes || []);
    }
    // 路线绘制：根据交通方式设置样式
    const kmDistance = (path: [number, number][]): number => {
      const R = 6371; // km
      const toRad = (d: number) => (d * Math.PI) / 180;
      let sum = 0;
      for (let i = 1; i < path.length; i++) {
        const [lng1, lat1] = path[i - 1];
        const [lng2, lat2] = path[i];
        const dLat = toRad(lat2 - lat1);
        const dLng = toRad(lng2 - lng1);
        const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        sum += R * c;
      }
      return sum;
    };
    const inferMode = (path: [number, number][]): string => {
      const d = kmDistance(path);
      if (d < 2) return "walk";
      if (d < 15) return "drive";
      return "transit";
    };
    const styleForMode = (mode?: string) => {
      const m = (mode || "").toLowerCase();
      if (m.includes("walk") || m.includes("步")) return { color: "#22c55e", weight: 3, style: "dashed" as const, opacity: 0.95 };
      if (m.includes("bike") || m.includes("骑")) return { color: "#10b981", weight: 3, style: "dashed" as const, opacity: 0.95 };
      if (m.includes("transit") || m.includes("地铁") || m.includes("公交")) return { color: "#8b5cf6", weight: 4, style: "solid" as const, opacity: 0.9 };
      return { color: "#3b82f6", weight: 5, style: "solid" as const, opacity: 0.85 }; // drive 默认更粗一些
    };
    // routes
    for (const r of routesToDraw) {
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
      const mode = r.mode || inferMode(path);
      const sty = styleForMode(mode);
      const pl = new AMap.Polyline({
        path,
        isOutline: true,
        outlineColor: "rgba(0,0,0,0.25)",
        borderWeight: 2,
        strokeColor: r.color || sty.color,
        strokeOpacity: sty.opacity ?? 0.9,
        strokeWeight: sty.weight,
        strokeStyle: sty.style,
        showDir: true,
        lineJoin: "round",
        lineCap: "round",
      });
      map.add(pl);
      created.push(pl);
    }
    // POI：仅使用名称校准后的坐标绘制；原始 day.pois 可能无坐标（仅地名），避免直接绘制导致报错
    const poisForDay = (refinedPoisRef.current[safeIndex] && refinedPoisRef.current[safeIndex].length > 0)
      ? refinedPoisRef.current[safeIndex]
      : [];
    for (let i = 0; i < (poisForDay as any[])?.length; i++) {
      const poi = (poisForDay as any[])[i];
      const idx = i + 1;
      const labelHtml = `
        <div style="display:inline-flex;align-items:center;gap:6px;padding:2px 8px;border-radius:10px;background:rgba(17,24,39,0.6);color:#fff;font-size:12px;backdrop-filter:saturate(180%) blur(2px);">
          <span style="display:inline-flex;align-items:center;justify-content:center;width:16px;height:16px;border-radius:50%;background:#334155;font-weight:600;">${idx}</span>
          <span>${poi.name}${poi.type ? ` · ${poi.type}` : ""}</span>
        </div>`;
      const mk = new AMap.Marker({ position: poi.coord, title: poi.name, label: { content: labelHtml, direction: "top" } });
      map.add(mk);
      // 地址展示：优先后端返回的 address，否则前端逆地理
      const key = `${poi.coord[0]},${poi.coord[1]}`;
      const cached = addressCacheRef.current[key];
      const info = new AMap.InfoWindow({ offset: new AMap.Pixel(0, -20) });
      const setInfo = (addr?: string) => {
        const addrLine = addr ? `<div style="margin-top:4px;color:#ccc">${addr}</div>` : "";
        const typeLine = poi.type ? `<div style="color:#bbb">类型：${poi.type}</div>` : "";
        info.setContent(`<div style="min-width:220px"><strong>${poi.name}</strong>${typeLine}${addrLine}</div>`);
      };
      setInfo(poi.address || cached);
      // 点击后拉取 PlaceSearch 详情并增强信息窗（评分、开放时间、电话）
      mk.on("click", () => {
        info.open(map, poi.coord);
        try {
          const ps = new AMap.PlaceSearch({ pageSize: 1 });
          ps.search(poi.name, (status: string, result: any) => {
            try {
              const item = result?.poiList?.pois?.[0];
              const rating = item?.biz_ext?.rating || "暂无评分";
              const openTime = item?.opening_hours || item?.opentime || item?.openTime || "未知开放时间";
              const address = item?.address || cached || poi.address || "地址暂无";
              const tel = item?.tel || "";
              const type = item?.type || poi.type || "";
              const content = `
                <div style="min-width:220px;max-width:280px;">
                  <div style="font-weight:600;margin-bottom:6px;">${poi.name}</div>
                  <div style="font-size:12px;line-height:1.6;">
                    <div>类型：${type || "未知"}</div>
                    <div>评分：${rating}</div>
                    <div>开放时间：${openTime}</div>
                    <div>地址：${address}</div>
                    ${tel ? `<div>电话：${tel}</div>` : ""}
                  </div>
                </div>`;
              info.setContent(content);
            } catch {}
          });
        } catch {}
      });
      if (!cached && geocoderRef.current && !(poi as any).address) {
        try {
          geocoderRef.current.getAddress(poi.coord, (status: string, result: any) => {
            if (status === "complete" && result?.regeocode?.formattedAddress) {
              addressCacheRef.current[key] = result.regeocode.formattedAddress;
              setInfo(result.regeocode.formattedAddress);
            }
          });
        } catch {}
      }
      created.push(mk);
    }

    // 起点/终点标注：以当天序列的首尾 POI 为准
    // 用户要求移除起点与终点标注，故删除相关标记创建逻辑
    overlaysRef.current = created;
    if (created.length > 0) {
      try { map.setFitView(created, true, [60, 60, 60, 60], 14); } catch {}
    } else {
      const center = plan.cityCenter ?? defaultCenter;
      try { map.setZoomAndCenter(12, center); } catch {}
    }
  }, [plan, ready, dayIndex, overrideRoutes]);

  // 名称校准 POI 坐标，并在前端用高德 API 计算多路线
  useEffect(() => {
    const shouldCompute = !!plan && Array.isArray(plan.days) && plan.days.length > 0 && ready;
    if (!shouldCompute) return;
    const safeIndex = Math.min(Math.max(dayIndex, 0), plan!.days.length - 1);
    const AMap = (window as any).AMap;

    // 若已有前端计算的路线且模式未变化，可不重复计算
    // 这里仍然在模式切换时重新计算
    setFetching(true);
    setError(null);
    setRouteStats(null);
    const myReqId = ++reqIdRef.current;

    // 城市推断用于提升 PlaceSearch 精度
    const inferCityFromText = (t: string): string | undefined => {
      const known = [
        "北京","上海","广州","深圳","成都","杭州","西安","重庆","南京","厦门",
        "青岛","苏州","武汉","长沙","昆明","桂林","三亚","大理","天津","郑州"
      ];
      for (const c of known) { if (t.includes(c)) return c; }
      return undefined;
    };
    const city = userText ? inferCityFromText(userText) : undefined;

    // 1) PlaceSearch 按名称校准坐标（支持仅地名+城市），并填充地址
    const originalPois = (plan!.days[safeIndex]?.pois || []).map((p: any) => ({ name: p.name, coord: p.coord as [number, number] | undefined, type: p.type, city: p.city }));
    const refineOne = (poi: { name: string; coord?: [number, number]; type?: string; city?: string }) => new Promise<{ name: string; coord: [number, number]; address?: string; type?: string; city?: string }>((resolve) => {
      try {
        const ps = new AMap.PlaceSearch({ city: poi.city || city || undefined, pageSize: 5 });
        const center = (plan?.cityCenter && Array.isArray(plan.cityCenter) && plan.cityCenter.length === 2)
          ? [Number(plan.cityCenter[0]), Number(plan.cityCenter[1])] as [number, number]
          : defaultCenter;
        const handleResult = (status: string, result: any) => {
          if (status === "complete" && result?.poiList?.pois?.length) {
            let cand = (result.poiList.pois as any[])
              .map((it: any) => ({
                name: it.name,
                coord: [Number(it.location?.lng), Number(it.location?.lat)] as [number, number],
                address: it.address || it.adname || it.pname || undefined,
                city: it.cityname || it.adname || it.pname || undefined,
              }))
              .filter((it: any) => Number.isFinite(it.coord[0]) && Number.isFinite(it.coord[1]));
            if (poi.coord) {
              // 若有原始坐标，按距离最近优先
              cand = cand.sort((a: any, b: any) => {
                const dx1 = a.coord[0] - poi.coord![0]; const dy1 = a.coord[1] - poi.coord![1];
                const dx2 = b.coord[0] - poi.coord![0]; const dy2 = b.coord[1] - poi.coord![1];
                return (dx1*dx1 + dy1*dy1) - (dx2*dx2 + dy2*dy2);
              });
            } else if (poi.city) {
              // 无坐标时，优先匹配城市一致的候选
              const byCity = cand.filter((x: any) => (x.city || "").includes(poi.city!));
              if (byCity.length > 0) cand = byCity;
            } else if (!poi.city && city) {
              const byCity2 = cand.filter((x: any) => (x.city || "").includes(city!));
              if (byCity2.length > 0) cand = byCity2;
            } else if (!poi.city && !city) {
              // 无城市上下文：根据城市中心坐标挑最近候选
              cand = cand.sort((a: any, b: any) => {
                const dx1 = a.coord[0] - center[0]; const dy1 = a.coord[1] - center[1];
                const dx2 = b.coord[0] - center[0]; const dy2 = b.coord[1] - center[1];
                return (dx1*dx1 + dy1*dy1) - (dx2*dx2 + dy2*dy2);
              });
            }
            if (cand.length > 0) { resolve({ name: poi.name, coord: cand[0].coord, address: cand[0].address, city: cand[0].city, type: poi.type }); return; }
          }
          // 搜索失败：尝试基于城市中心的附近搜索
          try {
            const nearByCenter = new AMap.LngLat(center[0], center[1]);
            ps.searchNearBy(poi.name, nearByCenter, 15000, (st2: string, res2: any) => {
              if (st2 === "complete" && res2?.poiList?.pois?.length) {
                const first = res2.poiList.pois[0];
                const lng = Number(first?.location?.lng); const lat = Number(first?.location?.lat);
                if (Number.isFinite(lng) && Number.isFinite(lat)) {
                  resolve({ name: poi.name, coord: [lng, lat], address: first?.address || undefined, city: first?.cityname || undefined, type: poi.type });
                  return;
                }
              }
              // 仍失败：若有原始坐标则回退；否则以城市中心兜底
              if (poi.coord && Number.isFinite(poi.coord[0]) && Number.isFinite(poi.coord[1])) {
                resolve({ name: poi.name, coord: poi.coord as [number, number], city: poi.city || city, type: poi.type });
              } else {
                resolve({ name: poi.name, coord: center, city: poi.city || city, type: poi.type });
              }
            });
            return;
          } catch {}
          // 搜索失败：若有原始坐标则回退；否则无法确定坐标，跳过（不返回该点）
          if (poi.coord && Number.isFinite(poi.coord[0]) && Number.isFinite(poi.coord[1])) {
            resolve({ name: poi.name, coord: poi.coord as [number, number], city: poi.city || city, type: poi.type });
          } else {
            // 选择城市中心作为兜底，避免完全无坐标造成崩溃（弱兜底，仅用于视图）
            resolve({ name: poi.name, coord: center, city: poi.city || city, type: poi.type });
          }
        };
        ps.search(poi.name, handleResult);
      } catch {
        if (poi.coord && Number.isFinite(poi.coord[0]) && Number.isFinite(poi.coord[1])) {
          resolve({ name: poi.name, coord: poi.coord as [number, number], city: poi.city || city, type: poi.type });
        } else {
          const center = (plan?.cityCenter && Array.isArray(plan.cityCenter) && plan.cityCenter.length === 2)
            ? [Number(plan.cityCenter[0]), Number(plan.cityCenter[1])] as [number, number]
            : defaultCenter;
          resolve({ name: poi.name, coord: center, city: poi.city || city, type: poi.type });
        }
      }
    });

    Promise.all(originalPois.map(refineOne)).then((refined) => {
      refinedPoisRef.current[safeIndex] = refined;

      // 2) 根据选择的模式用前端计算当天多段路线（相邻 POI 之间）
      const pairs: Array<[[number, number],[number, number]]> = [];
      for (let i = 1; i < refined.length; i++) {
        const a = refined[i - 1].coord; const b = refined[i].coord;
        if (Array.isArray(a) && Array.isArray(b)) pairs.push([a, b]);
      }
      const buildPolylineString = (path: any[]): string => {
        const pts: [number, number][] = [];
        for (const p of path || []) {
          if (Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1])) pts.push([p[0], p[1]]);
          else if (p?.lng && p?.lat) pts.push([Number(p.lng), Number(p.lat)]);
        }
        return pts.map((xy) => `${xy[0]},${xy[1]}`).join(";");
      };
      const normalizePath = (path: any[]): [number, number][] => {
        const pts: [number, number][] = [];
        for (const p of path || []) {
          if (Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1])) pts.push([p[0], p[1]]);
          else if (p?.lng && p?.lat) pts.push([Number(p.lng), Number(p.lat)]);
        }
        return pts;
      };
      const kmDistanceLocal = (pathXY: [number, number][]): number => {
        const R = 6371; // km
        const toRad = (d: number) => (d * Math.PI) / 180;
        let sum = 0;
        for (let i = 1; i < pathXY.length; i++) {
          const [lng1, lat1] = pathXY[i - 1];
          const [lng2, lat2] = pathXY[i];
          const dLat = toRad(lat2 - lat1);
          const dLng = toRad(lng2 - lng1);
          const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
          const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
          sum += R * c;
        }
        return sum;
      };

      let driving: any = null;
      let walking: any = null;
      let riding: any = null;
      let transfer: any = null;
      // 实例化所有可用插件，以支持在当前模式无解时的回退链
      try { driving = new AMap.Driving({ policy: (AMap as any).DrivingPolicy?.LEAST_TIME }); } catch {}
      try { walking = new AMap.Walking(); } catch {}
      try { riding = new AMap.Riding(); } catch {}
      try {
        const srcCityName = refined[0]?.city || city || undefined;
        const dstCityName = refined[refined.length - 1]?.city;
        transfer = new AMap.Transfer({ policy: (AMap as any).TransferPolicy?.LEAST_TIME, city: srcCityName, cityd: (dstCityName && dstCityName !== srcCityName) ? dstCityName : undefined });
      } catch {}

      let totalDistanceMeters = 0;
      let totalDurationSec = 0;

      const toLngLat = (xy: [number, number]) => {
        try { return new AMap.LngLat(Number(xy[0]), Number(xy[1])); } catch { return xy as any; }
      };
      const collect = (routes: any[], color: string, mode: string) => {
        const list: Route[] = [];
        const altCount = showAlternatives ? Math.min(routes.length, 3) : 1;
        for (let i = 0; i < altCount; i++) {
          const r = routes[i];
          const path = r?.path || r?.polyline || (r?.steps ? r.steps.flatMap((s: any) => s?.path || s?.polyline || []) : []);
          const poly = buildPolylineString(path || []);
          if (poly && poly.length > 0) list.push({ polyline: poly, color, mode });
          if (i === 0) {
            const xy = normalizePath(path || []);
            if (Array.isArray(xy) && xy.length > 1) totalDistanceMeters += kmDistanceLocal(xy) * 1000;
            if (Number.isFinite(r?.time)) totalDurationSec += Number(r.time);
          }
        }
        return list;
      };

      const searchEngine = (engine: "drive"|"walk"|"ride"|"transit", start: [number, number], end: [number, number]) => new Promise<Route[]>((resolve) => {
        try {
          if (engine === "drive" && driving) {
            driving.search(toLngLat(start), toLngLat(end), (status: string, result: any) => {
              if (status === "complete" && result?.routes?.length) { resolve(collect(result.routes, "#3b82f6", "drive")); } else { resolve([]); }
            });
            return;
          }
          if (engine === "walk" && walking) {
            walking.search(toLngLat(start), toLngLat(end), (status: string, result: any) => {
              if (status === "complete" && result?.routes?.length) { resolve(collect(result.routes, "#22c55e", "walk")); } else { resolve([]); }
            });
            return;
          }
          if (engine === "ride" && riding) {
            riding.search(toLngLat(start), toLngLat(end), (status: string, result: any) => {
              if (status === "complete" && result?.routes?.length) { resolve(collect(result.routes, "#10b981", "ride")); } else { resolve([]); }
            });
            return;
          }
          if (engine === "transit" && transfer) {
            transfer.search(toLngLat(start), toLngLat(end), (status: string, result: any) => {
              try {
                if (status === "complete" && (result?.plans?.length || result?.transferPlans?.length)) {
                  const plan = result?.plans?.[0] || result?.transferPlans?.[0];
                  if (Number.isFinite(plan?.time)) totalDurationSec += Number(plan.time);
                  const segments = plan?.segments || plan?.steps || [];
                  const list: Route[] = [];
                  segments.forEach((seg: any) => {
                    const isWalk = ((seg?.type || seg?.segmentType || "") as string).toLowerCase().includes("walk");
                    const path = seg?.path || seg?.walk?.path || seg?.transit?.path || [];
                    const poly = buildPolylineString(path || []);
                    if (poly && poly.length > 0) list.push({ polyline: poly, color: isWalk ? "#22c55e" : "#8b5cf6", mode: isWalk ? "walk" : "transit" });
                    const xy = normalizePath(path || []);
                    if (Array.isArray(xy) && xy.length > 1) totalDistanceMeters += kmDistanceLocal(xy) * 1000;
                  });
                  resolve(list);
                } else { resolve([]); }
              } catch { resolve([]); }
            });
            return;
          }
          resolve([]);
        } catch { resolve([]); }
      });

      const computeOne = async (start: [number, number], end: [number, number]) => {
        const primary = routeMode;
        const order = primary === "transit" ? ["transit","drive","walk"]
          : primary === "walk" ? ["walk","ride","drive"]
          : primary === "ride" ? ["ride","walk","drive"]
          : ["drive","ride","walk"];
        for (const t of order as ("drive"|"walk"|"ride"|"transit")[]) {
          const res = await searchEngine(t, start, end);
          if (Array.isArray(res) && res.length > 0) return res;
        }
        return [];
      };

      Promise.all(pairs.map(([s, e]) => computeOne(s, e))).then((segments) => {
        if (reqIdRef.current !== myReqId) return;
        segmentsByDayRef.current[safeIndex] = segments as Route[][];
        setSelectedSegmentIndex(0);
        const merged: Route[] = segments.flat();
        setOverrideRoutes((prev) => ({ ...prev, [safeIndex]: merged }));
        setRouteStats({ distanceMeters: totalDistanceMeters, durationSec: totalDurationSec });
        if (!merged || merged.length === 0) {
          setError("未检索到可用路线，请尝试切换模式或调整 POI 顺序");
        }
      }).catch(() => {
        if (reqIdRef.current !== myReqId) return;
        setError("前端路线计算失败，请稍后重试");
      }).finally(() => {
        if (reqIdRef.current !== myReqId) return;
        setFetching(false);
      });
    }).catch(() => {
      if (reqIdRef.current !== myReqId) return;
      setFetching(false);
      setError("地点校准失败");
    });
  }, [dayIndex, ready, routeMode, showAlternatives, plan, userText]);

  return (
    <div>
      <div ref={mapEl} className="w-full rounded-lg bg-neutral-800 h-[360px] md:h-[440px]" />
      {plan && Array.isArray(plan.days) && plan.days.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {plan.days.map((_, i) => (
            <button
              key={i}
              onClick={() => setDayIndex(i)}
              className={`rounded-md px-3 py-1 text-sm ring-1 ring-white/10 ${i === dayIndex ? "bg-violet-700/60 text-white" : "bg-neutral-800/70 text-neutral-200 hover:bg-neutral-700/70"}`}
            >{`第 ${i + 1} 天${fetching && i === dayIndex ? " · 加载中" : ""}`}</button>
          ))}
          <div className="ml-auto flex items-center gap-3 text-xs">
            <label className="inline-flex items-center gap-2">
              <span>模式</span>
              <select
                value={routeMode}
                onChange={(e) => setRouteMode(e.target.value as any)}
                className="bg-neutral-800/70 text-neutral-200 rounded px-2 py-1 ring-1 ring-white/10"
              >
                <option value="drive">驾车</option>
                <option value="walk">步行</option>
                <option value="ride">骑行</option>
                <option value="transit">公交/地铁</option>
              </select>
            </label>
            <label className="inline-flex items-center gap-1">
              <input type="checkbox" checked={showAlternatives} onChange={(e) => setShowAlternatives(e.target.checked)} />
              显示备选路线
            </label>
            <label className="inline-flex items-center gap-1">
              <input type="checkbox" checked={onlyOneSegment} onChange={(e) => setOnlyOneSegment(e.target.checked)} />
              仅看当前段
            </label>
            {(() => {
              const sidx = Math.min(Math.max(dayIndex, 0), plan!.days.length - 1);
              const names = ((refinedPoisRef.current[sidx] && refinedPoisRef.current[sidx].length > 0)
                ? refinedPoisRef.current[sidx]
                : (plan!.days[sidx]?.pois || [])) as any[];
              const count = Math.max(0, names.length - 1);
              if (!onlyOneSegment || count <= 0) return null;
              const segIdx = Math.min(Math.max(selectedSegmentIndex, 0), count - 1);
              const label = `${names[segIdx]?.name ?? `第${segIdx+1}点`} → ${names[segIdx+1]?.name ?? `第${segIdx+2}点`}`;
              return (
                <div className="inline-flex items-center gap-2">
                  <button
                    className="rounded-md px-2 py-1 bg-neutral-800/70 ring-1 ring-white/10"
                    disabled={segIdx <= 0}
                    onClick={() => setSelectedSegmentIndex((i) => Math.max(0, i - 1))}
                  >上一段</button>
                  <span className="px-2 py-1 rounded-md bg-neutral-800/60 ring-1 ring-white/10">{`当前：${label}`}</span>
                  <button
                    className="rounded-md px-2 py-1 bg-neutral-800/70 ring-1 ring-white/10"
                    disabled={segIdx >= count - 1}
                    onClick={() => setSelectedSegmentIndex((i) => Math.min(count - 1, i + 1))}
                  >下一段</button>
                </div>
              );
            })()}
            {routeStats && (
              <span className="inline-flex items-center gap-2 px-2 py-1 rounded-md bg-neutral-800/60 ring-1 ring-white/10">
                <span>里程：{(routeStats.distanceMeters / 1000).toFixed(1)} km</span>
                <span>· 时长：{(() => { const m = Math.round(routeStats.durationSec / 60); const h = Math.floor(m/60); const mm = m%60; return h>0? `${h}小时${mm}分`:`${mm}分`; })()}</span>
              </span>
            )}
            <span className="inline-flex items-center gap-2 px-2 py-1 rounded-md bg-neutral-800/60 ring-1 ring-white/10">
              <span className="inline-block w-5 h-[3px] rounded bg-blue-500" />
              <span>驾车</span>
            </span>
            <span className="inline-flex items-center gap-2 px-2 py-1 rounded-md bg-neutral-800/60 ring-1 ring-white/10">
              <span className="inline-block w-5 h-[3px] rounded bg-violet-500" />
              <span>地铁/公交</span>
            </span>
            <span className="inline-flex items-center gap-2 px-2 py-1 rounded-md bg-neutral-800/60 ring-1 ring-white/10">
              <span className="inline-block w-5 h-[3px] rounded bg-green-500" style={{ borderBottom: "1px dashed rgba(16,185,129,1)" }} />
              <span>步行/骑行</span>
            </span>
          </div>
        </div>
      )}
      {error && (
        <div className="mt-2 text-xs text-red-400">{error}</div>
      )}
    </div>
  );
}
