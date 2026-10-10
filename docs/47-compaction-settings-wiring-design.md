# 47 compaction 设置接线 —— 自动压缩默认开、预算按当前模型现解析（`B223`）

> **状态：📝 待用户审核（2026-10-10 起稿）。**
> 承接 `docs/04` 在册缺口 **`B223`**（2026-10-10 B139 包取证时新登，`docs/46` §6）。
>
> **一句话**：B139 修好后 `settings.json` 的 `compaction` 块**能完整读写了，但全仓没有任何
> 消费者读它**——宿主建 harness 时从不挂压缩设置槽，引擎 G0 恒见 `null` ⇒
> **pi-java 自动压缩默认关闭，pi 默认开启**；手动 `/compact` 用硬编码预算；
> RPC 开关只改内存不落盘；`compaction.modelOverrides` 是休眠字段。
>
> **本包只做「接线」**：把设置块按 pi 的解析语义接到引擎已有的全部压缩判定点，
> 引擎的压缩机制本身（阈值门、溢出恢复、切点、摘要）3b/3c 时代已逐条对齐，不动。

---

## 1. 缺口是什么（2026-10-10 实测复核）

### 1.1 自动压缩主开关在生产上恒为「关」

接线缺口链（四处全断，断口在第一处）：

| 环节 | 位置 | 现状 |
|---|---|---|
| 宿主装配 | `AgentSession.java:375-422` 建 `HarnessConfig.builder()` | **从不调** `.compactionSettings(...)`（同文件 :412 的 retry 槽 3d 已接，可作对照） |
| 引擎装载 | `AgentHarness.java:110` `lane.compactionSettings = config.compactionSettings();` | 恒得 `null`（`HarnessConfig.java:92` 槽位静默默认 null） |
| 判定 G0 | `PostRunCompactionCheck.java:133-137` | `settings == null` ⇒ **恒 false**，post-run 溢出/阈值两条自动路都不进 |
| 轮内门 | `CompactionExecutor.java:155-156`（`checkThreshold`）、：197-200（`runAutoCompaction`） | 同一个 `() -> lane.compactionSettings`（`AgentHarness.java:135`）⇒ 同样恒 null 跳过；`PiLaneEngine.java:414` 每轮调的阈值门因此从不发火 |

pi 默认（`packages/coding-agent/src/core/settings-manager.ts:914-916`，锚点逐字）：

```ts
getCompactionEnabled(): boolean {
    return this.settings.compaction?.enabled ?? true;
}
```

⇒ 用户不写 `compaction` 块时，**pi 自动压缩是开的**（预算 16384/20000），pi-java 是关的。
这是本包要修的**主行为差距**。

### 1.2 手动 `/compact` 用硬编码预算，不读设置

`MiscCommands.java:133`：

```java
ctx.session().compact(CompactionSettings.defaults(), custom);
```

RPC 同款（`RpcDispatcher.java:175-176`）：

```java
var result = session.compact(
    CompactionSettings.defaults(), c.customInstructions());
```

pi 的手动路在会话内部按**当前模型**现解析（`agent-session.ts:2901-2907`）：

```ts
const model = this.model;
if (!model) {
    throw new Error(formatNoModelSelectedMessage());
}

const settings = this.settingsManager.getCompactionSettings(model);
const pathEntries = this.sessionManager.getBranch();

const preparation = prepareCompaction(pathEntries, settings);
```

调用方只传 customInstructions（TUI `interactive-mode.ts:7012`
`await this.session.compact(customInstructions)`、:2234 同形；RPC `rpc-mode.ts:533`
`await session.compact(command.customInstructions)`）。手动路**不检查 enabled**
（关自动压缩时 `/compact` 仍可手动压），但 reserve/keepRecent 与 modelOverrides 必须生效。

### 1.3 RPC 开关只改引擎内存态，不落盘

`RpcDispatcher.java:426-433`：

```java
private void setAutoCompaction(boolean enabled) {
    var current = session.harness().getCompactionSettings();
    var defaults = CompactionSettings.defaults();
    session.harness().setCompactionSettings(new CompactionSettings(
        enabled,
        current == null ? defaults.reserveTokens() : current.reserveTokens(),
        current == null ? defaults.keepRecentTokens() : current.keepRecentTokens()));
}
```

pi（`settings-manager.ts:918-924`）是**写全局设置文件并即时 save**：

```ts
setCompactionEnabled(enabled: boolean): void {
    if (!this.globalSettings.compaction) {
        this.globalSettings.compaction = {};
    }
    this.globalSettings.compaction.enabled = enabled;
    this.markModified("compaction", "enabled");
    this.save();
}
```

后果三连：① 重启即忘；② `get_state` 的 `autoCompactionEnabled`（pi `rpc-mode.ts:459`
＝ `session.autoCompactionEnabled` → settings getter）在 pi-java 读 lane 内存态
（`RpcDispatcher.java:391,406`），全新会话恒报 **false**（pi 恒报 true）；③ 文件里
写的 `enabled` 永远不被尊重。

### 1.4 `modelOverrides` 休眠

B139 已让 `compaction.modelOverrides`（`Settings.java:140`）能落盘往返，但解析侧
不存在。pi 的预算解析是三级回退（`settings-manager.ts:927-953`）：

```ts
private getCompactionTokenSetting(
    field: keyof CompactionModelOverride,
    model?: Pick<Model<string>, "provider" | "id">,
): number {
    const compaction = this.settings.compaction;
    const ordinary = compaction?.[field];
    if (ordinary !== undefined && (typeof ordinary !== "number" || !Number.isSafeInteger(ordinary) || ordinary < 0)) {
        throw new Error(
            `Invalid compaction.${field} setting: ${String(ordinary)}. Expected a non-negative safe integer.`,
        );
    }

    const modelKey = model ? `${model.provider}/${model.id}` : undefined;
    const entry = modelKey !== undefined ? compaction?.modelOverrides?.[modelKey] : undefined;
    if (entry !== undefined && !isMergeableObject(entry)) {
        throw new Error(
            `Invalid compaction.modelOverrides["${modelKey}"] setting: ${String(entry)}. Expected an object.`,
        );
    }
    const override = entry?.[field];
    if (override !== undefined && (typeof override !== "number" || !Number.isSafeInteger(override) || override < 0)) {
        throw new Error(
            `Invalid compaction.modelOverrides["${modelKey}"].${field} setting: ${String(override)}. Expected a non-negative safe integer.`,
        );
    }
    return override ?? ordinary ?? DEFAULT_COMPACTION_TOKEN_SETTINGS[field];
}
```

键＝ **`provider/id` 精确匹配**，无层级回退；某条 override 只写一个字段时，另一个
字段继续走 ordinary → 默认。pi 四个自动/手动决策点全部传**当时的当前模型**现解析
（不缓存）：

| pi 调用点 | 用途 | model 实参 |
|---|---|---|
| `agent-session.ts:744`（`_exceedsCompactionThreshold`） | 请求前投影阈值门 | `this.model` |
| `agent-session.ts:2730`（手动 `compact()`） | 切点预算 | 当前 `model`（无模型先抛 no-model） |
| `agent-session.ts:3052`（`_checkCompaction`） | G0＋运行后阈值 | `this.model` |
| `agent-session.ts:2905`（`_runAutoCompaction`） | 实际压缩切点 | `const model = this.model` |

---

## 2. 目标形状（总览）

```
settings.json ──B139──▶ Settings.Compaction（边界 record，已就绪）
                              │
                 SettingsAccessors（本包新增，coding-agent）
                   · getCompactionEnabled()
                   · getCompactionSettings(ModelId?)  ← 三级回退 + 逐字校验
                   · setCompactionEnabled(boolean)    ← 全局写 + 即时 flush
                              │ Function<ModelId<?>, CompactionSettings>
                 HarnessConfig.compactionResolver（本包改槽：值→解析函数）
                              │ AgentHarness 装配：() -> resolver.apply(lane.model)
                              ▼
                 ExecutionContext 的 Supplier<CompactionSettings>（不动）
                              │
        ┌─────────────────────┼──────────────────────────┐
   G0/阈值（既有）      checkThreshold（既有）      runAutoCompaction（既有）
```

手动 `/compact` 与 RPC `compact` 改走新宿主方法 `AgentSession.compact(String)`
（内部按 `harness.getModel()` 现解析）；RPC `set_auto_compaction` 改走落盘 setter；
`get_state` 改读 settings getter。

---

## 3. 改动点（逐字代码）

### 3.1 `HarnessConfig`：值槽 → 按模型解析的函数槽

`pi-java-agent-core/.../harness/HarnessConfig.java`（现 273 行）。

第 92 行组件改类型（名字也改，避免「这是个静态值」的误读）：

```java
    // B223（docs/47）：pi 每个压缩决策点现读 getCompactionSettings(this.model)
    //（agent-session.ts:744/2730/2905/3052）。宿主给的是「按模型解析」函数；
    // AgentHarness 以 lane 当前模型逐次调用 ⇒ setModel、modelOverrides、设置文件
    // 改动都在下次决策自然生效；fork 的新 harness 用自己的 lane.model，不串会话。
    // null = 宿主未接线 ⇒ G0 关（引擎测试的旧形状；生产由 coding-agent 必接）。
    java.util.function.Function<ModelId<?>, CompactionSettings> compactionResolver,
```

- canonical ctor（:110-128）**不补默认**（null 合法，语义＝未接线）。
- 两个便利构造（:136-149）同位置参数同步换类型——现存直接调用方在该位置**全传
  null**（已逐个核实 `AgentHarnessIntegrationTest`/`CompactionTest` 等 10+ 处），
  改类型即兼容。
- Builder：字段（:164）与方法（:201）改名改型：

```java
    private java.util.function.Function<ModelId<?>, CompactionSettings> compactionResolver;
    public Builder compactionResolver(
            java.util.function.Function<ModelId<?>, CompactionSettings> resolver) {
        this.compactionResolver = resolver; return this;
    }
```

`build()` 透传（:265）；javadoc 第 52 行同步改写。

### 3.2 `AgentHarness`：按车道当前模型适配，删 lane 字段与内存 getter/setter

`.../harness/AgentHarness.java`。

- 删 :110 `lane.compactionSettings = config.compactionSettings();`。
- :135 的 ExecutionContext 装配改成现解析 supplier：

```java
final java.util.function.Function<ModelId<?>, CompactionSettings> compactionResolver =
    config.compactionResolver();
Supplier<CompactionSettings> compactionSettings = () -> compactionResolver == null
    ? null
    : compactionResolver.apply(lane.model);
```

   原参 `() -> lane.compactionSettings` 换成这个局部 supplier（ExecutionContext
   ctor 形参仍是 `Supplier<CompactionSettings>`，消费侧零改动）。
- 删 `LaneState.compactionSettings` 字段（`LaneState.java:235`，全仓引用只在
  AgentHarness 四处，见上）。
- 删 :542-547 一对公开 get/set（**唯一**外部使用者是 RpcDispatcher，本包改写）；
  AgentHarness 已为 fork 留存 `config`（:49），新增包私有观察口（不进公开 API）：

```java
/** 下一次压缩决策将使用的设置（按当前车道模型现解析）；引擎测试观察口（docs/47）。 */
CompactionSettings currentCompactionSettings() {
    var resolver = config.compactionResolver();
    return resolver == null ? null : resolver.apply(lane.model);
}
```

   `fork()`（:154）走 `new AgentHarness(config)` 同配置独立 lane，新 harness 的
   `lane.model` 独立可变 ⇒ fork 后各自解析，不串模型。

### 3.3 `SettingsAccessors`：pi 解析器三件套（新增方法）

`pi-java-coding-agent/.../core/SettingsAccessors.java`（现 242 行）。
返回 agent-core 的 `com.pijava.agent.compaction.CompactionSettings`
（getRetrySettings 返回 agent.harness.RetrySettings 已是同模式）。

```java
/** 自动压缩主开关（pi getCompactionEnabled，settings-manager.ts:914-916：?? true）。 */
public boolean getCompactionEnabled() {
    var c = manager.effective().compaction;
    return c == null || c.enabled() == null || c.enabled();
}

/**
 * pi getCompactionSettings(model)（:964-974）：每个压缩决策点现读合并视图。
 * 预算三级回退＝modelOverrides["provider/modelId"] 精确命中 → 普通字段 →
 * 16384/20000（:23-26）。model 为 null ⇒ 不查 override（pi model 缺席形状）。
 */
public CompactionSettings getCompactionSettings(com.pijava.ai.model.ModelId<?> model) {
    var c = manager.effective().compaction;
    return new CompactionSettings(
        getCompactionEnabled(),
        compactionToken(c, model, true),
        compactionToken(c, model, false));
}

private static int compactionToken(Settings.Compaction c,
                                   com.pijava.ai.model.ModelId<?> model,
                                   boolean reserve) {
    String field = reserve ? "reserveTokens" : "keepRecentTokens";
    int dflt = reserve ? 16_384 : 20_000;
    Integer ordinary = c == null ? null
        : reserve ? c.reserveTokens() : c.keepRecentTokens();
    if (ordinary != null && ordinary < 0) {
        // pi :935-938 逐字（Integer 必为安全整数，safe-integer 校验在类型层已成立）。
        throw new IllegalArgumentException(
            "Invalid compaction." + field + " setting: " + ordinary
                + ". Expected a non-negative safe integer.");
    }
    Settings.Compaction.ModelOverride entry = null;
    if (c != null && c.modelOverrides() != null && model != null) {
        entry = c.modelOverrides().get(model.provider() + "/" + model.modelName());
    }
    Integer override = entry == null ? null
        : reserve ? entry.reserveTokens() : entry.keepRecentTokens();
    if (override != null && override < 0) {
        // pi :947-950 逐字（含精确键名）。
        throw new IllegalArgumentException(
            "Invalid compaction.modelOverrides[\""
                + model.provider() + "/" + model.modelName() + "\"]." + field
                + " setting: " + override
                + ". Expected a non-negative safe integer.");
    }
    return override != null ? override : ordinary != null ? ordinary : dflt;
}

/**
 * 写全局 compaction.enabled（pi setCompactionEnabled，:918-924）：
 * 全局改写（保留其余组件与 modelOverrides）+ markModified + <b>即时</b> flush，
 * 与 setRetryEnabled 同款（settings 晚读口下次决策即见，不必碰引擎状态）。
 */
public void setCompactionEnabled(boolean enabled) {
    var global = manager.global();
    var c = global.compaction;
    global.compaction = c == null
        ? new Settings.Compaction(enabled, null, null, null)
        : new Settings.Compaction(enabled, c.reserveTokens(), c.keepRecentTokens(),
            c.modelOverrides());
    manager.markModified("compaction");
    manager.flush();
}
```

两点边界说明：

- pi 的「entry 不是 mergeable object 抛错」（:942-946）在 Java **类型层不可达**
  （`Map<String, ModelOverride>` 反序列化即对象）；普通字段类型错误在 Java 是
  Jackson 读文件期失败（与 retry 等全部数值设置同形的既有边界差异，本包不扩）。
- 校验只可能在**决策点**抛出（与 pi 同：pi 也是 resolver 被调用时才抛）。

### 3.4 `AgentSession`：接线 + 手动压缩新入口

装配处（:375 builder 链上，紧挨 :412 retry 三件套）加一行：

```java
            // B223（docs/47）：压缩设置晚读口 —— 引擎每次决策按 lane 当前模型
            // 回调本 resolver（pi getCompactionSettings(this.model)，四调用点）；
            // 不接线 ⇒ 自动压缩恒关（B223 原状）。
            .compactionResolver(model -> settings.accessors().getCompactionSettings(model))
```

（`settings` 是 create 链上既有的 `SettingsManager` 局部量，:317 已被
`settings.accessors()::getRetrySettings` 使用。）

新增手动入口（紧挨现有 :705-716 两个 compact 重载；旧重载保留作引擎层测试缝）：

```java
/**
 * pi {@code AgentSession.compact(customInstructions)}（agent-session.ts:2898-2907）：
 * 手动压缩的预算按<b>当前模型</b>现解析（modelOverrides 对手动路同样生效），
 * enabled 不门手动路（pi 手动路不读 enabled）。
 */
public com.pijava.agent.compaction.CompactionResult compact(String customInstructions) {
    var resolved = services.settings().accessors().getCompactionSettings(harness.getModel());
    return harness.compact(laneName(), resolved, customInstructions);
}
```

（`services()` 见 `AgentSession.java:513`；`SessionServices.settings()` 组件。
模型缺席时 resolver 按 null 解析、跳过 override——不抛 no-model；pi 的 no-model
抛错在 Java 由后续 LLM 调用路径承载，属既有方言，不在本包。）

### 3.5 两个调用面改形

- `MiscCommands.java:133`：

```java
                    ctx.session().compact(custom);
```

- `RpcDispatcher.java`：
  - :175-176 compact 命令 → `var result = session.compact(c.customInstructions());`
  - :191-193 → 落盘 setter，删 :426-433 整个 `setAutoCompaction` 私有方法：

```java
                case RpcCommand.SetAutoCompaction a -> {
                    session.services().settings().accessors()
                        .setCompactionEnabled(a.enabled());
                    out.write(RpcResponse.ok(a.id(), "set_auto_compaction"));
                }
```

  - `buildState()`（:391,406）→ 读 settings getter（pi rpc-mode.ts:459）：

```java
        boolean autoCompactionEnabled =
            session.services().settings().accessors().getCompactionEnabled();
```

    `RpcSessionState` 第 10 个实参由 `compaction != null && compaction.enabled()`
    换成它；删掉 `compaction` 局部量。若 `CompactionSettings` import 因此闲置则删。

### 3.6 受影响测试的机械迁移

- `CompactionInFlightTest.java:75`、`LaneMessagesTest.java:236`、
  `PostRunOverflowDriveTest.java:98` 的 builder 调用
  `.compactionSettings(X)` → `.compactionResolver(m -> X)`。
- `RpcDispatcherTest.java:126-127` 两条旧内存态断言改写（见 §4 T8）。
- `ExecutionContext` 直接构造的测试（`CompactionThresholdGateTest` 等）**不动**
  （ctor 形参仍是 `Supplier<CompactionSettings>`）。

---

## 4. 夹具（先红）与变异探针

### 4.1 agent-core 新增 `CompactionResolverWiringTest`

（同包 harness，极简 builder 形状照搬 `CompactionInFlightTest`。）

| # | 用例 | 断言 | 现状 |
|---|---|---|---|
| T-A1 | 不接 resolver | `currentCompactionSettings()` 为 null；`setModel` 后仍 null | 绿（旧形状）——护栏 |
| T-A2 | resolver 按模型返回不同预算；建 harness 时模型 A，`setModel(B)` 后再读 | 两次解析分别得到 A/B 的值（证明**每次决策重读、读当前模型**，不是建时快照） | **红**（槽是静态值） |
| T-A3 | fork：原 harness 模型 A，fork 后 fork `setModel(B)` | fork 解析得 B、原 harness 仍 A（证明适配用的是**各自 lane.model**，不闭包旧 harness） | **红** |

### 4.2 coding-agent 新增 `CompactionSettingsAccessorTest`

真 `SettingsManager` + @TempDir（`FileSettingsStorage(dir, dir)` /
`SettingsManager.withStorage(...)`，与 B139 的 `FileSettingsRoundTripTest` 同款，
绝不用内存替身）：

| # | 用例 | 断言要点 |
|---|---|---|
| T1 | 无 compaction 块 | `getCompactionEnabled()` true；任意/null model 得 `(true,16384,20000)` |
| T2 | 普通字段 `reserveTokens:100, keepRecentTokens:9` | `(true,100,9)` |
| T3 | `enabled:false` | enabled false；预算仍是默认（enabled 不影响预算解析） |
| T4 | modelOverrides `{"faux/small":{reserveTokens:7,keepRecentTokens:3}}` | 模型 `ModelId.of("faux","small")` 得 `(true,7,3)`；`("faux","big")`、`("other","small")` 均得默认；**键是精确 provider/id 全串** |
| T5 | override 只写 `keepRecentTokens:9` + ordinary `reserveTokens:100` | `(true,100,9)`（缺字段回退 ordinary，再缺回默认） |
| T6 | 非法值 | ordinary `reserveTokens:-1` ⇒ 异常消息逐字 `Invalid compaction.reserveTokens setting: -1. Expected a non-negative safe integer.`；override `keepRecentTokens:-5` ⇒ 消息含 `modelOverrides["faux/small"].keepRecentTokens` |
| T7 | setter | `setCompactionEnabled(false)`：全局文件即时出现 `compaction.enabled=false`（重读文件验证），reserve/modelOverrides 原值保留；再 `setCompactionEnabled(true)` 翻回；project 层 `enabled:false` 时写 global 不改变 effective（合并语义，与 pi 同） |

### 4.3 RPC 面：改写 + 新红

- 改 `secondBatchControlCommands`（RpcDispatcherTest:95-128）：旧的 harness 内存态
  断言（:126-127）换成——
  - `set_auto_compaction` 后从临时 home（该测试 :437-445 已把 `user.home` 指到
    @TempDir，FileSettingsStorage 构造期捕获路径）读 settings.json，含
    `"compaction":{"enabled":true ...}`；
  - 经 `get_state` 输出断言 `"autoCompactionEnabled":true`。
- 新增 T8（**主行为红**）：全新会话（设置目录无 compaction 块）直接 `get_state`
  ⇒ `"autoCompactionEnabled":true`。现状报 false（lane 为 null）⇒ **红**。

### 4.4 变异探针（实施后，全部先 grep 复核落地，CRLF 教训）

- **M1**：`getCompactionEnabled` 的缺席默认翻成 false（删 `c == null ||` 一支）
  ⇒ T1/T3 侧、T4/T5 与 T8 红（预测 ≥4 红）。
- **M2**：预算回退顺序翻转（override 存在时仍优先 ordinary）⇒ T4 恰 1 红
  （T5 的半覆盖形状也可能红，实施时记录实际红集）。
- **M3**：AgentHarness 适配改成建时快照（lambda 外 `final var m = lane.model;`
  闭包 m）⇒ T-A2/T-A3 红。探针恢复后全绿。

---

## 5. 裁决记录

| 议题 | 裁决 | 理由 |
|---|---|---|
| 槽位形状 | `Function<ModelId<?>,CompactionSettings>`（引擎按 lane.model 逐次调） | pi 四个调用点都传**当前模型**；静态值槽会让 setModel/modelOverrides 失效；plain `Supplier` 闭包宿主 harness 会在 fork 后串模型 |
| 引擎未接线时的默认 | resolver == null ⇒ G0 关（**不**改成默认开） | 引擎是库，既有测试与嵌入方依赖「不接＝不压」；pi 的「默认开」由生产宿主接线实现（本包在 AgentSession 接上），与 retrySettings 默认 defaults() 的情形不同（retry 环无 null 语义消费者） |
| 手动路 enabled | 忽略（enabled=false 也能手动压） | pi `compact()` 不检查 enabled；CompactionService 主源码零处读 enabled（已核） |
| 手动入口形状 | 新增 `AgentSession.compact(String)`，旧 `compact(CompactionSettings[,String])` 保留 | pi 入口只收 customInstructions；旧重载是引擎层测试缝，删除无收益 |
| RPC 开关 | 写 settings 文件 + 即时 flush，删引擎内存 setter | 对齐 pi setCompactionEnabled；晚读口让下次决策自然生效，不必双写 |
| 非法值校验 | 负数抛 pi 逐字文案；类型非法不可达不造 | Jackson 边界类型已保证；safe-integer 对 int 恒成立 |
| 引擎结构改动范围 | HarnessConfig 槽换型 + 删 LaneState 字段；ExecutionContext/PiLaneEngine/三个判定点**零改动** | 消费侧早就是「Supplier 现读」形状，断的只是供给 |

---

## 6. 范围外

- 压缩机制本身（阈值公式、切点、溢出恢复、摘要提示词、事件时序）——3b/3c 及
  包 13–23 已闭环，本包不碰。
- pi `experimental/durable`、`experimental/vacation` 的 harness-setup 也调
  `getCompactionSettings()`（无 model 实参），非 `bin pi` 主流可达，不接。
- TUI 键位绑定里的 auto-compaction 开关（pi 侧经 settings-selector 走同一
  setter）：pi-java TUI 主源码**零处** compaction 设置消费（已 grep），没有
  可接的面，不造。
- 不新登缺口（branchSummary 块等已在台账既有条目族内）。

## 7. 验证 ／ 提交

- 先红：T-A2/T-A3/T8 在旧代码红（编译红＋行为红混合，随落地顺序记录）。
- `JAVA_HOME=...graalvm-jdk-25 mvn -pl pi-java-agent-core,pi-java-coding-agent -am test`
  （-am 防旧构件假绿；`-Dsurefire.failIfNoSpecifiedTests=false` 只在筛跑时加）。
- 探针 M1/M2/M3 各自记录红集后恢复。
- 收尾：两模块全测；`mvn checkstyle:check`（14 模块 0 违规）；确认无
  `System.out.println` 残留；文件 ≤500 行（SettingsAccessors 约 242→310，
  HarnessConfig 273 不增行数）。
- 提交拟 2 个：
  1. `feat(agent-core): resolve compaction settings per current model (B223)`
     （槽换型 + AgentHarness/LaneState + T-A* + 三处测试迁移）
  2. `feat(coding-agent): wire compaction settings to resolver and persist toggle (B223)`
     （Accessors + AgentSession + 两个调用面 + T1–T8）
- 闭环回填：本稿 banner/§12、`docs/04`（销 B223、计数）、`docs/41`（§7 批次行）。

---

## 12. 闭环记录

（待闭环填写。）
