# docs/36 — tui Markdown 激活批设计稿：MarkdownRenderer 接线（R1/R2/R3/R4/R5/R9＋B65＋E2-43）

> 【背景摘要】本批销 B65：java 侧 `MarkdownRenderer`（221 行）与代码块高亮路径是**死代码**
> （生产零调用，全仓 grep 只命中自身与测试），助手消息实际走 `MessageBubble` 的纯文本转义
> 路径。拍板已定「接线不删声明」。本批把助手/thinking 两臂接到 Markdown 渲染，顺带收编
> map/05 的 R1（核心渲染，权重 3，全图单条最大缺失）、R2（管道表格）、R3（严格删除线）、
> R4（链接）、R5（流式围栏修剪）、R9（代码块高亮），以及 map/04 E2-43 的
> `markdown.codeBlockIndent` 消费点。
>
> **核心障碍（地基）**：聊天管线是宽度无感的 `LogicalLine`（markup 串）→ 渲染期
> `TextLayout.wrap` 换行 → 逐行 `MarkupParser.parse`。现有 `displayWidth`/换行/截断
> **不认 markup 标签**（存量文本全转义，问题从未暴露）；且 `RenderRow` 逐行独立 parse，
> 行内样式标签跨换行必须「本行闭合、下行重开」。不改造 wrap 地基，激活后换行全错。
>
> pi 取证锚点 ea448f454（已核工作树与锚点一致）；java 现状取证 2026-10-27 HEAD。

---

## 1. 命题表（待逐条取证 → 实施）

| # | 命题 | pi 证据 | java 现状 |
|---|---|---|---|
| P1 | 助手 text 块走 `Markdown` 组件（trim、paddingY=0、transform 钩） | `assistant-message.ts:110-117` | `MessageBubble.java:80-88`：`TextLayout.split(escapeMarkup(text))` 纯文本＋`• ` 前缀 |
| P2 | thinking 块走 `Markdown`（`defaultTextStyle`＝thinkingText 色＋italic） | `assistant-message.ts:146-162` | `MessageBubble.java:93-94`：dim 纯文本 |
| P3 | Markdown 主题十件（`mdHeading/mdLink/mdLinkUrl/mdCode/mdCodeBlock/mdCodeBlockBorder/mdQuote/mdQuoteBorder/mdHr/mdListBullet`＋bold/italic/underline/strikethrough 装饰） | `theme.ts:1022-1060`（§2.4 逐字） | `ThemeToken.MD_*` 十键已声明（ThemeToken.java:194-203），**零消费**；`TuiThemeBridge.fg(token)` 桥已在（SemanticColors 先例） |
| P4 | 行内：strong/em/codespan/del/link/escape/br（markdown.ts:683-729，§2.2 逐字） | 同左 | 无行内概念；`Block.Paragraph` 走 `markupText` 但无人调 |
| P5 | 块：heading（L1 加下划线、L≥3 显示 `### ` 前缀）/paragraph/code（围栏行可见）/list（有序编号、任务 `[x]`、嵌套 4 空格）/table（全边框宽度感知）/blockquote（`│ ` 前缀）/hr（`min(width,80)`）＋**块后间距规则**（next≠space 补空行） | markdown.ts:473-620、766-816、852-1024（§2.2 摘） | `parseBlocks` 七种块（标题 1-3/段落/围栏/平铺无序列表/单行引用/图片/表格）＋Element 渲染面**零调用** |
| P6 | 严格删除线 tokenizer（`~~` 两侧紧邻非空白才认） | markdown.ts:7-24（§2.1 逐字） | 无 |
| P7 | 流式局部闭合围栏修剪（`` ` ``/`` `` `` 尾行不吃进代码体，issue #5825） | markdown.ts:146-169（§2.3 逐字） | `parseBlocks:134-145` 把局部围栏当代码内容 |
| P8 | 代码高亮门：`supportsLanguage(lang)` 才高亮，否则整段 `mdCodeBlock` 色（自动侦测不可靠会乱上色） | theme.ts:1037-1056（§2.4 逐字） | `SyntaxHighlighter`（3 色＋40 关键字，SyntaxHighlighter.java:16-110）唯一消费点是输入编辑器当前行（EditorElement.java:123）；`MarkdownRenderer.highlightLine:98` 无人调 |
| P9 | `markdown.codeBlockIndent` 设置（默认 `"  "`）消费于代码块缩进 | settings-defaults.ts:49-52；settings-manager.ts:1404；interactive-mode.ts:1381 | `Settings.Markdown` record 已在（Settings.java:260-263），**无 accessor、无消费**（E2-43 存疑挂起项） |
| P10 | 换行/量宽 ANSI 感知（`wrapTextWithAnsi`/`visibleWidth`），跨行 SGR 重开 | utils.ts（pi-tui） | `TextLayout.wrap:81` 词级换行量原始 markup 串（标签计入宽度）；`hardBreak:172`/`hardTruncate:195` 可切断标签；`RenderRow` 逐行 parse（ChatViewportElement.java:283、ScrollbackTranscript.java:105） |
| P11 | 链接：`getCapabilities().hyperlinks` ⇒ OSC 8 只显文本；否则 fallback `text (href)`（mailto 剥前缀比对） | markdown.ts:699-718（§2.2 逐字） | 无链接分支；TamboUI MarkupParser **支持 `[link=URL]…[/]`**（javap 实证：tag=="link" 且带参 ⇒ `Style.hyperlink`），后端是否吐 OSC 8 待实测 |
| P12 | `/hotkeys` 面板经 Markdown 渲染 | interactive-mode.ts:6921 | `MiscCommands.java:34,122` 纯文本（S24 判「对齐」注记「pi 走 Markdown 组件」） |

---

## 2. pi 规格逐字（关键段）

### 2.1 严格删除线（markdown.ts:7-24 逐字）

```ts
const STRICT_STRIKETHROUGH_REGEX = /^(~~)(?=[^\s~])((?:\\.|[^\\])*?(?:\\.|[^\s~\\]))\1(?=[^~]|$)/;

class StrictStrikethroughTokenizer extends Tokenizer {
	override del(src: string): Tokens.Del | undefined {
		const match = STRICT_STRIKETHROUGH_REGEX.exec(src);
		if (!match) {
			return undefined;
		}

		const text = match[2];
		return {
			type: "del",
			raw: match[0],
			text,
			tokens: this.lexer.inlineTokens(text),
		};
	}
}
```

### 2.2 行内与块渲染要点（markdown.ts 逐字摘）

链接臂（:699-718 逐字）：

```ts
case "link": {
	const linkText = this.renderInlineTokens(token.tokens || [], resolvedStyleContext);
	const styledLink = this.theme.link(this.theme.underline(linkText));
	if (getCapabilities().hyperlinks) {
		// OSC 8: render as a clickable hyperlink. The URL is not printed inline,
		// so we always show only the link text regardless of whether it matches href.
		result += hyperlink(styledLink, token.href) + stylePrefix;
	} else {
		// Fallback: print URL in parentheses when text differs from href.
		// Compare raw token.text (not styled) against href for the equality check.
		// For mailto: links strip the prefix (autolinked emails use text="foo@bar.com"
		// but href="mailto:foo@bar.com").
		const hrefForComparison = token.href.startsWith("mailto:") ? token.href.slice(7) : token.href;
		if (token.text === token.href || token.text === hrefForComparison) {
			result += styledLink + stylePrefix;
		} else {
			result += styledLink + this.theme.linkUrl(` (${token.href)}`) + stylePrefix;
		}
	}
	break;
}
```

标题臂（:473-498 要点）：L1＝`heading(bold(underline(text)))`，其余＝`heading(bold(text))`；
L≥3 前缀 `"\#".repeat(level) + " "` 也上色；next≠space 补一空行。

代码臂（:530-549 要点，逐字骨架）：

```ts
case "code": {
	const indent = this.theme.codeBlockIndent ?? "  ";
	lines.push(this.theme.codeBlockBorder(`\`\`\`${token.lang || ""}`));
	if (this.theme.highlightCode) {
		const highlightedLines = this.theme.highlightCode(token.text, token.lang);
		for (const hlLine of highlightedLines) {
			lines.push(`${indent}${hlLine}`);
		}
	} else {
		const codeLines = token.text.split("\n");
		for (const codeLine of codeLines) {
			lines.push(`${indent}${this.theme.codeBlock(codeLine)}`);
		}
	}
	lines.push(this.theme.codeBlockBorder("```"));
	if (nextTokenType && nextTokenType !== "space") {
		lines.push(""); // Add spacing after code blocks (unless space token follows)
	}
	break;
}
```

列表臂（:766-816 要点）：无序规整为 `- `；有序 `${start+i}. `（`preserveOrderedListMarkers`
时保留源标记，助手臂不设 ⇒ 规整）；任务 `[x] `/`[ ] ` 缀在标记后；marker 整段
`listBullet` 色；续行缩进＝标记显示宽；嵌套深度 × 4 空格；`loose` 且非末项补空行。

引用臂（:566-612 要点）：内容按块递归渲染，`quote(italic(…))` 着色，行首
`quoteBorder("│ ")`；尾随空 quote 行弹出；next≠space 补空行。

hr 臂（:615-620 逐字要点）：`this.theme.hr("─".repeat(Math.min(width, 80)))`。

行内其余（:683-729 要点）：strong→`bold`、em→`italic`、codespan→`code` 色、del→
`strikethrough`、escape→原文（不设 preserveBackslashEscapes 时）、br→`\n`、html→原文；
每个自闭样式后接 `stylePrefix` 复位，串尾多余 prefix 剥掉。

### 2.3 流式局部围栏修剪（markdown.ts:146-169 逐字）

```ts
function trimPartialClosingFences(tokens: readonly Token[]): void {
	const token = tokens[tokens.length - 1];
	if (token?.type === "list") {
		trimPartialClosingFences(token.items[token.items.length - 1]?.tokens ?? []);
		return;
	}
	if (token?.type === "blockquote") {
		trimPartialClosingFences(token.tokens ?? []);
		return;
	}
	if (token?.type !== "code") {
		return;
	}

	// Trim streamed partial closing fences so code blocks do not shrink/flicker
	// when the final fence character arrives. See https://github.com/earendil-works/pi/issues/5825.
	const marker = /^(`{3,}|~{3,})/.exec(token.raw)?.[1];
	const lastLine = token.raw.split("\n").pop();
	if (!marker || !lastLine || lastLine.length >= marker.length || lastLine !== marker[0]?.repeat(lastLine.length)) {
		return;
	}

	token.text = token.text.slice(0, -lastLine.length).replace(/\n$/, "");
}
```

### 2.4 主题与高亮门（theme.ts:1022-1056 逐字摘）

```ts
export function getMarkdownTheme(): MarkdownTheme {
	return {
		heading: (text: string) => theme.fg("mdHeading", text),
		link: (text: string) => theme.fg("mdLink", text),
		linkUrl: (text: string) => theme.fg("mdLinkUrl", text),
		code: (text: string) => theme.fg("mdCode", text),
		codeBlock: (text: string) => theme.fg("mdCodeBlock", text),
		codeBlockBorder: (text: string) => theme.fg("mdCodeBlockBorder", text),
		quote: (text: string) => theme.fg("mdQuote", text),
		quoteBorder: (text: string) => theme.fg("mdQuoteBorder", text),
		hr: (text: string) => theme.fg("mdHr", text),
		listBullet: (text: string) => theme.fg("mdListBullet", text),
		bold: (text: string) => theme.bold(text),
		italic: (text: string) => theme.italic(text),
		underline: (text: string) => theme.underline(text),
		strikethrough: (text: string) => theme.strikethrough(text),
		highlightCode: (code: string, lang?: string): string[] => {
			// Validate language before highlighting to avoid stderr spam from cli-highlight
			const validLang = lang && supportsLanguage(lang) ? lang : undefined;
			// Skip highlighting when no valid language is specified. cli-highlight's
			// auto-detection is unreliable and can misidentify prose as AppleScript,
			// LiveCodeServer, etc., coloring random English words as keywords.
			if (!validLang) {
				return code.split("\n").map((line) => theme.fg("mdCodeBlock", line));
			}
			…highlight(code, opts).split("\n");
		},
	};
}
```

### 2.5 接线点（assistant-message.ts:110-117 逐字）

```ts
if (content.type === "text" && content.text.trim()) {
	// Assistant text messages with no background - trim the text
	// Set paddingY=0 to avoid extra spacing before tool executions
	this.contentContainer.addChild(
		new Markdown(content.text.trim(), this.outputPad, 0, this.markdownTheme, undefined, {
			transform: createMarkdownTransform("assistant", this.isStreaming, this.markdownTransformers),
		}),
	);
}
```

thinking 臂（:146-162）同构：`defaultTextStyle = { color: thinkingText, italic: true }`。

### 2.6 设置（settings-defaults.ts:49-52 逐字）

```ts
markdown: {
	codeBlockIndent: "  ",
	mermaid: "streaming",
},
```

accessor（settings-manager.ts:1404）：`getCodeBlockIndent()`＝
`settings.markdown?.codeBlockIndent ?? SETTINGS_DEFAULTS.markdown.codeBlockIndent`；
消费点 interactive-mode.ts:1381（注入 MarkdownTheme.codeBlockIndent）。

---

## 3. 裁决点（拍板清单）

| # | 裁决 | 建议 |
|---|---|---|
| ① | **wrap 地基走字符串级 tag 感知**：新增 `MarkupTokens` 扫描器（token＝开标签/闭标签/文本段；`[[`/`]]`/`\\` 按转义字面量计 1 列）；`displayWidth`/词级换行/`hardBreak`/`hardTruncate` 全部改走 token 流——量宽只计文本段，切词不切标签，**跨行时本行尾补 `[/]`×开栈深、下行首按原序重开**。`RenderRow(String,Style)` 签名不动，ScrollbackTranscript/toLine 零改动。备选（span 化 RenderRow）爆炸半径大，弃 | 做（地基，T1/T2） |
| ② | **样式分配原则**：块级整行样式（标题色/代码边框色/引用色+斜体/hr 色/thinking 色+斜体）走 `LogicalLine.style`（整行 patch，换行各行自动延续）；行内样式（bold/italic/del/codespan/link/bullet/高亮分段）走 markup 标签，fg 一律序列化 `[#RRGGBB]`（`toHex` 先例在 MarkdownRenderer.java:118；`[#…]`/`link` 标签经 javap 实证 TamboUI 支持） | 定调 |
| ③ | **代码块内容行 preformatted 硬截断**（保缩进；pi 是 ANSI 字级换行，java 管线词级换行会丢前导空白）。围栏行 ` ```lang `/` ``` ` 可见并上 `mdCodeBlockBorder` 色（pi 同形，替代旧 Element 面的圆角 panel） | 做，记窄差 |
| ④ | **链接两臂实证后择一**：verify 阶段实测 TamboUI 后端是否吐 OSC 8——吐 ⇒ pi OSC 8 臂（`[link=URL]` 标签＋underline＋mdLink，R4 翻**对齐**）；不吐 ⇒ fallback 臂 `text (href)`（mailto 剥前缀比对逐字，R4 翻**存疑**＋OSC 8 窄差） | 实证定臂 |
| ⑤ | **范围**：assistant TextContent＋ThinkingContent 两臂＋`/help` `/hotkeys` 文本（S24 增强，判定不翻）。**user 消息不做**（pi user-message.ts:42-58 的 `userMessageBg` 满宽底色管线表达不了；custom/branch/compaction/skill 卡不同批） | 收窄 |
| ⑥ | **E2-43**：`SettingsAccessors` 补 `getMarkdownCodeBlockIndent()`（回落 `"  "` 逐字）＋消费于代码块缩进；`mermaid` 键 record 已在，accessor 补上但**不消费**（R7 未落）；E2-43 维持**存疑**、注记更新（mermaid 半键仍挂 R7） | 做半键 |
| ⑦ | **删 Element 渲染面**：`render()`/`renderBlock()`/Element 版 `renderTable()` 全删（接线后仍零调用的面）；类改为 `parseBlocks`（增强）＋`toLogicalLines(String)` 单一出口；`MarkdownRendererTest` 改写。B65 按「接线」销号 | 做 |
| ⑧ | **量宽改正＝行为变化注记**：`[[`/`]]`/`\\` 旧按 2 列量宽（偏宽），新按 1 列；存量含方括号/反斜杠文本换行点变准。非回归，是修真，docs/04 §4 注记 | 注记 |
| ⑨ | **R5 落点**：`parseBlocks` 未闭合围栏的尾行若是开栏符的同字符真前缀（`` ` ``/`` `` ``/`` ~ ``…），修剪（语义逐字对位 §2.3；java 行级 parser 每次全量重解析，静态文本尾行同样适用） | 做 |
| ⑩ | **mermaid/图片/LaTeX/transformer 不做**：mermaid 围栏按普通代码块渲染（pi 无 transformer 时同形；D57 注记更新），图片行维持占位（R14/R15 不在批），R6 transformer 钩属扩展系统 | 不做 |

---

## 4. 实施任务（T1→T8，依赖序）

- **T1 `MarkupTokens` 扫描器**（tui/util 新件）：markup 串 → token 流（OPEN(name)/CLOSE/TEXT
  段）；转义三件套（`[[`/`]]`/`\\`）归并为字面量 TEXT；可见宽量法（宽字符 2 列规则复用
  `TextLayout.isWide`）。
- **T2 wrap 地基改造**（TextLayout）：`displayWidth` 改 markup 感知；`wrapLogicalLine`
  按 TEXT 段切词（空格不入标签，标签无空格——我方发射面保证单属性单标签）；词内
  `hardBreak` 只切 TEXT 不断标签；每行发射时闭栈/重开；`hardTruncate` 同理（省略号前闭栈）。
  存量纯转义文本路径行为不变（除裁决⑧的宽度修真）。
- **T3 `MarkdownRenderer` 重构**：删 Element 面（裁决⑦）；`parseBlocks` 增强——有序列表
  `1.`/`1)`、任务项 `[ ]`/`[x]`、嵌套列表（缩进深度）、hr（`---`/`***`/`___`）、标题 4-6 级、
  多行连续引用、`~~~` 围栏、R5 尾修剪（裁决⑨）；`Block` ADT 相应扩展。
- **T4 行内渲染 `InlineMarkup`**：escape 先行的分段扫描——`**`/`__` 粗、`*`/`_` 斜、
  `~~` 严格删除线（§2.1 正则语义）、`` ` `` codespan（mdCode 色）、`[text](href)` 链接
  （裁决④臂）、反斜杠转义还原；嵌套强调/autolink/html 不做（窄差）。
- **T5 块排放 `toLogicalLines`**：标题（L1 underline、L≥3 `#` 前缀、style＝mdHeading+bold）、
  段落（行内 markup）、代码（围栏行＋`codeBlockIndent` 缩进＋高亮门：lang 非空 ⇒
  SyntaxHighlighter 分段 markup，否则整行 mdCodeBlock 色；preformatted）、列表（marker 上
  mdListBullet 色，`LogicalLine` 双 indent 做悬垂）、表格（pi 全边框 `┌─┬─┐/├─┼─┤/└─┴─┘`
  自然宽 preformatted，表头 bold；markup 感知量宽算列宽）、引用（`│ ` 前缀 mdQuoteBorder 色
  ＋style mdQuote+italic）、hr（固定 80 `─`，mdHr 色）＋块后间距规则（next≠space 补空行）。
- **T6 主题接线**：`MarkdownTheme` java 件（TuiThemeBridge.fg(MD_*) 十键）；SyntaxHighlighter
  三色改主题令牌（SYNTAX_KEYWORD/SYNTAX_STRING/SYNTAX_NUMBER＋注释 SYNTAX_COMMENT），
  EditorElement 消费点同色（顺带修真硬编码 VSCode 色板）。
- **T7 MessageBubble 接线**：TextContent 臂 → `toLogicalLines(trim)`＋既有 `• ` 前缀/
  MESSAGE_INDENT 不变；ThinkingContent 臂 → `toLogicalLines`＋style fg(thinkingText)+italic；
  `SettingsAccessors.getMarkdownCodeBlockIndent()`＋消费缝（MessageBubble 无 settings 依赖 ⇒
  缩进串经 ChatViewportElement/装配层注入，缝位实施时定）。
- **T8 `/help` `/hotkeys`**：两臂文本走 `toLogicalLines`（MiscCommands.java:34,122）。

## 5. 窄差预案（登记不认账）

- D-new1：行级块 parser vs marked 全 CommonMark（setext 标题、围栏 info 串属性、松散列表空行、
  嵌套引用内块递归只做一层）[内部·边缘]
- D-new2：代码块内容行硬截断不wrap（pi 字级换行）[用户可见·边缘（窄终端长行）]
- D-new3：表格自然宽不收缩不wrap（pi 宽度感知 shrink/wrap＋超窄回落原文）[用户可见·边缘]
- D-new4：高亮门＝lang 非空（pi 是 supportsLanguage 白名单）；高亮器＝关键字/字符串/数字/注释
  子集（pi 是 highlight.js 全词法）[用户可见·着色粒度]
- D-new5：链接 OSC 8 视裁决④实证结果登记（不吐 ⇒ 恒 fallback 形状）[用户可见·交互]
- D-new6：hr 固定 80 列（pi `min(width,80)`；管线宽度无感）[用户可见·边缘]
- D-new7：行内子集（无嵌套强调/autolink/行内 html）[内部·边缘]
- D57 注记更新（mermaid 围栏渲染形状由「占位 panel」改「普通代码块」；R7 本体仍缺）
- 行为变化注记（非窄差）：裁决⑧量宽修真；助手文本可见形状整体变化（纯文本 ⇒ 渲染后）

## 6. 测试计划

- MarkupTokens：转义三件套、嵌套开闭、未闭合容错、宽字符、空串。
- TextLayout golden：含标签行换行点（标签不占宽）、跨行闭/重开、hardBreak 不断标签、
  preformatted 截断闭栈、存量转义文本回归（裁决⑧预期差）。
- parseBlocks：各块型＋有序/任务/嵌套列表＋hr 三形＋`~~~` 围栏＋R5 修剪三臂
  （`` ` ``/`` `` ``/完整闭合不动）。
- InlineMarkup：粗/斜/删/码/链五件＋转义还原＋严格删除线负例（`~~ a ~~` 不认）。
- toLogicalLines golden：块后间距规则、悬垂缩进、表格边框形状、引用前缀。
- MessageBubble 集成：助手消息（段落＋代码＋表格＋链接）端到端 golden；thinking 臂样式。
- 设置：accessor 回落/显式值两臂；缩进串注入生效。
- 裁决④实证：OSC 8 后端探测用例（结果决定 R4 判定）。

## 7. 账面预算（map/05 逐行实算）

| 行 | 判定翻动 | Δ |
|---|---|---:|
| R1 Markdown 渲染（w3） | 缺失→对齐 | +3.0 |
| R2 管道表格（w1） | 缺失→对齐 | +1.0 |
| R3 严格删除线（w1） | 缺失→对齐 | +1.0 |
| R5 流式围栏修剪（w2） | 缺失→对齐 | +2.0 |
| R9 代码块高亮（w2） | 缺失→对齐 | +2.0 |
| R4 链接（w1） | 缺失→对齐（OSC8 实证通过）或→存疑（fallback） | +1.0 或 +0.5 |
| **map/05 合计** | 122.5 → **132.0 或 131.5 / 244 ≈ 54.1%/53.9%** | **+9.5/+10.0** |
| 未加权 | 46 → **51 或 52 / 147 ≈ 34.7%/35.4%** | +5/+6 |
| 全仓（+map/05 Δ） | 1,122.0 → **≈ 1,131.5/1379 ≈ 82.0%** | +9.5 |

- 权重 3 子集：R1 是单条最大缺失，销掉后该子集 67.3% → **71.2%**（map/05:579 口径）。
- map/04：E2-43 维持存疑（0.5 不变），注记更新（codeBlockIndent 消费落地，mermaid 挂 R7）。
- 销号：B65。S24 判定不动（注记「pi 走 Markdown」落差消除）。

## 8. verify 计划

定向 `mvn test -pl pi-java-tui -am`（旧 jar 假绿坑 ⇒ 必带 -am）→ 全量 `mvn clean verify`
（spotbugs 在 verify 阶段）→ 日志落盘看退出码（管道接 head 假绿坑）。裁决④ OSC 8 实证
先行（决定 R4 臂与判定）。

---

## 9. 闭环记录（2026-10-27）

- **实施**：T1→T8 全落。三 commit：地基（`MarkupTokens`＋`TextLayout` 原子流）→ 本体
  （`MarkdownBlocks`/`InlineMarkup`/`MarkdownTheme`＋`MarkdownRenderer` 重写＋toLine 共享口）
  → 接线（`MessageBubble` 两臂＋settings accessor＋/help /hotkeys）。
- **裁决④ 实证结果：OSC 8 通过**——`MarkupParser` 携 `Style.hyperlink`、
  `AnsiCellWriter`（`AbstractBackend` 渲染路）吐 `]8;;url` 序列，双用例钉死
  （`InlineMarkupTest.markupParserProducesHyperlinkStyle`／`backendCellWriterEmitsOsc8Sequences`）
  ⇒ R4 走 pi OSC 8 臂、翻**对齐**（设计稿 +1.0 臂成立）。
- **实施细化（超设计稿两处，均收窄不改向）**：① `displayWidth` 公共口保持纯文本口径、
  新增 `displayWidthMarkup`（存量纯文本量宽调用点——SessionText/TreeHelpLine 等——不回摆）；
  ② T8 不携 marker——TUI 按原始输入行首 token 路由 /help /hotkeys（RPC/headless 面无泄漏），
  命令输出本身在 coding-agent 侧改产 markdown 源。
- **窄差落号**：D-new1→**D143**、D-new2→**D144**、D-new3→**D145**、D-new4→**D146**、
  D-new7→**D147**、D-new6→**D148**；新撞 **D149**（引用续行无 `│ ` 前缀，2 空格对齐代替）。
  D-new5 不登记（OSC 8 实证通过）。D57 注记更新（mermaid＝普通代码块）。行内标签丢 dim
  修饰随 D147 同族登记。
- **回填四处**（docs/06 §4 规则）：docs/04（B65 销号行＋§4 批 25 段〔裁决①–⑩落地＋
  两细化＋D143–D149＋行为变化注记〕＋E6 1,072→1,086/E7 1,031→1,040 实测＋§7 规则 6
  下一包号 docs/37）＋docs/05（§1 总表 tui/web 行 132.5/244≈54.3%·未加权 52/147≈35.4%·
  w3 71.2%、合计 1,132.0/1379≈82.1%；§2.4 批 25 条目；§2.5 行＋死功能清单 #1/#3 销号、
  #4 B221 批 24 漏删补收；§3 w3 队列行更新）＋map/05（R1/R2/R3/R4/R5/R9 六行翻对齐、
  R7 注记更新、S24 注记更新、R 域 7.5→17.5/39≈44.9%、模块合计 132.5/244≈54.3%＋批 25
  注记行、w3 清单 R1 翻＋16/5/5＝71.2%、预言节落地注记）＋map/04（E2-43 注记更新，
  判定维持存疑）＋docs/06（§1 四读数＋map/05 行新增、§2 批 25 行、§3.3 变量 3 落地注记）。
- **verify 史**：定向 `mvn test -pl pi-java-tui,pi-java-coding-agent -am` 全绿 → 全量
  `clean verify` #1 撞 `PiTuiAppInputTest` 旧钉（/help 断言 `ChatMessage.System`——T8
  行为变化落点，改钉 `Markdown`）→ 全量 `clean verify` #2 收口 **exit 0 BUILD SUCCESS，
  5,284 测 0 失败，checkstyle 0 违规，spotbugs 零钉**。
- **排障钉**：`Line.patchStyle` 方向是参数覆盖 span（行 style 须逐 span 重建作底）；
  无 `-am` 拾旧 jar 再撞（ExtensionCustomOverlayTest 找不到符号）；golden 两桩（任务标记
  `[x]` 须转义否则被当标签吞；`• ` 前缀落首块顶行）。
- **账面**：map/05 122.5→**132.5/244 ≈ 54.3%**（+10.0，六行缺失→对齐）；模块去重
  85.8% 不动；全仓 1,122.0→**1,132.0/1379 ≈ 82.1%**；**B65 销号**。

