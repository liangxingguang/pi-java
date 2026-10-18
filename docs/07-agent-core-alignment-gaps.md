# 07 agent-core 层未对齐清单

> 【背景摘要】本文档只回答一个问题：**pi-java-agent-core 层还有什么要对齐**。一行一条；已闭环的不写（历史在 git：`git log --follow -- docs/07-agent-core-alignment-gaps.md`）。

## 术语与定位

- 通用术语（pi / 锚点 / 对齐 / 闭环 / 工单编号 Bxxx、Axx / 裁决 / 模型可见面 / R5 / 分母 / 原 docs/NN）：见 [`04-open-items-register.md`](04-open-items-register.md) §0 与 [`05-module-alignment-map.md`](05-module-alignment-map.md)。
- **权威台账**是 [`04-open-items-register.md`](04-open-items-register.md)：B/A 编号与裁决以此为准。
- **全量加权盘点**是 [`map/03-agent-core.md`](map/03-agent-core.md)：104 个单元逐条判定、权重、完成度。
- 本稿＝两者的 agent-core 层索引：§1 确认缺口，§2 形状差（存疑、待裁决），§3 要取证/清点的 A 项，§4 已裁决不做。
- **map 单元号（#N）**：`map/03-agent-core.md` 里的能力单元编号。
- **批 agent-core-1/2/3**：2026-10-17 闭环的三个对齐包，实施记录在原 docs/20–22（已删，历史在 git）；**批 agent-core-4**：2026-10-18（设计稿 docs/24），确认缺口清零；**批 agent-core-5**：2026-10-18（设计稿 docs/25），存疑清零 ⇒ **模块 100%**（加权 180/180、未加权 90/90）。

## 基准

- pi 锚点 `ea448f454`（2026-10-11）。七份地图已于 2026-10-12 对该锚点逐条重测完毕（原 A16 销号）；本稿数字随重测同步。
- 整理：2026-10-12（旧版盘点过程稿已删）。

---

## 1. 确认缺口（地图判「缺失」，计分母）

**✅ 已清零（2026-10-18 批 agent-core-4，设计稿 docs/24）**——原 11 行全数闭环：
#62/#63/#64 技能与模板族、#24/#25/#27 branch summary 族（B1 销号）、工具表 #8 powershell（B243 销号，
激活面随 B236）、#48（B30 销号）/#55/#69/#79（B55 销号）/#98（B36 销号）翻对齐，#68 `streamProxy`
按裁决① R5 出分母（docs/24 §13.1）。逐条证据见 `map/03-agent-core.md`「十一条点名项」。

**同层相关、挂在 docs/04 但生产者跨模块的条目**：

- ~~B49 `turn_end` 颗粒度~~ —— 批 5 销号：pi 会话事件 union 本就含引擎逐回合 `turn_end`，批 2 的 TurnEnd 重塑已对齐（docs/25 §12.2）。
- ~~B53 `find()` 缺三级阶梯~~ —— 批 4 阶梯对齐（路径快车道＋header-only findById＋项目前缀＋跨项目精确胜前缀），无账面变动（挂 #78 行），销号。
- B31/B32/B33（get_state 三处 RPC 形状，生产者在 coding-agent）。
- **B244**（`skill:` slash 命令注册＋settings `skillCommands` 开关）、**B245**（tree 扩展事件＋TUI 摘要流）——批 4 新登记，归 coding-agent/TUI 批（docs/04 §3.3）。

---

## 2. 形状差（地图判「存疑」，计半分；待裁决）

这些不是「没有」，而是「形状/行为不同」。**每一条的收口顺序**：先定「算不算偏差」；认账则立 B 号转 §1，不认则进 `docs/04` §4 写裁决与触发条件。

### 2.1 权重 3（✅ 已清零）

权重 3 层 22/22 = **100%**，无存活条目。批 agent-core-1 清 #6/#41/#17/#23，批 agent-core-2 清 #34/#46/#52/#54/#58/#78/#100（2026-10-17）；逐条闭环记录见 map/03 与 git 历史。其中 #54/#58/#78/#100 的裁决仍有效，收在 `docs/04` §4。

### 2.2 权重 1/2（✅ 已清零）

**✅ 已清零（2026-10-18 批 agent-core-5，设计稿 docs/25）**——原 22 行全数收编：**模块加权 180/180 = 100%、未加权 90/90 = 100%**。13 条做（#57 bashExecution 全链最大件、#77 navigateTree 工具恢复、#75 fork 消费 `--session-id`＋删 `" (fork)"` 后缀、#87 双 twin thinking 帧＋S16 对拍零 diff、#89/#96 两夹具、#94 拆 `PiLaneSink`/`AgentHarness`（470/438）、#95 字节精确假 shell、#81 并发审计＋`FileMutationQueue` 共享单例修真、#83 消费者清点、#85/#92/#93 javadoc 三枚）；8 条裁决对齐（#14/#53〔**B49 销号**〕/#56/#80/#82/#84/#90/#91，裁决文本进 `docs/04` §4）；#60 按 R1 出分母（参照物随 pi `7fd478a2e` 删除，Σw 182→180）。旧字母编号（F1/F2/F4/C2/C3/C4/D2/D3/D6/E3/E4/E8/A1/A2/A5/A7/A10/A14/A15）同批全收尾。逐条证据见 `map/03-agent-core.md`「二十二条点名项」。

**跨层挂账提醒**：JSONL 条目信封 #16 仍存疑（见 map/06）；`--tools` glob/modifier 模式面在册 **B236**（docs/04 §3.3，归 coding-agent 批，设计稿 docs/26 搁置待审）。

**2026-10-17 批 agent-core-3**（详稿原 docs/22（批 3 设计稿））：内置工具四条存疑**清零**：

- bash 超时面逐字（无默认值／上限 2147483.647 s／毫秒粒度／isError 收尾）。
- grep 补七参＋gitignore＋输出形（纯 Java 引擎；**裁决①**：rg/fd 外壳与 tools-manager 不移植）。
- `glob`→`find` 改名＋fd 语义。
- ls `recursive`→`limit` 参数集换血。
- 窄差 D1–D8 入 map/03 工具表备注。
- 新登记：**B243**（powershell 实现＋默认激活集，挂 B236 族；设计稿原编号 B241，与 telemetry 批 1 撞号）、**B242**（bash 的 `PI_*` 会话环境变量面）——两者均于 2026-10-18 批 agent-core-4 销号。

**编号空间提醒**：旧 A/C/D/F 编号（如 A1/A2/C2/F1）来自各过程稿（docs/31 族），其中一部分已随包闭环；它们与 `docs/04` §2 的 A 类（A10/A14/A15/A16）**不是同一编号空间**。A16 重测时统一清理标签。

---

## 3. 要取证 / 清点才能定案（docs/04 §2 中归属本层的）

**✅ 已清零（2026-10-18 批 agent-core-5）**：A10（并发审计＋`FileMutationQueue` 共享单例修真）、
A14（`ShellByteBoundaryTest` 字节精确假 shell，泵审计无 bug）、A15（错误路 `TurnEnd.toolResults`
恒空夹具）全部销号。

（A16＝六图换锚重测，跨所有层，已销号；见 `docs/04` §2。）

---

## 4. 已裁决不做（不展开）

- **R1 作废 19 个单元 ＋ drive 运行时整块**：参照物（`agent/src/harness/**` 108 文件）已被 pi 删除；完整清单见 `map/03-agent-core.md`「作废清单」。
- **R5 不可达**：pico3、durable（import 者全在 `experimental/`）、**`streamProxy`（#68，批 4 裁决①：pi 侧存活但 bin pi 主流不可达——coding-agent 零 import，仅再导出/单测/构建冒烟；docs/24 §13.1）**。
- 替身归属：`before_payload`/`after_response`/`before_compaction` 的对应物是 coding-agent 扩展事件，不属本模块。
- **B59**（SQLite schema 分叉）：pi 的 session 后端已整包删除且从未进 `bin pi`，见 `docs/04` §4。

---

## 5. 取证口径

1. pi 侧一律 `git -C /d/workplaceForai/pi show ea448f454:<path>`，不读 pi 工作树。
2. pi-java 侧读当前 HEAD 工作树。
3. **事件类条目要 grep 到发射点**，不能只确认类型定义存在（B30 的坑：类型＋mapper 都在，缺的只是 emit）。
4. 行号会漂：引用优先给**文件 ＋ 符号/关键行**。
