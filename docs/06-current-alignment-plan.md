# 06 - 当前对齐执行计划

> 【背景摘要】本文档只记录当前执行顺序、状态和审核门。旧批次、旧排期和完成度沿革不再保留。
>
> 当前目标锚点为 pi `56cae7823`，完整规则见 [`08-pi-rebaseline-master-plan.md`](08-pi-rebaseline-master-plan.md)。

## 1. 当前结论

- 采用逐层滚动换锚，不采用全仓一次性改写或只做增量 diff。
- 每层先全量重测，再提交详细设计；用户批准后才实施。
- 下层未闭环前，上层不得把其新接口当作稳定输入。docs/10 B264 的窄纵贯迁移（record 编译适配、机械错误/responseId 传播与 malformed 执行前安全门）随该包闭环，不授权其他上层行为或账面翻转。
- 旧 TUI/examples 队列停止执行，分别由 L7/L8 重新盘点。
- `docs/11` 是 L1 行为面设计（B265 第一部分），已闭环（2026-11-02）。
- `docs/12` 是 L1 目录数据与运行时设计（B265b：#16/#21/#33/#34），已按阶段 A/B 实施并闭环（2026-11-02）。
- `docs/13` 是 L1 请求遥测设计（B263：#106），已实施并闭环（2026-11-03）。
- `docs/14` 是 L1 Client Identity 设计（B266：#105），已实施并闭环（2026-11-03）。
- `docs/15` 是 L1 usage 与 OpenRouter 账单设计（B267 第一批：#53／#59／#107），已实施并闭环（2026-11-03）。
- `docs/17` 是 L1 Provider wire 对齐设计（B267 第三批：#1／#2／#108／#101／#110／#111），已实施并闭环（2026-11-03）；前置 `openai-java 4.42.0 → 4.96.0` 升级（用户已批准）。
- `docs/18` 是 L1 retry 与 overflow 判定设计（B267 第四批：#70／#71），已实施并闭环（2026-11-03）。
- `docs/19` 是 L1 OAuth／device／env 与 CLI 凭证权限设计（B267 第五批：#43／#44／#45／#50／#112），已实施并闭环（2026-11-03）。
- `docs/20` 是 L1 Faux 可编程核心与 deferred 设计（B267 第六批：#80），已实施并闭环（2026-11-04）；L1 账面 232/232＝100%。
- `docs/16` 是 L1 存疑行复核设计（B267 第二批：15 个存疑行），已裁决并闭环（2026-11-03）。
- `docs/21` 是 L2 mcp 新锚点重测与对齐设计（W1–W12，含 W2c 夹具与 W12a 口径修正；销 B181/B184/B217），已实施并闭环（2026-11-04）；主体账面 126.0/131＝96.18%。
- `docs/22` 是 L2 mcp 视口捕获收尾与跨层口径裁决（#85 完整对齐；其余 9 条转出 MCP 分母），已实施并闭环（2026-11-04）；MCP 终态 122.0/122＝100%。
- `docs/23` 是 L3 agent-core 新锚点重测与对齐设计（W1–W3），已实施并闭环（2026-11-05）：W1 模板命令引号空参数＋JS 空白、W3 grep 孤立 CR（实施），W2 技能路径比较键（复核无改动）；#77／#24 转出 L4；agent-core 终态 **177/177＝100%**（88 计分＋2 转出）。
- `docs/24` 是 L4 session/SQLite 新锚点重测与对齐设计（W1–W4＋B278），已实施并闭环（2026-11-05）：W1 恢复 active tools、W2 摘要语义、W3 省略成对、W4 持久化细节＋显式会话目录布局；map/06 存储后端 **44.0/44＝100%**，D218 登记；全仓 verify **6,227 测零失败**，Checkstyle/SpotBugs 零问题。
- `docs/25` 是 L5 coding-agent 新锚点重测与对齐设计（W1–W10；§14 第 2–5 项已裁决），实施中（2026-11-05）：已落 W2（`reachesTools` 投影）、W3（`pi.exec` maxBuffer/truncated/UTF-8 边界）、W9（思考档 RPC 过滤）、W10（print 截断、trust 优先级、删除 cwd、帮助/slash 文案、图片编码、shell 平台）、W1（run 启动窗＋`before_agent_start` user/custom 联合）、W4（三个新 settings 键、`/share` destination、`update self` 配置命令、扩展 changelog 启动展示、manifest `changelogPath`）。W1 引入的树导航回归（`agentRunActive` 复位晚于引擎 idle）随 W4 修复。余 W5/W6/W7/W8。

## 2. 层级顺序与状态

| 层 | 范围 | 当前状态 | 下一步产出 |
|---|---|---|---|
| L0 | telemetry | 已闭环 | B260–B262 已验收；账面 36/36＝100%；B263 随 L1 设计 |
| L1 | ai | 已闭环 | B264、docs/11 行为面（#31/#61/#83/#84）、docs/12 目录数据与运行时（#16/#21/#33/#34）、docs/13 请求遥测（#106）、docs/14 Client Identity（#105）、docs/15 usage 与 OpenRouter 账单（B267 第一批：#53/#59/#107）、docs/16 存疑复核（B267 第二批）、docs/17 provider wire（#1／#2／#108／#101／#110／#111）、docs/18 retry/overflow（#70／#71）、docs/19 OAuth／device／env＋CLI 权限（#43／#44／#45／#50／#112）与 docs/20 Faux／deferred（#80）全部闭环；账面 **232.0/232＝100%**（分母因 6 个 Java 扩展作废由 240 缩至 232） |
| L2 | mcp | 已闭环 | docs/21＋docs/22：新锚点重测＋W1–W12＋#85 视口捕获；账面 **122.0/122＝100%**（对齐 82／存疑 0／缺失 0；未加权 82/82；9 条跨层方言转出分母，D217 销号）。 |
| L3 | agent-core | 已闭环 | docs/23 W1–W3：模板引号空参数＋JS 空白、grep 孤立 CR（实施），技能路径比较键（复核无改动）；identity／`reachesTools`／AI 新类型复核对齐；#77／#24 转出 L4；账面 **177/177＝100%**（88 计分＋2 转出） |
| L4 | session / SQLite | 已闭环 | docs/24 W1–W4＋B278：恢复 active tools、摘要语义、省略成对、持久化细节、显式会话目录平铺；账面 **44.0/44＝100%**；下一步 L5 coding-agent |
| L5 | coding-agent | 实施中 | docs/25 W1–W4、W9、W10 已实施（run 启动窗＋`before_agent_start` 联合、`reachesTools` 投影、`pi.exec` 上限、settings/分享/自更新/扩展 changelog、思考档 RPC 过滤、print 截断/trust/cwd/help/图片/shell）；余 W5（包管理器容错）、W6（模型解析）、W7（tool_search）、W8（HTML 导出） |
| L6 | evals | 未开始 | 新行为的评测 oracle 与 harness 复核 |
| L7 | TUI / web 兼容面 | 未开始 | 新锚点 TUI 全量重测与剩余屏域设计 |
| L8 | examples | 未开始 | 新锚点清单、Java 移植与验收计划 |

状态只允许使用：待重测、重测中、设计待审、实施中、已闭环。不得用“文档已写”代替层闭环。

## 3. 跨层顺序约束

```text
流式工具参数：ai → agent-core → coding-agent → RPC/TUI/web
Client Identity：telemetry/ai → agent-core → coding-agent
动态模型目录：ai → coding-agent → TUI/RPC/web
MCP 结果与 OAuth：ai/mcp → coding-agent
扩展启动事件：coding-agent → evals → examples
会话恢复与压缩：agent/session → coding-agent → export/web
```

## 4. 每层完成条件

1. 地图头部写明 `PI_PIN=56cae7823` 和 pi-java 当层 SHA。
2. 每个计分单元都有新锚点证据和明确判定。
3. R1/R2/R5 与去重规则一致。
4. 开放项已登记，获批实施项已通过测试。
5. docs/04 与当层地图一致。
6. 本表状态更新为已闭环。

全部层完成后才更新 `docs/05` 总表和 `docs/map/ANCHOR.md` 的全局当前锚点。

## 5. 文档分工

| 文档 | 只负责什么 |
|---|---|
| docs/04 | 当前开放项和有效裁决 |
| docs/05 | 最终量化快照和统一计分口径 |
| docs/06 | 当前执行顺序和状态 |
| docs/07 | examples 的有效结论 |
| docs/08 | 新基线完整换锚设计与风险控制 |
| docs/09 起 | 各层重测或实施工作包设计 |
