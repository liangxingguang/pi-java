# 01 基础设施层（telemetry / protocol / client / server）

> 【背景摘要】本图分两种锚点读：A 节 telemetry 已按新锚点全量重测，旧“休眠契约、100%”结论失效；B–D 节 protocol/client/server 仍保留旧锚点快照，不与 A 节合成跨锚点百分比。

## 基准与口径

- **A. telemetry：`PI_PIN=56cae7823`**（完整 SHA `56cae782341c339d3bb820081f0f9425d8c5b1ec`）；pi-java HEAD @ **`28e3f0d9b29b2a96cabb4109adb17a41cf39bf39`**，B260–B262 在该基线的隔离 worktree 实施并验收。
- **B–D. protocol/client/server：仍为 pi `ea448f454` / pi-java `edc8749d` 历史快照**，本次未重测、未翻行、权重仍为 0。
- 判定单位 = **能力单元**（CLI 参数 / RPC 方法 / 事件类型 / 配置键 / span 名 / 契约级导出类型）；内部类与私有方法不计。
- 每条给两侧 `file:line`。pi 新证据全部由 `git show 56cae7823:<path>` 取得；详细差异与实施审核稿见 [`../09-l0-telemetry-rebaseline-design.md`](../09-l0-telemetry-rebaseline-design.md)。
- 通用术语（工单编号 / 裁决 / R1 / R5 / 分母 / 模型可见面）：见 [`../04-open-items-register.md`](../04-open-items-register.md) §0。

## 0. 三条必须先读的口径

### 0.1 判定三档的用法

| 档 | 含义 |
|---|---|
| **对齐** | pi 有、pi-java 有，且**行为相同**（读过两侧代码，不是类名像） |
| **缺失** | pi 有、pi-java 无 |
| **存疑** | 其余全部：① pi-java 有而 pi **无对照物**（扩展）；② pi-java 是 **pi 已删除版本**的移植；③ 两侧都有但**形状/行为不同**、够不上「行为相同」；④ 台账未结条目 |

**⚠️ 读表须知**：三档里没有「都有但形状不同」这一档——这类按定义**不能算对齐**，全部落在 **存疑**（判定列的子标签 `（形状）`/`（旧 pi）`/`（扩展）`/`（台账）` 指明是哪一种）。所以本表的「完成度」是**严格口径**：只数行为逐条相同的。

> **子标签增补（2026-10-17，原 docs/23 §7.4）**：`（扩展·确认）`——pi 无对照物的扩展单元，经裁决确认保留、且行为有一致性套件/测试钉床 ⇒ 判 **对齐**（计满分、**留分母**，pi 若日后长出对照物可原位重测翻红）。判据是行为：pi 无此行为面，不存在「差距」。

### 0.2 参照物错位 —— 本层最大的单一事实

**`pi-java-protocol` 的整个「命令/结果/事件」层，参照的是 pi 已经删掉的代码。**

证据链：

- pi 现 `packages/protocol/src/` 只有 `cbor/`、`codec.ts`、`framing.ts`、`index.ts`、`protocol.ts`（`ls` 实测）——**没有 `schemas.ts`**。
- `git grep -l 'server_snapshot\|session_progress\|session_removed\|session_snapshot\|ServerSnapshotSchema\|SessionSnapshotSchema\|SessionPhaseSchema\|TranscriptItemSchema\|ModelMetadataSchema\|SessionMetadataSchema\|ProtocolThinkingLevel' ea448f454 -- 'packages/**/*.ts'` → **全部零命中**（本锚点复跑，结论不变）。
  ⚠️ 单独 grep `CommandSchema` 会命中一处 `packages/coding-agent/src/experimental/session-worker.ts:116`——那是**无关**的 `SessionWorkerCommandSchema`（worker 控制协议，5 变体：shutdown/discover_workers/session_demand/operation/operation_cancel），**不是**会话 RPC 的 `CommandSchema`。
- pi 侧最后一个含 `CommandSchema` 的 `schemas.ts` 是 `1bd9c3f67`（2026-08-08，`PROTOCOL_VERSION = 1`）；它在 `e52de91d0`「feat(protocol): add service-addressed session RPC」（**2026-08-13**）被换成 `rpc.ts` + 瘦身版 `schemas.ts`。该提交同时删掉 `packages/client/src/session-handle.ts`(111)、`packages/client/src/state.ts`(156)、`packages/server/src/protocol.ts`(382)、`packages/server/src/sessions.ts`(346)、`packages/server/src/snapshots.ts`(62)。
- `git show e52de91d0^:packages/protocol/src/schemas.ts` 与 pi-java 逐项对上：9 个命令同名同字段、`SessionSnapshot` 14 字段同序、`ProtocolErrorCode` 7 值同集、`ServerHello{version, connectionId, snapshot}`、`ServerEvent` 4 变体、`PROTOCOL_VERSION = 1`（= `ProtocolVersion.java:9`）。
- 反证：pi 的 `Command` 判别键**始终**是 `command`（`1bd9c3f67:385`、`6189e53b3:291`、`e52de91d0^`），pi-java 用的是 `type`（`Command.java:14`、`CommandResult.java:16`）⇒ 连旧 pi 也没完全照抄。

**结论**：`framing.ts` + CBOR 层对齐**现** pi；`Command`/`CommandResult`/`ServerEvent`/快照族对齐的是**已被删除**的 pi（且判别键还改了）。这两半必须分开算，否则数字会骗人。

### 0.3 接线事实 —— client/server 在 pi-java 无生产消费者

- `grep -rln 'com\.pijava\.server' --include='*.java' pi-java-*/src` → 命中**只有 `pi-java-server` 自己** + `pi-java-server/src/test/.../PiServerClientIntegrationTest.java`。
- `grep -rln 'com\.pijava\.client' --include='*.java' pi-java-*/src` → 命中**只有 `pi-java-client` 自己** + 上面那一个测试。
- `PiServerService`（`PiServerService.java:14`）**全仓无实现类**；`pi-java-coding-agent` 的 CLI 无 `--serve`/`--connect`/`serverId` 任何字样（grep 零命中）。
- pi 侧相反：`packages/coding-agent/src/experimental/` **9950** LOC + `src/cli/experimental/` 504 LOC 是这套 RPC 的真实宿主（`client.ts`/`client-runtime.ts`/`server.ts`/`coordinator.ts`/`session-worker*.ts`/`services/*`）。

即：pi-java 的 client/server 是**能跑通集成测试的孤岛**，pi 的对应物是**已接线的生产子系统**。

---
## 规模

> A 节 pi 数字以 `git show 56cae7823:<path>` 实测，Java 数字以 `28e3f0d9` 工作树实测；B–D 数字仍是旧锚点快照。行数比不构成判据。

| 模块 | pi 包 | pi 文件数 | pi LOC | java 文件数 | java LOC | 比例 |
|---|---|---:|---:|---:|---:|---:|
| pi-java-telemetry | `packages/telemetry`（`src/`，含 `testing/`） | 8 | 1572 | 38 | 2876 | 183% |
| pi-java-protocol | `packages/protocol`（`src/`，旧快照） | 8 | 869 | 24 | 748 | 86% |
| pi-java-client | `packages/client`（`src/`，旧快照） | 8 | 1135 | 7 | 374 | 33% |
| pi-java-server | `packages/server`（`src/`，旧快照） | 16 | 1958 | 13 | 563 | 29% |

> 两侧均只计 `src/`（`main`）生产代码（含随包发布的 `testing/`），不含测试。
> 新锚点 telemetry 测试为 **999 LOC / 8 文件**；Java telemetry 测试为 **1664 LOC / 11 文件**。
> B–D 参照量级与测试量沿用旧快照，不在 L0 重算；Chord 本身按 R5 出分母。

---

## 能力单元清单

### A. telemetry（20 条；17 条计分＋3 条 R5 出分母）

| # | 能力单元 | pi `56cae7823` 证据 | pi-java `28e3f0d9` 证据 | 新判定 | 台账 | 权重 |
|---|---|---|---|---|---|---:|
| T1 | `TelemetryContext.startSpan(options, callback)` | `telemetry/src/index.ts:98-103` | `TelemetryContext.java:54` | **对齐**（同步回调 / `AutoCloseable` 结构裁决维持） | — | 3 |
| T2 | `TelemetrySpan`：span 即 context、可嵌子跨度；link 另计 T18 | `telemetry/src/index.ts:105-110` | `TelemetrySpan.java:30`、`InMemoryTelemetryContext.java:175-229` | **对齐** | D2 / D3 | 3 |
| T3 | `SpanOptions {name, attributes}`；`links` 另计 T18 | `telemetry/src/index.ts:24-28` | `SpanOptions.java:11-21` | **对齐** | — | 3 |
| T4 | `AttributeValue` 标量、三类数组与 `undefined` 缺席语义 | `telemetry/src/index.ts:3-7`、`testing/conformance.ts:310-329` | `SpanOptions.java:18-21`、`TelemetryAttributes.java:14-39`；initial/late-null 一致性案 | **对齐** | — | 1 |
| T5 | `TelemetrySpan.addEvent` | `telemetry/src/index.ts:107`、`memory.ts:211-218` | `TelemetrySpan.java:44`、`InMemoryTelemetryContext.java:233-245` | **对齐** | — | 1 |
| T6 | `TelemetrySpan.setAttributes` 整包合并、settled 后 inert | `telemetry/src/index.ts:108`、`memory.ts:219-222` | `TelemetrySpan.java:51`、`InMemoryTelemetryContext.java:247-264` | **对齐** | — | 2 |
| T7 | `setStatus` + `SpanStatus{ok\|error}`，显式状态赢过自动派生 | `telemetry/src/index.ts:30,109`、`memory.ts:223-245` | `TelemetrySpan.java:59`、`SpanStatus.java:11-27` | **对齐** | — | 2 |
| T8 | `NOOP_TELEMETRY_CONTEXT` 基本跨度行为；noop metrics 另计 T14 | `telemetry/src/noop.ts:4-6,25-37` | `NoopTelemetryContext.java:9-67` | **对齐** | — | 3 |
| T9 | InMemory 跨度快照、carrier 身份与 measurement 快照；links 另计 T18 | `telemetry/src/memory.ts:27-61,130-161,248-275,431-462` | `InMemoryTelemetryContext.java:28-126,175-310`、`RecordedTelemetrySpan.java:19-41`、`RecordedTelemetryMeasurement.java:7-24` | **对齐** | — | 1 |
| T10 | `createTelemetryAdapterConformance`：pi **14 用例 / 8 组**；Java 可适用 **10 / 6** | `telemetry/src/testing/conformance.ts:44-386` | `testing/TelemetryAdapterConformance.java:47-80,101-190,272-300`；InMemory/Jsonl 各实跑 10 案 | **对齐**：9 个可适用 pi 案＋既有 Java 原子记录防护案；links 2＋propagation 3 按 T18 R5 | — | 1 |
| T11 | 可序列化 schema＋`createTypedSpanStarter`＋`createTypedMetrics` | `telemetry/src/index.ts:128-177,325-456`、`metrics.ts:128-145`；主流消费 `ai/src/telemetry.ts:64,110,151` | `TelemetrySchemaDefinition.java:9-16`、`TelemetrySchemas.java:9-25`、`TypedTelemetrySpanStarter.java:12-24`、`TypedTelemetryMetrics.java:7-46` | **对齐**（运行时行为；TS 编译期约束登记 D201） | D201 | 2 |
| T12 | `TelemetryContext.openSpan(options)` | 无（Java 扩展） | `TelemetryContext.java:63-70` | **对齐**（扩展·确认） | D3 | 3 |
| T13 | `recordEvent` / `recordsPayloads` / `pushCurrent` / `popCurrent` | 无（Java 扩展） | `TelemetryContext.java:83-120`、`JsonlFileTelemetry.java:165-225` | **对齐**（扩展·确认；opt-in payload 与 span 小属性事件分离） | — | 2 |
| T14 | `TelemetryMetrics`：counter/upDownCounter/histogram、属性、metadata、exemplar 与 noop | `telemetry/src/metrics.ts:10-41`、`memory.ts:248-275,325-335,431-439`、`noop.ts:8-17`；主流消费 `ai/src/telemetry.ts:109-134,198-204` | `TelemetryMetrics.java:7-29`、`InMemoryTelemetryContext.java:54-114`、`NoopTelemetryMetrics.java:7-30`、`OtelTelemetryContext.java:96-155`；Jsonl 独立 `metric` 行 | **对齐** | — | 3 |
| T15 | `with(key, value)` 维度上下文 | 无（Java 扩展） | `TelemetryContext.java:78-81`、`JsonlFileTelemetry.java:158-163` | **对齐**（扩展·确认） | D1 | 2 |
| T16 | `JsonlFileTelemetry` JSONL 导出器 | 无（Java 扩展） | `JsonlFileTelemetry.java:49-487`、`JsonlTelemetryMetrics.java:15-82`；接线 `SessionWiring.java:132-133` | **对齐**（扩展·确认；20 个专项测试＋10 案一致性套件） | D4 / D5 | 3 |
| T17 | `OtelTelemetryContext` OTel 适配器 | 无（Java 扩展） | `OtelTelemetryContext.java:28-344` | **对齐**（扩展·确认；11 个真实 SDK 测试） | — | 1 |
| T18 | `SpanLink`＋`TelemetryPropagation`＋noop propagation | `telemetry/src/index.ts:9-28,100-106`、`noop.ts:19-23`、`memory.ts:301-323`；Durable 消费 `durable/src/harness/submissions.ts:107,241,257-260`、`scheduler.ts:2223,2238-2239` | 无 | **缺失，R5 出分母**：生产消费者为 Chord＋Durable；AI exemplar 所需 carrier 不随此项排除，已留 T9/T14 | — | 0 |
| T19 | `TelemetryStore`＋StoredSpan/Trace/Metric 查询 | `telemetry/src/index.ts:32-95`、`memory.ts:337-429` | 无 | **缺失，R5 出分母**：查询消费者仅 `chord/telemetry-sqlite` | — | 0 |
| T20 | `renderTrace` 子路径导出 | `telemetry/src/render.ts:10-49`、`telemetry/package.json` 的 `./render` | 无 | **缺失，R5 出分母**：未见主流生产消费者；telemetry README 自身示例不作为生产接线证据 | — | 0 |

**权重与范围依据**

| 权重 | 单元 | 判据 |
|---:|---|---|
| 3 | T1 T2 T3 T8 T12 T14 T16 | 默认跨度、noop、每次运行/请求/工具的 Java 扩展面；metrics 现已被 AI 每请求直接消费 |
| 2 | T6 T7 T11 T13 T15 | 常用属性/状态/上下文；T11 每次 AI 请求经 typed starter/metrics 使用，但属于内部绑定工具 |
| 1 | T4 T5 T9 T10 T17 | 内部值域、参考后端、测试套件与需外部 SDK 的扩展后端 |
| 0 | T18 T19 T20 | 固定锚点全包检索：T18 生产消费者为 Chord＋Durable，T19 为 Chord，T20 未见主流生产消费者；均按既定 R5 出分母，出现主流消费者时原位重开 |

> T10 的可适用新增案是 **3 个用例**，不是 7 个：link/propagation 五案随 T18 出分母。Java 另保留一个 pi 已删的 Proxy 防护等价测试，形成 10 案／6 组。
>
> **D201（T11 窄差）**：Java 保留 schema identity、显式 parent/child starter 绑定和 metric metadata 合并，但无法复刻 TS 条件类型对 exact attributes、required keys、schema 名与 instrument kind 的编译期约束；不增加 pi 没有的运行时校验。

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
| C16 | 客户端错误分类 `ServerError`/`DisconnectedError`/`ClientDisposedError`/`toError` | 契约级导出类型 | `client/src/errors.ts:3,13,20,27` | `PiClientException.java:6`（单一异常类型） | **缺失** | — | 0 |
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
| S15 | `onConnectionCountChanged` 观察者 | 配置键 | `server/src/types.ts:10`、`server/src/server.ts:523-529` | 无 | **缺失** | — | 0 |
| S16 | `onError` 观察者 | 配置键 | `server/src/types.ts:11`、`server/src/server.ts:531-537` | 无 | **缺失** | — | 0 |
| S17 | 宿主契约 `ServerHost` / `RoutedSessionHandle` / `RoutedServerServiceHost` / `RoutedServerPresentation` | 契约级导出类型 | `server/src/types.ts:21-68` | `PiServerService.java:14` + `PiSessionRuntime.java:92`（会话控制面，形状不同） | 存疑（形状） | — | 0 |
| S18 | `SessionRouter`（多 attachment、opening 去重、disconnect 清理、draining） | 契约级导出类型 | `server/src/session-router.ts:34-80` | `PiServer.java:43`（`ConcurrentHashMap` 租约表，无 opening 去重） | 存疑（形状） | — | 0 |
| S19 | Unix 监听器 + `getUnixSocketPath` + `createUnixListener` + `createUnixServer` preset | RPC 方法 | `server/src/transports/unix/address.ts:4`、`listener.ts:388`、`preset.ts:10` | `UnixSocketListener.java:19`（仅 bind/accept；**路径由调用方给，无 serverId→路径派生**） | **缺失** | — | 0 |
| S20 | `UnixListenerOptions`（`mode` 0o600 / `maxPendingBytes` 慢对端断连 / `gracefulCloseTimeoutMs`） | 配置键 | `server/src/transports/unix/types.ts:3-14`、`listener.ts:191` | 无 | **缺失** | — | 0 |
| S21 | 服务端测试基础设施（`createTestServer` / `ProtocolTestClient` / `TestHarness` / `TestServerHost` / `Deferred`） | 契约级导出类型 | `server/src/testing/index.ts:1-5`、`testing/client.ts`(183)、`testing/host.ts`(**204**)、`testing/server.ts`(28) | 无（只有 1 个自建 `MemService` 的集成测试） | **缺失** | — | 0 |
| S22 | 服务端异常类型 `ServerError`/`WrongServerError`/`SessionNotFoundError`/`SessionAmbiguousError`/`SessionNotAttachedError`/`ServerDrainingError` | 契约级导出类型 | `server/src/errors.ts:14,24,31,38,45,52` | `PiServerException.java:6`（单一异常）+ `SessionLockedException.java:6` | **缺失** | — | 0 |

**权重 0 的说明**：`server` 整模块按用户裁决排除出分母（依据＝pi 删了 `schemas.ts`，见 §0.2）。
⚠️ **该依据只覆盖 S17/S18 的一部分**（`server/src/protocol.ts`/`sessions.ts`/`snapshots.ts` 被删）。其余 20 条**不**在该依据范围内 —— `S1`–`S16`/`S19`–`S22` 全是**现 pi 有而 pi-java 无**的真缺口 ⇒ 建议权重 3/2/2/2/3/1/1/3/1/3/2/3/2/1/1/2/3/3/3/2/1/2。

---

## 整块缺失

| 子系统 | pi LOC | 说明 |
|---|---|---|
| **Chord service-addressed RPC 全层**（`RpcTarget`/`ServiceCall` 路由 + `cancel` + `service_update` 订阅/水合 + `attachment` 通知 + 校验型增量解码器） | ~1600（实测：`protocol/src/protocol.ts` 110 + `codec.ts` 141 + `server/src/session-router.ts` 312 + `server/src/server.ts` 576 + `client/src/client.ts` 479） | pi-java **零对应物**。这不是「少几条命令」—— 是**寻址模型不同**：pi 把会话/服务当可寻址端点（`RpcTarget` 两态 + `ServiceCall{serviceId, member, args}`），pi-java 把一切压成 9 个固定命令。见 §0.2。 |
| **RPC 基座包 `packages/chord`** | **8817**（29 文件） | pi 的 protocol/client/server 三层都建立在 Chord 的 `defineService`/`ReplicatedState`/`ServiceCall`/`ServiceProviderUpdate` 之上；pi-java 无对应包（Maven 依赖图里没有）。**这是上一条缺失的根因。** chord 自身另有状态复制/差分机制（`src/delta/` 的 `diff`/`tracker`/`draft`/`revision-validator`/`apply-immutable-trusted` 五文件，提供 `track()`/`diffRevisions` 原语；`src/services/state.ts` 与 `api.ts`/`provider.ts`/`consumer.ts` 的 `ReplicatedStateSource` / `AttachedReplicatedState`），但那不属 protocol/client/server 的能力面；chord 在本层按裁决权重 0（pi-java 无 chord 包）⇒ **本层未因此长出任何新能力单元**。 |
| **coding-agent 侧的接线层**（client-runtime / coordinator / session-worker / services/*） | **10454**（`src/experimental/` **9950** + `src/cli/experimental/` 504） | pi 的 `--serve`/`--connect` 面全在这里；pi-java 的 `pi-java-client`/`pi-java-server` **无任何生产消费者**（§0.3），`PiServerService` 无实现类。experimental 下另有 `durable/`、`vacation/` 等实验前端，均不在 `bin pi` 主流路径 ⇒ **不构成本层的新能力单元**。 |
| **telemetry AI 激活面** | `metrics.ts` 145＋schema/conformance 增量 | **B260/B261/B262 已闭环**：初始 attributes、事件快照、metrics、carrier/measurement、typed schema/starter 与新版一致性套件已对齐；AI 的 `pi.ai.*` 生产行为另由 **B263 / L1** 承接。Chord＋Durable 等 R5 面的 links/propagation/store/render 按 T18–T20 出分母。 |
| **server testing/ 测试基础设施** | **420** | pi-java 的 server 测试只有一个集成测试（212 行），自建桩；pi 有可复用的 `ProtocolTestClient`/`TestHarness`/`createTestServer`。 |
| **Unix 传输的可配置面**（权限 mode / 慢对端背压 / 优雅关闭超时 / serverId→路径派生 / 客户端服务发现） | ~380（估：`server/src/transports/unix/` 476 中约 300 + `client/src/unix.ts` 中约 80） | pi-java 两侧各一个极简实现（`UnixSocketListener.java` 77 行 / `UnixSocketTransport.java` 39 行），只有 bind/accept 与 connect。 |
| **CBOR 自研编解码器 + 资源上限** | 436（`encoder.ts` 216 + `decoder.ts` 168 + `options.ts` 52） | pi-java 用 Jackson CBOR 34 行封装，无 `maxByteLength`/`maxContainerLength`/`maxDepth`/`CborError`。**口径**：这是**实现替换**不是缺口（Jackson 是成熟库），但上限这一层是真的没有。 |
| **服务端错误分类体系**（6 个具名错误 + `wrong_server`/`draining` 语义） | 57 | pi-java 只有 2 个异常类型 + 3 个错误码。 |

---

## 汇总

**L0 新锚点只发布 telemetry，不发布跨 A–D 合计。** 计分单元 17：对齐 17／存疑 0／缺失 0；T18–T20 三条 R5 缺失不进分母。

| 模块 / 锚点 | 对齐 | 缺失 | 存疑 | 计分合计 | 未加权完成度 |
|---|---:|---:|---:|---:|---:|
| telemetry / `56cae7823` | 17 | 0 | 0 | 17 | **100%** |
| protocol / `ea448f454` 旧快照 | 7 | 9 | 12 | 28 | 25.0% |
| client / `ea448f454` 旧快照 | 1 | 12 | 5 | 18 | 5.6% |
| server / `ea448f454` 旧快照 | 0 | 14 | 8 | 22 | 0.0% |

> telemetry 未加权得分 = `17/17 = 100%`；加权结果见下文。B–D 数字只用于保存历史，不与该读数平均。

**B–D 旧快照存疑的 25 条按成因拆开**（互斥计数，合计 25）：

| 成因 | 条数 | 条目 |
|---|---:|---|
| 形状不同（两侧都有，行为不达「相同」） | 16 | P5 P16 P20 P28 / C3 C11 C13 C14 / S1 S2 S5 S8 S10 S14 S17 S18 |
| 旧 pi 移植（参照物已被 pi 删除，见 §0.2） | 9 | P7 P14 P17 P22 P23 P24 P25 P27 / C18 |
| pi-java 扩展（pi 无对照物） | 0 | — |

### 挂到能力单元上的台账条目

| 台账 | 条目 | 挂点 |
|---|---|---|
| D1 | `JsonlFileTelemetry.with(...)` 返回新实例 | T15 |
| D2 | `JsonlSpan.startSpan` 不碰当前栈 —— 未文档化的局部行为 | T2 |
| D3 | 接口 `openSpan` 的 default 实现返回**已结束**的 span | T2 / T12 |
| D4 | 摘要重试每次尝试没有各自跨度 | T16 |
| D5 | 截断兜底生成器下「无事件、无 token」的跨度形状无测试 | T16 |
| D201 | Java 无 TS 条件类型的 exact attributes、required keys、schema 名与 instrument kind 编译期约束；运行时 identity、parent binding 与 metadata 行为已对齐 | T11 |
| H | `session` / `process` 细粒度控制（pi 命令更多） | 见「整块缺失」第 1 行（service-addressed RPC 层） |

## 加权汇总

**权重规则**（照打不自创）：权重 = 用户可观察影响 × 频率。3 = 每轮对话都走/默认路径；2 = 每次会话走/常用命令；1 = 低频/边缘/纯内部；**0 = 非目标（排除出分母）**。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权完成度 |
|---|---:|---:|---:|---:|
| telemetry（T1–T17 计分；T18–T20 R5） | 36 | 36.0 | **100%** | 100% |
| protocol（旧快照，权重 0） | 0 | 0 | — | 46.4% |
| client（旧快照，权重 0） | 0 | 0 | — | 19.4% |
| server（旧快照，权重 0） | 0 | 0 | — | 18.2% |

**L0 新层账面**：Σ权重 **36** ／ Σ(w×c) **36.0** ／ **加权完成度 = 36/36 = 100%**；未加权 `17/17 = 100%`。

> 验收后 T4/T9/T10/T11/T14 均翻为对齐；D201 只登记 Java 不可复刻的 TS 编译期约束，不扣运行时行为分。B–D 旧快照继续权重 0，不参与新层账面。

### 权重 3 的单元（7 条）

| # | 单元 | 判定 | 权重依据 |
|---|---|---|---|
| T1 | `TelemetryContext.startSpan` | **对齐** | 每个跨度都走 |
| T2 | `TelemetrySpan` | **对齐** | 每个跨度都走 |
| T3 | `SpanOptions {name, attributes}` | **对齐** | 每个跨度都走 |
| T8 | `NOOP_TELEMETRY_CONTEXT` | **对齐** | 未配置遥测时的默认实现 |
| T12 | `TelemetryContext.openSpan` | **对齐**（扩展·确认） | `RunSpanFactory.java:33`／`PiLaneRequestTelemetry.java:62`／`PiLaneToolRecords.java:99` |
| T14 | `TelemetryMetrics` | **对齐** | `PiLaneRequestTelemetry.java:95-101`／`PiLaneToolRecords.java:182-186`；新 AI metrics 生产点见 `ai/src/telemetry.ts:109-134` |
| T16 | `JsonlFileTelemetry` | **对齐**（扩展·确认） | `SessionWiring.java:132-133` → `PayloadRecordingStreamFn.java:69-71`，每次会话接线 |

**权重 3 子集：Σ权重 21 ／ Σ(w×c) 21.0 ／ 完成度 = 100%**。

### 分权重档完成度（telemetry）

| 档 | 单元数 | Σ权重 | Σ(w×c) | 完成度 |
|---|---:|---:|---:|---:|
| 权重 3 | 7 | 21 | 21.0 | **100%** |
| 权重 2 | 5（T6 T7 T11 T13 T15） | 10 | 10.0 | **100%** |
| 权重 1 | 5（T4 T5 T9 T10 T17） | 5 | 5.0 | **100%** |

### 权重 0 明细（排除出分母）

| 模块 | 排除依据 | 该依据**实际**覆盖的单元 | 不在覆盖范围内的单元 |
|---|---|---|---|
| telemetry | 新锚点生产检索：T18＝Chord＋Durable，T19＝Chord，T20 无主流生产消费者（均为 R5） | T18 T19 T20 | **T9/T11/T14 的 AI schema、carrier、exemplar 与 metrics** 主流可达，必须计分 |
| protocol | pi `e52de91d0` 删 `schemas.ts`（§0.2） | P22 P23 P24 P25 P27（＋P7/P14/P17 的旧形状） | **P1–P4**（framing/CBOR，pi 仍在且逐条相同）；**P11–P13 / P18 / P19 / P21**（**现 pi 有、pi-java 无**的真缺口） |
| client | 同上（删 `session-handle.ts`） | C18 | C1–C17 全部（含 **C2/C4–C10/C12/C15/C16** 这些真缺口） |
| server | 同上（删 `protocol.ts`/`sessions.ts`/`snapshots.ts`） | S17 / S18 的一部分 | S1–S16 / S19–S22（**全是现 pi 有、pi-java 无**的真缺口） |
