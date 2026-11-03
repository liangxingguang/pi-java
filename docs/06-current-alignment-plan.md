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
- `docs/17` 是 L1 Provider wire 对齐设计（B267 第三批：#1／#2／#108／#101／#110／#111），已实施并闭环（2026-11-03）；前置 `openai-java 4.42.0 → 4.96.0` 升级（用户已批准）；下一新文档号为 **docs/18**。

## 2. 层级顺序与状态

| 层 | 范围 | 当前状态 | 下一步产出 |
|---|---|---|---|
| L0 | telemetry | 已闭环 | B260–B262 已验收；账面 36/36＝100%；B263 随 L1 设计 |
| L1 | ai | 实施中 | B264、docs/11 行为面（#31/#61/#83/#84）、docs/12 目录数据与运行时（#16/#21/#33/#34）、docs/13 请求遥测（#106）、docs/14 Client Identity（#105）、docs/15 usage 与 OpenRouter 账单（B267 第一批：#53/#59/#107）、docs/16 存疑复核（B267 第二批：15 个存疑行）均已闭环；docs/17 provider wire（#1／#2／#108／#101／#110／#111，前置 openai-java 4.96 升级）已闭环；B267 其余面（retry／overflow、OAuth／device／env、CLI 权限、Faux、deferred fetch）待拆；账面 **219.0/232＝94.40%**（分母因 6 个 Java 扩展作废由 240 缩至 232） |
| L2 | mcp | 未开始 | structuredContent、连接、OAuth 与安全复核 |
| L3 | agent-core | 未开始 | identity、streamProxy 和 AI 新类型适配复核 |
| L4 | session / SQLite | 未开始 | 恢复、截断、压缩和持久化复核 |
| L5 | coding-agent | 未开始 | 扩展启动、设置、分享、自更新和工具加载复核 |
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
