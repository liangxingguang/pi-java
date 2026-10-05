# 包 15：`Entry` 判别值单一事实源设计（B167）

> **状态：✅ 已闭环（2026-10-05）—— 序列化、Java 侧、解码侧三处判别字面量收敛为常量，B167 销号。**
> 纯内部重构，**线上字节与行为零变化**。实施与证据见 §7。

## 1. 问题（B167 实测）

同一个判别串在代码里写了多遍：

| 拷贝 | 位置 | 谁读它 |
|---|---|---|
| **A：序列化** | `@JsonSubTypes(... name = "message")` | **真正落线**：`valueToTree(entry)` 产出的 `type` 键来自这里 |
| **B：Java 侧** | `type()` switch 返回字面量 | `SessionState`（过滤/停止界）、SQLite 的 `SqliteMutationReplay`/`SqliteSessionStorage`（`entry.type()` 比较） |
| **C：解码侧**（实施期新发现） | `EntryJsonCodec.decode` 的 switch case 与 `JsonlCodec.ENTRY_TYPES` 白名单 | 读取 pi/legacy 行时按字面量分派 |

漂移时（改字面量只改一处）**文件与内存各说各话**。发现史：D6 的 M4 探针改 `type()` **零红**（打错对象，落线看的是 A），改 `@JsonSubTypes` 才红。今天各份同值。

## 2. 方案：判别常量作唯一事实源

在 `Entry` 接口体顶部声明 9 个判别常量，A、B 两处都引用它 ⇒ 字面量全文只出现一次：

```java
public sealed interface Entry {

    // wire 判别字面量 —— 唯一事实源：@JsonSubTypes.name 与 type() 都从这里取。
    String TYPE_MESSAGE = "message";
    String TYPE_MODEL_CHANGE = "model_change";
    String TYPE_THINKING_LEVEL_CHANGE = "thinking_level_change";
    String TYPE_COMPACTION = "compaction";
    String TYPE_BRANCH_SUMMARY = "branch_summary";
    String TYPE_CUSTOM = "custom";
    String TYPE_CUSTOM_MESSAGE = "custom_message";
    String TYPE_USAGE = "usage";
    String TYPE_CONTEXT_EDIT = "context_edit";
    …
```

**A（注解）** —— 注解元素引用同类的编译期常量（constant variable，合法）：

```java
@JsonSubTypes({
    @JsonSubTypes.Type(value = Entry.Message.class,        name = Entry.TYPE_MESSAGE),
    @JsonSubTypes.Type(value = Entry.ModelChange.class,   name = Entry.TYPE_MODEL_CHANGE),
    @JsonSubTypes.Type(value = Entry.ThinkingLevelChange.class,
        name = Entry.TYPE_THINKING_LEVEL_CHANGE),
    @JsonSubTypes.Type(value = Entry.Compaction.class,    name = Entry.TYPE_COMPACTION),
    @JsonSubTypes.Type(value = Entry.BranchSummary.class, name = Entry.TYPE_BRANCH_SUMMARY),
    @JsonSubTypes.Type(value = Entry.Custom.class,        name = Entry.TYPE_CUSTOM),
    @JsonSubTypes.Type(value = Entry.CustomMessage.class, name = Entry.TYPE_CUSTOM_MESSAGE),
    @JsonSubTypes.Type(value = Entry.Usage.class,         name = Entry.TYPE_USAGE),
    @JsonSubTypes.Type(value = Entry.ContextEdit.class,   name = Entry.TYPE_CONTEXT_EDIT)
})
```

**B（type()）**：

```java
default String type() {
    return switch (this) {
        case Message m -> TYPE_MESSAGE;
        case ModelChange mc -> TYPE_MODEL_CHANGE;
        case ThinkingLevelChange tlc -> TYPE_THINKING_LEVEL_CHANGE;
        case Compaction c -> TYPE_COMPACTION;
        case BranchSummary bs -> TYPE_BRANCH_SUMMARY;
        case Custom c -> TYPE_CUSTOM;
        case CustomMessage cm -> TYPE_CUSTOM_MESSAGE;
        case Usage u -> TYPE_USAGE;
        case ContextEdit c -> TYPE_CONTEXT_EDIT;
    };
}
```

效果：将来要改某个 wire 名，**只改常量一处**，A/B 同时跟随 ⇒ 漂移在结构上不可能。
新增变体仍需两处都加（switch 不加＝编译红；注解不加＝该型不被识别），但「同值写两遍」这个 B167 钉的问题被消除。

## 3. 为什么不用更激进的方案

| 备选 | 不选理由 |
|---|---|
| 删 `type()`，各处改读 Jackson 树 | 内存路径（`SessionState`）根本没有 JSON 树，为读个判别值序列化一次，荒唐 |
| 序列化改由 `type()` 驱动（自定义 Serializer，去掉 `@JsonSubTypes`） | 反序列化的多态子类型注册仍需一份清单；徒增自定义多态处理、风险大，而 wire 字节必须不变 |
| 每个 record 多存一个 `type` 分量 | 改变全部 9 个 record 形状与线格式，违背「字节零变化」 |

常量方案是命中问题的最小改动。

## 4. 测试与证据

纯重构，**不存在也不应有 RED**（行为不变）。证据两层：

1. **新增一致性护栏测试** `EntryDiscriminantSourceTest`：对 9 个变体各构造一个实例，
   断言 **Jackson `valueToTree(entry)` 产出的 `type` 串 ＝ `entry.type()`**。
   重构前两份同值 ⇒ 该测试**落地即绿**，作用是把「两份漂移」从此变成**测试可捕获的红**
   （这正是 B167 缺的守卫）。

2. **变异探针 M1（钉单一源）**：把 `TYPE_MESSAGE` 临时改成 `"message_x"` 一处，
   预期**序列化与 `type()` 两侧同时变化** —— 一致性测试及若干 `type()` 比较用例一起红，
   证明一个常量同时承重两边（若只红一边，说明还有隐藏副本、修复不彻底）。探针后还原。

3. 全 reactor `mvn -o clean verify` **14/14**（checkstyle/spotbugs 含），确认字节/行为零变化。

## 5. 实施与提交

单步即可（改动集中在一个文件 ＋ 一个新测试）：

`refactor(agent-core): make entry discriminants a single source`

显式路径 `git add`；commit 末尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
闭环回填：本包 banner、`docs/05` 的 B167 **销号**。

## 6. 明确不做

- 不改任何 record 形状、不改 wire 字节、不改 `committed()`；
- 不处理 B168/B169/B170（各自独立）；
- 不重排 `@JsonSubTypes` 顺序（保持 wire 键序稳定）。

## 7. 闭环记录（2026-10-05）

### 7.1 实施期裁决

- **范围扩大（设计稿只覆盖 A/B）**：实施时实测发现解码侧 `EntryJsonCodec` switch 与
  `JsonlCodec.ENTRY_TYPES` 还有第三批字面量副本 ⇒ 一并接入常量。String switch 的 case
  标签引用编译期常量（`case Entry.TYPE_MESSAGE ->`）合法；`ENTRY_TYPES` 白名单改引用常量。
- `ENTRY_TYPES` 里的 `"active_tools_change"` **无对应 Entry 变体**（已删型、仅 legacy 接收）
  ⇒ 保留字面量并加注释，不为此新造常量。
- `StepKind`/`UsageCause` 里的 `"branch_summary"` 是 **record 族独立词表**，不属 Entry，不动。

### 7.2 证据

- 一致性护栏 `EntryDiscriminantSourceTest`：10/10（9 变体参数化 ＋ 计数），落地即绿；
- **M1 探针**：`TYPE_MESSAGE` 改 `"message_x"` 单一处 ⇒ 序列化字节变 `message_x`
  （`EntryTest.jsonSerializationUsesPiKeyNames` 红：expected "message" but was "message_x"）
  且 `type()` 同步变 —— 一个常量同时改变落线与 Java/解码，**单一事实源钉死**。
  护栏测试在常量驱动下两边仍相等（恒绿，预期：它守的是「直接改某一份字面量副本」的漂移）；
- 全 agent-core `clean test` BUILD SUCCESS。

### 7.3 提交

```
<待最终提交后回填哈希>
```

### 7.4 台账

- **B167 销号**：序列化、Java 侧、解码侧三处判别字面量均收敛为常量；wire 字节零变化。
- 仍在册：B168/B169/B170。
