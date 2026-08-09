# pi-java 实施计划

> 本文档定义 pi-java 项目的分阶段实施路线图，包括里程碑、任务分解、工时估算和风险管理。

---

## 1. 总览

> **阶段设计文档约定**：每个阶段的第一项任务是编写 `docs/XX-phaseN-xxx-design.md`——从 `03-detailed-design.md` 中提取该阶段相关内容，扩展为独立的设计文档。这份文档既是该阶段的实施蓝图，后续也整理为开发教程。

| 阶段 | 代号 | 目标 | 预计工时 | 产出 |
|------|------|------|----------|------|
| Phase 0 | 基础设施 | 项目骨架、构建系统、CI | 1 周 | 可构建的空项目 + `05-phase0-infrastructure-design.md` |
| Phase 1 | LLM API | 统一 LLM 调用层 | 3–4 周 | `pi-java-ai` 可用 + `06-phase1-ai-design.md` |
| Phase 2 | Agent 运行时 | Agent 循环 + 工具 | 4–5 周 | `pi-java-agent-core` 可用 + `07-phase2-agent-core-design.md` |
| Phase 3 | CLI + TUI | 交互式终端 | 2–3 周 | `pi-java` 命令行可用 + `08-phase3-cli-tui-design.md` |
| Phase 4 | 持久化与恢复 | 会话存储、压缩 | 3–4 周 | 完整会话生命周期 + `09-phase4-persistence-design.md` |
| Phase 5 | 原生分发 | GraalVM Native Image | 1–2 周 | 独立二进制文件 + `10-phase5-native-design.md` |
| Phase 6 | 生态扩展 | 更多 Provider、远程会话 | 按需持续 | 持续迭代 + `11-phase6-ecosystem-design.md` |

**总估算 MVP（Phase 0–4）**：13–17 周（约 3–4.5 个月，单人全职）

---

## 2. Phase 0 — 基础设施（第 1 周）

### 目标
搭建可构建、可测试、可 CI 的项目骨架。

### 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P0-0 | **编写阶段设计文档** | `05-phase0-infrastructure-design.md`（从 03 提取 + 扩展） | 0.5d |
| P0-1 | 创建 Maven 多模块项目（11 模块） | `pom.xml` × 12（根 + 11 模块，含 BOM） | 0.5d |
| P0-2 | 配置 `module-info.java` | 每个模块的 JPMS 声明 | 0.5d |
| P0-3 | 配置 Checkstyle / SpotBugs | 代码风格 + 静态分析 | 0.5d |
| P0-4 | 配置 `maven-enforcer-plugin` | 依赖收敛 + JDK 26 版本约束 | 0.5d |
| P0-5 | GitHub Actions CI | 构建 + 测试 + Checkstyle | 1d |
| P0-6 | 写入 `CONTRIBUTING.md` + `AGENTS.md` | 贡献指南 | 0.5d |
| P0-7 | 配置 `.gitignore` + `.editorconfig` | 项目约定 | 0.5d |
| P0-8 | 搭建测试基础设施 | JUnit 5 + AssertJ + Mockito | 0.5d |

### 里程碑
- [ ] `mvn clean verify` 通过
- [ ] CI 绿灯
- [ ] 所有模块包含基本信息（README、模块描述）

---

## 3. Phase 1 — LLM API 层（第 2–5 周）

### 目标
提供统一的 LLM 调用接口，首批支持 5 个主流 Provider。

### 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P1-0 | **编写阶段设计文档** | `06-phase1-ai-design.md`（从 03 §1 提取 + 扩展） | 0.5d |
| P1-1 | 定义核心接口 | `Provider`、`StreamApi`、`ChatApi`、`ModelId` | 1d |
| P1-2 | 实现消息/流事件类型 | `Message`、`StreamEvent` 密封层次 | 1d |
| P1-3 | 实现 HTTP 传输层 | `PiHttpClient`（HttpClient 封装） | 1d |
| P1-4 | 实现 Anthropic Provider | `AnthropicProvider` + `AnthropicChatApi` | 2d |
| P1-5 | 实现 OpenAI Provider | `OpenAIProvider` + `OpenAIChatApi` | 2d |
| P1-6 | 实现 Google Gemini Provider | `GoogleProvider` + `GoogleChatApi` | 2d |
| P1-7 | 实现 DeepSeek Provider | `DeepSeekProvider`（OpenAI 兼容） | 1d |
| P1-8 | 实现 Mistral Provider | `MistralProvider` + `MistralChatApi` | 1.5d |
| P1-9 | Provider 注册机制（SPI） | `ProviderFactory` + ServiceLoader | 1d |
| P1-10 | 模型目录系统 | `BuiltinCatalog` + 内置模型数据 | 1d |
| P1-11 | 认证系统 | `EnvApiKeyResolver` + `CredentialStore` 接口 | 1d |
| P1-12 | Faux Provider（测试用） | 可编程的假 Provider | 1d |
| P1-13 | `pi-ai` CLI 子命令 | `list-models`、`auth`、`ping` | 1d |
| P1-14 | 单元测试 + 集成测试 | 覆盖率 > 80% | 3d |

### 里程碑
- [ ] 5 个 Provider 流式调用均可工作
- [ ] `pi-ai list-models` 输出正确
- [ ] Faux Provider 可用于下游测试

---

## 4. Phase 2 — Agent 运行时（第 6–10 周）

### 目标
实现可用的 Agent 循环引擎，包含工具系统和会话管理。

### 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P2-0 | **编写阶段设计文档** | `07-phase2-agent-core-design.md`（从 03 §2 提取 + 扩展） | 0.5d |
| P2-1 | Entry + LaneRecord 类型系统 | `Entry` + `LaneRecord` 密封接口（对齐 pi） | 1d |
| P2-2 | AgentHarness 核心 | 多车道模型、手动驱动、快照订阅、11 个 Hook | 5d |
| P2-3 | 工具注册与执行 | `Tool` 接口 + `ToolRegistry` | 1d |
| P2-4 | Bash 工具 | 进程管理（ProcessBuilder + 虚拟线程） | 2d |
| P2-5 | 文件读写工具 | Read / Write / Edit / EditDiff | 2d |
| P2-6 | 搜索工具 | Find（glob 匹配）+ Grep（正则搜索） | 1d |
| P2-7 | LS 工具 | 目录列表（NIO） | 0.5d |
| P2-8 | 图片处理工具 | 图片读取/压缩（javax.imageio + FFM） | 1.5d |
| P2-9 | 系统提示构建器 | 模板化 System Prompt | 1d |
| P2-10 | Agent 循环基础版 | 用户消息 → LLM → 工具结果 → 循环 | 3d |
| P2-11 | 模型路由 | 根据任务自动选模型 | 1d |
| P2-12 | 遥测系统 | Telemetry 接口 + 事件计数 | 1d |
| P2-13 | 压缩（Compaction）v1 | 简单截断策略 | 1d |
| P2-14 | 单元测试 + 集成测试 | 使用 Faux Provider | 3d |
| P2-15 | 事件系统（Hooks + Events） | 11 个 Hook + EventBus | 1d |
| P2-16 | HTTP 代理检测 | 自动检测系统代理配置 | 0.5d |

### 里程碑
- [ ] Agent 循环可完成多轮工具调用
- [ ] 所有内置工具可独立测试
- [ ] 遥测事件正确记录

---

## 5. Phase 3 — CLI + TUI（第 11–13 周）

### 目标
提供完整的交互式终端体验。

### 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P3-0 | **编写阶段设计文档** | `08-phase3-cli-tui-design.md`（从 03 §3–4 提取 + 扩展） | 0.5d |
| P3-1 | TamboUI 依赖集成 + Panama 后端配置 | 引入 TamboUI 库，配置 Panama 终端后端 | 0.5d |
| P3-2 | TCSS 主题系统（暗色/亮色主题） | TamboUI TCSS 暗色/亮色双主题 | 1d |
| P3-3 | 业务组件开发 | ChatPanel, MessageBubble, ToolCallCard, DiffView | 3d |
| P3-4 | Markdown → TamboUI Widget 转换桥接 | Markdown 内容映射为 TamboUI Widget 树 | 1d |
| P3-5 | 编辑器组件（基于 TamboUI TextArea） | 多行输入 + 语法高亮 + 补全 | 1d |
| P3-6 | 主应用壳 PiTuiApp + 全局快捷键 | 应用主框架 + 全局 Keybinding | 2d |
| P3-7 | 会话浏览器 + 设置页 | 交互式会话列表 + JSON 配置 UI (settings.json) | 1d |
| P3-8 | CLI 参数解析 | `ArgsParser`（picocli） | 1d |
| P3-9 | Print Mode | `-p "prompt"` 一次性调用 | 1d |
| P3-10 | 交互模式主循环 | TUI 模式集成 | 3d |
| P3-11 | 设置管理 | 全局 + 项目级 JSON 配置 (settings.json)，与 pi 对齐 | 1d |
| P3-12 | 手动测试 + 调优 | 终端兼容性测试 | 2d |
| P3-13 | Slash 命令系统（23 built-in commands） | 内置命令注册 + 扩展机制 | 2d |

### 里程碑
- [ ] `pi-java` 交互模式可用
- [ ] `pi-java -p "hello"` 输出正确
- [ ] 支持 Windows Terminal / macOS Terminal.app / iTerm2 / Alacritty

---

## 6. Phase 4 — 持久化与恢复（第 14–17 周）

### 目标
SQLite + JSONL v4 双轨会话存储（1:1 对齐 pi），含 FTS5 全文搜索、写租约、分支缓存和崩溃恢复。

### 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P4-0 | **编写阶段设计文档** | `09-phase4-persistence-design.md`（从 03 §5–6 提取 + 扩展） | 0.5d |
| P4-1 | SQLite schema + migrations | 12 tables, `001_initial.sql` | 2d |
| P4-2 | SqliteSessionRepository | create/open/list/delete/fork | 2d |
| P4-3 | SqliteSessionStorage | lanes, entries, records, facts, stats, log | 3d |
| P4-4 | Writer leases | TTL + heartbeat，跨进程 fencing | 1d |
| P4-5 | Branch cache | materialized branch paths | 1d |
| P4-6 | FTS5 search backend | trigram tokenizer, BM25 评分 | 1d |
| P4-7 | JSONL v4 格式兼容 + 导入/导出 | 旧版 v3 兼容 | 1d |
| P4-8 | 压缩 v2 | 智能摘要压缩（可选 LLM 驱动） | 2d |
| P4-9 | 信任管理 | 项目级信任标记持久化（`~/.pi-java/trust/`） | 0.5d |
| P4-10 | 集成测试（端到端） | 完整会话 → 中断 → 恢复流程 | 2d |

### 里程碑
- [ ] 会话可跨进程重启恢复
- [ ] 崩溃仅丢失最后一行未完整写入的数据
- [ ] 分支和合并功能正常
- [ ] 并发会话不互相干扰（写租约验证）

---

## 7. Phase 5 — 原生分发（第 18–19 周）

### 目标
通过 GraalVM Native Image 生成独立可执行文件。

### 任务分解

| 编号 | 任务 | 产出 | 工时 |
|------|------|------|------|
| P5-0 | **编写阶段设计文档** | `10-phase5-native-design.md`（GraalVM 配置 + 平台矩阵） | 0.5d |
| P5-1 | GraalVM 配置 | `native-image.properties` + reflection config | 2d |
| P5-2 | 静态分析排除 | 处理 Jackson/Jackson-CBOR 的反射需求 | 1d |
| P5-3 | 构建脚本 | `mvn -Pnative package` 一键构建 | 1d |
| P5-4 | CI 自动化 | GitHub Actions 的 Native Image 构建矩阵 | 1d |
| P5-5 | 平台测试 | win-x64, mac-arm64, linux-x64 | 2d |

### 里程碑
- [ ] 三个平台的 Native Image 构建成功
- [ ] 启动时间 < 100ms
- [ ] 内存占用 < 50MB 空闲

---

## 8. Phase 6 — 生态扩展（持续）

### 可选任务

| 优先级 | 任务 | 工时 |
|--------|------|------|
| P6-0 | **编写阶段设计文档** | `11-phase6-ecosystem-design.md`（Provider 扩展 + RPC + evals） | 0.5d || 高 | 新增 Provider（35 个）：Amazon Bedrock, Azure OpenAI, Cloudflare Workers AI, Cohere, DeepInfra, Fireworks AI, GitHub Models, Google Vertex AI, Groq, HuggingFace, Hyperbolic, Lambda, Minimax, Nebius, Nvidia NIM, OctoAI, Ollama, OpenRouter, Perplexity, Replicate, Sambanova, Scale, Snowflake Cortex, Sourcegraph, Together AI, WatsonX, xAI, Zhipu AI, Moonshot, Baidu Qianfan, Alibaba Bailian, Tencent Hunyuan, ByteDance Doubao, StepFun, 01.AI | 每个 0.5–2d |
| 高 | 评估框架（evals）— conformance tests | Provider/API 合规性测试套件 | 3d |
| 高 | 评估框架（evals）— smoke tests | 快速冒烟测试（每个 Provider 1 个请求） | 2d |
| 高 | 评估框架（evals）— extension tests | 扩展/插件集成测试 | 2d |
| 中 | RPC 模式（JSONL） | 3d |
| 中 | 技能系统（Skills） | 3d |
| 中 | 扩展系统（Extensions / Plugin） | 5d |
| 中 | 远程模型目录更新（ETag） | 2d |
| 低 | CBOR 协议 + 远程会话 | 10d |
| 低 | 模型目录 CLI 发布工具 | 3d |
| 低 | Maven Central 发布流水线 | 2d |

---

## 9. 风险管理

### 9.1 风险矩阵

| 序号 | 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|------|---------|
| R1 | **TUI 跨平台兼容性差** | 高 | 中 | TamboUI 自带终端兼容层（Panama + JLine3），已在主流终端验证；Phase 3 第一天配置 3 平台 CI 矩阵（`windows-latest` / `macos-latest` / `ubuntu-latest`）；用 tmux 自动化截屏回归；发布前人在 3 平台真机跑交互模式 |
| R2 | **GraalVM Native Image 反射配置** | 中 | 高 | 从 Day 1 用 Tracing Agent 自动生成 `reflect-config.json`（不手写）；Provider SPI 用 `ServiceLoader`（Native Image 原生支持）；选库时优先选已有 GraalVM 方案（Jackson、TamboUI、Picocli）；每个 PR 都跑 `mvn -Pnative package` |
| R3 | **LLM Provider API 变更** | 中 | 中 | 每个 Provider 独立包；Faux Provider 录制真实响应离线回放；Phase 6 evals 含每个 Provider 的 smoke test（CI 定期跑）；请求带 `User-Agent: pi-java/<version>` 头 |
| R4 | **虚拟线程 Bug** | 低 | 低 | 仅在 LLM 流处理 + bash 等待两处使用；`StructuredTaskScope` 管理子任务；启动参数 `-Dpi.virtual-threads=false` 可降级到固定线程池 |
| R5 | **单人开发进度延迟** | 高 | 中 | AI 驱动开发（人只审核）；严格分层 + 接口先行，模块可并行开发；MVP 最短可验证路径 4 周；每阶段有强制验收标准 |
| R6 | **TamboUI 0.3.x API 不稳定** | 中 | 中 | `TamboUIAdapter` 隔离层封装所有直接依赖；CI 中锁定 TamboUI 精确版本；每次升版本单独 PR，先跑全量测试 |
| R7 | **AI 生成代码质量不稳定** | 中 | 中 | AI 自验证强制通过 `mvn verify`（零错误、零警告、Checkstyle 通过）；单 commit ≤ 500 行；大型功能拆分为多个小 PR；CI 直接拦截不符合规范的代码 |

### 9.2 进度保障四层模型

```
┌──────────────────────────────────────────────┐
│ 流程层：AI 驱动开发                          │
│ AI 写代码 + 自验证 → 人只审核               │
├──────────────────────────────────────────────┤
│ 架构层：接口先行                             │
│ 每个模块定义接口 → 模块可并行开发             │
│ StreamApi / SessionStorage / Tool / ...      │
├──────────────────────────────────────────────┤
│ MVP 层：最短可验证路径                       │
│ Phase 0 → 1 → 2 → 4 周可跑通完整流程         │
├──────────────────────────────────────────────┤
│ 验证层：里程碑强制验收                       │
│ 每阶段达标才进入下一阶段                     │
└──────────────────────────────────────────────┘
```

### 9.3 关键策略：接口先行实现并行

```
Phase 0（基础设施）
    │
    ▼
Phase 1（AI 接口层）── 产出 StreamApi / Provider 接口
    │
    ├──────────────────────────────────────────────┐
    ▼                                              ▼
Phase 2（Agent 运行时）                     Phase 4（存储层）
用 Faux Provider 并行开发                   用 SessionStorage 接口独立测试
    │                                              │
    └──────────────────┬───────────────────────────┘
                       ▼
              Phase 3（CLI + TUI）
              各模块联调 + 集成测试
```

Phase 2 和 Phase 4 可以并行：AgentHarness 用 Faux Provider + InMemorySessionStorage 开发；SQLite 存储层用独立的 conformance 测试验证，互不阻塞。

---

## 10. 推荐开始路径

**最短可验证路径（约 4 周）**：

```
Week 1:  Phase 0 — 项目骨架
Week 2:  Phase 1 — Anthropic + OpenAI Provider + Faux Provider
Week 3:  Phase 1 — 其余 3 个 Provider + 认证 + CLI
Week 4:  Phase 2 — AgentHarness + Bash/Read/Write 工具 + 基本 Agent 循环
```

此时即可通过命令行运行 `pi-java -p "write a hello world"` 完成完整的端到端调用链。
