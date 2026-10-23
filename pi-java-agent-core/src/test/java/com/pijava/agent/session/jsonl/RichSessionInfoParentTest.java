package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 14（docs/25 T5，§7 #15）：header {@code parentSession} ⇒
 * {@link RichSessionInfo#parentSessionPath()}（threaded 树认父）；缺席 ⇒ {@code null}。
 */
class RichSessionInfoParentTest {

    private static final String CWD = System.getProperty("user.dir");

    private static Entry.Message user(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    @Test
    void headerParentSessionFlowsIntoRichInfo() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-parent");
        var repo = JsonlSessionRepository.over(root);
        var child = repo.create(new JsonlSessionCreateOptions(null, CWD, "parent-ref-123", null));
        child.appendEntry(new ProvisionedEntry<>(user("hello")), "main");
        child.storage().drain();

        var infos = JsonlSessionRepository.over(root).listRich(CWD, null, null);

        assertThat(infos).hasSize(1);
        assertThat(infos.getFirst().parentSessionPath()).isEqualTo("parent-ref-123");
    }

    @Test
    void absentParentSessionYieldsNull() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-no-parent");
        var repo = JsonlSessionRepository.over(root);
        var session = repo.create(new JsonlSessionCreateOptions(null, CWD, null, null));
        session.appendEntry(new ProvisionedEntry<>(user("hello")), "main");
        session.storage().drain();

        var infos = JsonlSessionRepository.over(root).listRich(CWD, null, null);

        assertThat(infos).hasSize(1);
        assertThat(infos.getFirst().parentSessionPath()).isNull();
    }
}
