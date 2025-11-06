# AI Travel Agent

一个基于大模型的智能旅游规划应用，包含后端（Spring Boot）与前端（Next.js）。目前已完成：

- 接入大模型并提供同步与流式（SSE）调用能力
- 行程规划接口 `/api/v1/itinerary/plan`（POST）与流式接口 `/api/v1/itinerary/plan/stream`（GET）
- 前端通过 `EventSource` 监听 `progress/draft/final/error` 事件，实时渲染草稿与最终行程

## 主要功能
- 行程草拟：在 LLM 流式输出过程中推送 `draft` 事件，前端可先行渲染
- 最终行程：流结束后推送 `final` 事件，包含 `cityCenter/days/routes/pois` 等结构化数据
- 失败处理：统一推送 `error` 事件便于前端显示提示

## 后端
- 技术栈：Java 17 + Spring Boot 3 + Maven
- 关键类：
  - `LlmService`：封装大模型的文本生成与流式输出
  - `ItineraryController`：行程规划 REST 与 SSE 控制器

### 环境变量
在 `apps/server/.env.local` 中配置模型相关的密钥与提示（不会提交到仓库）：

```
OPENAI_API_KEY=...
OPENAI_API_BASE=...
OPENAI_SYSTEM_PROMPT="你是一位资深旅游规划师..."
```

## 前端
- 技术栈：Next.js + TypeScript + Tailwind
- 关键页面与组件：
  - `ItineraryInput.tsx`：发起 SSE 订阅并消费 `progress/draft/final/error` 事件
  - `PlanTextView.tsx`：按事件更新行程展示

## 快速启动
1. 后端
   - 进入 `apps/server`
   - 配置 `.env.local`
   - `mvn -DskipTests package && java -jar target/server-0.1.0-SNAPSHOT.jar`
2. 前端
   - 进入 `apps/web`
   - `pnpm install && pnpm dev`

## 接口
- `POST /api/v1/itinerary/plan`
  - `body`: `{ text: string, city?: string }`
  - `resp`: `{ plan: ItineraryPlan }`
- `GET /api/v1/itinerary/plan/stream`
  - `query`: `text?`, 或 `destination` + `days`
  - 事件：`progress`/`draft`/`final`/`error`

## 说明
- `.env*` 文件默认忽略，不会提交到仓库；请在本地配置密钥。
- 若使用不同模型供应商，请在 `LlmService` 中调整流式解析逻辑。