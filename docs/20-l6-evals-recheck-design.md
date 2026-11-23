# 20 - L6 复核设计：评测域按目标锚点重测

> 【背景摘要】`docs/09` 预审计把 L6（evals）投影为「48.0/49≈98.0%，`packages/evals/src` 零行为变化，唯一存疑 #20 不变」，但该读数一直是**投影**（map/06 评测域 24 行仍按旧锚点 `56cae7823` 计分）。按「由底往上、逐层对齐」裁决，L5 收口后的下一批就是 L6。本稿对 `56cae7823..a00a5048d` 的评测面逐项复核，把投影转成复核读数。
>
> 【复核结论（先行）】预审计投影**成立**：两锚点间 `packages/evals` 仅 `package.json` 变化（版本 1.4.0→1.6.0、依赖同步、`clean` 脚本换实现），`src`／`evals/`／`test/` 逐字节未变 ⇒ 24 行判定与 pi 证据全部续用，**零代码**；唯一存疑 #20 维持。L6 定格 **48.0/49＝98.0%**。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；Java 证据取当前 main（`942a0ce5`）。分层判定只写 `docs/map/06-session-evals-chord.md`，本稿只做设计、复核与验收。

术语：
- **归属复核**：不改判定口径，只核对「该层到底有哪些 pi 单元、它们的当前证据是什么、Java 是否仍对得上」。
- **oracle**：eval 里判定被测输出是否合格的参照实现（本域涉及 Levenshtein 阈值、取证断言、展开逐字比对）。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | 评测域 24 行按 `a00a5048d` 复核通过；投影读数 48.0/49 转为复核读数 |
| 行为不变量 | `pi-java-evals` 零代码改动；`EvalSuite`／harness／judge／report／docker 五面行为全部不变 |
| 完成判据 | ①`mvn test -pl pi-java-evals -am` 全绿；②`map/06` 评测域基准行改 `a00a5048d`＋#20 复核补记；③`docs/05` §1.2／`docs/09` 进度行同步 |
| 不做项 | 不碰 L8 examples（plan-mode、subagent 重写仍归 P11，另批）；不动 #20 的替代 oracle（其收口条件在 L7 状态行落地，见 §4）；不重跑真模型 eval（无密钥环境，框架单测已覆盖判定面） |

## 2. pi 证据（`a00a5048d`）

### 2.1 锚点间变更清单

`56cae7823..a00a5048d` 中 `packages/evals` 的改动只有一个文件（`git diff --stat`）：

| 文件 | +/- | 性质 |
|---|---|---|
| `packages/evals/package.json` | +4/−5 | 版本与构建脚本，非产品面 |

全文 diff（4 个 hunk）：

1. `version`：`1.4.0` → `1.6.0`（随全仓版本推进）。
2. `scripts.clean`：`shx rm -rf .eval` → `node ../../scripts/package-files.mjs clean .eval`（制品打包重构随迁，见 `docs/map/06`「防漂移脚本」段的 `package-artifacts` 重构；属仓库工具链，非 `bin pi` 产品面）。
3. `devDependencies`：`pi-ai`／`pi-coding-agent` 同步到 `1.6.0`；删 `shx`。
4. 其余脚本（`eval`／`eval:host`／`eval:audit`／`eval:docs`／`test`）逐字未变——**`eval:audit` 档位在旧锚点已存在**，评测 #24 的 pi 证据不变。

**`src`（1,447 行）、`evals/*.eval.ts`（950 行）、`test/`（830 行）、`docker/`、`vitest.evals.config.ts` 全部逐字节未变。**

### 2.2 对 24 行判定的影响

pi 侧 oracle 与 harness 面零变化 ⇒ map/06 评测域表中的 pi 证据 `file:line`（引用 `56cae7823`）**全部续用**，无需像 docs/18 那样重钉行号。唯一需要复核的是「计分单元是否随 AI／coding-agent 语义变化」：

- pi 的 AI／coding-agent 在两锚点间确有大改（L1/L5 的对齐来源），但 **evals 的 24 个单元judge的是「评测框架能力面」**（声明式定义、harness、judge、报告、CLI、docker），不是被测 agent 的具体行为输出；pi 自己没有随 1.6.0 改 eval 断言（`src` 零 diff 即证据）⇒ 判定基准未动。
- Java 侧 L1/L5 对齐改变的是 `pi-java-ai`／`pi-java-coding-agent` 行为，方向是**逼近 pi**，不改变 Java 评测框架 24 个单元的能力存在性 ⇒ 无改判。

### 2.3 Java 侧证据有效性

map/06 评测域 java 证据钉在 `51dc0f7c＋docs/26`。此后触及 `pi-java-evals` 的提交只有两笔：

| 提交 | 内容 | 对 24 行的影响 |
|---|---|---|
| `6867078c` | OpenAI Images provider（`ai`） | 仅 `ProviderCatalogConformance`／`ProviderCatalogTest` 计数断言随 provider 数同步，不触判定 |
| `d035ffa0` | qwen/xiaomi token-plan providers（`ai`） | 同上 |

两笔均为「测试计数断言随 provider 目录增长」的既有教训复现（`docs/04` §7），不触任何单元判定。**Java 侧 24 行证据续用。**

## 3. 24 行复核表（判定全部续用）

逐行核对结果：24 行的 pi 证据（逐字节未变）与 java 证据（仅计数断言变化）均有效，**无一行改判**。行数/权重不变：24 行，Σ=49，Σ(w×c)=48.0（对齐 47.0＋存疑 #20 系数 1.0）⇒ **48.0/49＝98.0%**。

| 行组 | 行号 | 复核方式 | 结论 |
|---|---|---|---|
| 框架面（#1/#3/#4/#9/#16/#17） | 6 行 | pi `src/{harness,cli,plan,report}.ts` 零 diff；java `api/judge/plan/report/cli` 零改动 | 续用 |
| harness 面（#2/#6/#7/#8） | 4 行 | `src/harness.ts`、`src/report.ts` 零 diff；java harness/report 零改动 | 续用 |
| 内容 eval（#10–#13/#15/#20–#22） | 8 行 | `evals/*.eval.ts` 零 diff（oracle 逐字未变）；java contents 零改动 | 续用 |
| 报告面（#5/#23） | 2 行 | `src/report.ts` 零 diff | 续用 |
| 基建面（#14/#18/#19/#24） | 4 行 | docker 三件零 diff；`vitest.evals.config.ts` 零 diff；`eval:audit` 脚本续用 | 续用 |

## 4. 存疑 #20 复核：维持

【场景】pi 的 TUI 文档 eval 用 context-footer oracle：`RecordingTerminal` 捕获渲染输出，断言状态行出现 `42.2%/272k` 形态的上下文占用指示（`BUILT_IN_CONTEXT_PATTERN = /\d+(?:\.\d+)?%\/272k(?: \(auto\))?/`，`tui.docs.eval.ts:13-15`；`CONTEXT_WINDOW = 272_000` `:14`），配内联 `levenshteinSimilarity`（`:19-30`）。

【新锚点证据】`tui.docs.eval.ts` 两锚点间零 diff（+41/−25 的改动发生在上一换锚周期 `ea448f454..56cae7823`，map/06 已记）⇒ oracle 形态未变。

【决定】**维持存疑**，理由未变：java TUI 没有 context footer（272k 状态行），Java 侧以「Java 文档问答＋Levenshtein 0.75」（`TuiDocsEval`＋`RecordingFooterOracle`）替代，判分核心（LLM 读取文档后回答的相似度门槛）同形但**观测对象不同**——pi 观测的是 TUI 状态行渲染，Java 观测的是文档问答文本。该差异的根因在 TUI 状态行能力缺失，属 **L7 渲染面**（C5/C6 状态域）。

【收口条件】L7 落地 context footer 状态行后，`TuiDocsEval` 换接真 footer oracle，#20 改判「对齐（形状不同）」并销 D183/D189 的存疑联动。在那之前 #20 保持存疑系数 0.5，L6 的 −1.0 是全层唯一扣分。

## 5. 测试与验收

| 步骤 | 内容 |
|---|---|
| 自验证 | `mvn test -pl pi-java-evals -am` 全绿（上游模块随 reactor 全跑；evals 模块自身含 harness/judge/plan/report/docker/contents/fixtures/conformance 八组） |
| 地图 | `map/06`：评测域基准行改「按 `a00a5048d` 复核（docs/20）」；§存疑清单 S7 补复核日期；目标锚点预审计段的 L6 行补「已复核」 |
| 计划视图 | `docs/09` §3「完成度总览」L6 行补复核出处；§4「进度」补 L6 完成；「现状速览」「下一批次」改为 L7 |
| 台账 | `docs/04` §0 增一条 L6 复核说明：零代码、无新 B／D 号、#20 维持 |
| 读数 | `docs/05` §1.2 L6 行：`49 / 48.0`，列改「**98.0%**（2026-11-23 复核，`docs/20`）」；合计行的 L6 不再「沿用投影」 |

## 6. 实施记录（2026-11-23）

- 复核取证：`git -C D:/workplaceForai/pi diff --stat 56cae7823..a00a5048d -- packages/evals` ⇒ 仅 `package.json`（+4/−5）；`git diff` 全文核对四个 hunk（§2.1）。
- `mvn test -pl pi-java-evals -am`（JDK 25）：全绿——`pi-java-evals` 自身约 200 测 0 失败 0 错误（live/smoke 真模型用例按既有口径 skip 33）；`-am` 全链 905 份 surefire 报告 0 失败 0 错误。
- 地图/台账回填：`map/06`（基准行、重判说明补记、S7、预审计段）、`docs/05` §1.2（L6 行、合计行、行值维护段）、`docs/09`（§3 L6 行＋合计行＋怎么读、L6 节、现状速览、§4 进度）、`docs/04`（§0 增 L6 复核条＋头部设计稿清单修正）。
- 本批**零代码提交**，仅 docs 变更（`docs/20` 新增＋五处回填），改动留在工作区待指示提交。

## 7. 风险与遗留

- **#20 收口依赖 L7**：context footer 属 TUI 状态行（C 域）；L7 未开工前 #20 无法改判。L6 的 98.0% 是「评测域自身已尽」的读数，不是全仓乐观上界。
- **真模型 eval 未重跑**：eval 的 judge 阈值面（Levenshtein 0.75、取证断言）由框架单测覆盖；真模型冒烟依赖外部密钥，与既有各层口径一致（按用户裁决豁免真机冒烟的先例，不在本批验收内）。
- **L8 examples 仍归 P11 另批**：plan-mode、subagent 在新锚点重写而 Java 仍旧行为（−2 件），不随 L6 复核闭环。
