# 38 - 包⑪：夹具与生产不同路（A16）

> **文档约定**（用户 2026-09-19）：一个功能模块一份文档；`docs/32` 是唯一索引。
> 流程：**① 命题表 → ② 逐条代码验证 → ③ 实施稿**；**用户审核 §8 之后才写代码**。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
>
> **本文件当前状态**：命题**已逐条核过**；§8 已实施，**§10 实施记录已落**（含 §10.6 两处
> 超出 §8 的改动，**待用户过目**）。

---

## 1. 这一包解决什么

**pi-java 的 `FauxProvider` 产出的消息不带身份**（`api`/`provider`/`model`/`timestamp`），
而**生产路径的每条消息都带** —— 因为身份挂载点只有一个：`AbstractChatApi`，而
`FauxChatApi` **直接实现 `ChatApi`、绕过它**。

**后果**：一切经 faux 驱动的夹具都在**另一个形状**上跑。包⑩ 的 B48（RPC 线在带
timestamp 的消息上**抛**）就是这么藏住的 —— **测试与生产不同路，测试就证明不了生产**。

⚠️ **这不是「pi 的 faux 是个特例」**：pi 的 faux **也挂身份**（见 P1–P3），
**pi-java 的 faux 才是异类**。

---

## 2. 命题表

### 2.1 pi 侧

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | **pi 的 faux 挂身份**：`cloneMessage(message, api, provider, modelId)` 写 `api`/`provider`/`model`/`timestamp`/`usage` | `ai/src/providers/faux.ts:281-291` | **已核** |
| P2 | 它的三个构造器（deferred / error / aborted）**同样写全** `api`/`provider`/`model`/`usage`/`timestamp` | `faux.ts:293-305`、`:307-319`、`:321-328` | **已核** |
| P3 | 默认取值：`DEFAULT_API="faux"`、`DEFAULT_PROVIDER="faux"`、`DEFAULT_MODEL_ID="faux-1"` | `faux.ts:23-25` | **已核** |
| P4 | `usage` 兜底 `DEFAULT_USAGE`（`cloned.usage ?? DEFAULT_USAGE`） | `faux.ts:289` | **已核** |

⇒ **pi 侧没有「faux 是一等公民之外的东西」这回事**；夹具与生产**同形**。

### 2.2 pi-java 侧

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P5 | 身份挂载点**唯一**：`AbstractChatApi.stream` 的 `IdentitySubscriber` 把每个事件的 partial 换成挂好身份的副本，一次流固定一个 `timestamp` | `AbstractChatApi.java:45-50`、`:72-83` | **已核** |
| P6 | `FauxChatApi` **直接实现 `ChatApi`** ⇒ **绕过** P5 的挂载 | `FauxProvider.java:145` | **已核** |
| P7 | 故 faux 事件的 partial 只有 `content`/`stopReason`，`api`/`provider`/`model`/`timestamp`/`usage` 全为 null（除非夹具自己塞） | 同上 + 各 faux 夹具的构造 | **已核** |
| P8 | ⚠️ **下游真的读这些字段**：harness 的自动压缩估算与溢出守卫从这条消息读起（`api`/`provider`/`model` 的 `sameModel` 判断、`timestamp` 的 stale 判断）；身份四元也是 3a 的产物 | `Message.java:44-52` 的 javadoc（逐条点名） | **已核** |
| P9 | 24 个测试文件引用 `FauxProvider` | 全仓 grep | **已核** |
| P10 | RPC 线（`JsonEventMapper`）与 web 线**都**会序列化这些消息 ⇒ faux 驱动的 RPC 夹具此前**永远见不到 timestamp** | `docs/37 §2.3`（B48 的成因链） | **已核** |

---

## 3. 被推翻的命题

| # | 我原先以为 | 实测 | 后果 |
|---|---|---|---|
| **R1** | 「pi 的 faux 大概也不挂身份，这是夹具件的通例」 | **错**：pi 的 faux **挂得比 pi-java 还真**（`api`/`provider`/`model`/`timestamp`/`usage` 五项全写，P1–P4） | ⇒ 本包不是「给夹具加个特例」，而是**把 pi-java 的 faux 拉回 pi 的形状** |
| **R2** | 「A16 只是个测试卫生问题」 | **半错**：它有一条**真实的行为后果** —— faux 驱动的夹具里，压缩估算与溢出守卫**读的是 null 身份**（P8）⇒ 那些守卫在夹具里**根本没被行使过** | ⇒ 本包会**改变既有夹具的行为**（不是「只改形状」），风险与价值都在这里 |

---

## 4. 审计（顺带发现）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| **A** | **风险与价值是同一件事**：挂上身份后，faux 驱动的夹具会**第一次**真正走到 P8 的那几条守卫（`sameModel`、stale、估算）⇒ 既可能**暴露新问题**（好），也可能**红掉一批既有夹具**（要逐一判断是「夹具在钉错的形状」还是「真回归」） | P8、P9 | ⇒ **裁决点 A**：范围先收窄到「只让 faux 挂身份」，红了再逐个判断 |
| **B** | 包⑩ 的 B48 **本可以被更早发现**：只要有一条 faux 驱动的 RPC 夹具，`agent_end` 就会带 timestamp、就会抛 | `docs/37 §4-B` | ⇒ 本包**应**顺手补一条这样的端到端夹具（B48 的回归面），但**要等 P8 的连带影响澄清之后** |
| **C** | `FauxProvider` 有 `send()`（非流式）实现，也直接构造 `Message.AssistantMessage` ⇒ **同一个身份问题** | `FauxProvider.java:192-200` | ⇒ 一并处理 |
| **D** | pi-java 的其它测试侧 `ChatApi` 桩（若有）可能同类 | 未清点 | ⇒ **裁决点 C** |

---

## 5. 待核 / 裁决点

| # | 问题 | 结论 |
|---|---|---|
| **N1** | 怎么让 faux 挂身份 | **裁决点 A**：继承 `AbstractChatApi`（走**同一个缝**）vs 在 `FauxChatApi` 里自己挂（复制逻辑） |
| **N2** | `apiName()` 返回什么 | pi 是 `"faux"`（P3）⇒ 照抄 |
| **N3** | 范围 | **裁决点 B**：只 `FauxProvider` vs 连同其它测试侧 `ChatApi`（§4-D 未清点） |
| **N4** | P8 连带影响的处置 | **裁决点 C**：红了**逐个判断**（并如实记下每一条是「夹具钉错形状」还是「真回归」），**不许批量改断言蒙过去** |

---

## 6. 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 给 faux 造一套**与生产不同**的身份（如 `api="faux"` 但其余留空） | 那就还是「不同路的夹具」；pi 的 faux 五项全写（P1） |
| 2 | 顺手改生产路径 | 生产侧本来就是对的（P5） |
| 3 | 为了让既有夹具变绿而**放宽**它们 | §8.0 裁决 C 明写「逐个判断」——放宽＝回到盲区 |

---

## 8. 实施稿（步骤 ③ 的产物；**待用户审核后才写代码**）

### 8.0 裁决点

| # | 裁决 | 后果 |
|---|---|---|
| **A** | **`FauxChatApi` 继承 `AbstractChatApi`**（实现 `apiName()` ＋ `streamInternal`）⇒ 走**同一个**身份挂载缝 | 一处改动、两条路同形。⚠️ 备选「自己挂」会留下**两份**身份逻辑，日后必分叉 |
| **B** | **本包只做 `FauxProvider`**（不起其它测试侧 `ChatApi`） | 范围可控；其余桩若存在，**登记**另办 |
| **C** | 红了**逐个判断**并**如实记录**每条的性质 | 这是本包最重要的纪律 —— 它决定了这批红是「发现」还是「掩盖」 |

### 8.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-ai` | `FauxChatApi` 改继承 `AbstractChatApi`（`apiName()="faux"`、`streamInternal(...)`）＋ `send()` 走同一条身份路 ＋ 新夹具（钉「faux 的消息带五项身份」） |
| **2** | 全仓 | 跑全量、**逐个判断**变红的夹具（§8.0 裁决 C），把每条的判断写进记录 |
| **3** | `docs/38`＋`docs/32` | 实施记录 ＋ A16 结案 ＋ 顺带补 B48 的回归面 |

### 8.2 精确改动（形状）

`FauxChatApi extends AbstractChatApi`：

```java
private static final class FauxChatApi extends AbstractChatApi {
    @Override public String apiName() { return "faux"; }      // pi: DEFAULT_API（faux.ts:23）

    @Override protected void streamInternal(StreamRequest request,
                                            SubmissionPublisher<StreamEvent> publisher) {
        // 既有的事件重放循环（原样搬进来）
    }
}
```

⚠️ `AbstractChatApi` 是 `abstract class` —— 从「实现接口」改成「继承基类」要核：
① `stream()`/`streamBlocking()`/`send()` 的默认实现是否改变 faux 的既有语义
（尤其 `stream()` 的**懒启动**与「先订阅后发射」那条竞态，见其 javadoc）；
② `FauxChatApi` 现有的自定义 `stream()`（`FauxProvider.java:163-184`）与基类版本的差别。

### 8.3 测试计划（**先红**）

| 模块 | 夹具 | 条 | 钉什么 |
|---|---|---|---|
| `pi-java-ai` | `FauxProviderIdentityTest` | 4 | ① 流式 partial 带 `api="faux"`/`provider`/`model`/`timestamp` ② 一个流只有一个 `timestamp` ③ 终局消息带五项（含合成 `usage`）④ `send()` 同样带 |
| `pi-java-coding-agent` | `RpcModeEndToEndTest` 扩 | 1 | ⑤ **B48 的回归面**：faux 驱动的 RPC 线上，`agent_end.messages` 带 `timestamp` 且**不再抛** |

### 8.4 变异探针设计（**红集不预测，跑完实测记录**）

| # | 改坏什么 | 期望能证明 |
|---|---|---|
| P1 | faux 不挂身份（回到今天） | ①②③④⑤ 有牙 |
| P2 | 每个事件各取一次 `Instant.now()` | ② 有牙 |
| P3 | `send()` 留在旧路 | ④ 有牙 |
| P4 | `apiName()` 返回别的 | ① 有牙 |

### 8.5 未覆盖（预登记）

- ⚠️ **P8 的连带影响无法预判**：`sameModel`/stale/估算那几条守卫**从未在夹具里被行使过**
  ⇒ 挂上身份后它们的行为变化**只能在实施时测量**。**不许预测**，红了逐个判断。
- **其它测试侧 `ChatApi` 桩**（§4-D）：未清点 ⇒ 若本包只做 `FauxProvider`，如实登记。
- **端到端**：真实 provider 仍不在夹具范围内（那要靠 `PayloadRecording` 一类的手段）。

---

## 9. 下一步

用户审核 §8（含 §8.0 三个裁决点）之后才写代码；实施完成后追加**实施记录**
（含**每一条变红夹具的判断**）。

---

## 10. 实施记录（2026-09-20）

### 10.1 落地了什么

| 步 | 模块 | 内容 |
|---|---|---|
| 1 | `pi-java-ai` | `FauxChatApi` 改**继承 `AbstractChatApi`**（`apiName()="faux"` ＋ `streamInternal` 重放），删掉自己那套 `stream`/`streamBlocking`/`send`；新夹具 `FauxProviderIdentityTest`（4 条） |
| 1′ | `pi-java-ai` | ⚠️ **超出 §8 的一处**（见 §10.6）：`AbstractChatApi` 新增**终局事件补计量** `withTerminalUsage` ＋ `ZERO_USAGE` |
| 1″ | `pi-java-coding-agent` | ⚠️ **实施中发现**（见 §10.6）：把包⑩ 的「`Instant → epoch 毫秒`」止血提成 `WireJson`，并给**命令线**（`JsonlWriter`）与**导出线**（`RpcDispatcher`）补上同一处 —— 这两处此前是裸 `ObjectMapper`（台账 B52） |
| 3 | `pi-java-coding-agent` | `RpcModeEndToEndTest` 加 `get_entries` 一段（§8.3 ⑤ 的回归面） |

### 10.2 三个裁决点执行情况

| # | 裁决 | 执行 |
|---|---|---|
| A | 继承 `AbstractChatApi`（走同一个缝） | ✅ 照做。`grep "implements ChatApi"` 现在只剩 `AbstractChatApi` 自己 —— **全仓再无旁路桩** |
| B | 只做 `FauxProvider` | ✅ 照做；§4-D 的「其它测试侧桩」**已清点**：`editing` 两个测试桩（`AbstractChatApiTest.ZeroIoApi`、`DefaultProvidersTest.RecordingChatApi`）**本来就继承基类** ⇒ 唯一绕路的就是 `FauxChatApi`，本条无欠账 |
| C | 红了逐个判断、不许批量改断言 | ✅ 见 §10.5（全量只红一条，且判定为**既有** A12，证据在下面） |

### 10.3 夹具与实测（§8.3 五条）

`pi-java-ai` 452 条全绿（含新夹具 4 条）：

| # | 夹具 | 结果 |
|---|---|---|
| ① | `streamedPartialsCarryTheFiveIdentityFields` | ✅ |
| ② | `oneStreamKeepsASingleTimestamp` | ✅ |
| ③ | `terminalMessageCarriesUsageToo` | ✅ |
| ④ | `sendReturnsAFullyIdentifiedMessage` | ✅ |
| ⑤ | `RpcModeEndToEndTest`（faux 驱动的 RPC 线上 `get_entries` 带 timestamp 且不抛） | ✅ |

⚠️ **夹具是先有实现、后补夹具的**（本包在工作区里已改完才补证）⇒ **没有红灯可看**，
故 §8.4 的四条变异探针**不能省**（§10.4）。

### 10.4 变异探针实测红集（§8.4；**跑出来的，不是预测的**）

| # | 改坏什么 | 实测红集 |
|---|---|---|
| P1 | faux 不挂身份（`FauxChatApi` 覆盖 `stream()` 走旧路＝改动前） | **4/4 全红**（①②③④） |
| P2 | 每个事件各取一次 `Instant.now()` | 恰 1 条：② |
| P3 | `send()` 留在旧路（自己拼消息、不过缝） | 恰 1 条：④ |
| P4 | `apiName()` 返回别的 | 3 条：①③④（② 仍绿 —— 它只钉「同一个 timestamp」） |

夹具⑤ 单独做了一次同类探针：把 `JsonlWriter` 的 mapper 退回裸 `ObjectMapper`
⇒ `RpcModeEndToEndTest.fullPromptLoopOverPipes` **恰一条红** ⇒ ⑤ 钉的正是 B52 那条回归面。

⚠️ 探针实施坑（记下来省下次的时间）：`FauxProvider.java` 是 **CRLF**，锚点用 `\n` 拼会**找不到**；
`mvn -pl <模块>` **不带 `-am`** 会用 `~/.m2` 的旧构件，跑出的红/绿都可能是假的（见 §10.5）。

### 10.5 P8 连带影响实测（§8.1 步 2）—— 全量结果与逐条判断

全量 `mvn -fae test`（14 模块，6:16）：**13 SUCCESS / 1 FAILURE**，模块条数
telemetry 31、ai 452、agent-core 487、sqlite 35、coding-agent 263、protocol 14、server 2、
TUI 14（3:42 min）、evals/protocol/client SUCCESS、dist SKIPPED。

**唯一一条红**：`pi-java-web` 的 `PiWebServerAuthTest.acceptsConnectionWithValidToken`
（判定：**既有 A12，非本包引入**）。判断过程与证据：

1. 全量里的症状是**收不到 `ready` 帧**（客户端 17:02:14.348 连上 → 17:02:29.353 断开，
   15 s 超时），且同批**通过**的 `PiWebServerIntegrationTest` 在同一位置也等了 **4.3 s**
   才收到 `ready` ⇒ 这是「`ready` 的时延 = `AgentSession.createWeb` 全量（settings 载入
   ＋ 模型目录 ＋ 会话恢复）耗时」在负载下超了夹具的 15 s 窗口，与 A12 记的**同一症状家族**。
2. ⚠️ **第一轮"基线对照"证据作废**（如实记）：我用 `mvn -pl pi-java-web surefire:test`
   （**不带 `-am`**）想跑基线，三次**确定性红**、症状是 `Failed to create agent session: null`
   —— 抓到栈是 `Settings.unknown() → Map.copyOf` **NPE**。但当前源码里**根本没有**
   `Map.copyOf`（aa7d6ac 早已修掉）⇒ 那个类来自 `~/.m2` 里的**旧 jar**。即：**那条红是
   假红**，与基线/改动**都无关**，是「单模块跑吃了旧构件」的产物。
3. 正确的对照：`mvn -pl pi-java-web -am test -Dtest=PiWebServerAuthTest`（全链编译、机器空闲）
   —— **基线 3/3 绿（2.13 s）**、**带本包改动 3/3 绿（2.06 s）**，两侧对称 ⇒ 本包**不引入**
   这条红，也没有把这个窗口变差。

⚠️ **P8 的结论要按字面读**：挂上身份后全量**0 条新红**。这**不**证明「那几条守卫
（`sameModel`／stale／估算）现在与 pi 同形」—— 更可能的解释是**它们在夹具里依然没被行使**
（那是 A16 的原发现，本包只解决了「路不同」，没解决「有没有走到」）。**不许把绿当成对。**

### 10.6 ⚠️ 超出 §8 的两处（**请用户过目**）

| # | 改动 | 为什么做了 | 风险 |
|---|---|---|---|
| 1 | `AbstractChatApi.withTerminalUsage`：终局事件（`StreamDone`/`StreamError`）快照若整条流**没报过用量**，补事件自带的 `UsageInfo`、没有则补 `ZERO_USAGE` | 夹具③ 要钉「pi 的 faux 每条消息都带 `usage`」（`faux.ts:289` 的 `cloned.usage ?? DEFAULT_USAGE`），而 pi-java 的用量挂在**缝**上 ⇒ 不补则 faux 的终局消息 `usage` 恒 null、与 pi 不同形 | ⚠️ **这是生产路径改动**（§6-2 明写「不顺手改生产路径」）。若你裁决不要，**夹具③ 就得改成只钉 faux 自己的事件**（即由 `FauxProvider` 造带 usage 的消息），缝不动 |
| 2 | `WireJson`（提取包⑩ 的止血）＋ `JsonlWriter`／`RpcDispatcher` 注册 | 实施中发现**同成因**咬在命令线（`get_entries`/`get_tree`）与导出线（`export_html`/`export_jsonl`）：那两处也是裸 mapper ⇒ 带 `Instant` 的消息**直接抛**，客户端只看到 `success:false`。⑤ 就是钉它 | 低（纯补一件事，两处各 +1 行注册）；已登记台账 **B52**（此前代码注释引用了这个号，但**台账里并没有这一条** —— 本包补登记） |

### 10.7 未覆盖（如实登记）

- **真实 provider 仍不在夹具范围内**（§8.5 原样）：本包只让 faux 与生产同形，不等于
  真实车道的消息形状被钉住。
- **P8 那几条守卫的实际行为**：本包没测（见 §10.5 的告警）。要真回答「faux 驱动下压缩估算
  与溢出守卫读到的身份对不对」，得**另立夹具**去行使它们。
- **A12（web `ready` 帧时延）**：本包只补了证据（§10.5），**没有定位根因**，故未修。

### 10.8 提交

| commit | 内容 |
|---|---|
| `feat(ai)` | faux 走身份缝 ＋ 夹具 4 条（含 §10.6-1 的补计量） |
| `fix(coding-agent)` | `WireJson` ＋ 命令线/导出线止血 ＋ ⑤ 回归面夹具 |

