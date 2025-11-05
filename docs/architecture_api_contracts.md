# AI Travel Planner 后端架构与接口契约（Java 方案）

本文档明确后端模块划分、数据模型草案、接口契约与流式返回规范，面向“Web 版 AI 旅行规划师”。不包含具体代码实现，聚焦于架构与协议对齐。

## 总览
- 技术栈建议：
  - 运行时与框架：Spring Boot 3 + Java 21
  - Web 层：Spring WebFlux（SSE/高并发/非阻塞）
  - HTTP 客户端：WebClient（统一外部 API 调用）
  - 数据库：PostgreSQL（主业务数据）
  - ORM/驱动：R2DBC（响应式）或 JPA（阻塞式，二选一）
  - 缓存与限流：Redis（POI/路线/结果缓存、速率限制）
  - 对象存储：S3/MinIO（音频与图片）
  - 队列与任务：Kafka/RabbitMQ（长任务、重试、导出）
  - 观测：Micrometer + Prometheus + Grafana；错误上报 Sentry
  - 安全：Spring Security（JWT），Resilience4j（熔断/重试/隔离）
- 外部服务：
  - 语音识别（STT）：科大讯飞
  - 地图与地理：高德地图（可替换百度地图）
  - 大语言模型（LLM）：通义/Qwen 或 OpenAI/Azure OpenAI（可路由切换）
- 部署与环境：Docker 镜像，云上部署（K8s/ECS），或轻平台（Railway/Render）；国内可选阿里云/腾讯云。

## 模块划分
1. 接口网关（API Gateway / HTTP Layer）
   - 职责：统一入口、鉴权（JWT）、CORS、速率限制、输入校验（JSON Schema/Zod）、错误映射。
   - 能力：SSE 流式输出；统一响应格式；请求追踪（Trace ID）。

2. 编排层（Orchestration Service）
   - 职责：将“用户约束 → 地理/POI检索 → LLM生成 → 预算校验 → 修正 → 入库”编排为稳定流程。
   - 能力：并发组合、超时控制、重试退避、熔断降级、多模型路由（国内/海外）。

3. Provider 适配层（Integration Providers）
   - SpeechProvider：封装科大讯飞 STT（长语音、分段、置信度）。
   - MapProvider：封装高德/百度的 POI、路线规划、地理编码与逆编码。
   - LLMProvider：统一对接 Qwen/OpenAI 等，支持流式输出与严格 JSON 生成。
   - 约束：所有 Provider 输出需标准化并通过 Schema 校验。

4. 数据访问层（DAL）
   - Repository：plans/days/activities/expenses/preferences 等实体读写。
   - RLS/ACL：行级安全（仅所有者可读写），分享链接只读或协作权限受控。
   - Storage：音频/图片的上传、访问签名与生命周期管理。

5. 队列与任务层（Async Jobs）
   - 用途：长任务（音频转写、行程重算、导出 PDF/ICS）、失败重试、审计日志。
   - 主题：`speech.transcribe`, `plan.recompute`, `plan.export` 等。

6. 观测与治理（Observability & SRE）
   - 指标与日志：请求量、延迟、错误率、外部 API 配额使用；集中化日志与追踪。
   - 开关与配额：功能开关（Feature Flags）、按用户/计划的速率限制与配额。

7. 配置与密钥管理（Config & Secrets）
   - 环境变量：数据库、Redis、对象存储、语音/地图/LLM API Key；仅服务端持有。
   - 密钥轮换：分环境（dev/staging/prod）管理与审计。

## 数据模型（JSON Schema 草案）
- User/Profile（简略）：
  - `user`: { id, email, phone?, createdAt }
  - `profile`: { userId, displayName?, locale?, homeCity?, currency?, preferences }
- Plan：
  - `plan`: { id, ownerId, destination, startDate, endDate, totalDays, budget: { amount, currency }, tags: [], status: "draft|published", createdAt, updatedAt }
- Day：
  - `day`: { id, planId, index, date, summary?, accommodation?, transport?, notes? }
- Activity：
  - `activity`: { id, dayId, type: "sight|food|shopping|kids|culture|outdoor", title, poi: { id?, name?, location: { lat, lng }, address? }, startTime?, endTime?, transport?: { mode, from?, to?, duration?, cost? }, estimatedCost?: { amount, currency }, description?, images?: [] }
- Expense：
  - `expense`: { id, planId, dayId?, category: "hotel|flight|train|food|ticket|shopping|misc", amount, currency, time?, note?, source: "ai|user|voice" }
- Preference：
  - `preferences`: { userId, pace: "relaxed|normal|packed", likes: ["food","anime","kids","culture","outdoor"], avoid?: ["crowded","expensive"], childFriendly?: boolean }
- MediaAsset：
  - `mediaAsset`: { id, ownerId, type: "audio|image", url, duration?, size?, createdAt }

> 说明：实际实现需以 JSON Schema 或数据库 DDL 固化，并以 DTO 层做入/出参校验。

## 接口通用约定
- Base Path：`/api/v1`
- Auth：
  - Header：`Authorization: Bearer <JWT>`（除登录注册外均需）
  - 角色：`user`（默认）、`admin`（管理）
- Content-Type：`application/json`；上传音频使用 `multipart/form-data` 或原始音频 MIME（如 `audio/wav`）。
- 响应格式（统一包）：
  ```json
  { "code": 0, "message": "ok", "data": { ... }, "traceId": "..." }
  ```
  - 错误码：
    - `0` 成功
    - `400` 参数错误（invalid_input）
    - `401` 未授权（unauthorized）
    - `403` 禁止访问（forbidden）
    - `404` 未找到（not_found）
    - `429` 触发限流（rate_limited）
    - `500` 服务内部错误（internal_error）
    - `502` 外部服务错误（provider_error）
    - `503` 过载或降级（unavailable）
- 流式（SSE）：`Content-Type: text/event-stream`，事件字段：`event`, `id`, `data`，心跳 `event: ping`。

## 认证与用户接口（可选最小集）
- `POST /auth/signup`
  - 请求：{ email, password, displayName? }
  - 响应：{ code, data: { userId } }
- `POST /auth/login`
  - 请求：{ email, password }
  - 响应：{ code, data: { token, user: { id, email } } }
- `GET /auth/me`
  - Header：Bearer JWT
  - 响应：{ code, data: { id, email, profile } }

## 语音识别（Speech）
- `POST /speech/recognize`
  - 用途：上传音频并进行语音转文本，可选择流式（SSE）。
  - Header：`Authorization`；`Content-Type: multipart/form-data` 或音频 MIME（`audio/wav`/`audio/mpeg`）。
  - 表单字段：
    - `file`：音频文件（必填）
    - `lang`：`zh-CN|en-US` 等（默认 `zh-CN`）
    - `source`：`plan|expense|general`（业务上下文）
    - `hint`：可选识别提示（如“记账”、“新增活动”）
  - 同步响应示例：
    ```json
    { "code": 0, "message": "ok", "data": { "text": "我想去日本，五天，预算一万元", "confidence": 0.92, "segments": [ { "start": 0, "end": 3.2, "text": "我想去日本" } ] } }
    ```
  - SSE 流式事件：
    ```
    event: partial
    data: {"text":"我想去日本"}
    
    event: partial
    data: {"text":"五天，预算一万元"}
    
    event: final
    data: {"text":"我想去日本，五天，预算一万元","confidence":0.92}
    ```

## 行程生成与编辑（Plans）
- `POST /plans/generate`（SSE 推荐）
  - 用途：根据目的地/日期/预算/偏好生成个性化行程。
  - 请求：
    ```json
    {
      "destination": "日本东京",
      "startDate": "2025-03-01",
      "endDate": "2025-03-05",
      "budget": { "amount": 10000, "currency": "CNY" },
      "party": { "adults": 2, "kids": 1 },
      "preferences": { "likes": ["food","anime"], "pace": "normal", "childFriendly": true }
    }
    ```
  - SSE 事件：
    - `event: progress` data: { stage: "poi_fetch|llm_draft|budget_check|adjust" }
    - `event: draft` data: { plan: { ...部分JSON... } }
    - `event: final` data: { plan: { ...完整JSON... }, summary: { budget, highlights } }
  - 最终响应（非流式）：
    ```json
    { "code": 0, "data": { "plan": { "id": "p_123", "destination": "日本东京", "totalDays": 5, "days": [ { "index": 1, "date": "2025-03-01", "activities": [ { "type": "sight", "title": "浅草寺", "poi": { "name": "浅草寺", "location": { "lat": 35.71, "lng": 139.79 } }, "startTime": "09:30", "transport": { "mode": "metro", "duration": 15 } } ] } ] } } }
    ```

- `PUT /plans/{id}`
  - 用途：更新计划元信息（预算、标签、状态）。
  - 请求：{ destination?, startDate?, endDate?, budget?, tags?, status? }
  - 响应：{ code, data: { plan } }

- `GET /plans/{id}` / `GET /plans`
  - 用途：查询单个或列表（分页）。
  - 响应：{ code, data: { plan | items: [plan], page } }

- `PUT /plans/{id}/days/{day}/replan`（SSE 可选）
  - 用途：对某一天增量重算（保持整体稳定，最小范围更新）。
  - 请求：{ constraints?: { pace?, likes?, avoid? }, poiHints?: [ ... ], budgetAdjust?: { amountDelta } }
  - SSE：`event: progress|final`；最终返回更新后的 day JSON。

## POI 与路线（Map）
- `GET /poi/search`
  - 查询参数：`q`（关键词）、`city?`、`lat?`、`lng?`、`radius?`（米，默认 3000）
  - 响应：
    ```json
    { "code": 0, "data": { "items": [ { "id": "amap_abc", "name": "浅草寺", "location": { "lat": 35.71, "lng": 139.79 }, "address": "...", "category": "sight", "rating": 4.6 } ] } }
    ```

- `GET /route`
  - 查询参数：`fromLat, fromLng, toLat, toLng, mode=drive|walk|metro|bus`
  - 响应：
    ```json
    { "code": 0, "data": { "duration": 32, "distance": 5800, "polyline": "encoded_polyline_string" } }
    ```

## 费用预算与记账（Expenses）
- `POST /expenses`（手动记账）
  - 请求：{ planId, dayId?, category, amount, currency, time?, note? }
  - 响应：{ code, data: { expenseId } }

- `POST /expenses/voice`
  - 用途：语音记账（识别→意图解析→金额/分类抽取→入库）。
  - 请求：同 `/speech/recognize`，增加 `currency?` 与上下文 `planId?`。
  - 响应：
    ```json
    { "code": 0, "data": { "expense": { "category": "food", "amount": 120, "currency": "CNY", "time": "2025-03-02T12:05:00+08:00", "note": "午餐拉面" } } }
    ```

- `GET /expenses/summary`
  - 查询参数：`planId`, `groupBy=day|category`
  - 响应：{ code, data: { total: { amount, currency }, groups: [ { key, amount } ] } }

## 导出与分享（Export & Share）
- `POST /plans/{id}/export`
  - 用途：导出 PDF/.ics，走异步队列。
  - 请求：{ format: "pdf|ics" }
  - 响应：{ code, data: { jobId } }

- `GET /exports/{jobId}`
  - 用途：查询导出状态与下载链接。
  - 响应：{ code, data: { status: "pending|running|done|failed", url? } }

## 流式返回规范（SSE）
- Header：`Content-Type: text/event-stream`; `Cache-Control: no-cache`; `Connection: keep-alive`。
- 事件字段：
  - `event: progress` data: { stage, percent? }
  - `event: partial` data: { text | planFragment }
  - `event: final` data: { result }
  - `event: error` data: { code, message }
  - `event: ping` data: {}
- 关闭：服务端发送 `event: final` 或 `event: error` 后结束连接。

## Provider 接口契约（抽象，非代码）
- SpeechProvider
  - `recognize(audio, lang, hints?) -> { text, confidence, segments[] }`
  - 语义：支持长音频分段识别与中间结果回调；错误统一映射为 provider_error。

- MapProvider
  - `searchPOI(q, city?, location?, radius?) -> POI[]`
  - `route(from, to, mode) -> { duration, distance, polyline }`
  - `geocode(address) -> { lat, lng }` / `reverseGeocode(lat,lng) -> address`

- LLMProvider
  - `generateItinerary(constraints, poiData?) -> planJSON`
  - `adjustItinerary(plan, constraints) -> planJSON`
  - 约束：输出必须满足行程 JSON Schema；支持流式 token 与中间草稿。

## 速率限制与配额（建议）
- 每用户/每计划维度限流：
  - `/speech/recognize`: 60 次/小时
  - `/plans/generate`: 10 次/小时
  - `/poi/search`: 300 次/小时
- 突发限制：令牌桶（burst 10-20）；溢出返回 `429`。

## 安全与隐私
- 秘钥仅服务端持有；前端通过受控路由访问。
- 音频默认短期保存（如 7 天），用户可关闭保存；数据最小化原则。
- 法务与合规：隐私政策、用户同意；对第三方内容的免责声明与来源标注。

## 版本与演进
- 版本通过 Base Path（`/api/v1`）或 Header（`X-API-Version`）管理。
- 向后兼容策略：新增字段默认可选；破坏性变更需发布新版本路径。

## 错误处理与重试建议
- 外部 API 失败（网络/配额）：指数退避重试（最大 3 次），降级至缓存或备选 Provider。
- LLM 输出不合规：触发二次提示（self-healing），或回退到模板与推荐路线。
- 地图路线不可行：切换交通模式或替换活动，保证可行性与节奏。

## 示例：行程 JSON（简版）
```json
{
  "id": "p_123",
  "ownerId": "u_1",
  "destination": "日本东京",
  "startDate": "2025-03-01",
  "endDate": "2025-03-05",
  "totalDays": 5,
  "budget": { "amount": 10000, "currency": "CNY" },
  "days": [
    {
      "index": 1,
      "date": "2025-03-01",
      "summary": "抵达与浅草寺文化体验",
      "activities": [
        {
          "type": "sight",
          "title": "浅草寺",
          "poi": { "name": "浅草寺", "location": { "lat": 35.71, "lng": 139.79 } },
          "startTime": "09:30",
          "endTime": "11:00",
          "transport": { "mode": "metro", "duration": 15 },
          "estimatedCost": { "amount": 0, "currency": "CNY" }
        },
        {
          "type": "food",
          "title": "上野拉面午餐",
          "poi": { "name": "一兰上野店", "location": { "lat": 35.71, "lng": 139.77 } },
          "startTime": "12:00",
          "endTime": "13:00",
          "estimatedCost": { "amount": 80, "currency": "CNY" }
        }
      ]
    }
  ]
}
```

---