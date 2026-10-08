# 包 40：上下文文件发现 —— git worktree 影子去重

> **状态：✅ 已闭环（2026-10-08）。**
> 承接上一轮：我一度判「上下文文件发现整块缺失（P0）」，实测后**收窄**——
> 发现器（`ContextFileDiscovery.java`）与 harness 接线（`AgentSession.java:417-422`）
> **都已落**，`--no-context-files` 也已消费。唯一真缺口是本包内容。

---

## 1. 缺口是什么

pi 在祖先遍历时，会**跳过一个特定文件**：当 `cwd` 是一个**嵌套在主仓库工作树内的 linked worktree**
（`git worktree add` 出来的），主仓库根的 `AGENTS.md`/`CLAUDE.md` 与 worktree 自己的那份
**占据同一个逻辑仓库作用域**。若两份都加载，同一份上下文被应用**两次**。

pi 的承重实现是两个函数（`resource-loader.ts`）：

| 函数 | 行数 | 职责 |
|---|---|---|
| `findGitPaths(cwd)`（实现在 `footer-data-provider.ts:16-48`） | ~33 | 向上找 `.git`，解析 worktree 的 `gitdir:` 与 `commondir` |
| `findShadowedContextFile(cwd)`（`resource-loader.ts:205-227`） | ~23 | 判定「主仓库根的上下文文件」是否该被影子化 |

Java 的 `ContextFileDiscovery.discover` **两者都没有** ⇒ 在该场景下祖先链里的主仓库文件被照常加载。

⚠️ **触发条件很窄**（必须是「linked worktree 物理嵌套在主工作树子目录内」），
普通仓库、兄弟 worktree（`git worktree add ../feat`）、bare 布局都**不触发**。
但命中时是**静默的双重上下文** —— 模型无感知，属于「窄但错」的一类。

---

## 2. 裁决

### 2.1 落点：`ContextFileDiscovery` 内补 shadow 判定（推荐）

发现器已在 `pi-java-agent-core/.../prompt/`，shadow 逻辑是它**祖先循环的一部分**
（pi 也是在同一个 `while` 里判 `isShadowed`），不另起类。
对外签名 `discover(Path cwd, Path agentDir)` **不变**，仅内部行为补齐。

### 2.2 不引入 git 进程调用

pi 的 `findGitPaths` **不 spawn git**，只读 `.git` 文件 / 目录（`footer-data-provider.ts:16-48`）。
Java 侧照做：纯文件解析，不依赖 `git` 是否在 PATH 上。
（pi 另有 `spawnSync("git", …)` 那是**分支名**用的，与本缺口无关，不移植。）

### 2.3 边界逐字对齐 pi（四条判定，缺一即错）

`findShadowedContextFile` 返回「被影子化文件的 realpath」或 `undefined`：

1. **`findGitPaths(cwd)` 为 null ⇒ undefined。** 不在任何 git 仓库内。
2. **`worktreeRoot` 必须以 `mainRepoRoot + sep` 开头**（`resource-loader.ts:221`）。
   - `mainRepoRoot = dirname(commonGitDir)`；
   - 普通仓库二者同目录 ⇒ 不满足；
   - 兄弟 worktree（`../feat`）⇒ main 不是祖先 ⇒ 不满足。
3. **`realpath(join(mainRepoRoot, ".git"))` 必须等于 `commonGitDir`**（`:224`）。
   - bare 布局（`proj/.bare` ＋ `proj/main`）：`dirname(commonGitDir)` 只是装 `.bare` 的目录，
     其下没有被检出的 `.git` ⇒ 不满足；
   - submodule：gitdir 落在 `.git/modules/...`，无 `commondir` 指回 ⇒ 不满足。
4. **主仓库根确实有上下文文件**（`loadContextFileFromDir(mainRepoRoot)`），
   返回 `join(mainRepoRoot, basename(命中文件))`；没有 ⇒ undefined。

### 2.4 `findGitPaths` 的解析规则（`footer-data-provider.ts:16-48`）

从 `cwd` 向上逐级找 `.git`：

| `.git` 形态 | 解析 |
|---|---|
| **文件**，内容以 `gitdir: ` 开头（linked worktree） | `gitDir = resolve(当前目录, 内容.slice(8).trim())`；`gitDir/HEAD` 不存在 ⇒ null；读 `gitDir/commondir`，**存在**则 `commonGitDir = resolve(gitDir, commondir 内容.trim())`，**不存在**则 `commonGitDir = gitDir`；返回 `{repoDir: 当前目录, commonGitDir, headPath}` |
| **目录**（普通仓库） | `gitPath/HEAD` 不存在 ⇒ null；返回 `{repoDir: 当前目录, commonGitDir: gitPath, headPath}` |
| 其它（既非文件也非目录）/ 读取异常 | 继续向上 / 返回 null |

⚠️ 两个细节：
- `.git` **文件但内容不以 `gitdir: ` 开头** ⇒ 落到 `stat` 分支末尾，**继续向上**（pi 无 else）；
- 所有 `resolve` 都是相对**含 `.git` 的那个目录**解析，不是相对 cwd。

---

## 3. Java 侧改动设计

### 3.1 `ContextFileDiscovery` 增补

祖先循环改为（对照 pi `resource-loader.ts:235-264`）：

```java
public static List<ContextFile> discover(Path cwd, Path agentDir) {
    ... global 段不变 ...
    var shadowed = findShadowedContextFile(cwd);          // 新增：Path 或 null
    var current = cwd;
    while (true) {
        var file = loadFromDir(current).orElse(null);
        var isShadowed = file != null && shadowed != null
                && canonical(Path.of(file.path())).equals(shadowed);   // realpath 比较
        if (file != null && !isShadowed && seen.add(canonical(Path.of(file.path())))) {
            ancestors.add(0, file);
        }
        var parent = current.getParent();
        if (parent == null) break;
        current = parent;
    }
    ...
}
```

⚠️ **比较必须走 canonical（realpath）**：`findShadowedContextFile` 返回 realpath，
而祖先循环拿到的 `file.path()` 可能仍含 symlink（pi 的注释明写，`:203-204`）。
`canonical(...)` helper 已存在（`ContextFileDiscovery.java:98-104`），直接复用。

### 3.2 新增两个 private static

| 方法 | 对应 pi | 返回 |
|---|---|---|
| `findGitPaths(Path cwd)` | `footer-data-provider.ts:16-48` | record `GitPaths(Path repoDir, Path commonGitDir, Path headPath)` 或 `null` |
| `findShadowedContextFile(Path cwd)` | `resource-loader.ts:205-227` | 被影子化文件的 canonical 路径，或 `null` |

`findShadowedContextFile` 内部：

```java
var gitPaths = findGitPaths(cwd);
if (gitPaths == null) return null;
var commonGitDir = gitPaths.commonGitDir().toRealPath();
var worktreeRoot = gitPaths.repoDir().toRealPath();
var mainRepoRoot = commonGitDir.getParent();
if (!worktreeRoot.startsWith(mainRepoRoot.resolve("x").getParent())) return null;  // ① startsWith(mainRepoRoot + sep)
if (!mainRepoRoot.resolve(".git").toRealPath().equals(commonGitDir)) return null;  // ②
var mainContext = loadFromDir(mainRepoRoot).orElse(null);                           // ③
return mainContext == null ? null : canonical(Path.of(mainContext.path()));
```

⚠️ **① 的 Java 写法**：pi 是字符串 `startsWith(mainRepoRoot + sep)`，
Java `Path.startsWith` 是**路径段**匹配、且**不处理 `..`/`.`**。
两边都先 `toRealPath()`（已归一），再用
`!worktreeRoot.equals(mainRepoRoot) && worktreeRoot.startsWith(mainRepoRoot)`
—— realpath 后无 `.`/`..`，段匹配即等价于「main 是真祖先」（排除相等）。
`sep` 由 `Path.startsWith` 的段语义隐含。**此点是最容易写错的一处**，夹具 §4 专门覆盖。

⚠️ **`Path.resolve` 的绝对路径陷阱**：pi 的 `resolve(dir, 相对gitdir)` 在 `gitdir` 是绝对路径时
（commondir 常写成绝对路径）**直接采用该绝对路径**。Java 的 `Path.resolve` 同义，
但读取 `commondir` 内容后要先 `Path.of(内容.trim())`，`resolve` 会自动处理绝对/相对。

---

## 4. 文件清单与测试

**改动**：`ContextFileDiscovery.java`（+约 70 行，仍远低于 500）。
无新文件、无对外签名变更、无 pom 变更。

**夹具**：`ContextFileDiscoveryTest` 增补 —— 全部用真实目录布局（`git worktree` 的形状可以纯手搓，
不需要 git 参与）：

| 新夹具 | 构造 | 断言 |
|---|---|---|
| `linkedWorktreeNestedInMain_shadowed` | `main/.git`(dir，含 HEAD)、`main/AGENTS.md`、`main/feat/.git`(file 内容 `gitdir: <abs>/.git/worktrees/feat`)、`<gitdir>/HEAD`、`<gitdir>/commondir` 内容指向 `main/.git`、`main/feat/AGENTS.md` | 结果**只含 feat 的**（"feat/AGENTS.md"），**不含 main 的** |
| `ordinaryRepo_mainFileNotShadowed` | 普通 `.git` 目录 ＋ 根 AGENTS.md ＋ 子目录 AGENTS.md | 两份都在（farthest→nearest），无丢失 |
| `siblingWorktree_notNested` | worktree 的 `repoDir` 不在 main 子树（用 `../feat` 布局） | main 的文件**不被**影子化（与该 worktree 无关）⇒ 仍按普通祖先链 |
| `noCommondir_commonIsGitDir` | worktree 的 gitdir 下**不放 commondir** | `commonGitDir == gitDir` ⇒ `mainRepoRoot=dirname(gitDir)`，布局不等价主仓库 ⇒ 不影子化 |
| `bareLayout_notCheckedOut` | `proj/.bare`(commonGitDir) ＋ `proj/main/.git` 文件指回 `.bare` | `dirname(.bare)` 下无 `.git` ⇒ 不影子化 |
| `mainRepoWithoutContextFile_nothingShadowed` | worktree 布局成立但 main 根**无** AGENTS.md | 返回空 ⇒ 不跳过任何东西 |
| `gitdirWithoutHead_returnsNull` | worktree 的 gitdir 下不放 HEAD | `findGitPaths` = null ⇒ 不影子化 |
| `dotGitFileWithoutPrefix_ignored` | `.git` 是文件但内容无 `gitdir: ` | 继续向上，最终 null，不抛 |

---

## 5. 变异探针计划（8 个）

| # | 变异 | 预期红 |
|---|---|---|
| M1 | shadow 比较不走 canonical（直接比 path 字符串） | 1（symlink 场景） |
| M2 | `startsWith(mainRepoRoot)` 漏掉「排除相等」 | 1（普通仓库被误影子化） |
| M3 | `commondir` 缺失时仍读（不回落 `commonGitDir = gitDir`） | 1 |
| M4 | 第②条 `.git` 检查删掉（bare 被误判） | 1 |
| M5 | `findGitPaths` 向上循环提前 break（不继续找父级） | 1 |
| M6 | `gitdir: ` 前缀匹配删掉 | 1 |
| M7 | shadow 返回的是 main 文件**名字**而非 full canonical 路径 | 1 |
| M8 | 祖先循环里 shadowed 文件仍加入（`!isShadowed` 删掉） | 1 |

---

## 6. 台账影响

| # | 条目 |
|---|---|
| B222 | **（本包闭环后）** linked-worktree 影子去重已对齐；此前该窄场景会静默双重加载上下文 |
| —— | 不新增长期开放项；`A14`（shell 集成夹具）与本包无关，仍在册 |

回填 `docs/map/04`：E4-5「上下文文件发现」原标 **缺失**，本包后该单元的**发现语义全齐**
（接线早前已落）⇒ 判定可由「缺失」进为「对齐」—— 权重 3，会动模块汇总，闭环时重算。

---

## 7. 实施记录

**提交**：`ContextFileDiscovery.java`（+105，`findGitPaths` / `findShadowedContextFile` / `GitPaths`）
＋ `ContextFileDiscoveryTest.java`（+262，11 条新夹具）。

**测试**：`ContextFileDiscoveryTest` 13 → **24**（11 新）；`pi-java-agent-core` 641 → **652**。
checkstyle 本模块 **0 警告**；`mvn test checkstyle:check -pl pi-java-agent-core` exit 0。

### 7.1 设计稿覆盖不了的一处

**§3.2 第③条写错了。** 设计稿写「`loadContextFileFromDir(mainRepoRoot)` 返回 ⇒
`join(mainRepoRoot, basename(命中文件))`」，即**在主仓库根重新找一遍**候选文件、
拿它的 basename 拼路径。pi 实际是（`resource-loader.ts:226`）：

```ts
const worktreeContextFile = loadContextFileFromDir(worktreeRoot);
return worktreeContextFile ? join(mainRepoRoot, basename(worktreeContextFile.path)) : undefined;
```

**basename 取自 worktree 自己的那份文件**，不是主仓库根命中的那份。差别在候选名不同时
显现：worktree 有 `AGENTS.override.md`、主仓库根有 `AGENTS.md` 时，pi 拼出的是
`mainRepoRoot/AGENTS.override.md`（该文件不存在）⇒ **不影子化任何东西，两份都加载**；
设计稿的写法会拼出 `mainRepoRoot/AGENTS.md`（存在）⇒ **误影子化主仓库的 `AGENTS.md`**。

按 pi 逐字改写，并新增夹具 `shadowFollowsTheWorktreeBasenameNotTheMainRoots` 钉死这条。
**两版实现都返回「一个路径」，但指向不同的文件** —— 这是设计稿「照语义重述、没抄逐字代码」的典型代价
（对照 `design-doc-needs-code` 那条教训）。

### 7.2 探针（8/8 有牙，其中 4 条靠**新夹具**才有牙）

| # | 变异 | 首轮 | 补夹具后 |
|---|---|---|---|
| M1 | shadow 比较不走 canonical | ⚪ 0 红（`@TempDir` 无 symlink 可解析） | ✅ 恰 1（新 `shadowComparisonSurvivesASymlinkedCwd`） |
| M2 | `startsWith(mainRepoRoot)` 漏掉「排除相等」 | ⚪ 0 红（原夹具**不建 `.git`**，走不到相等分支） | ✅ 恰 1（新 `ordinaryRepoRootItselfIsNotShadowed`） |
| M3 | `commondir` 缺失时仍读（不回落） | ✅ 2 红（首轮「1 红」是**编译失败**的假象，见 7.3） | ✅ 2 |
| M4 | 第②条 `.git` 检查删掉 | ✅ 恰 1 | ✅ 恰 1 |
| M5 | `findGitPaths` 向上循环提前 break | ⚪ 0 红（原夹具 cwd 就在 worktree 根，不走向上） | ✅ 恰 1（新 `worktreeRootFoundByWalkingUpFromADeepCwd`） |
| M6 | `gitdir: ` 前缀匹配删掉 | ⚪ 0 红（原夹具的内容**根本不是合法 gitdir 路径**） | ✅ 恰 1（改写夹具，内容改为合法路径但无前缀） |
| M7 | 返回 main 文件**名字**而非完整路径 | ✅ 2 红 | ✅ 2 |
| M8 | 祖先循环里 shadowed 文件仍加入 | ✅ 2 红（设计稿预测 1） | ✅ 2 |

⚠️ **8 条里 4 条（M1/M2/M5/M6）首轮零红** —— 全部成因是**夹具没牙**，不是代码对。
补夹具后 8/8 命中。

### 7.3 过程坑（三条，都值回登记）

**① 「零红」的又一成因：变异体没编译。** M3 第一次改的是
`if (false && Files.exists(commonDirFile))`，与已声明的 `Path commonGitDir;`
冲突 ⇒ **编译失败**；`mvn -q` 下 surefire 报告**没被覆盖**，我读到的是**上一次运行的旧报告**
（1 红），差点当成「M3 恰 1 红」。**变异后必须确认编译通过 + 报告时间戳更新**，
否则读到的是陈旧绿/红。

**② 「变异体语义等价」第 N 次。** M6 第一次只把 `if (content.startsWith("gitdir: "))`
改成 `if (true)`，**没动 `content.substring("gitdir: ".length())`** —— 于是对非法内容
仍会 `substring(8)` 切出垃圾路径、`resolve` 出不存在目录、`HEAD` 不存在 ⇒ 仍返回 null。
**变异点必须是「判据的实际执行体」，不是判据的外壳。** 正确写法是 `${if} + {substring}` 一起改。

**③ 变异探针必须是「反事实」而非「反语法」。** M1/M2/M5 首轮零红的根因一致：
**夹具的输入流根本没流过待变异的那段代码**。M1 要有 symlink、M2 要有真 `.git`、
M5 要 cwd 在 worktree 根**之下**。**判断夹具是否有牙的可操作标准**：
把变异体画成控制流图，**逐节点问「有哪条夹具路径经过这里」** —— 一个都没有 ⇒ 没牙。

### 7.4 一处**刻意偏差**

`canonical(Path)` 吞 `IOException`（既有 helper，`ContextFileDiscovery.java:106-112`），
而 pi 的 `canonicalizePath` 也是 catch 后回落原路径（`paths.ts:28-34`）——**两侧同形**。
但 §3.2 设计稿写的 `toRealPath()` 会**抛** `IOException`（路径不存在/权限不足），
需要外层 try/catch 兜住；改用 `canonical` 后 try/catch 变得**不可达**（编译器直接报错：
「不抛出 `IOException`」）⇒ 已删除。**行为等价且更贴近 pi**（pi 从不抛，只回落）。

### 7.5 台账

| # | 处置 |
|---|---|
| B222 | **销号**（本包闭环：linked-worktree 影子去重已对齐） |
| —— | 无新增开放项 |

