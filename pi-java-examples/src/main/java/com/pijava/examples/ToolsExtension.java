package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/tools.ts:1-148} 移植（docs/52 §2.3）——
 * 命令 {@code /tools} 交互式启停工具，选择持久化到 {@code tools-config} 条目，
 * session_start/session_tree 从当前分支恢复。capturesViewportInput 属性对齐（D99）。
 */
public final class ToolsExtension implements PiExtension {

    /** pi customType。 */
    static final String ENTRY_TYPE = "tools-config";

    private final Set<String> enabledTools = new LinkedHashSet<>();
    private List<com.pijava.coding.agent.extension.ExtensionToolInfo> allTools = List.of();

    @Override
    public String name() {
        return "tools";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("tools",
            "Enable/disable tools", args -> run(ctx)));
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            restore(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_TREE, (event, c) -> {
            restore(c);
            return null;
        });
    }

    /** pi L69-137：命令主流程（守卫＋同步方言选择器）。 */
    void run(ExtensionContext ctx) {
        if (!"tui".equals(ctx.mode())) {
            ctx.ui().notify("/tools requires TUI mode", NotifyType.ERROR);
            return;
        }
        refreshTools(ctx);
        ctx.ui().custom((com.pijava.coding.agent.extension.ExtensionUiHostEnv env,
                         com.pijava.coding.agent.core.KeybindingsManager kb,
                         java.util.function.Consumer<String> done) -> {
            done.accept(null);
            return new ToolsComponent();
        }, null);
    }

    /** pi L39-64：从分支恢复（纯逻辑核心可单测）。 */
    void restore(ExtensionContext ctx) {
        refreshTools(ctx);
        var saved = savedTools(ctx.sessionManager().getBranch());
        if (saved != null) {
            var validNames = new LinkedHashSet<String>();
            for (var t : allTools) {
                validNames.add(t.name());
            }
            enabledTools.clear();
            for (var name : saved) {
                if (validNames.contains(name)) {
                    enabledTools.add(name);
                }
            }
            ctx.setActiveTools(new ArrayList<>(enabledTools));
        } else {
            enabledTools.clear();
            enabledTools.addAll(ctx.getActiveTools());
        }
    }

    /** 从分支条目找最后一个 tools-config 的 enabledTools（无则 null）。 */
    static List<String> savedTools(List<Entry> branch) {
        List<String> saved = null;
        for (var entry : branch) {
            if (entry instanceof Entry.Custom c && ENTRY_TYPE.equals(c.customType())) {
                var data = c.data();
                if (data != null && data.get("enabledTools") instanceof List<?> list) {
                    var names = new ArrayList<String>();
                    for (var v : list) {
                        if (v instanceof String s) {
                            names.add(s);
                        }
                    }
                    saved = names;
                }
            }
        }
        return saved;
    }

    /** 应用当前选择到宿主并落盘（供渲染回调调用）。 */
    void apply(ExtensionContext ctx) {
        ctx.setActiveTools(new ArrayList<>(enabledTools));
        ctx.appendEntry(ENTRY_TYPE, Map.of("enabledTools", List.copyOf(enabledTools)));
    }

    private void refreshTools(ExtensionContext ctx) {
        allTools = ctx.getAllTools();
    }

    Set<String> enabledTools() {
        return enabledTools;
    }

    /** 占位组件：capturesViewportInput 对齐内层列表（改动点 D99）。 */
    static final class ToolsComponent implements ExtensionComponent {
        @Override
        public boolean capturesViewportInput() {
            return true;
        }

        @Override
        public List<String> render(int width) {
            return List.of("Tool Configuration");
        }

        @Override
        public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) {
        }
    }
}
