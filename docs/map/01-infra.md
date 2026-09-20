# 01 基础设施层（telemetry / protocol / client / server）

> ⚠️ **2026-09-20 文档整理**：本文件「台账条目」列里的 `phase1-pi-code-mapping:NN` 出处**已失效** ——
> 那份对照表已删（映射到 pi 已删的目录结构、百分比口径与本项目判据不可通约）。
> 标 `H` 的条目指 `docs/32 §9` 的 H 类；其原文需按 `git log --diff-filter=D` 取回。

> 基准：`pi-java` @ `34849a2`（main，2026-09-20）；`pi` @ **`3390bd936`**（2026-09-20，工作区 HEAD）。
> **pi 侧引用已于 2026-09-20 对新 HEAD 全量复测**（旧 HEAD `71dca871b` → 新 `3390bd936`，111 个提交）：
> `packages/{telemetry,protocol,client,server}/src` 两提交间**零改动**（`git diff --name-only` 空），
> 四模块的 CHANGELOG/package.json 只有版本号节（0.85.1 → 0.86.1）；全文件 582 条 `file:line` 引用**全部命中、零漂移**。
> 唯一变化在范围外：`packages/chord/src/delta/`（内部重构）与 `packages/coding-agent/src/experimental/micro/`（新子系统）⇒ 见 §规模与整块缺失的复测注。
> 判定单位 = **能力单元**（CLI 参数 / RPC 方法 / 事件类型 / 配置键 / span 名 / 契约级导出类型）；内部类与私有方法不计。
> 每条给两侧 `file:line`。

## 0. 三条必须先读的口径

### 0.1 判定三档的用法

| 档 | 含义 |
|---|---|
| **对齐** | pi 有、pi-java 有，且**行为相同**（读过两侧代码，不是类名像） |
| **缺失** | pi 有、pi-java 无 |
| **存疑** | 其余全部：① pi-java 有而 pi **无对照物**（扩展）；② pi-java 是 **pi 已删除版本**的移植；③ 两侧都有但**形状/行为不同**、够不上「行为相同」；④ 台账未结条目 |

**⚠️ 读表须知**：三档里没有「都有但形状不同」这一档 —— 这类按定义**不能算对齐**，全部落在 **存疑**（判定列的子标签 `（形状）`/`（旧 pi）`/`（扩展）`/`（台账）` 指明是哪一种）。所以本表的「完成度」是**严格口径**：只数行为逐条相同的。

### 0.2 参照物错位 —— 本层最大的单一事实

**`pi-java-protocol` 的整个「命令/结果/事件」层，参照的是 pi 已经删掉的代码。**

证据链：

- pi 现 `packages/protocol/src/` 只有 `cbor/`、`codec.ts`、`framing.ts`、`index.ts`、`protocol.ts`（`ls` 实测）——**没有 `schemas.ts`**。
- `grep -rl 'server_snapshot\|session_progress\|session_removed\|session_snapshot\|ServerSnapshotSchema\|SessionSnapshotSchema\|SessionPhaseSchema\|TranscriptItemSchema\|ModelMetadataSchema\|SessionMetadataSchema\|ProtocolThinkingLevel' --include='*.ts' packages/` → **全部零命中**（**2026-09-20 在新 HEAD `3390bd936` 复跑，结论不变**）。
  ⚠️ 单独 grep `CommandSchema` 会命中一处 `packages/coding-agent/src/experimental/session-worker.ts:123` —— 那是**无关**的 `SessionWorkerCommandSchema`（worker 控制协议，5 变体：shutdown/discover_workers/session_demand/operation/operation_cancel），**不是**会话 RPC 的 `CommandSchema`。
- pi 侧最后一个含 `CommandSchema` 的 `schemas.ts` 是 `1bd9c3f67`（2026-08-08，`PROTOCOL_VERSION = 1`）；它在 `e52de91d0`「feat(protocol): add service-addressed session RPC」（**2026-08-13**）被换成 `rpc.ts` + 瘦身版 `schemas.ts`。该提交同时删掉 `packages/client/src/session-handle.ts`(111)、`packages/client/src/state.ts`(156)、`packages/server/src/protocol.ts`(382)、`packages/server/src/sessions.ts`(346)、`packages/server/src/snapshots.ts`(62)。
- `git show e52de91d0^:packages/protocol/src/schemas.ts` 与 pi-java 逐项对上：9 个命令同名同字段、`SessionSnapshot` 14 字段同序、`ProtocolErrorCode` 7 值同集、`ServerHello{version, connectionId, snapshot}`、`ServerEvent` 4 变体、`PROTOCOL_VERSION = 1`（= `ProtocolVersion.java:9`）。
- 反证：pi 的 `Command` 判别键**始终**是 `command`（`1bd9c3f67:385`、`6189e53b3:291`、`e52de91d0^`），pi-java 用的是 `type`（`Command.java:14`、`CommandResult.java:16`）⇒ 连旧 pi 也没完全照抄。

**结论**：`framing.ts` + CBOR 层对齐**现** pi；`Command`/`CommandResult`/`ServerEvent`/快照族对齐的是**已被删除**的 pi（且判别键还改了）。这两半必须分开算，否则数字会骗人。

### 0.3 接线事实 —— client/server 在 pi-java 无生产消费者

- `grep -rln 'com\.pijava\.server' --include='*.java' pi-java-*/src` → 命中**只有 `pi-java-server` 自己** + `pi-java-server/src/test/.../PiServerClientIntegrationTest.java`。
- `grep -rln 'com\.pijava\.client' --include='*.java' pi-java-*/src` → 命中**只有 `pi-java-client` 自己** + 上面那一个测试。
- `PiServerService`（`PiServerService.java:14`）**全仓无实现类**；`pi-java-coding-agent` 的 CLI 无 `--serve`/`--connect`/`serverId` 任何字样（grep 零命中）。
- pi 侧相反：`packages/coding-agent/src/experimental/` 6852 LOC + `src/cli/experimental/` 504 LOC 是这套 RPC 的真实宿主（`client.ts`/`client-runtime.ts`/`server.ts`/`coordinator.ts`/`session-worker*.ts`/`services/*`）。

即：pi-java 的 client/server 是**能跑通集成测试的孤岛**，pi 的对应物是**已接线的生产子系统**。

---

## 规模

> **2026-09-20 复测**（旧 HEAD `71dca871b` → 新 `3390bd936`）：四模块 `src/` 零改动 ⇒ **LOC 与文件数全部不变**。

| 模块 | pi 包 | pi 文件数 | pi LOC | java 文件数 | java LOC | 比例 | 旧 LOC | 变化 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| pi-java-telemetry | `packages/telemetry`（`src/`，含 `testing/`） | 6 | 935 | 6 | 787 | 84% | 935 | 不变 |
| pi-java-protocol | `packages/protocol`（`src/`） | 8 | 869 | 23 | 748 | 86% | 869 | 不变 |
| pi-java-client | `packages/client`（`src/`） | 8 | 1135 | 7 | 374 | 33% | 1135 | 不变 |
| pi-java-server | `packages/server`（`src/`） | 16 | 1966 | 14 | 563 | 29% | 1966 | 不变 |

> 两侧均只计 `src/`（`main`）生产代码，不含测试。行数比不构成判据，仅示量级 —— 见 §0.1。
> 参照量级（**复测后**）：pi `packages/chord`（RPC 基座，pi-java 无对应包）**6503 LOC / 24 文件**（旧 5822 —— 本轮 `src/delta/` 重构 +681）；
> pi `packages/server/src/testing/` 431 LOC（不变）；pi `packages/telemetry/src/testing/` 339 LOC（不变）。
> pi 侧测试量：telemetry 243 / protocol 567 / client 799 / server 1057 LOC（均不变）；java 侧：telemetry 678 / protocol 214 / client **0** / server 212 LOC。

---

## 能力单元清单

### A. telemetry（17 条）

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 台账条目 | 权重 |
|---|---|---|---|---|---|---|---|
| T1 | `TelemetryContext.startSpan(options, callback)` | 契约级导出类型 | `telemetry/src/index.ts:14-16` | `TelemetryContext.java:40` | 存疑（形状） | — | 3 |
| T2 | `TelemetrySpan`（span 即 context，可嵌子跨度） | 契约级导出类型 | `telemetry/src/index.ts:18-22` | `TelemetrySpan.java:26` | 存疑（形状） | D2 / D3 | 3 |
| T3 | `SpanOptions {name, attributes}` | 契约级导出类型 | `telemetry/src/index.ts:7-10` | `SpanOptions.java:11` | **对齐** | — | 3 |
| T4 | `AttributeValue` 类型闭集（string/number/boolean + 三个数组） | 契约级导出类型 | `telemetry/src/index.ts:1` | `SpanOptions.java:11`（`Map<String,Object>`，无闭集） | 存疑（形状） | — | 1 |
| T5 | `TelemetrySpan.addEvent(name, attributes)` | 契约级导出类型 | `telemetry/src/index.ts:19` | 不移植（裁决见 `TelemetrySpan.java:13-19`） | **缺失** | H（`phase1-pi-code-mapping:130` 记的 `addEvent`/`addLink`；**pi 侧无 `addLink`，该半条是映射文档的错记**） | 1 |
| T6 | `TelemetrySpan.setAttributes(整包合并)` | 契约级导出类型 | `telemetry/src/index.ts:20` | `TelemetrySpan.java:39`（单键 `addAttribute`） | 存疑（形状） | — | 2 |
| T7 | `TelemetrySpan.setStatus` + `SpanStatus{ok\|error}` | 契约级导出类型 | `telemetry/src/index.ts:12,21` | 不移植；状态自动派生（`TelemetrySpan.java:14-20`、`JsonlFileTelemetry.java:318`） | **缺失** | — | 2 |
| T8 | `NOOP_TELEMETRY_CONTEXT` | 契约级导出类型 | `telemetry/src/noop.ts:20` | `NoopTelemetryContext.java:12` | **对齐** | — | 3 |
| T9 | `InMemoryTelemetryContext` + `RecordedTelemetrySpan`/`RecordedTelemetryEvent` | 契约级导出类型 | `telemetry/src/memory.ts:192,16,11` | 无 | **缺失** | H（`phase1-pi-code-mapping:134,:395`） | 1 |
| T10 | `createTelemetryAdapterConformance` 套件（9 用例 / 6 组：status·recording×3·parentage·passivity×2） | 契约级导出类型 | `telemetry/src/testing/conformance.ts:142,184,206,220,246,274,301` | 无 | **缺失** | C7（倾向不做，触发条件＝出现 OTel 之外的真 adapter）、H（`:134`） | 1 |
| T11 | `defineTelemetrySchema` + `createTypedSpanStarter` 类型化跨度词汇 | 契约级导出类型 | `telemetry/src/index.ts:72,349` | 无 | **缺失** | H（`phase1-pi-code-mapping:134`） | 1 |
| T12 | `TelemetryContext.openSpan(options)`（无回调开跨度） | 契约级导出类型 | 无（pi 无对照物） | `TelemetryContext.java:47` | 存疑（扩展） | D3 | 3 |
| T13 | `recordEvent` / `recordsPayloads` / `pushCurrent` / `popCurrent`（事件行 + 线程绑定） | 契约级导出类型 | 无 | `TelemetryContext.java:75,82,93,102` | 存疑（扩展） | C5 | 2 |
| T14 | `incrementCounter` / `recordTiming` 指标 | 契约级导出类型 | 无 | `TelemetryContext.java:57,60` | 存疑（扩展） | — | 3 |
| T15 | `with(key, value)` 维度上下文 | 契约级导出类型 | 无 | `TelemetryContext.java:63` | 存疑（扩展） | D1 | 2 |
| T16 | `JsonlFileTelemetry` JSONL 落盘导出器（**每个会话无条件接线**，`AgentSession.java:292-293`；`--trace-payloads` 只门控 payload 事件行） | 契约级导出类型 | 无 | `JsonlFileTelemetry.java:44,107` | 存疑（扩展） | D4 / D5 | 3 |
| T17 | `OtelTelemetryContext` OTel 适配器 | 契约级导出类型 | 无（pi 只有 noop + 测试用 memory 两个 adapter） | `OtelTelemetryContext.java:22` | 存疑（扩展） | C7 | 1 |

**权重依据（telemetry，逐个可查）**

| 权重 | 单元 | 判据 |
|---|---|---|
| 3 | T1 T2 T3 | 每个跨度都走 `startSpan`/`SpanOptions`（默认路径） |
| 3 | T8 | 未配置遥测时是**默认实现**（默认路径） |
| 3 | T12 | `openSpan` 在默认路径每次调用：`RunSpanFactory.java:32`（每次运行）、`PiLaneSink.java:185`（每次 LLM 请求）、`PiLaneSink.java:237`（每次工具调用）、`CompactionExecutor.java:254,399` |
| 3 | T14 | 指标在默认路径每次请求/工具：`PiLaneSink.java:214-220,468-472`、`PiLaneEngine.java:95`、`RunSpanFactory.java:31`、`CompactionExecutor.java:249` |
| 3 | T16 | **每个会话无条件接线**（`AgentSession.java:292-293`）⇒ 每次会话都产 trace 文件 |
| 2 | T6 T7 | 属性/状态写入常见，但非每个跨度都发生（T7 的**可观测量已由 java 自动派生保住**，缺的只是显式设置 API） |
| 2 | T13 | `pushCurrent(llmSpan)` **每 LLM 请求**都调（`PiLaneSink.java:191`），但可观察性受 `--trace-payloads` 门控（默认关）⇒ 频率 3 × 影响 1，取 2 |
| 2 | T15 | `with("sessionId", …)` 每次会话一次（`AgentSession.java:292`） |
| 1 | T4 T5 T9 T10 T11 T17 | 内部契约形状 / 遥测细节 / 仅测试后端 / 纯类型层（pi 侧 12 个 `pi.*` 跨度 11 个零发射点，`docs/31 §8.28`）/ 无生产接线（Otel 需外部 SDK） |

### B. protocol（28 条）

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 台账条目 | 权重 |
|---|---|---|---|---|---|---|---|
| P1 | 4 字节大端无符号长度前缀 `encodeFrame` | 帧类型 | `protocol/src/framing.ts:28` | `FrameCodec.java:14` | **对齐** | — | 0 |
| P2 | 增量分帧 `FrameDecoder.push(chunk)` | 帧类型 | `protocol/src/framing.ts:44,64` | `FrameDecoder.java:33` | **对齐** | — | 0 |
| P3 | 帧上限默认 16 MiB | 配置键 | `protocol/src/framing.ts:6` | `ProtocolVersion.java:12` | **对齐** | — | 0 |
| P4 | `FrameError` + 解码器状态机（open/ended/failed、残留帧即抛） | 帧类型 | `protocol/src/framing.ts:12,49,60` | `FrameException.java:12`、`FrameDecoder.java:16,82` | **对齐** | — | 0 |
| P5 | CBOR 编解码 | 帧类型 | `protocol/src/cbor/encoder.ts`(216) + `decoder.ts`(168) | `CborCodec.java:12`（Jackson CBOR 封装，34 行） | 存疑（形状） | — | 0 |
| P6 | CBOR 安全上限（`DEFAULT_MAX_CBOR_BYTE_LENGTH`/`CONTAINER`/`DEPTH` + `CborError`） | 配置键 | `protocol/src/cbor/options.ts:6-8,25` | 无 | **缺失** | — | 0 |
| P7 | `PROTOCOL_VERSION` 常量 | 配置键 | `protocol/src/protocol.ts:5`（**= 8**） | `ProtocolVersion.java:9`（**= 1**） | 存疑（旧 pi） | — | 0 |
| P8 | `ServerId` 小写 UUIDv4 + `isServerId` 校验 | 契约级导出类型 | `protocol/src/protocol.ts:12-19` | 无 | **缺失** | — | 0 |
| P9 | `isSupportedProtocolVersion(version)` | 契约级导出类型 | `protocol/src/codec.ts:139` | 无（`PiServer.java:84-85` 裸等值比较） | **缺失** | — | 0 |
| P10 | `ClientHello {type:"hello", version}` | 帧类型 | `protocol/src/protocol.ts:29-33` | `ClientMessage.java:24` | **对齐** | — | 0 |
| P11 | `RequestEnvelope {type:"request", id, target, call}` | 帧类型 | `protocol/src/protocol.ts:49-54` | `ClientMessage.java:29`（`{id, request}`，**无 target**） | **缺失** | — | 0 |
| P12 | `CancelEnvelope {type:"cancel", id, target}` | 帧类型 | `protocol/src/protocol.ts:55-59` | 无 | **缺失** | — | 0 |
| P13 | `RpcTarget` / `SessionTarget {serverId, sessionId, attachmentId}` | 契约级导出类型 | `protocol/src/protocol.ts:36-47` | 无 | **缺失** | — | 0 |
| P14 | `ServerHello {type:"hello", version, serverId}` | 帧类型 | `protocol/src/protocol.ts:65-69` | `ServerMessage.java:27`（`{version, connectionId, snapshot}`） | 存疑（旧 pi） | — | 0 |
| P15 | `ServerHelloError {type:"hello_error", error}` | 帧类型 | `protocol/src/protocol.ts:70-73` | `ServerMessage.java:33` | **对齐** | — | 0 |
| P16 | `ResponseEnvelope {ok:true, result} \| {ok:false, error}` | 帧类型 | `protocol/src/protocol.ts:74-87` | `ServerMessage.java:38`（`{id, result, error}`，**去掉 ok 判别**） | 存疑（形状） | — | 0 |
| P17 | `ServiceEventEnvelope {type:"service_update", subscriptionId, update}` | 帧类型 | `protocol/src/protocol.ts:88-92` | `ServerMessage.java:44`（`{type:"event", event}`） | 存疑（旧 pi） | — | 0 |
| P18 | `AttachmentEnvelope {type:"attachment", attachment}` | 帧类型 | `protocol/src/protocol.ts:93-97` | 无 | **缺失** | — | 0 |
| P19 | 消息级 schema 严格校验（typebox `Check` + `additionalProperties:false` + `ProtocolValidationError`） | 契约级导出类型 | `protocol/src/codec.ts:13,20-32` | 无（Jackson 反序列化，无严格模式） | **缺失** | — | 0 |
| P20 | `encodeClientMessage` / `encodeServerMessage`（校验→CBOR→分帧 一步到位） | 契约级导出类型 | `protocol/src/codec.ts:56,61` | `CborCodec.java:18` + `FrameCodec.java:14` 两步，**无校验**（`PiClient.java:204`、`PiServer.java:265`） | 存疑（形状） | — | 0 |
| P21 | `ClientMessageDecoder` / `ServerMessageDecoder`（增量 + 校验 + 失败锁死） | 契约级导出类型 | `protocol/src/codec.ts:65-137` | 无 | **缺失** | — | 0 |
| P22 | `Command` 9 变体（list/create/attach/detach/prompt/steer/abort/set_model/set_thinking） | RPC 方法 | 现 pi **无**；旧 `e52de91d0^:schemas.ts`（判别键 `command`） | `Command.java:27-79`（判别键 **`type`**） | 存疑（旧 pi） | — | 0 |
| P23 | `CommandResult` 9 变体 | RPC 方法 | 现 pi 无；旧 `e52de91d0^:schemas.ts` | `CommandResult.java:29-78` | 存疑（旧 pi） | — | 0 |
| P24 | `ServerEvent` 4 变体（server_snapshot / session_snapshot / session_progress / session_removed） | 事件类型 | 现 pi 无（全仓 grep 零命中）；旧 `e52de91d0^:schemas.ts` | `ServerEvent.java:19-45` | 存疑（旧 pi） | — | 0 |
| P25 | 快照族 wire 类型（`ServerSnapshot` / `SessionSnapshot` / `SessionMetadata` / `ModelMetadata` / `ModelRef` / `TranscriptItem` / `TranscriptProgress` / `SessionPhase` / `ThinkingLevel`） | 契约级导出类型 | 现 pi 无；旧 `e52de91d0^:schemas.ts` | `ServerSnapshot.java:23`、`SessionSnapshot.java:44`、`SessionMetadata.java:117`、`ModelMetadata.java:98`、`ModelRef.java:87`、`TranscriptItem.java:176`、`TranscriptProgress.java:73`、`SessionPhase.java:135`、`ProtocolThinkingLevel.java:157` | 存疑（旧 pi） | — | 0 |
| P26 | `ProtocolError {code, message}` | 帧类型 | `protocol/src/protocol.ts:21-24` | `ProtocolError.java:6` | **对齐** | — | 0 |
| P27 | `ProtocolErrorCode` 取值集 | 配置键 | `protocol/src/protocol.ts:25`（**开放 string**）+ server 侧 `ServerOperationErrorCode` 联合（`server/src/errors.ts:3-9`） | `ProtocolErrorCode.java:11`（闭集 7 值，= 旧 pi） | 存疑（旧 pi） | — | 0 |
| P28 | `ByteConnection` 字节连接抽象 | 契约级导出类型 | `server/src/connection.ts:7-11`（send/close）+ `client/src/transport.ts:1-18`（send/onData/onClose/onError） | `ByteConnection.java:194`（`InputStream`/`OutputStream` 阻塞式） | 存疑（形状） | — | 0 |

**权重 0 的说明**：`protocol` 整模块按用户裁决排除出分母（依据＝pi 删了 `schemas.ts`，见 §0.2）。
⚠️ **该依据只覆盖 P22–P25 / P27 那一族**（＝参照物被删的旧 pi 移植）。下列两类**不**在该依据范围内，若日后拉回分母，用这里的建议权重：
- **P1–P4（framing/CBOR 基础件）**：pi 仍在且逐条相同 ⇒ 建议 3/3/2/2
- **P11–P13 / P18 / P19 / P21（现 pi 有、pi-java 无）**：**是真正的当前缺口**，不是「作废代码」⇒ 建议 3/3/1/2/2/3

建议权重全表（拉回分母时用）：P1=3 P2=3 P3=2 P4=2 P5=3 P6=1 P7=2 P8=1 P9=1 P10=3 P11=3 P12=1 P13=3 P14=3 P15=2 P16=3 P17=3 P18=2 P19=2 P20=3 P21=3 P22=3 P23=3 P24=3 P25=3 P26=2 P27=2 P28=3

### C. client（18 条）

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 台账条目 | 权重 |
|---|---|---|---|---|---|---|---|
| C1 | `Client.connect()` / 静态 `Client.connect()`（失败自动 dispose） | RPC 方法 | `client/src/client.ts:117,128` | `PiClient.java:58`（无静态版、失败不清理） | **对齐** | — | 0 |
| C2 | `reconnect()` | RPC 方法 | `client/src/client.ts:134` | 无 | **缺失** | — | 0 |
| C3 | `disconnect(reason)` | RPC 方法 | `client/src/client.ts:138` | `PiClient.java:141`（`close()`，无 reason、不可复用） | 存疑（形状） | — | 0 |
| C4 | `connectionState` + `onConnectionStateChange`（disconnected/connecting/connected） | 契约级导出类型 | `client/src/types.ts:5`、`client/src/client.ts:142` | 无（只有私有 `volatile connected`，`PiClient.java:50`） | **缺失** | — | 0 |
| C5 | `hello` getter（握手结果） | 契约级导出类型 | `client/src/client.ts:109` | 无（`PiClient.java:66-75` 只用它置位） | **缺失** | — | 0 |
| C6 | `attachment` getter + `onAttachmentChange` | 契约级导出类型 | `client/src/client.ts:113,148` | 无（`SessionHandle.java:18` 自持快照） | **缺失** | — | 0 |
| C7 | `request(target, call, signal)` 低层路由调用 | RPC 方法 | `client/src/client.ts:155` | `PiClient.java:82`（`send(Command)`，**无 target / 无 signal**） | **缺失** | — | 0 |
| C8 | `AbortSignal` → `cancel` 帧 | RPC 方法 | `client/src/client.ts:252-270` | 无 | **缺失** | — | 0 |
| C9 | `serviceCatalogue(target)` | RPC 方法 | `client/src/client.ts:159` | 无 | **缺失** | — | 0 |
| C10 | `subscribeService` + `ServiceSubscription{snapshot, start(), dispose()}` | RPC 方法 | `client/src/client.ts:172`、`client/src/types.ts:16-23` | `PiClient.java:111`（`subscribe(Consumer<ServerEvent>)`，无快照水合、无有序投递、无 dispose） | **缺失** | — | 0 |
| C11 | `dispose()` / `Symbol.asyncDispose` | RPC 方法 | `client/src/client.ts:379,394` | `PiClient.java:141`（`close()`；**不拒绝 pending 请求**，只能等超时，`PiClient.java:89-107`） | 存疑（形状） | — | 0 |
| C12 | `createClientServiceTransport`（Chord `RemoteServiceTransport` 适配） | 契约级导出类型 | `client/src/client.ts:448` | 无 | **缺失** | — | 0 |
| C13 | `ByteTransport` / `ByteTransportFactory` / `ByteTransportHandlers` | 契约级导出类型 | `client/src/transport.ts:1-18` | `ByteTransport.java:10`（`connect()` 返回 `ByteConnection`，拉式 IO） | 存疑（形状） | — | 0 |
| C14 | `ClientOptions{transportFactory, serverId, maxFrameLength, onListenerError}` | 配置键 | `client/src/types.ts:25-32` | `PiClientOptions.java:8`（`{transport, maxFrameLength, connectTimeout}`；**无 serverId 校验、无 onListenerError**） | 存疑（形状） | — | 0 |
| C15 | Unix 服务发现 `discoverUnixServers` / `createUnixTransportFactory` / `UnixServerRoute` | RPC 方法 | `client/src/unix.ts:19,24,29,37,88` | `UnixSocketTransport.java:75`（仅 `connect()`） | **缺失** | — | 0 |
| C16 | 客户端错误分类 `ServerError`/`DisconnectedError`/`ClientDisposedError`/`toError` | 契约级导出类型 | `client/src/errors.ts:3,14,21,26` | `PiClientException.java:6`（单一异常类型） | **缺失** | — | 0 |
| C17 | 握手时序护栏（服务端数据早于 hello / hello 前收到 hello / 重复 hello） | 事件类型 | `client/src/connection.ts:147,171,183,209` | 无 | **缺失** | — | 0 |
| C18 | `SessionHandle`（会话租约 + 快照跟踪 + detach 释放） | RPC 方法 | 现 pi **已删除**（`e52de91d0` 删 `client/src/session-handle.ts`，111 行） | `SessionHandle.java:14` | 存疑（旧 pi） | — | 0 |

**权重 0 的说明**：`client` 整模块按用户裁决排除出分母（依据＝pi 删了 `schemas.ts`，见 §0.2）。
⚠️ **该依据只覆盖 C18**（参照物被删的旧 pi 移植）。其余 17 条**不**在该依据范围内 —— 它们要么对齐**现** pi（C1/C13）、要么是**现 pi 有而 pi-java 无**的真缺口（C2/C4–C10/C12/C15/C16）⇒ 建议权重 3/2/2/2/1/2/3/1/2/3/2/2/3/2/2/2/1/3。

### D. server（22 条）

| # | 能力单元 | 类别 | pi 侧证据 | pi-java 侧证据 | 判定 | 台账条目 | 权重 |
|---|---|---|---|---|---|---|---|
| S1 | `Server.start()` → `Promise<this>`（重复 start 拒绝） | RPC 方法 | `server/src/server.ts:95-101` | `PiServer.java:55`（`void`、无重复保护） | 存疑（形状） | — | 0 |
| S2 | `Server.close()` → `Promise<void>` + `closed` promise（可 reject） | RPC 方法 | `server/src/server.ts:176,49` | `PiServer.java:63`（`void`、无 `closed`） | 存疑（形状） | — | 0 |
| S3 | 握手超时（`handshakeTimeoutMs`，默认 5000） | 配置键 | `server/src/server.ts:42,147-152` | `PiServerOptions.java:12` 声明，**`PiServer` 全文件零读取**（grep 实测）⇒ 死选项 | **缺失** | — | 0 |
| S4 | 连接阶段机 `awaitingHello/handshaking/ready/closing/closed` | 契约级导出类型 | `server/src/connection.ts:21,35` | 无（内联流程，`PiServer.java:83-108`） | **缺失** | — | 0 |
| S5 | 首帧必须 hello + 版本协商 | 事件类型 | `server/src/server.ts:224-245,262-269` | `PiServer.java:84-91`（等值比较 + `version` 错误；**无「hello 只能首帧」的后续拒绝** —— 第二次 hello 被静默丢弃） | 存疑（形状） | — | 0 |
| S6 | 重复 request id 拒绝（`invalid_request`） | 事件类型 | `server/src/server.ts:307-315` | 无 | **缺失** | — | 0 |
| S7 | `cancel` 信封 → 中止在飞请求（`AbortController`） | RPC 方法 | `server/src/server.ts:298-304` | 无 | **缺失** | — | 0 |
| S8 | 服务调用路由（session 目标 vs server 目标） | RPC 方法 | `server/src/server.ts:351-357` | `PiServer.java:151-201`（命令级 switch + 租约表） | 存疑（形状） | — | 0 |
| S9 | `wrong_server` 拒绝 | 事件类型 | `server/src/server.ts:346`、`server/src/errors.ts:24-29` | 无 | **缺失** | — | 0 |
| S10 | 会话 attach/detach 路由 + 独占租约 | RPC 方法 | `server/src/session-router.ts:60-80` | `PiServer.java:212-240`（全局 `globalLeases` + `SESSION_LOCKED`） | 存疑（形状） | — | 0 |
| S11 | `attachment` 信封推送（attached 变更通知客户端） | 事件类型 | `server/src/server.ts:80-85` | 无 | **缺失** | — | 0 |
| S12 | `service_update` 订阅推送 + 订阅水合（snapshot 响应前缓冲 update） | 事件类型 | `server/src/server.ts:334-382` | 无 | **缺失** | — | 0 |
| S13 | 服务端错误分类 `session_not_found`/`session_ambiguous`/`session_not_attached`/`server_draining` | 事件类型 | `server/src/errors.ts:3-9,31-56` | 只有 `SESSION_LOCKED`/`INVALID_REQUEST`/`INTERNAL_ERROR`（`PiServer.java:140-147`） | **缺失** | — | 0 |
| S14 | `INTERNAL_SERVER_ERROR_MESSAGE` 兜底（不泄漏内部文本） | 配置键 | `server/src/errors.ts:11`、`server/src/server.ts:520` | `PiServer.java:145-146` 回 `e.getMessage()` 原文 | 存疑（形状） | — | 0 |
| S15 | `onConnectionCountChanged` 观察者 | 配置键 | `server/src/types.ts:11`、`server/src/server.ts:523-529` | 无 | **缺失** | — | 0 |
| S16 | `onError` 观察者 | 配置键 | `server/src/types.ts:12`、`server/src/server.ts:531-537` | 无 | **缺失** | — | 0 |
| S17 | 宿主契约 `ServerHost` / `RoutedSessionHandle` / `RoutedServerServiceHost` / `RoutedServerPresentation` | 契约级导出类型 | `server/src/types.ts:17-64` | `PiServerService.java:14` + `PiSessionRuntime.java:92`（会话控制面，形状不同） | 存疑（形状） | — | 0 |
| S18 | `SessionRouter`（多 attachment、opening 去重、disconnect 清理、draining） | 契约级导出类型 | `server/src/session-router.ts:34-80` | `PiServer.java:43`（`ConcurrentHashMap` 租约表，无 opening 去重） | 存疑（形状） | — | 0 |
| S19 | Unix 监听器 + `getUnixSocketPath` + `createUnixListener` + `createUnixServer` preset | RPC 方法 | `server/src/transports/unix/address.ts:4`、`listener.ts:388`、`preset.ts:11` | `UnixSocketListener.java:19`（仅 bind/accept；**路径由调用方给，无 serverId→路径派生**） | **缺失** | — | 0 |
| S20 | `UnixListenerOptions`（`mode` 0o600 / `maxPendingBytes` 慢对端断连 / `gracefulCloseTimeoutMs`） | 配置键 | `server/src/transports/unix/types.ts:3-14`、`listener.ts:191` | 无 | **缺失** | — | 0 |
| S21 | 服务端测试基础设施（`createTestServer` / `ProtocolTestClient` / `TestHarness` / `TestServerHost` / `Deferred`） | 契约级导出类型 | `server/src/testing/index.ts:1-6`、`testing/client.ts`(183)、`testing/host.ts`(215)、`testing/server.ts`(28) | 无（只有 1 个自建 `MemService` 的集成测试） | **缺失** | — | 0 |
| S22 | 服务端异常类型 `ServerError`/`WrongServerError`/`SessionNotFoundError`/`SessionAmbiguousError`/`SessionNotAttachedError`/`ServerDrainingError` | 契约级导出类型 | `server/src/errors.ts:14,24,31,38,45,52` | `PiServerException.java:6`（单一异常）+ `SessionLockedException.java:6` | **缺失** | — | 0 |

**权重 0 的说明**：`server` 整模块按用户裁决排除出分母（依据＝pi 删了 `schemas.ts`，见 §0.2）。
⚠️ **该依据只覆盖 S17/S18 的一部分**（`server/src/protocol.ts`/`sessions.ts`/`snapshots.ts` 被删）。其余 20 条**不**在该依据范围内 —— `S1`–`S16`/`S19`–`S22` 全是**现 pi 有而 pi-java 无**的真缺口 ⇒ 建议权重 3/2/2/2/3/1/1/3/1/3/2/3/2/1/1/2/3/3/3/2/1/2。

---

## 整块缺失

| 子系统 | pi LOC | 说明 |
|---|---|---|
| **Chord service-addressed RPC 全层**（`RpcTarget`/`ServiceCall` 路由 + `cancel` + `service_update` 订阅/水合 + `attachment` 通知 + 校验型增量解码器） | ~1700（估：`protocol/src/protocol.ts` 111 + `codec.ts` 141 + `server/src/session-router.ts` 312 + `server/src/server.ts` 约 700 + `client/src/client.ts` 约 400） | pi-java **零对应物**。这不是「少几条命令」—— 是**寻址模型不同**：pi 把会话/服务当可寻址端点（`RpcTarget` 两态 + `ServiceCall{serviceId, member, args}`），pi-java 把一切压成 9 个固定命令。见 §0.2。 |
| **RPC 基座包 `packages/chord`** | 6503（**复测**；旧 5822） | pi 的 protocol/client/server 三层都建立在 Chord 的 `defineService`/`ReplicatedState`/`ServiceCall`/`ServiceProviderUpdate` 之上；pi-java 无对应包（Maven 依赖图里没有）。**这是上一条缺失的根因。** 本轮 chord 只动 `src/delta/`（内部重构，+1160/−394），`src/services/`、`src/api.ts`、`src/index.ts`、`src/context`、`src/facets` **零改动** ⇒ 服务寻址 RPC 的基座未变。 |
| **coding-agent 侧的接线层**（client-runtime / coordinator / session-worker / services/*） | 11197（**复测**：`src/experimental/` 10693 + `src/cli/experimental/` 504；旧 7356） | pi 的 `--serve`/`--connect` 面全在这里；pi-java 的 `pi-java-client`/`pi-java-server` **无任何生产消费者**（§0.3），`PiServerService` 无实现类。本轮 `src/experimental/` **+3841**，其中 **`micro/` 是全新子系统**（8 文件 ~1524 LOC）—— 但 `grep -rn defineService micro/` **零命中**、只 import `chord/context`，**完全不经过 pi-protocol/pi-client/pi-server** ⇒ 它是「进程内控制面」的**另一条路线**，**不构成本层的新能力单元**；另 `client.ts` +15 行是「操作响应可能早于其终局转录事件」的**时序修复**（行为修正，非新单元）。 |
| **telemetry testing/（conformance 套件 + 类型化 schema 词汇）** | ~490（`testing/` 339 + `index.ts:26-354` 的类型化部分） | pi-java 只有 3 个自建测试类（678 行），无 runner 无关的一致性套件、无 `defineTelemetrySchema`。台账 C7 裁决「倾向不做」，触发条件＝出现 OTel 之外的真 adapter。 |
| **server testing/ 测试基础设施** | 431 | pi-java 的 server 测试只有一个集成测试（212 行），自建桩；pi 有可复用的 `ProtocolTestClient`/`TestHarness`/`createTestServer`。 |
| **Unix 传输的可配置面**（权限 mode / 慢对端背压 / 优雅关闭超时 / serverId→路径派生 / 客户端服务发现） | ~380（估：`server/src/transports/unix/` 477 中约 300 + `client/src/unix.ts` 中约 80） | pi-java 两侧各一个极简实现（`UnixSocketListener.java` 77 行 / `UnixSocketTransport.java` 39 行），只有 bind/accept 与 connect。 |
| **CBOR 自研编解码器 + 资源上限** | 436（`encoder.ts` 216 + `decoder.ts` 168 + `options.ts` 52） | pi-java 用 Jackson CBOR 34 行封装，无 `maxByteLength`/`maxContainerLength`/`maxDepth`/`CborError`。**口径**：这是**实现替换**不是缺口（Jackson 是成熟库），但上限这一层是真的没有。 |
| **服务端错误分类体系**（6 个具名错误 + `wrong_server`/`draining` 语义） | 57 | pi-java 只有 2 个异常类型 + 3 个错误码。 |

---

## 汇总

**对齐 10 / 缺失 40 / 存疑 35 / 合计 85 ；完成度 = 10/85 = 11.8%**

分模块：

| 模块 | 对齐 | 缺失 | 存疑 | 合计 | 完成度 |
|---|---:|---:|---:|---:|---:|
| telemetry | 2 | 5 | 10 | 17 | 11.8% |
| protocol | 7 | 9 | 12 | 28 | 25.0% |
| client | 1 | 12 | 5 | 18 | 5.6% |
| server | 0 | 14 | 8 | 22 | 0.0% |
| **合计** | **10** | **40** | **35** | **85** | **11.8%** |

**存疑的 35 条按成因拆开**（互斥计数，合计 35）：

| 成因 | 条数 | 条目 |
|---|---:|---|
| 形状不同（两侧都有，行为不达「相同」） | 20 | T1 T2 T4 T6 / P5 P16 P20 P28 / C3 C11 C13 C14 / S1 S2 S5 S8 S10 S14 S17 S18 |
| 旧 pi 移植（参照物已被 pi 删除，见 §0.2） | 9 | P7 P14 P17 P22 P23 P24 P25 P27 / C18 |
| pi-java 扩展（pi 无对照物） | 6 | T12 T13 T14 T15 T16 T17 |

### 挂到能力单元上的台账条目

| 台账 | 条目 | 挂点 |
|---|---|---|
| C5 | 开/关顺序不对称（`JsonlFileTelemetry.startSpan:131-132` 与 `PiLaneSink.endRequest:197-198` 相反） | T13 |
| C7 | 是否另立 `TelemetryAdapterConformance` 套件（倾向不做） | T10 |
| D1 | `JsonlFileTelemetry.with(...)` 返回新实例 | T15 |
| D2 | `JsonlSpan.startSpan:338-349` 不碰当前栈 —— 未文档化的局部行为 | T2 |
| D3 | 接口 `openSpan` 的 default 实现返回**已结束**的 span | T2 / T12 |
| D4 | 摘要重试每次尝试没有各自跨度 | T16 |
| D5 | 截断兜底生成器下「无事件、无 token」的跨度形状无测试 | T16 |
| H | `TelemetrySpan` 的 `addLink`/`event` 未实现 | T5（`addLink` 半条是映射文档错记） |
| H | telemetry memory 后端 + conformance 套件未实现 | T9 / T10 / T11 |
| H | `session` / `process` 细粒度控制（pi 命令更多） | 见「整块缺失」第 1 行（service-addressed RPC 层） |

**不挂能力单元的台账条目**：`D7` —— `client`/`protocol`/`server` 三个 `package-info.java` 仍写「Phase 6 will implement…」（`pi-java-client/.../package-info.java:4`、`pi-java-protocol/.../package-info.java`、`pi-java-server/.../package-info.java:4`），三个模块都已实现 ⇒ 纯注释级小账，无对应能力单元。

**一句话结论**：这一层**没有一条「接线对上了」**。真正逐条相同的只有 framing/CBOR 基础件（P1–P4）、`ClientHello`/`ServerHelloError`/`ProtocolError` 三个信封、`NOOP`/`SpanOptions` 两个 telemetry 契约 —— 共 10 条。protocol 的 RPC 层是**已删除 pi 代码的移植**（§0.2），client/server 是**无消费者的孤岛**（§0.3）。

---

## 加权汇总

**权重规则**（用户 2026-09-20 定，照打不自创）：权重 = 用户可观察影响 × 频率。3 = 每轮对话都走/默认路径；2 = 每次会话走/常用命令；1 = 低频/边缘/纯内部；**0 = 非目标（排除出分母）**。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| telemetry（T1–T17） | 35 | 17.5 | **50.0%** | 41.2% |
| protocol（P1–P28，**权重 0，排除**） | 0 | 0 | — | 46.4% |
| client（C1–C18，**权重 0，排除**） | 0 | 0 | — | 19.4% |
| server（S1–S22，**权重 0，排除**） | 0 | 0 | — | 18.2% |

**模块合计**：Σ权重 **35** ／ Σ(w×c) **17.5** ／ **加权完成度 = 17.5/35 = 50.0%**（未加权 41.2%）

> 分母口径：`protocol`/`client`/`server` 三模块按用户裁决整体排除（依据＝pi 删了 `schemas.ts`，见 §0.2），
> 单列一行、权重填 0、不参与计算 ⇒ 模块合计 = telemetry。**未加权列**仍照旧给出三模块的数字，便于对照。

> **2026-09-20 复测（新 HEAD `3390bd936`）**：本层**单元无增无删**、**判定无变动** ⇒ **Σ权重 35 / Σ(w×c) 17.5 / 50.0% 全部不变**。
> 复测依据：① 四模块 `src/` 两提交间零改动（⇒ 582 条引用零漂移、无行为变更、无删除）；② `packages/chord/src/services|api.ts|index.ts|context|facets` 零改动，
> 且 chord 全 `src/` 唯一的导出签名增删是 `track<T>(root, options): Tracker<T>` 的**原样位移**（`src/delta/index.ts`）⇒ **服务寻址 RPC 层没有长出新的能力单元**；
> ③ 新出现的 `coding-agent/src/experimental/micro/` **零 `defineService`**、不 import pi-protocol/pi-client/pi-server ⇒ 不属本层（详见「整块缺失」第 3 行）。

### 权重 3 的单元（7 条 —— 最该先修）

| # | 单元 | 判定 | 权重依据 |
|---|---|---|---|
| T1 | `TelemetryContext.startSpan` | 存疑（形状） | 每个跨度都走 |
| T2 | `TelemetrySpan` | 存疑（形状） | 每个跨度都走 |
| T3 | `SpanOptions {name, attributes}` | **对齐** | 每个跨度都走 |
| T8 | `NOOP_TELEMETRY_CONTEXT` | **对齐** | 未配置遥测时的默认实现 |
| T12 | `TelemetryContext.openSpan` | 存疑（扩展） | `RunSpanFactory:32`／`PiLaneSink:185,237`／`CompactionExecutor:254,399` —— 每次运行/每次 LLM 请求/每次工具调用 |
| T14 | `incrementCounter` / `recordTiming` | 存疑（扩展） | `PiLaneSink:214-220,468-472`／`PiLaneEngine:95`／`RunSpanFactory:31`／`CompactionExecutor:249` —— 默认路径每次请求/工具 |
| T16 | `JsonlFileTelemetry` | 存疑（扩展） | `AgentSession:292-293` **无条件接线** ⇒ 每次会话都产 trace 文件 |

**权重 3 子集完成度：Σ权重 21 ／ Σ(w×c) 13.5 ／ = 64.3%**

### 分权重档完成度（telemetry）

| 档 | 单元数 | Σ权重 | Σ(w×c) | 完成度 |
|---|---:|---:|---:|---:|
| 权重 3 | 7 | 21 | 13.5 | **64.3%** |
| 权重 2 | 4（T6 T7 T13 T15） | 8 | 3.0 | 37.5% |
| 权重 1 | 6（T4 T5 T9 T10 T11 T17） | 6 | 1.0 | 16.7% |

### 未加权 vs 加权的差 —— 差在哪

**telemetry：41.2% → 50.0%，加权后拉高 +8.8 pt。**

- 拉高的原因：**权重 3 的 7 条里没有一条缺失**（2 对齐 + 5 存疑）⇒ 大件是齐的。
- 被降权的原因：**5 条缺失全部落在权重 1–2**（T5=1、T7=2、T9=1、T10=1、T11=1）—— `addEvent`/`setStatus`/测试后端/conformance 套件/类型化 schema，都是**遥测细节与测试基础设施**。
- 一句话：**telemetry 缺的都是小件**；核心契约（`startSpan`/`SpanOptions`/`NOOP`）、默认路径（`openSpan`/指标）、默认导出器（`JsonlFileTelemetry`）都在。

**protocol / client / server（若拉回分母）：加权只把它们拉高 +3.6 ~ +4.6 pt，幅度远小于 telemetry。**

| 模块 | 未加权 | 加权（用表内建议权重） | Δ |
|---|---:|---:|---:|
| protocol | 46.4% | 50.0% | +3.6 |
| client | 19.4% | 23.7% | +4.3 |
| server | 18.2% | 22.8% | +4.6 |
| 三模块合计 | 30.1% | 35.2% | +5.1 |

- 原因：这三块**既缺大件也对齐大件**。对齐侧有 `encodeFrame`/`FrameDecoder`/CBOR/`ClientHello`/`ServerHelloError`/`ProtocolError`/`Client.connect`/`ByteTransport` 这些**每帧都走**的权重 3；缺失侧同样有 `RpcTarget`/`RequestEnvelope.target`/`subscribeService`/`attachment`/`service_update`/`SessionRouter`/unix listener 等权重 3，**也有** `CancelEnvelope`/`isServerId`/`isSupportedProtocolVersion`/`握手护栏`/`重复 request id`/`wrong_server`/`onConnectionCountChanged`/`testing/` 这些权重 1 的小件。
- ⇒ 与 telemetry 的差别是**幅度而非方向**：telemetry「缺的全是小件」所以加权明显回血；三模块「大小件都缺」所以加权几乎不动。**这也说明：加权口径不能替代 §0.2 的参照物错位判断** —— protocol 的 46.4%/50.0% 是被「与已删除代码逐条相同」撑起来的，不是与现 pi 对齐。

### 权重 0 明细（排除出分母）

| 模块 | 排除依据 | 该依据**实际**覆盖的单元 | 不在覆盖范围内的单元 |
|---|---|---|---|
| protocol | pi `e52de91d0` 删 `schemas.ts`（§0.2） | P22 P23 P24 P25 P27（＋P7/P14/P17 的旧形状） | **P1–P4**（framing/CBOR，pi 仍在且逐条相同）；**P11–P13 / P18 / P19 / P21**（**现 pi 有、pi-java 无**的真缺口） |
| client | 同上（删 `session-handle.ts`） | C18 | C1–C17 全部（含 **C2/C4–C10/C12/C15/C16** 这些真缺口） |
| server | 同上（删 `protocol.ts`/`sessions.ts`/`snapshots.ts`） | S17 / S18 的一部分 | S1–S16 / S19–S22（**全是现 pi 有、pi-java 无**的真缺口） |

**建议权重全表**（若日后拉回分母直接用）：
- protocol：P1=3 P2=3 P3=2 P4=2 P5=3 P6=1 P7=2 P8=1 P9=1 P10=3 P11=3 P12=1 P13=3 P14=3 P15=2 P16=3 P17=3 P18=2 P19=2 P20=3 P21=3 P22=3 P23=3 P24=3 P25=3 P26=2 P27=2 P28=3（Σw=68）
- client：C1=3 C2=2 C3=2 C4=2 C5=1 C6=2 C7=3 C8=1 C9=2 C10=3 C11=2 C12=2 C13=3 C14=2 C15=2 C16=2 C17=1 C18=3（Σw=38）
- server：S1=3 S2=2 S3=2 S4=2 S5=3 S6=1 S7=1 S8=3 S9=1 S10=3 S11=2 S12=3 S13=2 S14=1 S15=1 S16=2 S17=3 S18=3 S19=3 S20=2 S21=1 S22=2（Σw=46）
