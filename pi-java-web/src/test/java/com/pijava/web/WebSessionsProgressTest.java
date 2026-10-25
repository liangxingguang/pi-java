package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 22（T1，W15）：web 渐进会话列表——{@code getSessions} 起虚拟线程富列表，
 * 周期发布点发 {@code sessionsProgress}（partial 快照），完成发终态
 * {@code sessions}；{@code cancelSessions} 中止（pi AbortSignal 对位）。
 *
 * <p>隔离 user.home 手法同 {@link PiWebServerIntegrationTest}（防真 home 继承）。</p>
 */
class WebSessionsProgressTest {

    private static final String CWD = System.getProperty("user.dir");

    private Path fakeHome;
    private String savedUserHome;
    private AgentSession session;
    private BlockingQueue<WebProtocol.WebServerMessage> sent;
    private WebDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        fakeHome = Files.createTempDirectory("pi-web-progress-home");
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        session = AgentSession.createWeb(ArgsParser.parse(new String[] {}));
        sent = new LinkedBlockingQueue<>();
        dispatcher = new WebDispatcher(session, session.sessionArgs(), sent::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setProperty("user.home", savedUserHome);
        if (session != null) {
            session.close();
        }
    }

    /** 在隔离 home 的会话根下种一个 cwd=当前目录的会话文件。 */
    private void seed(String text) throws Exception {
        var repo = JsonlSessionRepository.over(
            fakeHome.resolve(".pi-java").resolve("agent").resolve("sessions"));
        var s = repo.create(new JsonlSessionCreateOptions(null, CWD, null, null));
        s.appendEntry(new ProvisionedEntry<>(new Entry.Message(
            UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null)),
            "main");
        s.storage().drain();
    }

    /** await 轮询不丢帧——非匹配消息进 {@code seen}（进度帧在终态前到达）。 */
    private final List<WebProtocol.WebServerMessage> seen =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private WebProtocol.WebServerMessage await(java.util.function.Predicate<
            WebProtocol.WebServerMessage> pred, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var msg = sent.poll(200, TimeUnit.MILLISECONDS);
            if (msg == null) {
                continue;
            }
            if (pred.test(msg)) {
                return msg;
            }
            seen.add(msg);
        }
        return null;
    }

    @Test
    void progressiveFramesThenTerminal() throws Exception {
        int count = 25;   // publish 间隔 10 ⇒ ≥2 个发布点
        for (int i = 0; i < count; i++) {
            seed("session " + i);
        }
        dispatcher.handle(new WebProtocol.WebClientMessage.GetSessions());

        var terminal = await(m -> m instanceof WebProtocol.WebServerMessage.Sessions, 15_000);
        assertThat(terminal).isNotNull();
        var items = ((WebProtocol.WebServerMessage.Sessions) terminal).sessions();
        // +1＝被测 web 会话自身（同根落盘）。
        assertThat(items).hasSize(count + 1);

        // 进度帧：loaded/total 字段形＋部分表非空（周期发布点）——seen（await
        // 收集的终态前帧）＋队列残余。
        var progress = new java.util.ArrayList<WebProtocol.WebServerMessage.SessionsProgress>();
        for (var m : seen) {
            if (m instanceof WebProtocol.WebServerMessage.SessionsProgress p) {
                progress.add(p);
            }
        }
        WebProtocol.WebServerMessage msg;
        while ((msg = sent.poll()) != null) {
            if (msg instanceof WebProtocol.WebServerMessage.SessionsProgress p) {
                progress.add(p);
            }
        }
        assertThat(progress).isNotEmpty();
        assertThat(progress).allSatisfy(p -> {
            assertThat(p.total()).isEqualTo(count + 1);
            assertThat(p.loaded()).isBetween(1, count + 1);
            assertThat(p.sessions()).isNotEmpty();
        });
    }

    @Test
    void cancelThenRelistRecoversWithSingleTerminal() throws Exception {
        // 200 个会话文件：首轮扫描耗时 ≫ 同线程紧随的 cancel 到达时间（防竞态）。
        for (int i = 0; i < 200; i++) {
            seed("session " + i);
        }
        long gen0 = dispatcher.sessionsListingGeneration();
        dispatcher.handle(new WebProtocol.WebClientMessage.GetSessions());
        dispatcher.handle(new WebProtocol.WebClientMessage.CancelSessions());
        // 代次闸：cancel 翻代次（旧轮在飞回调/终态自弃）。
        assertThat(dispatcher.sessionsListingGeneration()).isGreaterThan(gen0);
        // 中止后重开一轮（pi 选择器重开即新扫）——终态只来一轮、内容全量。
        dispatcher.handle(new WebProtocol.WebClientMessage.GetSessions());
        var terminal = await(m -> m instanceof WebProtocol.WebServerMessage.Sessions, 30_000);
        assertThat(terminal).isNotNull();
        assertThat(((WebProtocol.WebServerMessage.Sessions) terminal).sessions())
            .hasSize(201);   // +1＝被测 web 会话自身
        // drain：新轮终态之后不应再有第二个终态帧（旧轮已被代次闸/中止压掉）。
        Thread.sleep(500);
        long terminals = 1;
        WebProtocol.WebServerMessage msg;
        while ((msg = sent.poll(100, TimeUnit.MILLISECONDS)) != null) {
            if (msg instanceof WebProtocol.WebServerMessage.Sessions) {
                terminals++;
            }
        }
        assertThat(terminals).isEqualTo(1);
    }

    @Test
    void wireShapes() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var progress = mapper.writeValueAsString(new WebProtocol.WebServerMessage.SessionsProgress(
            10, 25, List.of()));
        assertThat(progress).contains("\"type\":\"sessionsProgress\"")
            .contains("\"loaded\":10").contains("\"total\":25");
        var cancel = mapper.readValue("{\"type\":\"cancelSessions\"}",
            WebProtocol.WebClientMessage.class);
        assertThat(cancel).isInstanceOf(WebProtocol.WebClientMessage.CancelSessions.class);
    }
}
