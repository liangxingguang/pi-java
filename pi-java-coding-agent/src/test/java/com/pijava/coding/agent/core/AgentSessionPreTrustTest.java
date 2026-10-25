package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionResults.ProjectTrustEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.TrustDecision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.PROJECT_TRUST;

/**
 * 批 23（docs/34 T3）：预信任装配集成——{@code project_trust} 投票经 TCCL
 * 作用域 loader 注入（批 15 先例）；投 no ⇒ 项目 settings 不生效（纯内存清空），
 * 投 yes＋remember ⇒ trust.json 落盘且项目 settings 生效。
 */
class AgentSessionPreTrustTest {

    /** 投票扩展：静态开关驱动（TCCL loader 加载的是同一 Class）。 */
    public static final class Voter implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Voter() {}

        @Override
        public String name() {
            return "trust-voter";
        }

        @Override
        public void register(ExtensionContext ctx) {
            ctx.on(PROJECT_TRUST, (e, c) -> {
                EVENTS.add("project_trust:" + e.cwd());
                return VOTE == null ? null
                    : new ProjectTrustEventResult(VOTE, REMEMBER);
            });
        }
    }

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile TrustDecision VOTE;
    static volatile Boolean REMEMBER;

    private ClassLoader previousTccl;
    private String savedHome;
    private String savedDir;
    private Path home;
    private Path cwd;

    @BeforeEach
    void setUp() throws Exception {
        EVENTS.clear();
        VOTE = null;
        REMEMBER = null;
        ProjectTrustSetup.clearCacheForTests();
        home = Files.createTempDirectory("pi-java-trust-home");
        cwd = Files.createTempDirectory("pi-java-trust-cwd");
        var servicesDir = Files.createDirectories(cwd.resolve("tccl")
            .resolve("META-INF").resolve("services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Voter.class.getName() + "\n");
        var testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {
                cwd.resolve("tccl").toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        // 会话项目目录＝settings 存储面的 projectDir（默认取 user.dir）——
        // 重定向让项目 settings 夹具落在 temp cwd（pi 单源 process.cwd() 对位）。
        savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", cwd.toString());
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(previousTccl);
        System.setProperty("user.home", savedHome);
        System.setProperty("user.dir", savedDir);
        ProjectTrustSetup.clearCacheForTests();
    }

    /** 项目 settings 种标记值＝敏感资源（hasTrustRequiringProjectResources 命中）。 */
    private void plantProjectSettings() throws Exception {
        var dir = Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(dir.resolve("settings.json"),
            "{\"shellCommandPrefix\": \"PROJECT-MARKER\"}");
    }

    private AgentSession createSession() {
        return AgentSession.create(ArgsParser.parse(new String[] {"--no-session"}),
            ProviderRegistry.create(),
            new ToolContext(cwd.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    @Test
    void voteNoBlocksProjectSettings() throws Exception {
        plantProjectSettings();
        VOTE = TrustDecision.NO;
        try (var session = createSession()) {
            assertThat(EVENTS).anyMatch(e -> e.startsWith("project_trust:"));
            assertThat(session.services().settings().isProjectTrusted()).isFalse();
            assertThat(session.services().settings().effective().shellCommandPrefix)
                .isNull();
        }
    }

    @Test
    void voteYesWithRememberAppliesAndPersists() throws Exception {
        plantProjectSettings();
        VOTE = TrustDecision.YES;
        REMEMBER = Boolean.TRUE;
        try (var session = createSession()) {
            assertThat(session.services().settings().isProjectTrusted()).isTrue();
            assertThat(session.services().settings().effective().shellCommandPrefix)
                .isEqualTo("PROJECT-MARKER");
        }
        // remember ⇒ trust.json 落盘（agentDir＝<home>/.pi-java/agent）。
        var store = new ProjectTrustStore(
            home.resolve(".pi-java").resolve("agent"));
        assertThat(store.get(cwd)).isTrue();
    }

    @Test
    void noVoteAskNoUiUntrusted() throws Exception {
        plantProjectSettings();
        // VOTE=null ⇒ 处理器返 null ⇒ NPE 进 errors（pi undefined.trusted 对位）——
        // 无结果 ⇒ store 空 ⇒ ask ⇒ 无 UI ⇒ false。
        try (var session = createSession()) {
            assertThat(session.services().settings().isProjectTrusted()).isFalse();
            assertThat(session.services().settings().effective().shellCommandPrefix)
                .isNull();
        }
    }

    @Test
    void noResourcesTrustedWithoutVote() {
        // cwd 无敏感资源 ⇒ 恒 true，事件不发。
        try (var session = createSession()) {
            assertThat(session.services().settings().isProjectTrusted()).isTrue();
            assertThat(EVENTS).isEmpty();
        }
    }
}
