# 46 Settings 嵌套块回写不丢值（`B139`）

> **状态：⬜ 待审核 → 待实施。**
> 承接 `docs/41` 的在册缺口 **`B139`**（请求选项/Provider 协议面；2026-10-10 §3.2 回测维持）。
>
> **一句话**：共享 mapper 关了 GETTER 可见性，5 个嵌套 record（`Compaction`/`Terminal`/`Image`/`Markdown`/`Tui`）
> 被序列化成 **`{}`** —— 用户写在 settings.json 里的这些块，**经过 Java 一次读写即全部丢失**。
> `Retry`/`ProviderRetry` 当年已用 `@JsonProperty` 修好，本包照同一药方补齐其余 5 块，
> 并把组件类型校正到 pi 锚点的真实形状。
>
> **本稿只做「持久化往返不丢」这一件事**；接线缺失（见 §6 新登 `B222`）不做。

---

## 1. 缺口是什么（2026-10-10 实测复核）

台账原行（`docs/04`）：

> | B139 | 嵌套 record 经 Json mapper 序列化成空对象 | mapper 关 GETTER；Retry/ProviderRetry 已修，其余嵌套 record 待取证 |

用临时夹具（已删）经**真 `FileSettingsStorage`** 复现，四个观察点全红。给一块填满的 Settings 做序列化：

```json
"compaction" : { },
"terminal" : { },
"images" : { },
"markdown" : { },
"tui" : { }
```

| # | 破坏路径 | 后果 |
|---|---|---|
| ① | `Json.mapper().writeValue(settings)` | 5 个块 → `{}`，所有子键丢 |
| ② | `FileSettingsStorage.writeGlobal(...)` | **落盘即 `{}`**，不等到 flush |
| ③ | `SettingsManager.effective()`（merge 经 `valueToTree` 往返） | 用户块在合并视图里已是 `{}` |
| ④ | `accessors().setTheme(...)` + `flush()` | 借一次无关写，用户块永久销毁 |

### 1.1 根因

`Json.java:15-17`：

```java
.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.PUBLIC_ONLY)
.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
```

record 没有独立字段，组件访问器（`enabled()`）按「getter」被忽略 ⇒ BeanSerializer 看不到任何属性 ⇒ `{}`。
当年 `Retry` 的修法（`Settings.java:105-112`）＝逐组件 `@JsonProperty` 显式钉属性，本包复用。

### 1.2 取证中发现的附带形状错误

逐字比对 pi 锚点（`git show 200387122`，`packages/coding-agent/src/core/settings-manager.ts`）后，
除 `{}` 之外还有三处「往返必坏」：

| # | 位置 | Java 现状 | pi 锚点 | 坏法 |
|---|---|---|---|---|
| **a** | `Markdown` | `int codeBlockIndent, boolean mermaid` | `string codeBlockIndent`（默认 `"  "`）、`mermaid: "off"\|"final"\|"streaming"`（:85-90） | pi 写出的块 Java **反序列化直接抛** `InvalidFormatException` ⇒ 整个 settings 读不起来 |
| **b** | `Compaction` | 3 组件 | 多 `modelOverrides?: Record<string, {reserveTokens?, keepRecentTokens?}>`（:28-35） | 嵌套未知键被静默丢弃，flush 即销毁 |
| **c** | `Terminal` | 2 组件 | 多 `clearOnShrink`/`showTerminalProgress`/`hyperlinks`/`images`/`trueColor`（:57-65） | 同 b |
| **d** | 全部 5 块 | primitive 组件 | pi 全部 `?` 可选（:28-90） | `{}` 读成 `false/0`；合并时「缺席」与「显式零」不可区分，部分块会零值覆盖 |

旁证：pi 的用户文档 `packages/coding-agent/docs/settings.md:70-113` 逐行确认上述键与默认值；
且锚点**全仓零处**出现 `tui.scroll_*` 等 10 个键 —— Java 的 `tui` 块是**自有扩展**
（源自 Codex TUI2 PR #8357，不是 pi），但有真实消费者（`ScrollConfig`），本包保留仅修往返。

---

## 2. pi 锚点的权威形状

`packages/coding-agent/src/core/settings-manager.ts`（行号为锚点行号）：

```ts
// :18-25
export interface CompactionModelOverride {
	reserveTokens?: number;
	keepRecentTokens?: number;
}

// :28-34
export interface CompactionSettings {
	enabled?: boolean; // default: true
	reserveTokens?: number; // default: 16384
	keepRecentTokens?: number; // default: 20000
	modelOverrides?: Record<string, CompactionModelOverride>; // exact "provider/modelId" keys
}

// :57-65
export interface TerminalSettings {
	showImages?: boolean; // default: true
	imageWidthCells?: number; // default: 60
	clearOnShrink?: boolean; // default: false
	showTerminalProgress?: boolean; // default: false
	hyperlinks?: boolean | "auto";
	images?: "kitty" | "iterm2" | "auto" | false;
	trueColor?: boolean | "auto";
}

// :67-71
export interface ImageSettings {
	autoResize?: boolean; // default: true
	blockImages?: boolean; // default: false
}

// :85-90
export interface MarkdownSettings {
	codeBlockIndent?: string; // default: "  "
	mermaid?: MermaidRenderingMode; // default: "streaming"
}
```

**pi 的落盘形状（关键）**：`persistScopedSettings`（:705-735）在锁内读原始文件文本，
只把**被修改的顶层字段**、嵌套块只把**被修改的子键**合并进原对象后 `JSON.stringify`：

```ts
// :722-731（逐字节选）
const mergedNested = { ...baseNested };
for (const nestedKey of nestedModified) {
    mergedNested[nestedKey] = inMemoryNested[nestedKey];
}
(mergedSettings as Record<string, unknown>)[field] = mergedNested;
```

⇒ pi 的块里**只有被设置的键，缺席即 `undefined`（键不出现）**，永不产出 `null`/`0` 占位。
本包不动 Java 的「整对象写」架构（见 §5 裁决），但用类级 `@JsonInclude(NON_NULL)` 达到同一子键形状。

---

## 3. 改动点（逐字代码）

只动 **1 个主文件**：`pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/Settings.java`
（现 168 行，改后约 235 行，≤500）。

### 3.1 五个 record 的新形状

imports 增加：

```java
import com.fasterxml.jackson.annotation.JsonInclude;
```

```java
/**
 * Compaction settings (JSON boundary representation)。
 * 全组件可选（≙ pi {@code ?}，settings-manager.ts:28-34）；默认值在消费点按
 * pi 的 ?? 链兜底。类级 NON_NULL ⇒ 缺席组件不出现在 JSON 里（pi 只写已修改子键）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Compaction(
    // Json mapper 关了 getter 可见性，record 访问器会被忽略（实测得 {}）
    // ⇒ 与 Retry 同款，逐组件显式钉 JSON 属性。
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("reserveTokens") Integer reserveTokens,
    @JsonProperty("keepRecentTokens") Integer keepRecentTokens,
    @JsonProperty("modelOverrides") Map<String, ModelOverride> modelOverrides
) {
    /** pi {@code CompactionModelOverride}（settings-manager.ts:18-21）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelOverride(
        @JsonProperty("reserveTokens") Integer reserveTokens,
        @JsonProperty("keepRecentTokens") Integer keepRecentTokens
    ) {}
}

/** Terminal settings，对齐 pi {@code TerminalSettings}（settings-manager.ts:57-65）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Terminal(
    @JsonProperty("showImages") Boolean showImages,
    @JsonProperty("imageWidthCells") Integer imageWidthCells,
    @JsonProperty("clearOnShrink") Boolean clearOnShrink,
    @JsonProperty("showTerminalProgress") Boolean showTerminalProgress,
    // 形状＝ boolean | "auto"，本边界原样保类型（Boolean/String），消费点再判。
    @JsonProperty("hyperlinks") Object hyperlinks,
    // 形状＝ "kitty" | "iterm2" | "auto" | false。
    @JsonProperty("images") Object images,
    // 形状＝ boolean | "auto"。
    @JsonProperty("trueColor") Object trueColor
) {}

/** Image settings，对齐 pi {@code ImageSettings}（settings-manager.ts:67-71）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Image(
    @JsonProperty("autoResize") Boolean autoResize,
    @JsonProperty("blockImages") Boolean blockImages
) {}

/** Markdown settings，对齐 pi {@code MarkdownSettings}（settings-manager.ts:85-90）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Markdown(
    @JsonProperty("codeBlockIndent") String codeBlockIndent,
    @JsonProperty("mermaid") String mermaid
) {}

/**
 * TUI settings —— pi-java 自有扩展（pi 锚点无此块），消费者＝
 * {@code com.pijava.tui.util.ScrollConfig}。全组件可选；语义见 ScrollConfig javadoc。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tui(
    @JsonProperty("scrollMode") String scrollMode,
    @JsonProperty("scrollEventsPerTick") Integer scrollEventsPerTick,
    @JsonProperty("scrollWheelLines") Integer scrollWheelLines,
    @JsonProperty("scrollTrackpadLines") Integer scrollTrackpadLines,
    @JsonProperty("scrollTrackpadAccelEvents") Integer scrollTrackpadAccelEvents,
    @JsonProperty("scrollTrackpadAccelMax") Integer scrollTrackpadAccelMax,
    @JsonProperty("scrollInvert") Boolean scrollInvert,
    @JsonProperty("scrollWheelTickDetectMaxMs") Integer scrollWheelTickDetectMaxMs,
    @JsonProperty("scrollWheelLikeMaxDurationMs") Integer scrollWheelLikeMaxDurationMs,
    @JsonProperty("disableMouseCapture") Boolean disableMouseCapture
) {}
```

### 3.2 顺手校正

- 删 `Settings` 类首过时注释 *"Fields keep pi's snake_case JSON names"* —— 不实：pi 键是
  **camelCase**（`defaultProvider`/`reserveTokens`…）。改为说明 camelCase 直映、未知字段根级透传。
- primitive → 装箱后，检查全仓**无主源码**按这 5 块的子组件分支（已逐处 grep：
  compaction/terminal/images/markdown **零主消费者**；tui 仅 `ScrollConfig`，其现读
  `Integer`/`Boolean` 且自带 null/范围兜底）⇒ 行为面零改动。

### 3.3 为什么 merge 路径不用改

`SettingsManager.deepMerge`（:168-184）经 `ObjectNode` 递归：NON_NULL ⇒ 缺席子键不进树，
project 部分块只覆盖自己设置的子键，`convertValue` 回 record 时缺席组件得 `null`。
现有的 `isNull()` 跳过与 record 装箱语义正好拼成 pi 的「undefined 不覆盖」。

---

## 4. 夹具（先红，全部走真 `FileSettingsStorage`）

新增 `pi-java-coding-agent/src/test/java/com/pijava/coding/agent/core/FileSettingsRoundTripTest.java`
（@TempDir，`new FileSettingsStorage(dir, dir)`）：

| # | 用例 | 断言要点 | 现状 |
|---|---|---|---|
| T1 | `allFiveBlocksRoundTripThroughRealFile` | 填满 5 块（含 `modelOverrides` 一条、terminal 全 7 子键、markdown `"  "`/`"streaming"`、tui 全 10 键）→ writeGlobal → 重载，逐子键相等 | 红（全 `{}`） |
| T2 | `partialNestedBlocksMergeWithoutZeroClobber` | 原始写 global `compaction:{enabled:true,reserveTokens:16384}`、project `compaction:{keepRecentTokens:5000}` ⇒ effective 块 `(true,16384,5000)`；`images:{autoResize:false}` ⇒ `blockImages` 为 null | 红（0 值/丢失） |
| T3 | `piShapedFileSurvivesAnUnrelatedFlush` | 手搓 pi 代表文件（5 块全形状 ＋ 根级未知键 `futureField:42`）→ accessor 改 `theme` → flush → 逐键解析结果文件，原键原值全在 | 红（块全毁） |
| T4 | `markdownAcceptsPiTypes` | 文件含 `markdown:{"codeBlockIndent":"   ","mermaid":"final"}` → 正常加载、值相等 | 红（现抛异常） |
| T5 | `partialBlocksEmitNoNullPlaceholderKeys` | 只设 `images.blockImages=true` 的文件经一次 flush 后，块文本恰为 `{"blockImages":true}`，不含 `null`/`autoResize` | 红（无 NON_NULL 时带 null） |

存量护栏不动：`SettingsManagerTest`（含根级 unknown null 往返用例）、tui 模块 `ScrollInputNormalizerTest`。

**变异探针（落地后）**：

- M1：删 `Compaction.enabled` 的 `@JsonProperty` ⇒ T1 恰该子键断言红（证明注释有牙）。
- M2：删 `Image` 类级 `@JsonInclude(NON_NULL)` ⇒ T5 红。
- M3：把 `Markdown.mermaid` 退回 `boolean` ⇒ T4 红。

---

## 5. 裁决记录

| 议题 | 裁决 | 理由 |
|---|---|---|
| 是否改 mapper 全局 GETTER 可见性 | **不改** | 根 `Settings` 是可变类＋public 字段，全局配置是 Phase 3 既定形状；改全局牵面不可控 |
| 逐组件 `@JsonProperty` vs 其它 | **逐组件**（=Retry 现成药方） | 最小、可复制、变异可验 |
| primitive 还是装箱 | **装箱** | pi 子键全可选；primitive 制造「缺席＝零」的假值，且合并零覆盖 |
| `hyperlinks/images/trueColor` 类型 | **`Object`**（边界原样保 Boolean/String） | pi 联合形状；当前零消费者，强造 sealed 类型无收益 |
| 是否给嵌套 record 加 per-record unknown 透传 | **不加** | record 上 any-setter 需构造器 catch-all，5 个块各增状态、复杂度高；纪律是「pi 加键→锚点漂移检出→新缺口」，根级 unknown 已是项目边界 |
| 是否改成 pi 的「原文合并、只写修改子键」持久化 | **不改** | Java 走「整对象写」是 Phase 3 §12 架构，typed 字段＋根 unknown（含 null）已等价保数据；重构持久化是另一个包的规模 |
| `tui` 块去留 | **保留**（pi-java 扩展） | 有真实消费者 ScrollConfig；对 pi 是未知键，pi 侧无害 |

---

## 6. 范围外 ／ 新登记

**新登 `B222`：`Settings.compaction` 块零消费者 —— 自动压缩默认不跑。**

- `AgentSession.java:375` 构造 `HarnessConfig.builder()` 时**不挂** `.compactionSettings(...)`
  ⇒ `LaneState.compactionSettings`（`LaneState.java:235`）恒 null
  ⇒ `PostRunCompactionCheck.check()` 的 G0（`PostRunCompactionCheck.java:133-137`，`settings == null`）恒 false。
- pi 默认 `compaction?.enabled ?? true`（settings-manager.ts:914-916），主流即开。
- `/compact` 手动路径用硬编码 `CompactionSettings.defaults()`（`MiscCommands.java:133`），
  `modelOverrides` 在 B222 家族落地前是休眠字段。
- 这是**接线/功能**缺口，不属于「持久化往返」，本包只登记不做。

其余范围外：terminal 5 个新子键的**消费行为**（协议探测等）、pi 新增的 `warnings`/`codemode` 等块
（根级 unknown 照常透传，不丢）。

---

## 7. 验证 ／ 提交

- `mvn -pl pi-java-coding-agent -am test -Dtest=FileSettingsRoundTripTest
  -Dsurefire.failIfNoSpecifiedTests=false`（T1–T5 先红后绿）
- 收尾：`mvn -pl pi-java-coding-agent test`，再 `mvn checkstyle:check`；ai/agent-core 零改动。
- 单个 commit（tests＋fix 必须同 commit 才绿，约 300 行）：
  `fix(coding-agent): preserve nested settings blocks on write (B139)`
- 闭环回填：本稿 banner ＋ `docs/04`（删 B139、登 B222、计数）＋ `docs/41`（B139 状态与 §7）。

---

## 12. 闭环记录

（闭环时回填。）
