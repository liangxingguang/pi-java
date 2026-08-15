# 日志方案 — 设计文档

> **目标**：为 pi-java 引入成熟日志框架，提供程序运行过程的 debug 日志，用于排查 LLM 调用、工具执行、会话生命周期、TUI 交互中的运行问题。可定位到模块 + 带完整异常堆栈。
> **范围**：跨模块基础设施（非某个 Phase 专属），建议与 Phase 4/5 并行推进。
> **对齐基准**：pi 无日志框架（仅 `console.error` + `--verbose` 启动诊断）；本设计为 **pi-java 自主增强**（Java 生态下引入 slf4j+logback），非对齐 pi 的行为，特此记录。
> **非目标**：结构化 JSON 日志、远程日志上报、MDC 追踪、性能指标（后者属 `telemetry` 模块）、会话数据持久化（属 Phase 4 JSONL/SQLite）。

---

## 1. 框架选型：slf4j-api + logback-classic

| 方案 | 成熟度 | 依赖 | 结论 |
|---|---|---|---|
| **slf4j + logback** | 事实标准 | slf4j-api + logback-classic（传递 logback-core） | ✅ 选用 |
| slf4j + log4j2 | 成熟、异步强 | 更重、配置更繁 | 备选，无必要 |
| java.util.logging | 零依赖 | JDK 内置 | API 笨、无滚动、格式化差 |

- 理由：`logger.info("... {}", arg)` 占位符 API 是 Java 生态标准；logback 自带滚动文件、异步 appender、MDC。
- **NF1「Pure Java 优先」在此显式放宽**（已获人确认）：日志框架属「无可替代」场景，不重复造轮子。
- **GraalVM native（NF5，Phase 5）**：logback 需 native-image 反射配置（`reflect-config.json` + `--initialize-at-build-time`）。本设计记录该风险，Phase 5 落地时补，本阶段不阻塞。

## 2. 依赖落地（关键：库模块不绑定后端）

```
slf4j-api（compile）  →  ai / agent / coding-agent / tui / session-backend-sqlite（凡打日志的模块）
logback-classic（runtime） →  仅 coding-agent + tui（两个应用入口）
```

- 库模块（ai/agent/sqlite）只依赖 **slf4j-api**（门面），不强制后端——可被任意宿主复用，后端由入口程序决定。
- 后端只在 `coding-agent`、`tui` 两个入口模块 `runtime` 引入，避免传递污染。
- `evals`/`protocol`/`telemetry` 按需再接入。

**pom 变更**（沿用现有 `dependencyManagement` + `<properties>` 版本属性约定，见根 `pom.xml`）：

```xml
<properties>
  <version.slf4j>2.0.16</version.slf4j>        <!-- 实施时锁定最新稳定版 -->
  <version.logback>1.5.16</version.logback>
</properties>
```

- `pi-java-ai` / `pi-java-agent-core` / `pi-java-session-backend-sqlite` / `pi-java-coding-agent` / `pi-java-tui`：`org.slf4j:slf4j-api`（compile）。
- `pi-java-coding-agent` / `pi-java-tui`：`ch.qos.logback:logback-classic`（runtime）。

## 3. 输出目标与 TUI 处理

| 模式 | console appender（stderr） | file appender |
|---|---|---|
| 非交互（`-p` print mode） | ✅ 开 | ✅ 开 |
| 交互 TUI（alternate screen） | ❌ 关（会破坏渲染） | ✅ 开 |

- 文件：`~/.pi-java/logs/pi-java.log`，滚动 10MB × 5 个，pattern 含时间戳。
- **TUI 模式下 console 关闭、只写文件**——最容易踩的坑（raw mode + alternate screen 下往 stderr 写会糊屏）。pi 同思路（错误只在不进 TUI 时 `console.error`）。
- 实现方式：默认 `logback.xml` 双 appender；入口初始化时按模式**程序化**将 console appender 的 level 置 `OFF`（TUI 模式），保留 file appender。

## 4. 级别与开关

- 默认 **INFO**（关键生命周期 + 错误）。
- `--debug` → **DEBUG**（LLM 请求/响应、工具命令）。
- **TRACE**（逐流式事件、逐 TUI 按键）不设 CLI 开关，经 `~/.pi-java/logback.xml` 覆盖级别启用。

> **命名决策**：`--debug` 而非 `--verbose`——`--verbose` 已被 `Args.java:89` 占用（语义「force verbose startup」），不冲突复用。

## 5. 配置

- 打包内置默认 `logback.xml`（console + file 双 appender、含异常堆栈）。
- 用户可选 `~/.pi-java/logback.xml` 覆盖（存在才加载，否则用内置默认）。
- 级别初值由 `--debug` 注入（`DEBUG` 否则 `INFO`）。

## 6. 类设计

```java
// pi-java-coding-agent .../core/Logging.java
/** 日志初始化：解析 --debug → 设根级别；按是否 TUI 决定 console appender 开关。 */
public final class Logging {
    public static void configure(boolean debug, boolean tui);
    // 1) 定位内置 logback.xml（或 ~/.pi-java/logback.xml 覆盖）
    // 2) 根 level = debug ? DEBUG : INFO
    // 3) tui ? console appender.level = OFF : 保持
    private Logging() {}
}
```

- 初始化点：`coding-agent` 的 `Main.main()`（CLI 入口）与 `tui` 的 `PiTuiApp`（交互入口）各自调用一次。
- 依赖方向：若 `tui` 可依赖 `coding-agent` 则复用该 helper；否则 `tui` 用等价的小型初始化。实现时按现有 `TuiEntryPoint` SPI 的依赖方向定。

## 7. 埋点清单（按层）

| 层 | 埋点 | 级别 |
|---|---|---|
| **ai** | provider/model 选择、请求体大小、响应 usage/stopReason、重试与流中断原因 | DEBUG；异常 ERROR（带堆栈） |
| **agent（harness）** | run/lane 生命周期、entry/record 提交、工具调用参数（脱敏）/返回码/超时、compaction 触发 | DEBUG/INFO |
| **coding-agent** | 会话 create/open/fork/delete、slash 命令、CLI 参数解析、设置加载 | INFO/DEBUG |
| **tui** | 按键/鼠标/滚动事件、流式事件、渲染异常 | TRACE/ERROR |
| **sqlite** | 迁移执行、writer lease 抢占/丢失、FTS 建表失败 | INFO/WARN/ERROR |

- 异常统一 `logger.error("...", e)`，带完整 `stackTrace`（当前多处只打 `message` 丢堆栈）。
- 工具参数/密钥等敏感字段**脱敏**（截断 + 掩码），不落完整内容。

## 8. 测试策略

- `LoggingTest`：`--debug` → 根级别 DEBUG；默认 → INFO；TUI 模式 → console appender OFF、file 保留。
- 埋点冒烟：`AgentSessionToolIntegrationTest` 注入 FauxProvider 跑一轮工具执行，断言 file appender 有对应 DEBUG 行（不崩溃、不糊 stdout）。
- 无 `System.out.println` 残留门禁保留（改为「日志经 slf4j，无裸 print」）。

## 9. 验收标准

- [ ] 非交互模式：`pi-java -p "..."` 运行后，stderr 有 INFO+ 日志、`~/.pi-java/logs/pi-java.log` 有完整记录（含异常堆栈）。
- [ ] `--debug` 追加后，出现 LLM 请求/工具命令的 DEBUG 行。
- [ ] TUI 模式：滚动/流式/工具调用全程无糊屏，日志仍写文件。
- [ ] `~/.pi-java/logback.xml` 覆盖生效（改级别后重启生效）。
- [ ] `mvn clean verify` + Checkstyle 通过；无 `System.out.println` 残留。
- [ ] 敏感字段（密钥）在 DEBUG 输出中被脱敏。

## 10. 不做

- 结构化 JSON 日志、远程上报、日志采样/聚合。
- MDC 请求追踪（可后续）。
- 替换 `telemetry` 模块（指标/追踪独立，不并进日志）。

## 11. 设计审查记录

### v1.0（2026-08-15 初稿）

- 选定 slf4j-api + logback-classic（NF1 显式放宽），库模块只依赖门面、后端收敛到两个入口。
- TUI 模式 console appender 关闭（避免 alternate screen 糊屏），日志仍落文件。
- 开关用 `--debug`（`--verbose` 已被占用）。
- GraalVM native 反射配置列为 Phase 5 风险，本阶段不阻塞。
