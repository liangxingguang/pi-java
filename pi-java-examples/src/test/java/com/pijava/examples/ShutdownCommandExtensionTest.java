package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolResult;

import org.junit.jupiter.api.Test;

/** docs/55 §2.5：shutdown-command 验收——quit 命令、两退出工具元数据/onUpdate/shutdown/return。 */
class ShutdownCommandExtensionTest {

    @Test
    void quitCommandTriggersShutdown() {
        var w = new W3Context();
        new ShutdownCommandExtension().register(w.ctx);

        w.command("quit", "");

        assertThat(w.shutdownCount).isEqualTo(1);
    }

    @Test
    void finishAndExit() throws Exception {
        var w = new W3Context();
        var ext = new ShutdownCommandExtension();
        ext.register(w.ctx);
        var tool = new ShutdownCommandExtension.FinishTool(w.ctx);

        assertThat(tool.name()).isEqualTo("finish_and_exit");
        assertThat(tool.label()).isEqualTo("Finish and Exit");
        assertThat(tool.description()).isEqualTo("Complete a task and exit pi");

        var result = tool.execute("c1", Map.of(), null, null, null);
        assertThat(ExampleFixtures.textOf(result.content()))
            .isEqualTo("Shutdown requested. Exiting after this response.");
        assertThat(w.shutdownCount).isEqualTo(1);
    }

    @Test
    void deployAndExitOnUpdateSequence() throws Exception {
        var w = new W3Context();
        var tool = new ShutdownCommandExtension.DeployTool(w.ctx);
        var updates = new ArrayList<String>();

        assertThat(tool.name()).isEqualTo("deploy_and_exit");
        assertThat(tool.label()).isEqualTo("Deploy and Exit");
        assertThat(tool.description()).isEqualTo("Deploy the application and exit pi");

        ToolResult<Map<String, Object>> result = tool.execute("c2",
            Map.of("environment", "staging"), null,
            partial -> updates.add(ExampleFixtures.textOf(partial.content())), null);

        assertThat(updates).containsExactly(
            "Deploying to staging...", "Deployment complete, exiting...");
        assertThat(w.shutdownCount).isEqualTo(1);
        assertThat(ExampleFixtures.textOf(result.content())).isEqualTo("Done! Shutdown requested.");
        assertThat(result.details()).containsEntry("environment", "staging");
    }
}
