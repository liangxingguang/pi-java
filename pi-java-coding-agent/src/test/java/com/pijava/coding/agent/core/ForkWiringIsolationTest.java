package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.compaction.CompactionSettings;
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
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/16（T1/§2.2）：共享 config 的会话绑定闭包串线修复 —— fork 经 wireSession
 * 重建后，压缩/重试事件、图片策略、PI_* 会话身份五组闭包全部读 <b>forked 自己</b>
 * （此前 {@code harness.fork()} 共享成品 config，sessionRef 恒指父会话）。
 */
class ForkWiringIsolationTest {

    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 16_384, 1);

    private static List<StreamEvent> textSeq(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession rig(Path tmp, ProviderRegistry providers) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-iso", "--model", "hello", "--no-session", "--no-extensions"});
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private ProviderRegistry fauxProviders() {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-iso",
            List.of(textSeq("seed answer"), textSeq("SUMMARY"))));
        return providers;
    }

    /** ①：forked 触发压缩 ⇒ CompactionStart/End 进 copy 订阅流、不进源会话订阅流
     *  （compactionObserver 经 sessionRef 读<b>自己</b>——串线期这些事件全错流进父会话）。 */
    @Test
    void compactionEventsRouteToTheForkedSessionOnly(@TempDir Path tmp) throws Exception {
        try (var parent = rig(tmp, fauxProviders())) {
            parent.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);
            var copy = parent.forkCopy("branch").orElseThrow();
            var parentEvents = new CopyOnWriteArrayList<AgentSessionEvent>();
            var copyEvents = new CopyOnWriteArrayList<AgentSessionEvent>();
            parent.subscribe(parentEvents::add);
            copy.subscribe(copyEvents::add);

            copy.compact(SETTINGS);

            assertThat(copyEvents)
                .anyMatch(e -> e instanceof AgentSessionEvent.CompactionStart)
                .anyMatch(e -> e instanceof AgentSessionEvent.CompactionEnd);
            assertThat(parentEvents)
                .as("forked 的压缩事件不得串进父会话事件流（docs/16 §2.2）")
                .noneMatch(e -> e instanceof AgentSessionEvent.CompactionStart)
                .noneMatch(e -> e instanceof AgentSessionEvent.CompactionEnd);
        }
    }

    /** ②：copy 的 toolContext 会话身份 ⇒ copy 的 sessionId/sessionFile
     *  （PI_SESSION_ID/PI_SESSION_FILE 晚绑定源，[模型可见] 身份缝）。 */
    @Test
    void sessionEnvironmentCarriesTheForkedSessionsOwnIdentity(@TempDir Path tmp) {
        try (var parent = rig(tmp, fauxProviders())) {
            var copy = parent.forkCopy("branch").orElseThrow();

            var env = copy.harness().toolContext().sessionEnvironment();
            assertThat(env).isNotNull();
            assertThat(env.sessionId())
                .as("PI_SESSION_ID＝forked 自己的会话身份（串线期是父会话的）")
                .isEqualTo(copy.sessionId());
            assertThat(env.sessionFile()).isEqualTo(copy.sessionFile());
            assertThat(env.model()).isEqualTo(copy.harness().getModel().modelName());

            var parentEnv = parent.harness().toolContext().sessionEnvironment();
            assertThat(parentEnv.sessionId()).isEqualTo(parent.sessionId());
            assertThat(copy.sessionId()).isNotEqualTo(parent.sessionId());
        }
    }

    /** ③：重试中止旗各自独立（会话字段层语义钉死；harness 闭包层由 ① 的
     *  sessionRef 机制覆盖 —— 两组闭包同根）。 */
    @Test
    void retryAbortFlagIsIndependentAfterFork(@TempDir Path tmp) {
        try (var parent = rig(tmp, fauxProviders())) {
            var copy = parent.forkCopy("branch").orElseThrow();

            copy.beginRetrySleep();
            parent.abortRetry();
            assertThat(copy.retryAborted())
                .as("父会话的 abortRetry 不得串到 forked 的退避窗口")
                .isFalse();
            copy.abortRetry();
            assertThat(copy.retryAborted()).isTrue();
        }
    }
}
