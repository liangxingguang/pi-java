# pi-java 需求分析

> **项目定位**：使用 JDK 26 + Pure Java 复刻 [pi](https://github.com/earendil-works/pi) 项目——一个自扩展的 AI 编码代理（coding agent）、通用代理运行时（agent runtime）以及统一多供应商 LLM API 网关。

---

## 1. 项目背景

pi（原名 pi-mono）是由 earendil-works 开发的开源 AI 编码代理，采用 TypeScript + npm workspaces 单体仓库架构，共 10 个工作区包，~1,110 个源文件，经过一年多的密集开发（5,582 次提交）。其核心能力分为三层：

- **LLM API 层**（`packages/ai`）：统一 40 个内置 LLM 供应商（含图片供应商）的 API 差异，提供一致的流式调用接口
- **代理运行时层**（`packages/agent`）：Agent 循环、工具调用、会话管理、持久化存储
- **编码代理层**（`packages/coding-agent`）：交互式 CLI、终端 TUI、slash 命令、技能系统

pi-java 的目标是使用 **JDK 26** 以 **Pure Java**（尽可能减少外部依赖）方式重新实现以上三层，保留 pi 的架构理念同时充分利用 Java 平台的特性优势。

---

## 2. 功能需求

### 2.1 LLM API 模块（类比 `@earendil-works/pi-ai`）

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| F1 | 统一 LLM API 接口 | P0 | 定义 `StreamApi`、`SimpleApi` 等统一抽象，屏蔽供应商差异 |
| F2 | 多供应商支持 | P0 | 全量 40 个内置 LLM 供应商（含图片供应商）；Phase 1 完成 5 个核心 provider，Provider SPI 从 Day 1 支持全量扩展 |
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
| F11 | Durable AgentHarness | P0 | 车道（lane）模型、操作记录、快照、确定性手动驱动模式 |
| F12 | 工具系统 | P0 | 可扩展的工具注册、参数校验（JSON Schema）、执行回调 |
| F13 | 内置工具 | P0 | Bash 执行、文件读写（Read/Write/Edit/EditDiff）、Grep、Find、LS、图片处理 |
| F14 | 会话管理 | P0 | 会话树（session tree）、分支、命名、列表、删除 |
| F15 | 会话持久化 | P0 | JSON Lines（JSONL）格式，支持增量写入和崩溃恢复 |
| F34 | SQLite 会话存储后端 | P0 | 实现 `SessionRepository`/`SessionStorage` 接口，含 FTS5 全文搜索、写租约（writer lease）、分支缓存（对应 `packages/session-backends/sqlite-node`） |
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
| NF2 | JDK 26 基线 | P0 | 利用虚拟线程、结构化并发、模式匹配、Foreign Function API、sealed classes 等新特性 |
| NF3 | 编译期类型安全 | P0 | 利用 Java 泛型、密封类、记录类实现比 TypeScript 更强的类型约束 |
| NF4 | 跨平台 | P0 | Windows / macOS / Linux 三个主要平台 |
| NF5 | 原生二进制分发 | P1 | 通过 GraalVM Native Image 编译为独立可执行文件 |
| NF6 | 响应式 / 非阻塞 I/O | P1 | LLM 流式响应使用 Flow API 或虚拟线程模拟同步风格 |
| NF7 | 可测试性 | P0 | 每个模块可独立测试；提供 faux/mock provider 用于离线测试 |
| NF8 | 模块化 | P0 | 基于 JPMS（Java Platform Module System）的多模块 Maven/Gradle 项目 |
| NF9 | 性能 | P2 | LLM 响应流处理延迟 < 10ms 额外开销；TUI 渲染 60fps 感知流畅 |
| NF10 | 崩溃恢复 | P1 | JSONL 增量写入，崩溃后可从最后一个完整记录恢复 |

---

## 4. 约束与前提条件

| 约束 | 说明 |
|------|------|
| 语言版本 | Java 22+（语言特性），JDK 26 作为构建和运行基线 |
| 构建系统 | Maven（推荐，JPMS 支持更成熟）或 Gradle |
| 外部依赖上限 | 核心模块 ≤ 5 个外部依赖；CLI 模块可放宽至 ~15 个 |
| 命名空间 | Maven groupId: `com.pi-java`；JPMS 模块名: `com.pijava`（JPMS 不允许连字符） |
| API 兼容性 | 不承诺与 pi（TypeScript）的 API 兼容；仅复刻架构理念和功能 |
| 功能点 兼容性 | 1：1复刻pi功能点，不能简化或者遗漏，只能对齐或者超越 |
| 许可证 | MIT（与原项目一致） |

---

## 5. 不做的事（Non-Goals）

1. **不追求与 pi 的线路级兼容** — 协议层面（CBOR 帧格式、RPC 消息词汇）无需对齐；JSONL v4 mutation 格式与 pi 保持一致；coding-agent 的 JSONL v3 格式作为导入/导出兼容；SQLite 后端 Schema 与 pi 对齐
2. **分阶段实现 provider** — Phase 1 完成 5 个核心 provider（Anthropic, OpenAI, Google, DeepSeek, Mistral），Phase 6 补齐全部 40 个，Provider SPI 架构从 Day 1 就支持全量扩展
3. **不复制 Bun 二进制编译** — 改用 GraalVM Native Image 作为唯一的原生分发方式
4. **不做浏览器端** — 纯终端 + RPC，无 Web UI
5. **不做 Slack/Chat 集成** — 那是独立项目 `pi-chat` 的范围，不在本项目中

---

## 6. 关键设计决策（待确认）

| 决策项 | 选项 | 推荐 |
|--------|------|------|
| 构建系统 | Maven / Gradle | **Maven** — JPMS 支持更成熟，声明式配置更清晰 |
| HTTP 客户端 | `java.net.http.HttpClient` / OkHttp | **HttpClient** — JDK 内置，虚拟线程友好 |
| JSON 处理 | Jackson / Gson / javax.json | **Jackson** — CBOR 模块复用，流式解析成熟 |
| TUI 库 | TamboUI | **TamboUI** — 源自 Ratatui（Claude CLI 同源），内置差量渲染、Widget 库、CSS 主题、GraalVM 支持 |
| 会话存储 | SQLite + JSONL v4 双轨 | **SQLite + JSONL v4 双轨（1:1 对齐 pi）** — SQLite 为主存储（FTS5 搜索、写租约）、JSONL v4 为 mutation 日志和崩溃恢复 |
| 评估框架 | JUnit 5 + vitest-evals 等价物 / 自研 | **自研 conformance harness** — 端到端 agent 行为验证 + provider 兼容性测试，对齐 pi evals |
| 原生编译 | GraalVM Native Image / jpackage | **GraalVM Native Image** — 单一二进制，启动快 |
| JSON Schema 校验 | networknt/json-schema-validator / 自研 | **networknt** — 成熟，与 Jackson 集成好 |
