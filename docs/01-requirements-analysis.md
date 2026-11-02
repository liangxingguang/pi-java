# pi-java 需求分析

> **项目定位**：使用 JDK 25 + Pure Java 复刻 [pi](https://github.com/earendil-works/pi) 项目——一个自扩展的 AI 编码代理（coding agent）、通用代理运行时（agent runtime）以及统一多供应商 LLM API 网关。

---

## 1. 项目背景

pi 是持续演进的 TypeScript AI 编码代理，核心能力分为 LLM API、Agent 运行时和 coding-agent 三层。包数量、Provider 数量与具体行为以固定对齐锚点为准；本文不使用立项时的目录规模作为当前事实。

pi-java 的目标是使用 **JDK 25** 以 **Pure Java** 方式实现相同的主流可观察行为，同时利用 Java 平台的类型、并发与存储能力。当前目标锚点为 `56cae7823`，范围裁决见 docs/04 与 docs/08。

---

## 2. 功能需求

### 2.1 LLM API 模块（类比 `@earendil-works/pi-ai`）

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| F1 | 统一 LLM API 接口 | P0 | 定义 `StreamApi`、`SimpleApi` 等统一抽象，屏蔽供应商差异 |
| F2 | 多供应商支持 | P0 | 覆盖 R2 批准的主流 Provider；Provider SPI 支持扩展，长尾 Provider 不自动进入对齐范围 |
| F3 | 供应商注册机制 | P0 | 可扩展的 Provider SPI，支持运行时注册新供应商 |
| F4 | 模型目录管理 | P1 | 内置模型列表 + 远程目录刷新（ETag/Last-Modified 增量更新） |
| F5 | 流式响应处理 | P0 | SSE/JSON Stream 解析，`Flow.Publisher` 或 `Iterator` 风格消费 |
| F6 | 多模态支持 | P1 | 图片输入（base64 + URL）、工具调用（function calling） |
| F7 | 认证管理 | P0 | 环境变量 + Keychain + OAuth 流程，支持多 profile |
| F8 | HTTP 代理支持 | P2 | 自动检测系统代理（HTTP_PROXY / HTTPS_PROXY） |
| F9 | CLI 工具（`pi-ai`） | P2 | 独立的命令行工具，用于模型列表查询、API 测试 |

### 2.2 代理运行时（类比 `@earendil-works/pi-agent-core`）

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| F10 | Agent 循环引擎 | P0 | 用户提示 → LLM 调用 → 工具结果 → 继续循环，直到 LLM 产生最终消息 |
| F11 | AgentHarness 运行时 | P0 | 车道（lane）模型、操作记录、快照、确定性手动驱动模式；不是 pi 的 R5 Durable 子系统 |
| F12 | 工具系统 | P0 | 可扩展的工具注册、参数校验（JSON Schema）、执行回调 |
| F13 | 内置工具 | P0 | Bash 执行、文件读写（Read/Write/Edit/EditDiff）、Grep、Find、LS、图片处理 |
| F14 | 会话管理 | P0 | 会话树（session tree）、分支、命名、列表、删除 |
| F15 | 会话持久化 | P0 | JSON Lines（JSONL）格式，支持增量写入和崩溃恢复 |
| F34 | SQLite 会话存储后端 | P0 | 实现 `SessionRepository`/`SessionStorage`，含 FTS5 与分支查询；schema 为本仓独立设计，不对齐 pi 已删除后端 |
| F16 | 压缩（Compaction） | P1 | 上下文过长时自动压缩历史，保留关键信息 |
| F17 | 分支摘要（Branch Summarization） | P1 | 子分支完成后生成摘要注入父分支 |
| F18 | 遥测（Telemetry） | P2 | 供应商无关的遥测接口，OpenTelemetry 适配器 |
| F19 | 事件系统 | P1 | 可订阅的 TypeBox 风格类型事件，支持缓冲监听 |
| F35 | 评估框架 | P1 | 端到端测试和 conformance 测试框架（对应 `packages/evals`），验证 agent 行为和 provider 兼容性 |

### 2.3 编码代理 CLI（类比 `@earendil-works/pi-coding-agent`）

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| F20 | 交互式 TUI | P0 | 终端 UI，差量渲染，Markdown/Mermaid 渲染，编辑器组件 |
| F21 | 非交互模式（Print Mode） | P1 | `-p "prompt"` 一次性调用，结果输出到 stdout |
| F22 | RPC 模式 | P2 | JSONL 格式的 RPC 服务端/客户端，供外部集成 |
| F23 | Slash 命令 | P1 | 内置命令 + 可扩展注册 |
| F24 | 技能系统（Skills） | P2 | Markdown 格式的技能定义，运行时加载 |
| F25 | 扩展系统 | P2 | 插件机制，允许第三方扩展工具、命令、provider |
| F26 | 系统提示管理 | P1 | 可配置的系统提示（system prompt），支持模板 |
| F27 | 模型路由 | P1 | 根据任务类型自动选择模型 |
| F28 | 设置管理 | P0 | 全局 + 项目级配置，JSON 格式（`settings.json`，与 pi 对齐），分层合并 |
| F29 | 信任管理 | P1 | 项目级信任标记，首次使用需用户确认 |
| F30 | 会话浏览器 | P1 | 交互式会话选择器，支持分页和搜索 |

### 2.4 远程会话（类比 `packages/protocol` + `packages/server` + `packages/client`）

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| F31 | CBOR 协议 | P2 | 传输无关的 CBOR 编解码 + 帧格式 |
| F32 | Unix Socket 传输 | P3 | 本地进程间通信 |
| F33 | 远程会话客户端 | P3 | 通过网络连接到远程 pi-java 实例 |

---

## 3. 非功能需求

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| NF1 | Pure Java 优先 | P0 | 核心功能仅使用 JDK 标准库；外部依赖仅限 JSON/CBOR/YAML 解析、TamboUI（TUI 库）、SQLite JDBC 驱动（存储层）等无可替代的场景 |
| NF2 | JDK 25 基线 | P0 | 利用虚拟线程、结构化并发、模式匹配、Foreign Function API、sealed classes 等新特性 |
| NF3 | 编译期类型安全 | P0 | 利用 Java 泛型、密封类、记录类实现比 TypeScript 更强的类型约束 |
| NF4 | 跨平台 | P0 | Windows / macOS / Linux 三个主要平台 |
| NF5 | 分发形态 | P1 | 使用 fat jar；GraalVM Native Image 已放弃，不作为发布目标 |
| NF6 | 响应式 / 非阻塞 I/O | P1 | LLM 流式响应使用 Flow API 或虚拟线程模拟同步风格 |
| NF7 | 可测试性 | P0 | 每个模块可独立测试；提供 faux/mock provider 用于离线测试 |
| NF8 | 模块化 | P0 | 基于 Maven 多模块结构，包隔离 + maven-enforcer-plugin 保证依赖收敛 |
| NF9 | 性能 | P2 | LLM 响应流处理延迟 < 10ms 额外开销；TUI 渲染 60fps 感知流畅 |
| NF10 | 崩溃恢复 | P1 | JSONL 增量写入，崩溃后可从最后一个完整记录恢复 |

---

## 4. 约束与前提条件

| 约束 | 说明 |
|------|------|
| 语言版本 | JDK 25 作为语言、构建和运行基线 |
| 构建系统 | Maven 4.x（声明式配置，依赖管理成熟） |
| 外部依赖上限 | 核心模块 ≤ 5 个外部依赖；CLI 模块可放宽至 ~15 个；新增依赖须在设计稿单独审核 |
| 命名空间 | Maven groupId: `com.pi-java`；包名: `com.pijava` |
| API 兼容性 | 不承诺 TypeScript 源码级 API 兼容；主流可观察行为按固定锚点对齐 |
| 功能点 兼容性 | 1：1复刻pi功能点，不能简化或者遗漏，只能对齐或者超越 |
| 许可证 | MIT（与原项目一致） |

---

## 5. 不做的事（Non-Goals）

> 下列为当前范围边界。

1. **不追求所有内部形状相同** — 以可观察行为对齐为准；会话 JSONL 与 pi 主流 v3 双向互读，SQLite schema 保持本仓独立设计。
2. **不内置全部长尾 Provider** — Provider SPI 支持扩展，内置范围按 R2。
3. **不做 TypeScript 扩展运行时、codemode、durable 或 chord** — 按当前 R5 裁决执行。
4. **不复制 Bun 或 Native Image 分发** — 使用 fat jar。
5. **不做 Slack/Chat 集成** — 独立项目范围；`pi-java-web` 是本项目既有交互面。

---

## 6. 关键设计决策

> 当前技术决策。

| 决策项 | 选项 | 推荐 |
|--------|------|------|
| 构建系统 | Maven / Gradle | **Maven** — 声明式配置，依赖管理更成熟 |
| HTTP 客户端 | `java.net.http.HttpClient` / OkHttp | **HttpClient** — JDK 内置，虚拟线程友好 |
| JSON 处理 | Jackson / Gson / javax.json | **Jackson** — CBOR 模块复用，流式解析成熟 |
| TUI 库 | TamboUI | **TamboUI** — 源自 Ratatui（Claude CLI 同源），内置差量渲染、Widget 库、CSS 主题、GraalVM 支持 |
| 会话存储 | JSONL（默认，pi v3 格式）/ SQLite（可选后端，本仓独立 schema） | **JSONL 为主、SQLite 可选**；二者均为持久后端，按配置选择 |
| 评估框架 | JUnit 5 + vitest-evals 等价物 / 自研 | **自研 conformance harness** — 端到端 agent 行为验证 + provider 兼容性测试，对齐 pi evals |
| 分发形态 | fat jar / Native Image | **fat jar**（Native Image 已放弃） |
| JSON Schema 校验 | networknt/json-schema-validator / 自研 | **networknt** — 成熟，与 Jackson 集成好 |
