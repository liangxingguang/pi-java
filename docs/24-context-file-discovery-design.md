# 包 24：上下文文件发现（AGENTS.md / CLAUDE.md）（B64）

> **状态：✅ 已闭环（2026-10-07，R-A）。**
> 承接台账 **B64**：pi 会话启动时从 cwd 向上遍历发现项目指令文件、注入每轮
> 系统提示的 `project_context` 段；`--no-context-files`/`-nc` 关闭发现。
> 实施记录见 §4。

## 1. 问题

pi-java 的 SystemPromptOptions 已有 `contextFiles` 槽位（`project_context` 段的
渲染器也早已逐字移植），但**没有发现器** —— 没有任何代码往槽位里填文件，
CLI 的 `--no-context-files`/`-nc` 被 ArgsParser 解析后**零消费者**。
后果：每个项目放在 AGENTS.md/CLAUDE.md 里的指令在 pi-java 全部缺席，这是
每轮 prompt 都受影响的默认路径缺口。

## 2. pi 锚点事实（`resource-loader.ts`）

### 2.1 单目录候选（`:184-201`）

```ts
function loadContextFileFromDir(dir) {
  const candidates = ["AGENTS.override.md", "AGENTS.md", "AGENTS.MD",
    "CLAUDE.md", "CLAUDE.MD"];
  for (const filename of candidates) { … 存在且 isFile ⇒ {path, content} … }
}
```

- 候选**按序**取第一个命中（同目录有多份时只取一份，override 优先）
- 内容 `readFileSync(…, "utf-8")` 后 **stripBom**；非普通文件（目录）跳过
- 读失败只告警、按未命中处理

### 2.2 全局＋祖先遍历（`loadProjectContextFiles`，`:239-280`）

```
1. 全局：loadContextFileFromDir(<agentDir>) → contextFiles（最前）
2. 祖先：从 cwd 起逐级 dirname 直到根，每级 loadContextFileFromDir
   - 命中文件 unshift 进 ancestor 列表（⇒ 最终顺序：最远祖先 → cwd）
   - seenPaths 按规范化路径去重
3. contextFiles.push(...ancestorContextFiles)
```

最终顺序：**全局 ＜ 最远祖先 ＜ … ＜ cwd（最近）**。

**worktree shadow**（`:204-237`）：linked git worktree 自身的上下文文件会遮蔽
主仓库那份（同一逻辑仓库 scope，不重复施加）；这是 git 布局的窄角，**本包不移植**
（Java 侧无对应 git worktree 工作流；登记为残留，需要时再补）。

### 2.3 注入形状（`system-prompt.ts:72-79,164`）

```
project_context 段（有文件才出现）：
Project-specific instructions and guidelines:

<project_instructions path="<abs path>">
<content>
</project_instructions>
```

多份文件各自一个 `<project_instructions>` 块，以空行分隔。**渲染器 Java 侧已存在**
（SystemPrompts.renderProjectContext），本包只接发现器。

### 2.4 旗标

`--no-context-files` / `-nc`（args.ts:207-208,325）→ noContextFiles:true ⇒
跳过发现，contextFiles 为空。

## 3. 方案

### 3.1 新增发现器（agent-core，`session` 包附近）

```java
public final class ContextFileDiscovery {
    public static final List<String> CANDIDATES =
        List.of("AGENTS.override.md", "AGENTS.md", "AGENTS.MD",
                "CLAUDE.md", "CLAUDE.MD");

    /** 单目录：按 CANDIDATES 序取第一个普通文件。 */
    static Optional<SystemPromptOptions.ContextFile> loadFromDir(Path dir);

    /**
     * pi loadProjectContextFiles：
     Real 去重；null agentDir ⇒ 跳过全局。
     */
    public static List<SystemPromptOptions.ContextFile> discover(
        Path cwd, Path agentDir);
}
```

要点：

- 路径检查 `Files.isRegularFile`；读取 UTF-8、**去 BOM**（`﻿` 前缀）
- 祖先遍历：`Path parent = dir.getParent()`；`parent == null` 到根（不用
  filename 字符串比较，适配 Windows 盘符根）
- 去重键：`toRealPath`（失败退 toAbsolutePath）
- 读失败（IO）按 pi 形状：warn、跳过，不抛

### 3.2 接线（coding-agent）

- `ContextAssembler.promptOptions`：builder 上补
  `.contextFiles(...)`；取值：

```java
boolean noContext = /* args.noContextFiles() */;
var files = noContext ? List.<ContextFile>of()
    : ContextFileDiscovery.discover(
        Path.of(System.getProperty("user.dir")),
        FileSettingsStorage.defaultAgentDir());
```

- Args 记录已有 `noContextFiles` 组件 —— 需要把它从装配点传到 ContextAssembler：
  经 ExecutionContext（新增一个 Supplier/定值槽）或在 AgentSession.assemble
  调用点预先 discover 后注入。选型：**ExecutionContext 加一个
  `contextFiles()` 供应商槽**（与 systemPrompt 同款晚读口），默认 List.of()，
  AgentSession.assemble 填实现 —— 保持 ContextAssembler 不依赖 CLI Args。
- agentDir 与 SkillDiscovery 用同一个 `FileSettingsStorage.defaultAgentDir()`。

### 3.3 不做

- worktree shadow（2.2）：Java 无此工作流
- pi 的全局 agentDir 是 `~/.pi` 语义；Java 侧用本仓既有的
  `FileSettingsStorage.defaultAgentDir()`（~/.pi-java 布局）—— 刻意，不另造

## 4. 实施记录（2026-10-07）

**落地内容**：

- `ContextFileDiscovery`（agent-core，prompt 包）：CANDIDATES 常量、
  loadFromDir、discover（全局＋祖先遍历、realpath 去重、BOM 剥离、IO 容错）
- `HarnessConfig`/`ExecutionContext` 加 `contextFiles` 晚读槽（默认空）；
  `ContextAssembler.promptOptions` 接入 builder 并把 contextFiles 纳入
  「全空 ⇒ 无提示」守卫
- `AgentSession.assemble` 填发现器：`args.noContextFiles()` ⇒ 空，
  否则按 cwd＋`FileSettingsStorage.defaultAgentDir()` 发现

**测试（13 条全绿）**：

- `ContextFileDiscoveryTest` ×11：候选优先级、大小写名（Windows 大小写不敏感
  路径兼容）、目录跳过、BOM、祖先顺序、全局前置、symlink 去重、不可读跳过等
- `ContextAssemblerContextFilesTest` ×2：文件流入 options/project_context 渲染、
  空 supplier ⇒ 无提示

**变异探针（3/3 红）**：

| 探针 | 红数 |
|---|---|
| 候选顺序改 CLAUDE 优先 | 2 |
| ancestors 改 add（破坏 farthest→nearest 顺序） | 1 |
| assembler builder 忽略 contextFiles | 1 |

**已知残留**：`AgentSession.assemble` 里 `-nc` 分支的 lambda 本身无 AgentSession
级夹具（FauxProvider 看不见 system prompt），行为由 discovery/assembler 两层
测试间接保证；worktree shadow 未移植（§3.3）。
