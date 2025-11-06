# 行程规划实施步骤（前后端联动）

本文档记录本次改造的目标、数据契约与落地步骤，确保“用户通过自然语言描述 → 大模型规划行程 → 后端产出地图数据 → 前端渲染”的完整闭环。

## 目标
- 前端：移除手动输入目的地的工具栏，仅保留地图展示；新增一个“自然语言输入”组件；根据后端返回的行程结果渲染地图（路线与 POI）。
- 后端：新增 `POST /api/v1/itinerary/plan` 接口，接收自然语言描述，返回分日行程与地图数据（轨迹 polyline、POI 打点等）。

## 前后端数据契约
- 请求：`POST /api/v1/itinerary/plan`
  - Body: `{ text: string, city?: string }`
- 响应：`{ plan: ItineraryPlan }`
  - `ItineraryPlan`
    - `cityCenter?: [lng, lat]` 地图初始视角，可选
    - `days: DayPlan[]`
  - `DayPlan`
    - `summary?: string` 当日概要
    - `routes: Route[]` 当日路线集合
    - `pois: Poi[]` 当日 POI 集合
  - `Route`
    - `polyline?: string` 轨迹坐标串，示例：`"lng,lat;lng,lat;..."`
    - `color?: string` 线颜色（可选）
  - `Poi`
    - `name: string`
    - `coord: [lng, lat]`
    - `type?: string`

坐标约定：一律使用 `[lng, lat]`（高德坐标系 GCJ-02）。

## 实施步骤
1) 前端新增“自然语言输入”组件 `ItineraryInput`，提交到后端接口，并获得 `plan`。
2) MapView 增加行程渲染入口（接收 `plan`），绘制 polyline 与 marker，支持 fitView。
3) 页面集成：在首页左侧加入 `ItineraryInput`，将 `plan` 传给右侧地图。
4) 后端新增行程规划接口骨架：`POST /api/v1/itinerary/plan`，先返回规则驱动的示例数据，后续再接入大模型与地图 API 编排。
5) 验证与预览：运行前端本地预览，输入自然语言，看到示例行程绘制在地图上。

## 后续迭代建议
- 接入 LLM：基于提示工程解析用户意图与约束，生成结构化行程。
- 地图编排：调用 `poi/search`、`route/driving` 等接口补齐坐标与轨迹。
- 体验优化：分日切换、图层控制、加载/错误状态提示。