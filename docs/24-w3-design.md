# 24 - L7 W3 设计稿：渲染缓存契约（R31 块缓存、R32 注释隐藏、R33/C21/R30 窄差）（目标锚点 `a00a5048d`）

> 【背景摘要】`docs/21` §7 把 W3 定为「渲染缓存契约」：**R31 Markdown 顶层块缓存(2)、R32 HTML 注释隐藏(1)、R33 ASCII/字素快路径(1)＋`invalidate` 重置 `defaultStylePrefix` 子机制；C21 render 只读/缓存契约(1) 与 R30 O(viewport) 帧缓存(2) 按 docs/21 §6 出窄差 D**。本稿是 spec＋实施计划。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前工作区（W1/W2 已收口、未提交）。分层判定只写 `docs/map/05-tui-web.md`，本稿只做设计、实施与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | R31/R32 从新增单元（pi 有、java 无）翻「对齐」；R33（性能契约）、C21（TamboUI 组件协议）、R30（TamboUI 布局帧缓存）登记窄差 D |
| 行为不变量 | 文本级架构不动（LogicalLine/markup 投影，换行仍由 `TextLayout.wrap` 每帧按宽度派生）；`MarkdownRenderer.toLogicalLines` 静态纯函数面保留（测试/非视口消费点零改）；渲染输出与缓存前逐字节一致 |
| 完成判据 | ①`mvnw test -pl pi-java-tui -am` 全绿（JDK25）；②`map/05` 新增 R30–R33、C21 行并回填 §1/§1.1/§1.2；③`docs/05`、`docs/09`、`docs/04` §0 同步 |
| 不做项 | R30 帧缓存（TamboUI 内部不可达）；C21 组件协议改造（外部库）；R33 ASCII 快路径（无 `Intl.Segmenter` 等价性能诉求）；图片硬换行（R14 无图像协议，无观察面） |

## 2. 上游证据（均对 `a00a5048d`）

### 2.1 R31 顶层块缓存 `packages/tui/src/components/markdown.ts`

- `:299-307 RenderedBlock`：`width`／`nextTokenType`（决定块后间距）／`lines`／`highlightedCode`（按 `lang\0source` 缓存高亮，:641-645）。
- `:318-332` 三层缓存：`cachedText/cachedWidth/cachedLines`（整块早退）、`cachedTokens: WeakRef<{source,tokens}>`（词法树跨主题/宽度失效保留，弱引用防长转录内存膨胀）、`blocks: WeakRef<Map<string,RenderedBlock>>`（上一帧顶层块，按 `token.raw` 键）。
- `:368-372` 整块早退：`cachedLines && cachedText===text && cachedWidth===width` ⇒ 直接返回。
- `:393-400` 词法缓存：`normalizedText`（tab→3 空格）相同才复用 tokens；否则 `lexer`＋`trimPartialClosingFences`＋`removeHtmlCommentBlocks`。
- `:402-421` 逐块复用：`reuseBlocks = Object.keys(tokens.links).length === 0`（引用链接可跨块解析 ⇒ 不复用）；每块 `blocks.get(token.raw) ?? previousBlocks?.get(token.raw)`；`block.width !== width || block.nextTokenType !== nextTokenType` 才 `renderBlock`；本帧只保留用到的块（流式旧版本自然丢弃）。
- `:350-366 setText/invalidate`：`setText` 清行缓存（不丢 tokens/blocks）；`invalidate` 清行缓存＋`blocks=undefined`＋`defaultStylePrefix=undefined`（主题变化时每个样式行与默认样式前缀都变）。

### 2.2 R32 HTML 注释隐藏

- `:200-205 HTML_COMMENT_REGEX = /<!--[\s\S]*?(?:-->|$)/g`＋`stripHtmlComments`（未闭合注释吞到文本末，流式期不闪）。
- `:207-225 removeHtmlCommentBlocks`：块级 html token 去掉注释后空白 ⇒ 移除该 token，并把相邻 space token 合并掉一个（注释在段间不制造额外空行；在首尾也不留空行）。
- `:752-757` 块级 html 渲染：`stripHtmlComments(raw).trim()`（非注释 HTML 仍按纯文本显示）。
- `:783-791, :869-876` 行内 html：`<!--` 开头 ⇒ 丢弃注释；若前一 token 以空白结尾，则丢弃下一 text 的前导空白（`a <!-- c --> b` ⇒ `a b`）。
- `:457-460` 图片行：`isImageLine(line) ? line.split("\n") : [line]`（图片行跳过 wrap，硬换行各自成行）。

### 2.3 R33 快路径／早退

- `packages/tui/src/utils.ts:177-241`：`isStandaloneGraphemeUnit` 白名单（ASCII/Latin/标点/箭头/制表/假名/CJK/全角）＋`segmentGraphemes` 只对复杂段走 `Intl.Segmenter`＋`graphemesOf` 快路径。
- `utils.ts:243-262`：单码点宽度缓存。
- `components/text.ts:25-32`：`setText` 相同文本直接返回（不失效 wrapped lines）。

### 2.4 C21／R30（框架层，docs/21 §2/§6 已在案）

- C21：`tui.ts:130 readonly string[]`、`:369-393 renderChildLines`、`:470-472`；`box.ts:100,112`——组件 render 只读＋Container 逐子缓存。
- R30：`layout.ts:398 renderLayoutFrame(reusableOutput)`、`:84 renderCached`；`tui.ts:613,1163`。

## 3. Java 现状

| 面 | 现状 | 证据 |
|---|---|---|
| Markdown 管线 | 每帧全量：`ChatViewportElement.flatten` → `MessageBubble.lines` → `MarkdownRenderer.toLogicalLines` → `MarkdownBlocks.parse`（无任何缓存；长转录每帧重解析/重着色全部消息） | `ChatViewportElement.java:334-355`、`MarkdownRenderer.java:38-68` |
| 词法/块缓存 | 无 | — |
| HTML 注释 | 无处理：`<!-- … -->` 按普通段落文本原样显示 | `MarkdownBlocks.java`（无 `<!--` 命中） |
| 图片块 | `Block.Image` ⇒ 单行占位 `🖼 alt`（无换行、无图像协议） | `MarkdownRenderer.java:88-90` |
| R33 | `TextLayout` 按 code point 处理，无 `Intl.Segmenter`；功能等价 | `TextLayout.java:116-127`、`:235-272` |
| C21/R30 | TamboUI 外部库，无同协议/帧缓存可达面 | `docs/21` §6 |

## 4. 设计（Java 方案）

### 4.1 R31：`MarkdownRenderCache`

新增 `com.pijava.tui.component.MarkdownRenderCache`（渲染期单线程对象，由视口持有）：

```java
public final class MarkdownRenderCache {
    // 词法缓存：归一化源 → 块表（≙ pi cachedTokens；跨主题/尺寸保留）
    private final Map<String, List<MarkdownBlocks.Spaced>> parsed = new HashMap<>();
    // 顶层块缓存：块 record → 行（≙ pi blocks；主题/默认样式变化即失配）
    private final Map<MarkdownBlocks.Block, Entry> rendered = new HashMap<>();
    private record Entry(List<LogicalLine> lines, MarkdownTheme theme, Style style) {}

    public List<LogicalLine> render(String markdown, MarkdownTheme theme, Style defaultStyle);
    public void beginFrame();      // 清「本帧使用」标记
    public void endFrame();        // retain 仅本帧用到的源/块（≙ pi 每 render 重建 blocks）
    public void invalidate();      // 全清（≙ pi invalidate 清 blocks + defaultStylePrefix）
    public int renderedBlockCount();  // 观察点：实际块渲染次数
    public int cacheHitCount();       // 观察点：块命中次数
}
```

- **键选择**：块缓存键＝`MarkdownBlocks.Block` record（值相等）。pi 以 `token.raw` 为键是因为 marked token 可携引用链接定义；Java 行级 parser 无链接定义，同一块内容渲染必然相同 ⇒ 值键等价且更精确（`raw` 中的 tab/空白差异不会误命中）。段落 `Paragraph(text)` 等 record 天然覆盖流式未变块。
- **失效**：`Entry.theme/style` 与当前不等 ⇒ 重渲染并覆盖（`MarkdownTheme.current()` 每次新 record，record 值相等；`Color.hex` 为 `Color$Rgb` 带 equals）。主题切换/`codeBlockIndent` 变化/thinking 默认样式变化全部覆盖；等价于 pi `invalidate` 清 `blocks`＋`defaultStylePrefix`。
- **帧界**：`ChatViewportElement.flatten` 先 `beginFrame()`，`finally endFrame()`；`endFrame` 丢弃本帧未渲染的源与块（流式旧快照、已删除消息自然回收）。词法缓存跨帧保留（≙ pi WeakRef tokens 跨 invalidate 保留；Java 以帧界裁剪代替弱引用，长转录只保留在用源）。
- **块后间距**：仍由现有 `blankAfter`＋next 块型规则计算（不进块缓存，因为依赖相邻块；pi 同：间距由 `nextTokenType` 参与复用条件）。

接线：

- `MarkdownRenderer` 拆出 `static List<LogicalLine> emitBlock(Block, theme, defaultStyle)` 与
  `static List<LogicalLine> assemble(List<Spaced>, theme, defaultStyle, Function<Block,List<LogicalLine>>)`；
  `toLogicalLines` 保持「parse＋assemble(无缓存)」原行为。
- `MessageBubble.lines(msg, index, cardExpanded)` 增 `MarkdownRenderCache` 参数重载；assistant text／thinking／`ChatMessage.Markdown` 三臂走 `cache.render(...)`；旧重载（测试/scrollback）保持无缓存路径。
- 三张消息卡（compaction/branch/skill）内部 Markdown 仍走静态路径（展开低频；登记窄差 N，后续若需要再透传缓存）。

### 4.2 R32：HTML 注释隐藏（`MarkdownBlocks` 词法前置）

`MarkdownBlocks.parse` 开头对源做 `stripHtmlComments`：

- 行级围栏状态（复用 `FENCE_OPEN`/`isFenceClose`）：围栏内原样保留（代码里的 `<!-- -->` 不吞）。
- 行内代码 span 状态（反引号 run）：span 内的 `<!--` 原样保留（pi 的 codespan token 优先）。
- 围栏外注释：`<!--` → 找 `-->`；未闭合 ⇒ 吞到源末（pi `(?:-->|$)`）。
- 注释独占行（移除后该行仅剩空白）⇒ 该行清空为 `""` 而不是删除：相邻空行经 `pendingBlank` 合并，效果＝pi「移除注释块并合并相邻 space token」，段间不多空行、首尾不留空行。
- 行内注释：移除后若输出尾字符与后侧字符均为空白 ⇒ 吞后侧空白（pi `dropLeadingSpace`）；`a<!--c-->b` ⇒ `ab`。

图片硬换行：Java `Block.Image` 恒单行占位、无终端图像协议 ⇒ 该子机制无观察面，登记窄差 N（随 R14 图片协议批）。

### 4.3 R33／C21／R30：窄差 D

- **R33**：无 `Intl.Segmenter`、无 pi `Text` 组件；功能正确性已有（码点宽度）。R31 的源级解析缓存＋块复用已承担「同源不重渲染」的等价收益；ASCII 快路径与 `setText` 早退属纯性能契约 ⇒ **窄差 D**（不计分）。
- **C21**：TamboUI 是外部库，组件 `render` 协议与 Container 缓存不可改；Java 应用层以 R31 的投影缓存承担等价意图 ⇒ **窄差 D**。
- **R30**：TamboUI 布局内部帧缓存不可达；视口仍每帧 wrap ⇒ **窄差 D**。

## 5. 测试计划

| 域 | 新增/改动 |
|---|---|
| `MarkdownRenderCacheTest`（新） | 同源二次 render：`renderedBlockCount` 不增、`cacheHitCount` 增；流式追加 `a\n\nb` → `a\n\nb c`：首块命中、末块重渲染；主题变化全失配；默认样式变化失配；`endFrame` 清未用块；`invalidate` 清空；空/空白输入 |
| `MarkdownRendererTest` 扩 | 注释独占行（段间无额外空行、首尾无空行）；行内注释 `a <!-- c --> b`→`a b`、`a<!--c-->b`→`ab`；围栏内保留；行内代码保留；未闭合注释吞尾；`<!--` 与非注释 HTML 混排 |
| `ChatViewportElementTest` 扩 | 连续两帧渲染：块渲染计数不增（缓存生效）；draft 改变只新增末块渲染 |
| 回归 | 既有 `MarkdownRendererTest`／`MessageBubbleTest` 全绿；`MessageBubble.lines` 旧重载行为不变 |

定向：`$env:JAVA_HOME='D:\soft\jdk\jdk-25'; ./mvnw test -pl pi-java-tui -am`。

## 6. 实施步骤

1. `MarkdownBlocks.stripHtmlComments`（围栏/行内代码感知）＋注释用例。
2. `MarkdownRenderer` 拆 `emitBlock`/`assemble`（`toLogicalLines` 行为不变）。
3. 新增 `MarkdownRenderCache`＋单测。
4. `ChatViewportElement` 帧界与 `MessageBubble` 缓存重载接线＋视口两帧用例。
5. 定向测试全绿 → `map/05` 新增 R30–R33/C21 行与 §1/§1.1/§1.2 回算 → `docs/05`／`docs/09`／`docs/04` §0 回填。

## 7. 预期读数

- 新增单元：R30(2)／R31(2)／R32(1)／R33(1)／C21(1)。
- 计分：R31 2 ＋ R32 1 ＝ **+3**；R30/R33/C21 窄差 D 不进分子。
- 逐域：渲染特性 Σ39→45（30.5→33.5）；TUI 组件 Σ41→42（32.5 不变）。
- 合计：**244 → 251**，分子 **196.0 → 199.0** ⇒ **79.3%**（未加权 101/152＝66.4%）。
- 目标锚点投影：**202.5/259＝78.2%**（W2 后 199.5＋3）。

## 8. 收口结论（2026-11-23）

- 全部五步落地；`mvnw test -pl pi-java-tui -am` 全绿（752 测，0 失败）。新增 `MarkdownRenderCacheTest` 8 测；`MarkdownRendererTest` 增至 34 测（R32 七测）；`ChatViewportElementTest` 增至 21 测（R31 两帧/流式 draft 两测）。
- **读数**：`map/05` **199.0/251＝79.3%**（未加权 101/152；＋3.0）；目标锚点投影 **202.5/259＝78.2%**。R31/R32 对齐；R30/R33/C21 窄差 D。
- **实施与设计的偏差**（均已回写 map/05 行注）：
  1. 块缓存键用 `MarkdownBlocks.Block` record 值相等（pi 用 `token.raw`）——Java 行级 parser 无跨块引用链接定义，同内容块渲染必然相同；语义等价且避免空白差异误命中。
  2. `MarkdownRenderer.toLogicalLines` 在「源非空白但块表为空」（仅注释）时返回单个空行，对位 pi `render` 的 `return result.length > 0 ? result : [""]`。
  3. 三张消息卡内部 Markdown 仍走静态 `toLogicalLines`（缓存仅覆盖 assistant text/thinking/`ChatMessage.Markdown` 与 draft）；展开低频，登记窄差 N。
  4. 帧界裁剪代替 pi 的 `WeakRef`：`endFrame` 只保留本帧用到的源与块，长转录内存随在用内容回收。
- 下一分段：**W4 补全与选择器**（S16 门控补偿、K41、K25/K26/K31、extension-selector 窗口化、C2 多选复核）。
