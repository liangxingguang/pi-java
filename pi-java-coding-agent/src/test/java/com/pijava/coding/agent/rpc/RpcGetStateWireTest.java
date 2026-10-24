package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 20（E7-3，T2）：get_state 三字段线格——B32（sessionName 缺席化）＋
 * B33（messageCount 工作副本口径）。B31 的 model 映射见
 * {@link RpcModelWireTest}；本类钉会话接线（无模型 ⇒ 键省略）。
 */
class RpcGetStateWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession session(List<List<StreamEvent>> replies) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-gs", "--model", "hello",
            "--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-gs", replies, 0));
        return AgentSession.create(args, providers, new ToolContext(tmp.toString(),
            Map.of(), new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private JsonNode getState(AgentSession s, ByteArrayOutputStream out) throws Exception {
        var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
            ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));
        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"get_state\"}");
        var line = out.toString().lines()
            .filter(l -> l.contains("\"command\":\"get_state\""))
            .findFirst().orElseThrow();
        return JSON.readTree(line).get("data");
    }

    @Test
    void sessionNameIsAbsentWhenUnnamed() throws Exception {
        try (var s = session(List.of(textTurn("r")))) {
            var out = new ByteArrayOutputStream();
            // B32：未命名（无 session_info）⇒ 键省略（pi undefined 同形）——
            // 旧实现恒发显示名 "session"。命名后出现/清名再缺席的线格对拍在
            // InitialNameWritebackTest（持久仓夹具在 core 包）。
            assertThat(getState(s, out).has("sessionName")).isFalse();
        }
    }

    @Test
    void modelKeyIsAbsentWhenNoModelSelected() throws Exception {
        // 不指定 --model：faux provider 无目录模型 ⇒ harness.getModel() null。
        var args = ArgsParser.parse(new String[] {"--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-none", List.of(textTurn("r")), 0));
        try (var s = AgentSession.create(args, providers, new ToolContext(tmp.toString(),
                Map.of(), new DefaultShellExecutor(), new DefaultFileSystem()))) {
            if (s.harness().getModel() != null) {
                // 真 home settings 带了默认模型（开发机环境差异）——跳过本断言。
                return;
            }
            var out = new ByteArrayOutputStream();
            var data = getState(s, out);
            assertThat(data.has("model"))
                .as("无模型 ⇒ model 键省略（pi undefined 同形；旧 \"\" 修真）").isFalse();
        }
    }

    @Test
    void modelIsFullObjectWhenSelected() throws Exception {
        try (var s = session(List.of(textTurn("r")))) {
            var out = new ByteArrayOutputStream();
            var data = getState(s, out);
            // B31：对象形（不再 "provider/id" 字符串）；faux 模型走
            // ModelInfo.minimal 容忍形状（窄差 D121）但必为对象。
            assertThat(data.get("model").isObject()).isTrue();
            assertThat(data.get("model").get("id").asText()).isEqualTo("hello");
            assertThat(data.get("model").get("provider").asText()).isEqualTo("faux-gs");
        }
    }

    @Test
    void messageCountFollowsWorkingCopyNotTranscript() throws Exception {
        // B33：pi 取 agent.state.messages.length（工作副本），java 旧取转录条数。
        // 确定性分叉构造：excludeFromContext 的 BashExecution 条目进转录
        //（Entry.BashExecution）但 messages() 为空 ⇒ 不进工作副本
        //（HarnessUtils.rebuildLaneMessages 经 entry.messages() 投影）。
        try (var s = session(List.of(textTurn("r")))) {
            s.processPrompt("hi",
                com.pijava.coding.agent.core.PromptConfig.defaults()).status();
            // 转录含非消息条目（run 工件），与工作副本**本已**分叉；
            // excludeFromContext 的 BashExecution 条目再 +1（只进转录）。
            s.recordBashResult("ls",
                new com.pijava.agent.tool.ShellResult("out", 0, false, false, 3, 3),
                true);

            int workingCopy = s.harness().getMessages().size();
            assertThat((int) s.entryCount())
                .as("非消息条目进转录、不进工作副本 ⇒ 分叉").isGreaterThan(workingCopy);

            var out = new ByteArrayOutputStream();
            assertThat(getState(s, out).get("messageCount").asInt())
                .as("pi agent.state.messages.length 对位（非转录条数）")
                .isEqualTo(workingCopy);
        }
    }
}
