# coding-agent 批 14 设计稿：CLI 参数尾（A 域）＋`-r` 交互会话选择器

> 【背景摘要】本稿是 coding-agent 对齐批 14（批次总账 docs/20 §3 序 13）的设计文档。
> 范围两件事：① A 域（CLI 参数，map/04 §A）剩余 13 行的收尾——`-r` 交互会话选择器是
> 重头（pi 侧 1,294 行）；② 会话解析链（`--session/--session-id/--fork/--continue`）
> 与 pi `createSessionManager` 的全序对齐。每批流程：设计稿 → 用户审核 → 写码 → 闭环回填。
>
> 术语（首现释义，后续直接用）：
> - **pi / 锚点**：被对标的原项目（TypeScript），取证只走 `git show ea448f454:<path>`，不读工作树。
> - **选择器（selector）**：`-r/--resume` 裸敲时弹出的全屏会话选择界面（pi `SessionSelectorComponent`）。
> - **阶梯（ladder）**：会话 id 解析的分级查找——⓪ 路径形快道 → ① 项目内精确 → ② 项目内前缀 → ③ 跨项目精确优先于前缀（B53，agent-core 批 4 已落 repo 层）。
> - **富列表（rich list）**：带名字/首条消息/全文/消息数的会话元数据扫描（`RichSessionInfo`，B55 已落 repo 层，带进度回调与取消）。
> - **存疑/缺失/对齐**：map 行三档判定（0.5/0/1.0）。**核销**：map 行证据滞后、代码其实已对齐，本批只翻判定不写码。
> - **窄差（D 编号）**：无法完全对齐、登记在册的差异。**工单（B 编号）**：docs/04 开放项。
> - **模型可见面**：大模型能读到的文本；本批几乎全部改动是【用户可见/内部】，模型可见面零触及。

---

## §0 基线与预期翻动

基线（批 13 闭环实算，2026-10-23）：模块 315.0/485 ≈ 64.9%；全仓 989.0/1379 ≈ 71.7%。

| 行 | 前态 | 后态 | Δ（加权） | 手段 |
|---|---|---|---:|---|
| map/04 **A-5** `-r/--resume`（w2） | 存疑 | 对齐 | +1.0 | T11 选择器接线 |
| A-12 `--no-session`（w1，B54） | 存疑 | 对齐 | +0.5 | T14 web 臂 |
| A-13 `--session`（w2） | 存疑 | 对齐 | +1.0 | T6/T7 |
| A-14 `--session-id`（w2） | 存疑 | 对齐 | +1.0 | T6/T8 |
| A-15 `--fork`（w1） | 存疑 | 对齐 | +0.5 | T6/T9 |
| A-17 `--models`（w2） | 缺失 | 对齐 | +2.0 | **核销**（T17，链已通） |
| A-24 `--export`（w1） | 存疑 | 对齐 | +0.5 | T13 |
| A-25 `-e/--extension`（w2） | 缺失 | 对齐 | +2.0 | T15 |
| A-29 `--theme`（w1） | 存疑 | **不动** | 0 | 重排批 20（§4） |
| A-30 `--use-theme`（w1） | 缺失 | 对齐 | +1.0 | T16 |
| A-40 `--offline`（w1） | 存疑 | 对齐 | +0.5 | **裁决④**（T17） |
| A-41 `--`（w1） | 存疑 | 对齐 | +0.5 | T12 |
| A-43 扩展注册 flag（w1） | 缺失 | **不动** | 0 | 重排批 15/E5（§4） |
| **E3-3** `-r` 选择器（w2） | 缺失 | 对齐 | +2.0 | T10/T11（重头） |
| E3-4 `--session` 查找（w2） | 存疑 | 对齐 | +1.0 | T6/T7 |
| E3-5 `--session-id`（w2） | 存疑 | 对齐 | +1.0 | T8 |
| E3-6 `--fork`（w2） | 存疑 | 对齐 | +1.0 | T6/T9 |
| E3-9 会话列表加强（w2，B55 尾） | 存疑 | 对齐 | +1.0 | T4 富列表暴露 |
| map/05 **K16** `app.session.*` 键位族（w2） | 缺失 | 对齐 | +2.0 | T3 |
| map/05 **S11** 选择器高级功能（w1） | 缺失 | 存疑 | +0.5 | T10（rename 无生产者，留白） |

**预期账面**：A 域 50.0→**60.5/62 ≈ 97.6%**（余 1.5：A-29/A-43 重排）；
E3 域 28.5→**34.5/40 ≈ 86.3%**；模块 315.0→**331.5/485 ≈ 68.4%**（+16.5）；
全仓 989.0→**1008.0/1379 ≈ 73.1%**（+19.0，含 map/05 K16/S11 的 +2.5）。

**闭环实算（2026-10-23）**：A 域 60.5/62 ≈ 97.6%（如期）；
E3 域 28.5→**37.0/40 ≈ 92.5%**（+8.5＝设计 6.0＋**E3-19 漏列同翻** +0.5〔B54，
批 10 E3-8 先例〕＋**E3-24 批 13 漏列核销** +2.0）；模块 315.0→**334.0/485 ≈ 68.9%**
（+19.0）；map/05 K16 对齐 +2.0／**K15 漏列同翻** +1.0／S11 存疑 +0.5 ⇒ tui/web
113.0→**116.5/245 ≈ 47.6%**；全仓 989.0→**1,011.5/1379 ≈ 73.3%**（+22.5）。
回归抓回两修（/hotkeys 空绑定 NPE／-c 旧夹具改钉）＋auth 三存储静态 home 修真
（批 13「环境性红」真因，coding-agent 979/979 绿）＋前存红
`TreeNavigationFlowTest.queuedMessagesRestoreToEditorBeforeAbort` 基线证红登记。

**附带修复（无账面变动）**：`-c` 空臂（pi 无会话时**新建**而非报错，§1.3.7）——
新登 **B255** 即批内销号（先例：ai 批 2 B239 即登即销）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 `-r` 触发链：`main.ts:415-430` → `cli/session-picker.ts`（55 行全贴）

```ts
// main.ts:415-430
if (parsed.resume) {
    try {
        const selectedPath = await selectSession(
            (onProgress, signal) => SessionManager.list(cwd, sessionDir, onProgress, signal),
            (onProgress, signal) => SessionManager.listAll(sessionDir, onProgress, signal),
            settingsManager,
        );
        if (!selectedPath) {
            console.log(chalk.dim("No session selected"));
            process.exit(0);
        }
        return SessionManager.open(selectedPath, sessionDir);
    } finally {
        stopThemeWatcher();
    }
}
```

```ts
// cli/session-picker.ts:14-55（全文）
export async function selectSession(
    currentSessionsLoader: SessionsLoader,
    allSessionsLoader: SessionsLoader,
    settingsManager: SettingsManager,
): Promise<string | null> {
    const ui = await createStartupTui(settingsManager);
    return new Promise((resolve) => {
        const keybindings = KeybindingsManager.create();
        setKeybindings(keybindings);
        let resolved = false;
        const selector = new SessionSelectorComponent(
            currentSessionsLoader,
            allSessionsLoader,
            (path: string) => { if (!resolved) { resolved = true; ui.stop(); resolve(path); } },
            () => { if (!resolved) { resolved = true; ui.stop(); resolve(null); } },
            () => { ui.stop(); process.exit(0); },
            () => ui.requestRender(),
            { showRenameHint: false, keybindings },
        );
        ui.addChild(selector);
        ui.setFocus(selector.getSessionList());
        startStartupTui(ui, settingsManager);
    });
}
```

要点：
- picker **不传 `renameSession`** ⇒ `canRename=false`、rename 键臂与 rename 面板在 picker 语境是**死代码**（交互选择器 `interactive-mode.ts:5710` 才是 rename 生产者，非本批）。
- picker **不传 `currentSessionFilePath`** ⇒ 「当前会话」高亮与删除守卫（"Cannot delete the currently active session"）在 picker 语境同样不可达。
- `onExit` 钩（`:302`）只有赋值、**全文件无调用点**——pi 死钩，不移植。
- 结局两臂：选中 ⇒ `SessionManager.open(path)`；取消 ⇒ 打 `No session selected`（dim），**exit 0**。
- 启动壳 `createStartupTui`（startup-ui.ts:85-95）：裸 TUI＋主题/键位/终端能力，选完 `ui.stop()` 交还终端，主 UI 随后才起。

### 1.2 选择器本体：`modes/interactive/components/session-selector.ts`（1,045 行）

三类件：`SessionSelectorHeader`（:56-187，标题＋三门状态＋两行 hint＋状态消息）、
`SessionList`（:283-647，搜索输入＋列表状态机＋键分派）、
`SessionSelectorComponent`（:694-1045，装配＋双 scope 加载状态机＋删除/重命名流）。

#### 1.2.1 列表状态机（:375-395 逐字）

```ts
private filterSessions(query: string): void {
    const trimmed = query.trim();
    const nameFiltered =
        this.nameFilter === "all" ? this.allSessions : this.allSessions.filter((session) => hasSessionName(session));
    if (this.sortMode === "threaded" && !trimmed) {
        // Threaded mode without search: show tree structure
        const roots = buildSessionTree(nameFiltered);
        this.filteredSessions = flattenSessionTree(roots);
    } else {
        // Other modes or with search: flat list
        const filtered = filterAndSortSessions(nameFiltered, query, this.sortMode, "all");
        this.filteredSessions = filtered.map((session) => ({
            session, depth: 0, isLast: true, ancestorContinues: [],
        }));
    }
    this.selectedIndex = Math.min(this.selectedIndex, Math.max(0, this.filteredSessions.length - 1));
}
```

`setSessions`（:362-373）：未摸过选择 ⇒ 重置 0；摸过 ⇒ 按 path 找回选中项（找不到保持当前下标被 clamp）。

#### 1.2.2 键分派（:540-646 分派序逐字，顺序即语义）

1. **删除确认态拦截一切**：`tui.select.confirm` ⇒ 执行删除；`tui.select.cancel` ⇒ 退出确认态；其余键吞掉。
2. `tui.input.tab` ⇒ scope 切换（Tab 在此**不是**补全）。
3. `app.session.toggleSort`（ctrl+s）⇒ 排序轮转 threaded→recent→relevance→threaded（:1009-1015）。
4. `app.session.toggleNamedFilter`（ctrl+n）⇒ all⇄named。
5. `app.session.togglePath`（ctrl+p）⇒ 路径显示开关（右侧前缀会话文件路径）。
6. `app.session.delete`（ctrl+d）⇒ 进入删除确认态（选中项）。
7. `app.session.rename`（ctrl+r）⇒ rename 回调（picker 无 ⇒ no-op）。
8. `app.session.deleteNoninvasive`（ctrl+backspace）：搜索串非空 ⇒  Forward 给输入框（删字符）；空 ⇒ 同 delete。
9. 以上均未命中 ⇒ `selectionTouched=true`，然后：`tui.select.up/down` ⇒ ±1（**clamp，不环绕**）；
   `pageUp/pageDown` ⇒ ±maxVisible；`confirm` ⇒ 选中定案；`cancel` ⇒ 取消回调；
   其余 ⇒ 全 Forward 给搜索输入框并重新过滤。

`maxVisible = 10`（:311）；可视窗公式（:450-454）：

```ts
const startIndex = Math.max(0,
    Math.min(this.selectedIndex - Math.floor(this.maxVisible / 2), this.filteredSessions.length - this.maxVisible));
const endIndex = Math.min(startIndex + this.maxVisible, this.filteredSessions.length);
```

滚动指示（:521-525）：`startIndex > 0 || endIndex < len` 时追加 `  (i+1/len)`。

#### 1.2.3 树构建（:209-278 逐字要点）

- `buildSessionTree`：按 `canonicalizePath(parentSessionPath)` 认父；父不在集合 ⇒ 根。
  `latestActivity`＝子树最大 modified；根与子层均按它**降序**。
- `flattenSessionTree`：深度先序；`ancestorContinues` 规则——`depth > 0 ? !isLast : false`（:268）；
  前缀（:530-538）：祖级 `│  `/`   `＋末级 `└─ `/`├─ `；depth 0 空前缀。
- `canonicalizePath`（utils/paths.ts）：`realpathSync`，失败回落原串。

#### 1.2.4 行渲染（:457-518 逐字公式）

- 显示文本：`session.name ?? session.firstMessage`，控制字符 `[\x00-\x1f\x7f]`→空格，trim。
- 右侧：`{msgCount} {age}`；`showCwd`（all scope）前缀 `shortenPath(cwd)`；`showPath` 前缀 `shortenPath(path)`。
  `shortenPath`（:26-33）：home 前缀 ⇒ `~` 起头。
- `age`＝`formatSessionDate`（:35-49）：<1m `now`；<60m `{n}m`；<24h `{n}h`；<7d `{n}d`；<30d `{n}w`；<365d `{n}mo`；否则 `{n}y`。
- 光标：`› `（accent）/两空格；颜色优先级：确认删除中 error > 当前会话 accent > 有名 warning > 默认；选中加粗＋`selectedBg` 底。
- 行宽：光标 2 列＋树前缀＋消息（`truncateToWidth(..., max(10, 可用), "…")`）＋右侧（间距 ≥1）。
- 空表文案（:429-447 四种，逐字见 §3 T10）：named×showCwd 两维四分。

#### 1.2.5 双 scope 加载状态机（:941-1007 逐字要点）

```
loadScope(scope):
  已在载 ⇒ return；新建 AbortController 存 currentLoad/allLoad；
  header.setScope + setLoading(true)（setLoading 清进度，:88-92）；
  onProgress(loaded,total,partial): isActive() 否则丢；partial 更新对应 scope 缓存，
    且 scope==当前 ⇒ setSessions；scope≠当前 ⇒ 不再碰 header/render；
  完成：isActive ⇒ 写缓存、清 load 句柄、当前 ⇒ setLoading(false)+setSessions+render；
  失败：isActive ⇒ 清句柄与缓存、当前 ⇒ "Failed to load sessions: <msg>"（4s 自动隐）+ 空表。
toggleScope: 切 scope；目标有缓存 ⇒ 立即 setSessions；无缓存且未在载 ⇒ loadScope（懒载 all）。
cancelLoads（选择/取消/删除刷新前）：两臂 abort＋清句柄清缓存。
refreshSessionsAfterMutation（删除成功后）：cancelLoads → 清双缓存 → loadScope(当前 scope)。
```

删除本体 `deleteSessionFile`（:654-689）：先试 `trash` CLI（`spawnSync`，`--` 防 `-` 起头路径），
成功或文件已消失 ⇒ `{method:"trash"}`；否则 `unlink` 兜底；失败文案
`Failed to delete: <unlinkErr> (trash: <err · stderr首行,截200>)`。成功文案：
trash ⇒ "Session moved to trash"（2s），unlink ⇒ "Session deleted"（2s）。

#### 1.2.6 header 渲染（:130-186 逐字公式）

- 标题：scope=current ⇒ `Resume Session (Current Folder)`；all ⇒ `Resume Session (All)`，bold。
- 右段：`{scopeText}  {nameText}  {sortText}`（两个空格分隔），整体 `truncateToWidth(width)`；
  scopeText 三态：加载中 `○ Current Folder | Loading {n}/{total}`（无进度 `...`）；
  current `◉ Current Folder | ○ All`；all `○ Current Folder | ◉ All`（选中半 accent，另半 muted）。
  nameText `Name: All|Named`；sortText `Sort: Threaded|Recent|Fuzzy`（注意 relevance 显示为 **Fuzzy**，:134）。
- hint 区三态：删除确认中 ⇒ `Delete session? {enter} confirm · {esc} cancel`（error 色，单行）；
  状态消息 ⇒ 消息单行（error/accent 色）；常态两行——
  行1 `{tab} scope · re:<pattern> regex · "phrase" exact`；
  行2 `{ctrl+s} sort · {ctrl+n} named · {ctrl+d} delete · {ctrl+p} path (on|off)`（picker 无 rename 段），
  ` · ` 为 muted，键位段＝`keyHint`（dim 键名＋muted 描述，keybinding-hints.ts:42-44）。

### 1.3 搜索：`session-selector-search.ts`（194 行，核心逐字）

`SortMode = "threaded"|"recent"|"relevance"`；`NameFilter = "all"|"named"`。
搜索文本（:26-28）：`` `${id} ${name ?? ""} ${allMessagesText} ${cwd}` ``。

`parseSearchQuery`（:39-114）：
- 空 ⇒ tokens 空（匹配一切，score 0）。
- `re:` 起头 ⇒ 正则模式（`i` 旗）；空模式 ⇒ `error:"Empty regex"`；构造抛错 ⇒ `error:<msg>`。
  error ⇒ `filterAndSortSessions` 返回**空表**（:168）。
- 否则引号分词：`"` 开合切 phrase/fuzzy 段，空白切 fuzzy 段；**引号未闭合 ⇒ 整体回落**为按空白切 fuzzy 词（:99-109）。

`matchSession`（:116-154）：
- 正则臂：`text.search(regex)`，命中 ⇒ `score = idx * 0.1`。
- tokens 空 ⇒ 全命中 score 0。
- phrase：归一化（小写＋`\s+`→单空格 trim）后 `indexOf`，未中 ⇒ 不命中；`score += idx*0.1`。
- fuzzy：`fuzzyMatch(token, text)`（见 §1.4），未中 ⇒ 不命中；`score += m.score`。

`filterAndSortSessions`（:156-194）：recent ⇒ 只滤**保持入序**；relevance（及 threaded 带查询）
⇒ score 升序，平分按 modified **降序**决胜。

### 1.4 fuzzy：`pi-tui/src/fuzzy.ts` `fuzzyMatch`（逐字）

```ts
export function fuzzyMatch(query: string, text: string): FuzzyMatch {
    const queryLower = query.toLowerCase();
    const textLower = text.toLowerCase();
    const matchQuery = (normalizedQuery: string): FuzzyMatch => {
        if (normalizedQuery.length === 0) return { matches: true, score: 0 };
        if (normalizedQuery.length > textLower.length) return { matches: false, score: 0 };
        let queryIndex = 0, score = 0, lastMatchIndex = -1, consecutiveMatches = 0;
        while (queryIndex < normalizedQuery.length) {
            const i = textLower.indexOf(normalizedQuery[queryIndex]!, lastMatchIndex + 1);
            if (i === -1) break;
            const isWordBoundary = i === 0 || /[\s\-_./:]/.test(textLower[i - 1]!);
            if (lastMatchIndex === i - 1) { consecutiveMatches++; score -= consecutiveMatches * 5; }
            else { consecutiveMatches = 0; if (lastMatchIndex >= 0) score += (i - lastMatchIndex - 1) * 2; }
            if (isWordBoundary) score -= 10;
            score += i * 0.1;
            lastMatchIndex = i; queryIndex++;
        }
        if (queryIndex < normalizedQuery.length) return { matches: false, score: 0 };
        if (normalizedQuery === textLower) score -= 100;
        return { matches: true, score };
    };
    const primaryMatch = matchQuery(queryLower);
    if (primaryMatch.matches) return primaryMatch;
    const alphaNumericMatch = queryLower.match(/^(?<letters>[a-z]+)(?<digits>[0-9]+)$/);
    const numericAlphaMatch = queryLower.match(/^(?<digits>[0-9]+)(?<letters>[a-z]+)$/);
    const swappedQuery = alphaNumericMatch ? `${digits}${letters}`
        : numericAlphaMatch ? `${letters}${digits}` : "";
    if (!swappedQuery) return primaryMatch;
    const swappedMatch = matchQuery(swappedQuery);
    if (!swappedMatch.matches) return primaryMatch;
    return { matches: true, score: swappedMatch.score + 5 };
}
```

要点：子序列匹配（不要求连续）；连续命中递减奖（−5×n）、词界奖（−10，界符 `[\s\-_./:]`）、
间隙罚（+2×gap）、位置微罚（+0.1×i）、全等大奖（−100）；主询不中时试字母数字互换臂（+5 罚）。
**java 现 `FuzzyMatcher` 是纯子串匹配（B78 在册）**，本批为选择器**新port**此类，不动 B78（§4）。

### 1.5 会话解析：`main.ts:243-336,358-449`（逐字全序）

`createSessionManager` 判定序（就是案例优先级，谁先要谁）：

```
:364  noSession/help/listModels ⇒ 内存会话（sessionId 有则带 id）
:368  --fork：:369-375 先查 --session-id 目标已存在（findLocalSessionByExactId 命中
      ⇒ "Session already exists with id '<id>'" exit 1）；再 resolveSessionPath 解析源，
      not_found ⇒ "No session found matching '<arg>'" exit 1；否则 forkFrom(path, cwd, {id: sessionId})
:391  --session：resolveSessionPath ⇒
      path/local ⇒ open；global ⇒ 打 "Session found in different project: <cwd>"（黄）
      + promptConfirm("Fork this session into current directory?")：n ⇒ "Aborted."（dim）exit 0，
      y ⇒ forkFrom(path, cwd)；not_found ⇒ "No session found matching '<arg>'" exit 1
:415  --resume：选择器（§1.1）
:432  --continue：continueRecent（见 1.3.7）
:436  --session-id：findLocalSessionByExactId ⇒ open；否则打
      "Warning: No project session found with id '<id>'; creating a new session with that id."（黄，不返回）
:448  create(cwd, {id: sessionId})
```

`resolveSessionPath`（:252-283）：路径形（含 `/`、`\` 或 `.jsonl` 结尾）⇒ 直接 resolvePath；
否则 ① `findById(cwd, id)`（header-only 精确）→ ② 项目内 list 前缀 → ③ 跨项目 `exact ?? prefix`
（一次扫，精确优先）。返回 `{type: path|local|global|not_found}`——**global 带 cwd**。

`promptConfirm`（:285-297）：readline `${message} [y/N] `，仅 `y`/`yes`（小写化）为真。

两个校验（:299-336，main() 解析后跑，print 文 `"Error: ..."` 红，exit 1）：

```ts
// validateForkFlags：--fork 与 --session/--continue/--resume/--no-session 互斥
`Error: --fork cannot be combined with ${conflictingFlags.join(", ")}`
// validateSessionIdFlags：--session-id 与 --session/--continue/--resume 互斥＋格式断言
`Error: --session-id cannot be combined with ${conflictingFlags.join(", ")}`
```

`assertValidSessionId`（session-manager.ts:268-273 逐字）：

```ts
if (!/^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$/.test(id)) {
    throw new Error("Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', and start and end with an alphanumeric character");
}
```

#### 1.3.7 `-c` 空臂（本批新取证，B255）

`continueRecent`（session-manager.ts:1793-1801）：无最近会话 ⇒ `new SessionManager(cwd, dir, undefined, true)`
——**静默新建**，不报错。java 现状 `resolvePersistent:196`/`resolveInMemory:255` 两臂都是
`orElseThrow("No previous session to continue")` ⇒ 行为差（web 臂 `:181-186` 反而是 orElseGet 新建，与 pi 同）。

### 1.6 键位默认表（core/keybindings.ts + pi-tui keybindings.ts:143-157）

| 动作 | 默认键 | 用途 |
|---|---|---|
| `app.session.toggleNamedFilter` | ctrl+n | 命名过滤（:55） |
| `app.session.togglePath` | ctrl+p | 路径显示（:99） |
| `app.session.toggleSort` | ctrl+s | 排序轮转（:103） |
| `app.session.rename` | ctrl+r | 重命名（:107；picker 不可达） |
| `app.session.delete` | ctrl+d | 删除（:111） |
| `app.session.deleteNoninvasive` | ctrl+backspace | 空查询删除别名（:115） |
| `app.session.new/tree/fork/resume` | **空表**（`defaultKeys: []`，:79-82） | 声明无绑定 |
| `tui.select.up/down/pageUp/pageDown` | up/down/pageUp/pageDown | 导航 |
| `tui.select.confirm` | enter | 定案 |
| `tui.select.cancel` | **escape, ctrl+c**（两键） | 取消 |
| `tui.input.tab` | tab | scope 切换 |

### 1.7 小旗面取证

1. **`--export`**（main.ts:639-651）：`exportFromFile(parsed.export, parsed.messages[0])`——
   **第二位置参数是输出路径**；默认输出 `${APP_NAME}-session-${inputBasename}.html`（export-html/index.ts:288-313，
   相对 cwd）；成功 `Exported to: <path>`（:649）exit 0；失败 `Error: <msg>` exit 1（无消息时 `"Failed to export session"`）。
2. **`--`**（args.ts:83-90）：首个 `--` 之后全部：`@x` ⇒ fileArgs，其余 ⇒ messages（含 `-`/`--` 起头），停止解析。
3. **`--models`**（args.ts:142-146 逗号切）：消费链 main.ts:812 `parsed.models ?? settingsManager.getEnabledModels()`
   → scopedModels → :499-516 初始模型＋:526-535 Ctrl+P 轮转。
4. **`--use-theme`**（args.ts:202-209）：值缺席或以 `-` 起头 ⇒ error 诊断 `"--use-theme requires a theme name"`；
   消费 main.ts:681-683 `applyOverrides({theme: parsed.useTheme})`——**先于选择器**生效（:695 传入被覆盖的 settingsManager）。
5. **`-e/--extension`**（args.ts:186-188 累积）：main.ts:728 `resolveCliPaths(cwd, parsed.extensions)` →
   resource-loader `additionalExtensionPaths`；**`-ne` 下显式路径仍加载**（resource-loader.ts:578/690
   `noExtensions ? cliEnabledExtensions : merge(...)`；help 文案 args.ts:327 同义）。
6. **`--offline`**（main.ts:578-582）：置 `PI_OFFLINE=1`＋`PI_SKIP_VERSION_CHECK=1` 进程级，
   下游门：目录刷新（interactive-mode.ts:1149）、包更新检查（:1264）、安装遥测（:1360）、
   version-check.ts:55、package-manager.ts:55、bug-report.ts:56。

### 1.8 pi 测试蓝本

`test/session-selector-search.test.ts`、`session-selector-path-delete.test.ts`、
`session-selector-rename.test.ts`（rename 属交互臂，本批只参照 search/delete 两套件）。

---

## §2 java 侧现状（取证 2026-10-23）

| 点 | 现状 | 出处 |
|---|---|---|
| `-r` 裸敲 | 落 `resolvePersistent:231` 与 `--session` 同臂 ⇒ `find(null)`⇒`"Session not found: null"` 抛栈 | `SessionPersistence.java:231-244` |
| 解析序 | continue **最先**（pi 倒数第二）；`-r` 与 `--session` 同臂 | `SessionPersistence.java:189-249` |
| `--session` 全局臂 | `handle.find` 命中即 attach，无「不同项目」提示＋确认 fork | 同上 :241-244 |
| `--session-id` | 走全阶梯（pi 只项目内精确）；缺 ⇒ `create(cwd, null)` **丢 id**、无 warning | 同上 :220-230 |
| `--fork` | ⓪＋阶梯已在；无 session-id 目标查重 | 同上 :198-219 |
| 校验 | 无 validateForkFlags/validateSessionIdFlags/assertValidSessionId | — |
| 错误面 | `IllegalStateException` 栈 trace（pi 是干净 `Error: <msg>` exit 1） | Main/各 mode 无收口 |
| `-c` 空臂 | 抛 `"No previous session to continue"`（pi 静默新建，B255） | `:196`、`:255` |
| 阶梯本体 | **已落**（B53，jsonl 臂 ⓪①②③） | `PersistentSessionRepositories.java:59-93` |
| 富列表本体 | **已落**（B55：`RichSessionLister.listRich/listAllRich`，进度＋取消＋并发） | `RichSessionLister.java`、`JsonlSessionRepository.java:105-112` |
| 富列表暴露 | `RepositoryHandle` 只有同步 `list(cwd)`（E3-9 存疑根因） | `PersistentSessionRepositories.java:292-350` |
| `RichSessionInfo` | 缺 `parentSessionPath`（threaded 树认父必需） | `RichSessionInfo.java` |
| `--models` | **已全链**（`ModelScoping.resolve:41` 吃 `args.models()`→`setScopedModels:677`→Ctrl+P `PiTuiApp:665`） | **核销** |
| `--export` | 只吃 1 参；文案 `Exported session to ` | `Main.java:84-94`、`HtmlExporter.java:71-84`（双参重载已在） |
| `--` | 未显式处理；`--` 后的 `-foo` 会被 `toArgs:224` 误判 `Unknown option` | `ArgsParser.java:212-230` |
| `-e` | `ArgsParser:107` 已收，`args.extensions()` **零消费者**；`loadExtensions` 的 `-ne` 早退臂会连显式路径一起跳 | `AgentSession.java:2237-2302` |
| `--use-theme` | 无此 flag；`--theme` 现被当「选择」用（`PiTuiLauncher.themeFrom:170-175`） | — |
| `--offline` | `ModelCatalogRefresh.offlineMode:77` 门目录刷新——java 唯一启动网络操作 | 裁决④ |
| 键位 | `KeybindingsManager` 全局 11＋overlay 13；无 `app.session.*` | `KeybindingsManager.java` |
| fuzzy | `FuzzyMatcher` 纯子串（B78 在册，SelectList 用） | `component/FuzzyMatcher.java` |
| 搜索输入件 | `EditorComponent`（批 9 label 编辑已复用为单行） | `component/EditorComponent.java` |
| 启动壳 | fullscreen runner＝`TamboUIAdapter.createRunner`（33ms tick）＋`eventRouter().addGlobalHandler`＋`runner.quit()`；跨线程 `TuiEventDispatcher`（root() 每帧 drain） | `PiTuiLauncher.java:81-93`、`PiTuiApp.java:186-190,281-283` |
| span 渲染纪律 | 批 9/10 的 `layout()→List<List<Span>>`＋`truncateToWidth` 列宽 | `UserMessageSelectorScreen.java`（样板） |

---

## §3 任务分解

实施序＝可独立编译的 commit 序（每步 ≤500 行/文件红线）。

### T1：`FuzzyMatch` port（pi-java-tui，新文件 `component/FuzzyMatch.java`，~110 行）

pi `fuzzy.ts` `fuzzyMatch` 逐字（§1.4）。形状：

```java
public final class FuzzyMatch {
    public record Result(boolean matches, double score) {}
    public static Result fuzzyMatch(String query, String text) { ... }
}
```

- 词界字符集 `[\s\-_./:]`、连击奖 −5×n、间隙罚 +2×gap、位置罚 +0.1×i、全等 −100、互换臂 +5 全逐字。
- 命名**不**动既有 `FuzzyMatcher`（B78 在册，SelectList 继续用旧件）——两件并存，javadoc 互相指引。
- 夹具：子序列命中/断命中、词界奖、连击递增奖、间隙罚、全等大奖、`abc123`⇄`123abc` 互换臂、空 query。

### T2：`SessionSelectorSearch`（pi-java-tui，新文件 `component/session/SessionSelectorSearch.java`，~200 行）

§1.3 逐字。纯函数集：

```java
public final class SessionSelectorSearch {
    public enum SortMode { THREADED, RECENT, RELEVANCE }      // pi 字面量小写串
    public enum NameFilter { ALL, NAMED }
    public record ParsedQuery(Mode mode, List<Token> tokens, Pattern regex, String error) {
        public enum Mode { TOKENS, REGEX }
        public record Token(Kind kind, String value) { public enum Kind { FUZZY, PHRASE } }
    }
    public record MatchResult(boolean matches, double score) {}
    public static boolean hasSessionName(RichSessionInfo s) { ... }          // :30-32
    public static ParsedQuery parseSearchQuery(String query) { ... }         // :39-114
    public static MatchResult matchSession(RichSessionInfo s, ParsedQuery p) { ... }  // :116-154
    public static List<RichSessionInfo> filterAndSortSessions(
        List<RichSessionInfo> sessions, String query, SortMode sortMode, NameFilter nameFilter) { ... }  // :156-194
}
```

- 搜索文本 `` id + " " + (name ?? "") + " " + allMessagesText + " " + cwd ``（:26-28 逐字）。
- `re:` 正则 `Pattern.CASE_INSENSITIVE`；构造异常 ⇒ `error`＝异常消息（java `PatternSyntaxException.getMessage()` 文案与 V8 不同——窄差 D75，仅错误提示文本差，行为同是「error ⇒ 空表」）。
- 归一化 `toLowerCase + \s+→" " + trim`（:22-24 逐字）；引号未闭合回落臂（:99-109）逐字。
- fuzzy 词走 T1 的 `FuzzyMatch.fuzzyMatch`。
- 夹具对照 pi `session-selector-search.test.ts` 蓝本：re: 三臂、引号对、未闭合回落、relevance 平分 modified 降序、recent 保序、named 过滤。

### T3：键位表（coding-agent `KeybindingsManager`，+~45 行 ⇒ ~235 行；K16 翻转）

overlay 表（`overlayBindings`，批 9 C1 纪律——不进全局 `resolve`，选择器内按动作 `matches`）：

```java
public static final String SESSION_TOGGLE_NAMED_FILTER = "app.session.toggleNamedFilter"; // ctrl+n
public static final String SESSION_TOGGLE_PATH = "app.session.togglePath";                // ctrl+p
public static final String SESSION_TOGGLE_SORT = "app.session.toggleSort";                // ctrl+s
public static final String SESSION_RENAME = "app.session.rename";                         // ctrl+r
public static final String SESSION_DELETE = "app.session.delete";                         // ctrl+d
public static final String SESSION_DELETE_NONINVASIVE = "app.session.deleteNoninvasive";  // ctrl+backspace
```

全局表（pi `defaultKeys: []` 声明无绑定 ⇒ `List.of()`；`resolve` 永不命中，`actionIds` 含）：

```java
public static final String SESSION_NEW = "app.session.new";       // pi :79 空绑定
public static final String SESSION_TREE = "app.session.tree";     // pi :80
public static final String SESSION_FORK = "app.session.fork";     // pi :81
public static final String SESSION_RESUME = "app.session.resume"; // pi :82
```

- 撞名核查：ctrl+p 全局＝MODEL_CYCLE、ctrl+d 全局＝EXIT——selector 六键全进 overlay 表，全局解析零影响（批 9 C1 同构）；夹具：`resolve(ctrl+p)` 仍 `MODEL_CYCLE`，`matches(SESSION_TOGGLE_PATH, ctrl+p)` 为真。
- `tui.select.*`／`tui.input.tab` **不入表**：沿批 10 先例直配 `KeyEvent` helper（isUp/isDown/isConfirm/isCancel/isPageUp/isPageDown/isTab——缺的 helper 在 T10 补）。窄差 D73。
- `KeyStroke.of("backspace", true, false, false)`；TamboUI 是否上报 ctrl+backspace 不可知——双臂都注册，deleteNoninvasive 在不上报的终端死键（pi 注释同病，:583）。窄差 D76 注记。
- `KeybindingHints.keyText` 已支持多段（ctrl+backspace ⇒ `ctrl+backspace`）✓；空绑定 ⇒ `""`（pi `formatKeys([])` 同）✓。

### T4：`RepositoryHandle` 富列表暴露＋两新口（coding-agent；E3-9 翻转）

`PersistentSessionRepositories.java` 现 461 行，先**拆 sqlite 臂**为新文件
`SqliteRepositoryHandle.java`（~170 行，package-private 工厂方法搬移，行为不变），主文件降至 ~300 行后加 ~90 行。

`RepositoryHandle` 新增四口：

```java
/** pi SessionManager.list(cwd, dir, onProgress, signal)（:943）对位——E3-9。 */
default List<RichSessionInfo> listRich(String cwd, SessionListProgress progress, AbortSignal signal) {
    // 退化臂（sqlite/未知后端）：同步 list 映射最小 RichSessionInfo，单次终末进度回调。
}
/** pi SessionManager.listAll(dir, onProgress, signal)（:1921）对位。 */
default List<RichSessionInfo> listAllRich(SessionListProgress progress, AbortSignal signal) { ... }
/** pi findLocalSessionByExactId（main.ts:243-250）：项目内 header-only 精确。 */
default Optional<? extends SessionMetadata> findLocalById(String cwd, String id) {
    return list(cwd).stream().filter(m -> m.id().equals(id)).findFirst();
}
/** pi SessionManager.create(cwd, dir, {id})（:448）＋ forkFrom {id} 对位。 */
Session<?> createWithId(String cwd, String id);
```

- jsonl 臂：`listRich/listAllRich` 直转 `repo.listRich/listAllRich`；`findLocalById` 转 `repo.findById(cwd, id)`；`createWithId` 转 `repo.create(new JsonlSessionCreateOptions(id, cwd, null, null))`。
- sqlite 臂：`findLocalById`/`createWithId` 实写（反射 options 本有 id 槽）；`listRich*` 走退化臂（窄差 D74：sqlite 无富扫描——pi 无 sqlite 对位，java 自有后端面，选择器列表退化但可用）。
- `find` 保持不动（--fork 语义不需要 scope 标）；新增 **`findWithScope`**：
  ```java
  record ScopedMatch(SessionMetadata metadata, boolean global) {}
  default Optional<ScopedMatch> findWithScope(String idOrPrefix) { ... }
  ```
  jsonl 臂按阶梯出 tier：①② ⇒ local、③ ⇒ global；sqlite 臂按 `cwd` 等否判定。

### T5：`RichSessionInfo.parentSessionPath`（agent-core，+~10 行）

- record 加末位组件 `String parentSessionPath`（可 null）。
- `RichSessionLister.buildRichInfo` header 支补读：`node.path("parentSession")` 文本 ⇒ 值（pi `header.parentSession`，session-manager.ts:857 同字段）。
- 既有构造点（lister 唯一生产）与测试夹具同步带新参。

### T6：会话解析重排＋校验＋干净错误面（coding-agent `SessionPersistence`/`ArgsParser`/`Main`）

#### T6a `resolvePersistent` 按 pi :358-449 全序重写（SessionPersistence，301→~430 行）

```java
static AgentSession resolvePersistent(AgentSession session, Args args) {
    var handle = session.persistentRepository();
    var cwd = System.getProperty("user.dir");
    if (args.fork() != null) { ... }       // pi :368（T9 查重内嵌）
    if (args.session() != null) { ... }    // pi :391（T7 全局臂内嵌）
    if (args.resume()) { ... }             // pi :415（T11 接线）
    if (args.continue_()) {                // pi :432＋B255 空臂
        return handle.latest(cwd).map(meta -> attach(session, handle, meta))
            .orElseGet(() -> createAttached(session, handle, cwd));   // pi :1800 静默新建
    }
    if (args.sessionId() != null) { ... }  // pi :436（T8）
    return createAttached(session, handle, cwd);   // pi :448
}
```

- `createAttached` 抽公共（create＋`session.session(created)`＋attach）——现 :225-228/:246-248 两处重复形收一。
- `resolveInMemory` 的 `-c` 空臂同步 orElseGet 新建（B255 双臂）。
- T11 落地前 resume 臂先放 `throw new SessionResolutionExit(1, "…")` 占位？——**不**：T6 与 T11 同批，resume 臂直接调 T11 的 SPI（任务序上 T11 的 SPI 接口在 T6 前先落骨架，见 commit 序 §7）。

#### T6b 校验两臂（ArgsParser.toArgs，diagnostics 形）

pi validateForkFlags/validateSessionIdFlags（:299-336）⇒ toArgs 尾部追加 error 诊断
（既有诊断出口 `error: <msg>`＋exit 2 沿用——窄差 D71 记 pi 的 `Error:`＋exit 1 文案/码差）：

```java
if (fork != null) {
    var conflicts = Stream.of(session != null ? "--session" : null,
        continue_ ? "--continue" : null, resume ? "--resume" : null,
        noSession ? "--no-session" : null).filter(Objects::nonNull).toList();
    if (!conflicts.isEmpty()) diagnostics.add(new ArgDiagnostic("error",
        "--fork cannot be combined with " + String.join(", ", conflicts)));
}
if (sessionId != null) {
    // 同上三旗（无 --no-session）；冲突诊断后仍跑格式断言（pi :329-335 同序）
    if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) diagnostics.add(new ArgDiagnostic("error",
        "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', and start and end with an alphanumeric character"));
}
```

`SESSION_ID_PATTERN = ^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$`（逐字）。
注意 pi 是先冲突诊断**后**格式断言且两者独立 exit——java 两臂都进 diagnostics 一次打印 ✓。

#### T6c 干净错误面（新类型＋Main 收口）

```java
package com.pijava.coding.agent.core;
/** 会话解析的干净结局（pi console.error + process.exit 对位）：消息已按 pi 文案成形，code 即进程码。 */
public final class SessionResolutionExit extends RuntimeException {
    private final int code;          // 0＝Aborted./No session selected；1＝各类 Error
    public int code() { return code; }
}
```

- 抛出点打印时机：message 由 Main 统一打 `System.err.println("Error: " + msg)`（code 1）
  或 `System.out.println(msg)`（code 0：pi 的 `console.log(chalk.dim(...))`——"Aborted."/"No session selected" 不带 `Error: ` 前缀）。
  实现：`SessionResolutionExit` 携带 `boolean errorPrefix`；Main.run 在最外层 try 包住 mode 分派段 catch 后 `println + return code`。
- 现 `"Session not found: <arg>"`/`"No previous session to continue"` 抛文全部废弃换 pi 文案。

### T7：`--session` 全局臂（SessionPersistence，随 T6a）

pi :391-413 逐字：

```java
// ⓪ 路径快道（现 :233-240 保留）；非路径形：
var match = handle.findWithScope(args.session());
if (match.isEmpty()) throw SessionResolutionExit.error("No session found matching '" + args.session() + "'");
if (!match.get().global()) { attach(session, handle, match.get().metadata()); return session; }
// global 臂：
System.out.println("Session found in different project: " + cwdOf(match.get().metadata()));
if (!promptConfirm("Fork this session into current directory?")) {
    throw SessionResolutionExit.aborted("Aborted.");   // exit 0
}
var forked = handle.fork(match.get().metadata(), new ForkOptions.Tree(), cwd);
session.session(forked); attach(session, handle, forked.getMetadata());
```

`promptConfirm`（pi :285-297）：`System.out.print(message + " [y/N] ")`＋读 `System.in` 一行
（`System.console()` 在则 `readLine`，否则扫 `System.in`）；`y`/`yes`（小写化）为真；
**EOF ⇒ false**（pi readline 关闭 ⇒ answer 空串 ⇒ false，同效）。

### T8：`--session-id` 项目内精确＋带 id 新建（SessionPersistence，随 T6a）

pi :436-448 逐字：

```java
var existing = handle.findLocalById(cwd, args.sessionId());   // 只项目内精确（pi :437）
if (existing.isPresent()) { attach(session, handle, existing.get()); return session; }
System.err.println("Warning: No project session found with id '" + args.sessionId()
    + "'; creating a new session with that id.");             // pi :441-445（黄⇒无色，B213 族）
var created = handle.createWithId(cwd, args.sessionId());     // 带 id 新建（修丢 id 现状）
session.session(created); attach(session, handle, created.getMetadata());
```

### T9：`--fork` 目标查重（SessionPersistence，随 T6a）

pi :369-375 逐字：`args.sessionId() != null && handle.findLocalById(cwd, args.sessionId()).isPresent()`
⇒ `throw SessionResolutionExit.error("Session already exists with id '" + args.sessionId() + "'")`。
其余臂（⓪/阶梯/not_found 文案 `"No session found matching '<arg>'"`）现已在，统一换 T6c 错误面。

### T10：选择器本体（pi-java-tui，新包 `component/session/`，7 文件 ~1,400 行）

布局纪律沿批 9（tree）/批 10（user-message）：状态机与渲染分离，`layout()→List<List<Span>>` 为测试断言面。

| 文件 | 行估 | 内容 |
|---|---:|---|
| `SessionTreeBuilder.java` | ~170 | `buildSessionTree`/`flattenSessionTree`（§1.2.3）＋`formatSessionDate`＋`shortenPath`＋`canonicalize`（`Path.toRealPath` 失败回落，pi realpathSync 对位）＋`FlatSessionNode`/`SessionTreeNode` |
| `SessionListModel.java` | ~270 | 列表状态机：`allSessions/filtered/selectedIndex/selectionTouched/searchText/showPath/confirmingDeletePath`；`filterSessions`（§1.2.1 逐字）；`setSessions`（:362-373）；导航 clamp/pageUp/pageDown；删除确认态转移 |
| `SessionListRenderer.java` | ~240 | 行渲染（§1.2.4 逐字公式：树前缀/光标/颜色优先/右侧/可视窗/滚动指示）＋空表四文案 |
| `SessionSelectorHeader.java` | ~210 | header 三行（§1.2.6）＋状态消息（type＋自动隐 deadline，tick 检查制——pi setTimeout 对位，见下）＋`setLoading` 清进度（:88-92） |
| `SessionSelectorComponent.java` | ~340 | 装配＋双 scope 加载状态机（§1.2.5 逐字：loadScope/isActive/cancelLoads/toggleScope 懒载/refreshAfterMutation）＋删除流＋rename 无生产者 no-op 臂＋`onKeyEvent` 分派（§1.2.2 序） |
| `SessionSelectorRunner.java`（app 包） | ~180 | 启动壳（下）＋SPI 实现 |
| 既有 `EditorComponent` 复用 | 0 | 搜索输入框（批 9 label 同手法）；`onSubmit` ⇒ 定案选中（pi :342-349） |

渲染/键层决定：
- **span 模型**：`layout(int width) → List<List<Span>>`（Span＝text＋Style，批 10 同形）；`render()` 转 TamboUI `Element`。
- **键分派**：删除确认态 → Tab → 五 toggles（`KeybindingsManager.matches`，T3 表）→ 导航/定案/取消（`KeyEvent` helper，缺的 `isPageUp/isPageDown/isTab` 在 `KeyEvent` 消费侧就地判 `event.code()`——TamboUI 有 `KeyCode.PageUp` 等，夹具钉）→ 其余 forward 搜索框。
- **样式映射**：pi theme 色 ⇒ `SemanticColors`（accent/muted/error/warning/dim/PLAIN.bold；`selectedBg` ⇒ 批 10 同例的背景 style 或加粗——批 10 用 `PLAIN.bold()` 选中行，沿例）。
- **状态消息自动隐**：pi `setTimeout`（2s/3s/4s 三档）⇒ java 记 `deadlineMs`，33ms tick 帧检查过期清除（tick 制是既有 runner 节律，无额外线程）。
- **删除**：`Files.delete(path)`（unlink 臂）；成功 "Session deleted"、失败 "Failed to delete: <msg>"；trash 臂无（窄差 D69）。
- **加载线程**：`loadScope` 在虚拟线程跑 `loader.load(progress, AbortSignal.create())`；progress 回调 `dispatcher.dispatch(...)`（runner root() 每 33ms drain，PiTuiApp 同手法）；`isActive` 用「句柄 identity 比较」（pi `:955` 逐字对位）。
- **当前会话高亮/删除守卫/rename 面板**：picker 语境 pi 本就不可达（§1.1）——`currentSessionPath` 字段保留形（恒 null）、rename 回调 null ⇒ 键臂 no-op；**rename 面板 UI 不移植**（窄差 D72，随交互选择器批收编）。
- **onExit 死钩不移植**（pi :302 赋值无调用点）。

`SessionSelectorRunner`（启动壳，pi session-picker.ts＋startup-ui.ts 对位）：

```java
public final class SessionSelectorRunner {
    /** pi selectSession（session-picker.ts:15-55）：全屏跑选择器，返回选中 path；取消 ⇒ empty。 */
    public static Optional<String> select(SessionListLoader current, SessionListLoader all,
                                          SettingsManager settings, String useThemeOverride) {
        var runner = TamboUIAdapter.createRunner(settings.uiPrefs().getShowHardwareCursor());
        runner.styleEngine(PiTheme.engineFor(PiTheme.resolveTheme(useThemeOverride, ...)));
        var component = new SessionSelectorComponent(current, all, ...);
        runner.eventRouter().addGlobalHandler(component::onEvent);
        runner.run(() -> { dispatcher.drain(); component.onTick(); return component.render(); });
        // component 定案/取消 ⇒ runner.quit()；结果落字段
        return component.selectedPath();
    }
}
```

终端前置：与 `PiTuiLauncher:30-34` 同判（无 console 且未 force ⇒ 报错——窄差 D70 同族注记）。

### T11：SPI＋`-r` 接线（coding-agent `spi/`＋tui 注册＋SessionPersistence resume 臂）

```java
// pi-java-coding-agent：com.pijava.coding.agent.spi（与 TuiEntryPoint 同包）
@FunctionalInterface
public interface SessionListLoader {
    List<RichSessionInfo> load(SessionListProgress progress, AbortSignal signal);
}
public interface SessionSelectorProvider {
    /** pi selectSession 对位：返回选中会话 path；用户取消 ⇒ empty。 */
    Optional<String> selectSession(SessionListLoader currentScope, SessionListLoader allScope,
                                   SettingsManager settings, String useThemeOverride);
}
```

- tui 侧 `SessionSelectorProviderImpl`（包 `com.pijava.tui.app`）转调 `SessionSelectorRunner.select`＋
  `META-INF/services/com.pijava.coding.agent.spi.SessionSelectorProvider` 注册。
- resume 臂（T6a 内，pi :415-430 逐字）：

```java
if (args.resume()) {
    var provider = ServiceLoader.load(SessionSelectorProvider.class).findFirst();
    if (provider.isEmpty()) {
        throw SessionResolutionExit.error(
            "--resume requires the pi-java-tui module on the classpath");   // 窄差 D70
    }
    var selected = provider.get().selectSession(
        (p, s) -> handle.listRich(cwd, p, s),
        (p, s) -> handle.listAllRich(p, s),
        session.services().settings(), args.useTheme());
    if (selected.isEmpty()) throw SessionResolutionExit.plain(0, "No session selected");  // pi :423-424
    var opened = handle.openPath(Path.of(selected.get()))
        .orElseThrow(() -> SessionResolutionExit.error("No session found matching '" + selected.get() + "'"));
    attachOpened(session, opened, opened.getMetadata());
    return session;
}
```

（pi 是 `SessionManager.open(path)`——选中项必来自列表扫描 ⇒ openPath 必中，兜底错误形防御。）
- `--session` 与 `-r` 同给：T6a 序保证 `--session` 先命中（pi :391 vs :415）✓ 夹具。

### T12：`--` 预分流（ArgsParser，+~30 行）

```java
public static Args parse(String[] args) {
    int sep = List.of(args).indexOf("--");
    String[] head = sep < 0 ? args : Arrays.copyOf(args, sep);
    String[] tail = sep < 0 ? new String[0] : Arrays.copyOfRange(args, sep + 1, args.length);
    ... // head 走 picocli（expandShortFlags 只吃 head——pi 不展开 `--` 后短旗）
}
// toArgs 尾部（unmatched 循环之后）追加 tail 路由（pi args.ts:83-90 逐字）：
for (var token : tail) {
    if (token.startsWith("@")) parsedFileArgs.add(token.substring(1));
    else parsedMessages.add(token);          // 含 -/-- 起头——pi 不再判
}
```

夹具：`-- -foo` ⇒ messages（现误判 Unknown option 的修真）；`-- @a.txt b` ⇒ fileArgs+messages；
`--tools x -- -t` ⇒ tools=x、`-t` 进 messages。

### T13：`--export` 位置输出参＋文案（Main，+~10 行）

```java
if (parsed.export() != null) {
    try {
        var input = Path.of(parsed.export());
        var out = parsed.messages().isEmpty()
            ? new HtmlExporter().export(input)
            : new HtmlExporter().export(input, Path.of(parsed.messages().getFirst()));
        System.out.println("Exported to: " + out);      // pi :649 逐字（"Exported session to " 废）
        return 0;
    } catch (Exception e) {
        System.err.println("Error: " + (e.getMessage() != null ? e.getMessage() : "Failed to export session"));
        return 1;                                        // pi :644-647（"Error: " 大写逐字）
    }
}
```

默认输出名 `pi-java-session-<base>.html` 保留（pi 是 `pi-session-*`——品牌族窄差 D77）。

### T14：`--no-session` web 臂（B54 销号，`SessionPersistence.resolvePersistentWeb`，+3 行）

```java
static AgentSession resolvePersistentWeb(AgentSession session, Args args) {
    if (args.noSession()) {
        return session;   // 与 SessionSetup.resolveSession:122-124 非 web 臂同语义：ephemeral 胜出
    }
    ...
}
```

B54 原文「谁赢未定义」⇒ 裁决⑤定案：**--no-session 赢**（与 CLI 臂一致，ephemeral 总是更安全的一侧）。

### T15：`-e/--extension` 显式 JAR 加载（AgentSession.loadExtensions，+~20 行）

pi `additionalExtensionPaths`＋`-ne` 旁路（§1.7.5）：

```java
// 现 :2240 早退臂收紧为「-ne 且无显式路径」：
if (args.noExtensions() && args.extensions().isEmpty()) { <既有过滤钩早退，不变> }
...
var manager = new ExtensionManager(context);
if (!args.noExtensions()) {
    manager.loadAll();
    for (var jar : ExtensionPackageManager.global().installedJars()) manager.loadJar(jar);
    for (var jar : ExtensionPackageManager.project().installedJars()) manager.loadJar(jar);
}
// pi resource-loader.ts:578/690：显式路径恒加载（-ne 旁路）；
// resolveCliPaths（main.ts:728）对位＝cwd 相对 ⇒ 绝对规范化。
for (var explicit : args.extensions()) {
    manager.loadJar(Path.of(explicit).toAbsolutePath().normalize());
}
<桥/ firer 装配照旧——-ne + -e 时扩展事件面活着（pi 同：resource loader 臂独立）>
```

- 窄差 D78：java `-e` 只收 **JAR 路径**；pi 另收 `builtin:<name>`（java 内置扩展恒经 ServiceLoader，无按名点装面）。
- `loadJar` 失败行为与既有 global/project 臂一致（现状是抛——保持，不再加诊断层）。
- `HelpText:54` 文案去「Phase 6」改 pi 形 `Load an extension JAR (repeatable; explicit paths still load with -ne)`。

### T16：`--use-theme`（ArgsParser/Args/HelpText/PiTuiLauncher.themeFrom，+~25 行）

- `@Option(names = "--use-theme") String useTheme;` → `Args` 加末位前组件 `useTheme`（`null`＝缺席）。
- toArgs 校验：值以 `-` 起头 ⇒ error 诊断 `"--use-theme requires a theme name"`（pi :204-205 逐字；
  picocli 对缺值本身报 ParameterException——诊断文案统一走此句，夹具钉两臂）。
- 消费（`PiTuiLauncher.themeFrom:170-175`）：

```java
var cli = args.useTheme() != null ? args.useTheme()
    : (args.themes() != null && !args.themes().isEmpty() ? args.themes().get(0) : null);
```

（`--theme` 的「选择」现状不动——A-29 重排批 20，§4。）
- 选择器主题：T11 SPI 签名已带 `useThemeOverride`（pi :681-683 先覆盖 settings 再进选择器的对位）。

### T17：核销＋裁决（零代码，map/台账翻新）

- **A-17 核销**：消费链实证 `AgentSession.java:374`（`ModelScoping.resolve` 吃 `args.models()`）→ `:677 setScopedModels` → `PiTuiApp.java:665 cycleModelForward` → `ScopedModelCycler`；初始模型 `AgentSession.java:423-428`（pi :499-516 对位注释在码）。翻 缺失⇒对齐，证据重写。
- **A-40 裁决④**（下）：offline 面＝java 已实现的启动网络操作全集 ⇒ 翻 存疑⇒对齐。

---

## §4 不做项与重排（docs/20 §3 联动）

| 项 | 决定 | 去向 |
|---|---|---|
| A-29 `--theme`（JSON 主题名/文件加载） | pi 语义依赖 JSON 主题系统（E4-4/B163，2,417 行域）——现在做只能是空壳 | 重排**批 20**（docs/20 §3 序 20「`theme` 键随此」同域），行证据改注 |
| A-43 扩展注册 flag（`registerFlag`/`getFlag`＋help 面＋extensionFlagValues 注入） | 是扩展 API 面（E5）工作，不是 CLI 工作；`parsedUnmatched` 已留料 | 重排**批 15**（docs/20 §3 序 15） |
| W15（web 会话列表 onProgress API，w1） | repo 层能力已在（B55/T4），**web 协议面暴露**是 web 模块工作 | 不动，留 web 批 |
| B78（FuzzyMatcher 子串⇒pi 子序列，SelectList 消费） | 本批新 port `FuzzyMatch` 供选择器；SelectList 换引擎牵动补全面 | 不动，留在册 |
| rename 面板/交互选择器（pi interactive-mode.ts:5710） | 生产者是会话内 `/resume` 面（map/05 S10/S11 族） | 窄差 D72 留白，随交互选择器批 |
| `onExit` 死钩 | pi :302 赋值无调用点（批 8「死钩删除」先例） | 不移植，不登记 |

---

## §5 窄差登记（接续 D68；台账格式【场景/决定/风险】随闭环入 docs/04）

| # | 条目 | 风险 |
|---|---|---|
| D69 | 删除会话只有 unlink 臂（pi 先试 `trash` CLI；java 无回收站通道）⇒ "Session moved to trash" 文案不可达，恒 "Session deleted" | 边缘场景 [用户可见] |
| D70 | `-r` 的选择器经 ServiceLoader SPI 提供：pi-java-tui 不在 classpath 时报 `--resume requires the pi-java-tui module on the classpath`（pi 恒打包 TUI） | 边缘场景 [用户可见] |
| D71 | 参数校验诊断出口沿用 java 既有 `error: <msg>`＋exit 2（pi 是 `Error: <msg>`＋exit 1） | [用户可见]文案差异 |
| D72 | rename 面板 UI 不移植（picker 臂 pi 本就无生产者；回调/键臂结构与 pi 同形留白） | 无影响 [内部] |
| D73 | `tui.select.*`/`tui.input.tab` 直配 KeyEvent helper 不入键位表（批 10 先例；用户自定义键位面落地前不可观察） | 无影响 [内部] |
| D74 | sqlite 后端选择器列表退化（无富扫描——pi 无 sqlite 对位，java 自有后端面）：名字/首条消息/全文/计数缺省 | 边缘场景 [内部] |
| D75 | `re:` 非法正则的错误消息文本是 `PatternSyntaxException` 形（pi 是 V8 形）；行为同（error ⇒ 空表） | [用户可见]文案差异 |
| D76 | ctrl+backspace（deleteNoninvasive）在不上报该组合的终端是死键（pi 注释同病，:583——ctrl+d 正是为此而设） | 边缘场景 [内部] |
| D77 | `--export` 默认输出名 `pi-java-session-<base>.html`（pi `pi-session-*`；B213 品牌族） | [用户可见]文案差异 |
| D78 | `-e` 只收 JAR 路径；pi 另收 `builtin:<name>`（java 内置扩展恒 ServiceLoader，无按名点装面） | 边缘场景 [用户可见] |

---

## §6 裁决

> 拍板状态（2026-10-23）：**①–⑦ 全部拍板＝按本稿对齐 pi**（①②④ 先批，③⑤⑥⑦ 同批核准）。

**裁决①：选择器触发缝＝coding-agent SPI＋TUI ServiceLoader 注册（非仅 interactive 模式内联）。**【已拍板 2026-10-23】
- 【场景】pi 的 `createSessionManager` 对所有 mode 生效（print/rpc 敲 `-r` 也弹选择器）；java 的解析在 `AgentSession.create` 深处，TUI 模块依赖 coding-agent（反向依赖不可）。
- 【决定】SPI 接口 `SessionSelectorProvider` 放 coding-agent `spi` 包（与 `TuiEntryPoint` 同包同手法）；`SessionPersistence.resolvePersistent` 的 resume 臂 `ServiceLoader.load` 调起；tui 模块注册实现。print/json/rpc/interactive 四模式自动同获。
- 【风险/代价】SPI 缺席（裁 tui 的精简部署）报干净错误（D70）。[内部，模型不可见]

**裁决②：会话解析错误面统一为 `SessionResolutionExit`＋Main 收口（废栈 trace）。**【已拍板 2026-10-23】
- 【场景】pi 所有解析失败是 `console.error("Error: <msg>") + exit 1`、用户放弃是 `console.log + exit 0`；java 现状是 `IllegalStateException` 栈 trace 冒顶。
- 【决定】单异常类型携带 exit code 与「是否带 Error: 前缀」位；`Main.run` 最外层 catch 打印＋返回码。各 mode 不需要各自收口（TUI runner 尚未启动，无 alt-screen 现场）。
- 【风险/代价】既有夹具若断言旧抛文（`Session not found:`/`No previous session to continue`）随批改钉新文案。[用户可见]

**裁决③：`-c` 空臂按 pi 静默新建（B255 即登即销）。**【已拍板 2026-10-23】
- 【场景】本批取证发现 pi `continueRecent` 无会话时 `new SessionManager(..., undefined, true)`（:1800）静默新建；java CLI/内存两臂抛 `"No previous session to continue"`（web 臂早已是新建——三臂不自洽）。
- 【决定】两臂改 orElseGet 新建；B255 登记 docs/04 并同批销号；A-4 判定不动（行本就是「对齐」，此修使其名实相符）。
- 【风险/代价】用户可见行为变化：`-c` 空库从报错变新会话（向 pi 靠）。[用户可见]

**裁决④：`--offline` 判对齐（面＝java 已实现操作全集）。**【已拍板 2026-10-23】
- 【场景】map A-40 存疑：pi 的 offline 面（目录刷新/包更新/安装遥测/version-check/bug-report）比 java 广。
- 【决定】java 的启动网络操作**全集**＝模型目录刷新，已被 `ModelCatalogRefresh.offlineMode:77` 门控；pi 的其余 PI_OFFLINE 消费点在 java **无对应子系统**（version-check/self-update/install-telemetry/package-update-check 均不存在）。按「批 12 裁决③ pi 零消费⇒声明面即全量」同族口径翻对齐；裁决入 docs/04 §4（触发条件：上述子系统任一落地时必须同步接 offline 门）。
- 【风险/代价】未来新增启动网络操作时漏接门——由 docs/04 裁决的触发条件兜底。[内部，模型不可见]

**裁决⑤：web 路径 `--no-session` 赢（B54 销号）。**【已拍板 2026-10-23】
- 【场景】B54：`--mode web` 恒持久化，与 `--no-session` 冲突，谁赢未定义。
- 【决定】`--no-session` 赢（ephemeral）——与 CLI 臂（`SessionSetup.resolveSession:122`）同语义，且 ephemeral 是更安全一侧（不落盘的一方可逆，反之不可）。
- 【风险/代价】web 刷新/重连不再恢复历史（正是 --no-session 的本义）。[用户可见]

**裁决⑥：`tui.select.*` 不入键位表，沿批 10 KeyEvent-helper 先例。**【已拍板 2026-10-23】
- 【场景】选择器导航/定案/取消键（up/down/pageUp/pageDown/enter/esc/ctrl+c/tab）pi 走 `kb.matches("tui.select.*")`。
- 【决定】java 直配 `KeyEvent` helper（批 10 `UserMessageSelectorScreen` 同手法）；`app.session.*` 六个才入 `KeybindingsManager` overlay 表（它们与全局动作撞名 ctrl+p/ctrl+d，需要上下文分表——批 9 C1 机制）。用户键位自定义（keybindings.json）落地前两口径不可观察地区分。
- 【风险/代价】自定义键位面落地时 `tui.select.*` 需回填表（触发条件入 D73）。[内部，模型不可见]

**裁决⑦：rename/当前会话守卫按 pi picker 语境留白（结构同形、UI 不移植）。**【已拍板 2026-10-23】
- 【场景】pi `SessionSelectorComponent` 的 rename 面板与当前会话高亮/删除守卫在 picker 用法下不可达（picker 不传 `renameSession`/`currentSessionFilePath`）。
- 【决定】java 组件保留 `renameCallback`（null ⇒ 键臂 no-op）与 `currentSessionPath`（恒 null）的同形字段；rename 面板 UI（pi :885-939 ~55 行）不移植，随交互选择器批（map/05 S11 族）收编——S11 因此只翻存疑。
- 【风险/代价】无（picker 行为与 pi picker 逐字节一致）。[内部，模型不可见]

---

## §7 测试计划

| # | 夹具（新文件） | 断言面 |
|---|---|---|
| 1 | `FuzzyMatchTest` | §1.4 行为：子序列/断命中/词界奖/连击递增/间隙罚/全等 −100/互换臂 +5/空 query/长 query 短 text |
| 2 | `SessionSelectorSearchTest` | pi 蓝本对照：空查询全中；`re:` 三臂（命中 score=idx*0.1/非法 error⇒空表/空模式）；引号 phrase；未闭合回落；多词全中方中；relevance 平分 modified 降序；recent 保序；named 过滤；搜索文本含 id/name/全文/cwd |
| 3 | `SessionTreeBuilderTest` | 认父（canonical）/孤儿⇒根/latestActivity 子树最大/降序/flatten 的 ancestorContinues（depth>0 规则）与 isLast/前缀 `│  ` `└─ ` `├─ `/formatSessionDate 七档/shortenPath home |
| 4 | `SessionListModelTest` | filterSessions  threaded 无查询⇒树、有查询⇒平表；selection clamp；setSessions 未摸⇒0、摸过⇒按 path 找回；删除确认态转移 |
| 5 | `SessionListRendererTest` | 行公式：光标 2 列/颜色优先（确认删除>当前>有名>默认）/右侧三段（count age、cwd、path 前缀）/可视窗居中公式/滚动指示出现条件/空表四文案 |
| 6 | `SessionSelectorHeaderTest` | 标题两态/scopeText 三态（Loading n/total 与 ...）/hint 三态（删除确认单行 error/状态消息/常态两行）/setLoading 清进度/自动隐 deadline |
| 7 | `SessionSelectorComponentTest` | 键分派全序（确认态拦截→Tab→五 toggle→导航→forward）；loadScope isActive 弃旧；toggleScope 懒载；删除流（confirm→delete→status 2s→refresh）；失败 status 4s；rename null no-op |
| 8 | `KeybindingsManagerTest`（追加） | 六 selector 键在 overlay 表、resolve(ctrl+p/ctrl+d) 全局不染；四空绑定注册且 resolve 不命中；ctrl+backspace stroke |
| 9 | `SessionSelectorProviderWireTest` | SPI 发现（tui 在 classpath）；picker e2e 形：选中⇒path、Esc⇒empty |
| 10 | `SessionPersistenceResolutionTest`（扩） | pi 全序：`--session` 赢 `-c`/`-r`；`-r` 选中⇒openPath attach；`-r` 取消⇒exit 0 "No session selected"；SPI 缺席⇒D70 错文；`--session` 全局臂 y/n/EOF 三结局；`--session-id` warn＋createWithId（id 落 metadata）；`--fork` 查重 exit 1；`-c` 空臂静默新建（B255 双臂）；not_found 文案逐字 |
| 11 | `ArgsParserTest`（追加） | `--` 三夹具（§3 T12）；`--use-theme` 缺值/`-` 起头两诊断；fork/session-id 冲突诊断五组合＋格式断言三反例；冲突诊断先于格式断言 |
| 12 | `MainExportTest` | 位置输出参生效；默认名 `pi-java-session-<base>.html`；文案 `Exported to: `；失败 `Error: ` exit 1 |
| 13 | `ExtensionCliPathsTest` | `-e` jar 加载（临时 jar 夹具）；`-ne -e` 显式仍载；`-ne` 裸早退不变 |
| 14 | `WebNoSessionTest`（web 模块） | `--no-session` ⇒ 无持久仓写入（ephemeral） |
| 15 | `RichSessionInfoParentTest`（agent-core） | header `parentSession` 进 record；缺席 ⇒ null |

验证口径（沿批 13 教训）：`mvn test -pl <module> -am`（无 -am 拾 .m2 旧 jar NCDFE 已两波）；
收口 `mvn clean verify` 全量（spotbugs 在 verify 段）；verify 日志别放 target/。

## §8 对账表（判定列：前态→后态）

| map 行 | 前 | 后 | 手段 |
|---|---|---|---|
| map/04 A-5 | 存疑 | 对齐 | T11 |
| A-12 | 存疑 | 对齐 | T14（B54 销号） |
| A-13 | 存疑 | 对齐 | T6/T7 |
| A-14 | 存疑 | 对齐 | T6/T8 |
| A-15 | 存疑 | 对齐 | T6/T9 |
| A-17 | 缺失 | 对齐 | T17 核销 |
| A-24 | 存疑 | 对齐 | T13 |
| A-25 | 缺失 | 对齐 | T15 |
| A-29 | 存疑 | 存疑（证据改注重排批 20） | §4 |
| A-30 | 缺失 | 对齐 | T16 |
| A-40 | 存疑 | 对齐 | 裁决④ |
| A-41 | 存疑 | 对齐 | T12 |
| A-43 | 缺失 | 缺失（重排批 15） | §4 |
| A 域汇总 | 50.0/62（80.6%） | 60.5/62（97.6%） | — |
| E3-3 | 缺失 | 对齐 | T10/T11 |
| E3-4 | 存疑 | 对齐 | T6/T7 |
| E3-5 | 存疑 | 对齐 | T8 |
| E3-6 | 存疑 | 对齐 | T6/T9 |
| E3-9 | 存疑 | 对齐 | T4 |
| **E3-19**（闭环补列） | 存疑 | 对齐 | T14（B54 销号——对账表漏列同翻，批 10 E3-8 先例） |
| **E3-24**（闭环补列） | 缺失 | 对齐 | 核销（批 13 漏列：cache-warmer 本体已落） |
| E3 域汇总 | 28.5/40（71.3%） | **37.0/40（92.5%）** | 设计 6.0＋补列 2.5 |
| map/05 K16 | 缺失 | 对齐 | T3 |
| **map/05 K15**（闭环补列） | 缺失 | 对齐 | T3/T10（命名过滤键注册并消费——漏列同翻） |
| map/05 S11 | 缺失 | 存疑 | T10（rename 留白，裁决⑦） |
| 模块（Σ485） | 315.0（64.9%） | **334.0（68.9%）** | +19.0 |
| tui/web（Σ245） | 113.0（46.1%） | **116.5（47.6%）** | +3.5 |
| 全仓（Σ1379） | 989.0（71.7%） | **1011.5（73.3%）** | +22.5 |

台账联动：B54 销号；**B255 新登即销**（`-c` 空臂，裁决③）；D69–D78 新登；
docs/04 §4 新增裁决④（offline）⑤（web no-session）⑥⑦（选录）。

## §9 闭环回填（2026-10-23 闭环实录）

1. **docs/04**：B54 出 F 类拍板销号；B255 §3.1 即登即销；批 14 段入 §4（裁决①–⑦
   全录＋D69–D78＋账面＋行为变化＋两修真＋前存红登记）；E1 更新 2,341。
2. **docs/05 + map/04 + map/05**：§8 对账表逐行翻新（A 域 11 行＋E3 域 7 行＋
   K15/K16/S11）；map/04 未加权汇总表逐行实算收平（E2 对齐 44→46 同步批 13 台账、
   分母 290→288）；A 域 97.6%、E3 域 92.5%、tui/web 47.6%。
3. **docs/20**：§2 追加批 14 行；§3 序 13 摘行、A-29 注记入批 20、A-43 注记入批 15、
   B54 核销注记入批 21；§1 四项读数重填（已闭环 14、模块 334.0/485 ≈ 68.9%、
   全仓 1,011.5/1379 ≈ 73.3%、总批次估计不变 17–21）。
4. **本稿 §0/§8**：按闭环实算回填（E3-19/E3-24/K15 补列；两回归修＋auth 修真＋
   前存红登记）。
5. 下一包号权威记忆同步（**docs/26**）。

### 闭环验证记录

- 模块全量：`mvn test -pl pi-java-agent-core,pi-java-coding-agent,pi-java-tui -am`
  经两轮修复后 **coding-agent 979/979 绿**（首轮抓回：/hotkeys 空绑定 NPE、
  AgentSessionTest -c 旧断言；ModelsJsonOverrideWireTest×2 套件性红→根因修真后绿）。
- 收口 `mvn clean verify`：checkstyle/spotbugs 全模块过；测试全绿**除**
  `TreeNavigationFlowTest.queuedMessagesRestoreToEditorBeforeAbort`——基线
  291e002d（批 13 闭环点）同点复跑同红 ⇒ **前存确定性红，非本批回归**
  （D41 队列恢复域，已登记 docs/04 批 14 段，留待下批取证）。
- 基线对照：批 13 闭环点 worktree 复跑 coding-agent 全量仅
  ModelsJsonOverrideWireTest 红（同签名）⇒ 本批仅两处真回归（均已修）。
- 教训归档：无 -am 拾 .m2 旧 jar NCDFE 本批又撞两次（规约再验证）；
  后台重定向日志目录须先存在（`verify-logs/` 首跑空跑一波）。

### commit 序（每步可独立编译；`git add <path>` 显式路径）

1. `feat(tui): batch-14 T1 — FuzzyMatch port（pi fuzzy.ts 子序列打分逐字）`
2. `feat(tui): batch-14 T2 — SessionSelectorSearch（re:/引号/未闭合回落/双排序逐字）`
3. `feat(coding-agent): batch-14 T3 — 键位表：selector 六键 overlay＋app.session 四空绑定（K16）`
4. `feat(agent-core): batch-14 T5 — RichSessionInfo.parentSessionPath（threaded 树认父）`
5. `feat(coding-agent): batch-14 T4 — RepositoryHandle 富列表/findLocalById/createWithId/findWithScope＋sqlite 臂拆文件（E3-9）`
6. `feat(coding-agent): batch-14 T6–T9 — 会话解析 pi 全序＋校验两臂＋SessionResolutionExit＋-c 空臂新建（B255）＋全局臂/session-id/fork 查重（T11 SPI 骨架先落）`
7. `feat(tui): batch-14 T10 — 会话选择器本体（component/session/ 七件，pi 1,294 行域对位）`
8. `feat(tui): batch-14 T11a — SessionSelectorRunner 启动壳＋SPI 实现注册`
9. `feat(coding-agent): batch-14 T11b — -r 接线（resume 臂 SPI 调起＋No session selected 臂，A-5/E3-3）`
10. `feat(coding-agent): batch-14 T12/T13/T16 — -- 预分流＋--export 位置输出＋--use-theme（A-41/A-24/A-30）`
11. `feat(coding-agent): batch-14 T14/T15 — web --no-session 臂（B54）＋-e 显式 JAR（A-25）`
12. `docs(coding-agent): batch-14 closure — 台账/map/docs20 三处回填`
