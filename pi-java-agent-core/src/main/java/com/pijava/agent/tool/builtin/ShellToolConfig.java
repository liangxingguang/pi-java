package com.pijava.agent.tool.builtin;

import java.util.List;

/**
 * pi {@code ShellToolConfig}（bash.ts:233-241）对位 —— bash/powershell 同骨架的
 * 六面差异参数化（B243 重构）。{@code prompt} 只喂 TUI 渲染器（pi
 * {@code createShellRenderers(config.prompt)}，bash.ts:413），TUI 层属 R3，
 * 这里仅留存配置值。
 */
record ShellToolConfig(
    String name,
    String label,
    String shellName,
    String prompt,
    String promptSnippet,
    List<String> promptGuidelines,
    String tempFilePrefix
) {}
