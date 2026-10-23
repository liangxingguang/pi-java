package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.Usage;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import org.junit.jupiter.api.Test;

/**
 * 批 15 T4：pi {@code serializeSessionBranch}（session-export.ts:9-31）＋
 * {@code createShareTrailingEntries}（session-share.ts:25-45）对拍——header 键、
 * {@code parentId} 重链（首条 null、逐条前驱）、trailing 形状、尾 {@code \n}。
 */
class SessionBranchSerializerTest {

    @Test
    void serializesHeaderRelinkedBranchAndShareTrailing() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--no-session"}),
                InMemorySessionRepository.create())) {
            var usage = new Usage(10, 5, 0, 0, null, null, 15, Usage.Cost.zero());
            session.appendUsage("cache_warm", "anthropic", "claude-fable-5", usage, null);
            session.appendUsage("cache_warm", "anthropic", "claude-fable-5", usage, null);

            var jsonl = SessionBranchSerializer.serialize(session);
            var lines = jsonl.split("\n");

            assertThat(jsonl).endsWith("\n");
            // header + 2 usage 条目 + 1 trailing。
            assertThat(lines).hasSize(4);

            var header = read(lines[0]);
            assertThat(header.at("/type").textValue()).isEqualTo("session");
            assertThat(header.at("/version").intValue())
                .isEqualTo(com.pijava.agent.session.jsonl.PiV3Wire.SESSION_VERSION);
            assertThat(header.at("/id").textValue()).isEqualTo(session.sessionId());
            assertThat(header.at("/cwd").textValue()).isEqualTo(session.cwd());
            assertThat(header.at("/timestamp").isTextual()).isTrue();

            // parentId 重链：首条 null、逐条前驱（覆盖原 parentId）。
            var first = read(lines[1]);
            var second = read(lines[2]);
            assertThat(first.at("/type").textValue()).isEqualTo("usage");
            assertThat(first.has("parentId") && first.at("/parentId").isNull()).isTrue();
            assertThat(first.has("seq")).as("落线去 seq").isFalse();
            assertThat(second.at("/parentId").textValue())
                .isEqualTo(first.at("/id").textValue());

            // trailing：pi.share 形状。
            var trailing = read(lines[3]);
            assertThat(trailing.at("/type").textValue()).isEqualTo("custom");
            assertThat(trailing.at("/customType").textValue()).isEqualTo("pi.share");
            assertThat(trailing.at("/id").textValue()).hasSize(8);
            assertThat(trailing.at("/parentId").textValue())
                .isEqualTo(second.at("/id").textValue());
            assertThat(trailing.at("/data").has("systemPrompt")).isTrue();
            assertThat(trailing.at("/data/tools").isArray()).isTrue();
            // 注册的工具带 name/description/parameters 三键。
            var tools = trailing.at("/data/tools");
            assertThat(tools.size()).isGreaterThan(0);
            assertThat(tools.get(0).has("name")).isTrue();
            assertThat(tools.get(0).has("description")).isTrue();
            assertThat(tools.get(0).has("parameters")).isTrue();
        }
    }

    @Test
    void customEntriesRideTheBranchAndRelink() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--no-session"}),
                InMemorySessionRepository.create())) {
            session.appendCustomEntry("pi.bug-report", Map.of("id", "r1", "delivery", "zip"));

            var lines = SessionBranchSerializer.serialize(session).split("\n");
            // header + 1 custom + trailing。
            assertThat(lines).hasSize(3);
            var custom = read(lines[1]);
            assertThat(custom.at("/customType").textValue()).isEqualTo("pi.bug-report");
            assertThat(custom.at("/data/id").textValue()).isEqualTo("r1");
            assertThat(read(lines[2]).at("/parentId").textValue())
                .isEqualTo(custom.at("/id").textValue());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode read(String line) {
        try {
            return SessionJson.mapper().readTree(line);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
