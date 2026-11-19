package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/52 §2.4：bookmark 验收——倒序命中 assistant、默认 label、往返清除、未找到文案。 */
class BookmarkExtensionTest {

    private static Entry messageEntry(String id, boolean user, String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, null, Instant.ofEpochMilli(1), message, false);
    }

    private W3Context withEntries() {
        var w = new W3Context();
        w.entries = List.of(
            messageEntry("u1", true, "question"),
            messageEntry("a1", false, "first answer"),
            messageEntry("u2", true, "another"),
            messageEntry("a2", false, "last answer"));
        new BookmarkExtension().register(w.ctx);
        return w;
    }

    @Test
    void bookmarkLastAssistantAndUnbookmarkRoundTrip() {
        var w = withEntries();

        w.command("bookmark", "important");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Bookmarked as: important");
        assertThat(w.labels).containsEntry("a2", "important");

        w.command("unbookmark", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Removed bookmark: important");
        assertThat(w.labels).doesNotContainKey("a2");
    }

    @Test
    void defaultLabelHasBookmarkPrefix() {
        var w = withEntries();

        w.command("bookmark", "   ");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .anySatisfy(m -> assertThat(m).startsWith("Bookmarked as: bookmark-"));
        assertThat(w.labels.get("a2")).startsWith("bookmark-");
    }

    @Test
    void unbookmarkSkipsUnlabeledAndClearsMostRecent() {
        var w = withEntries();
        w.labels.put("a1", "old-bookmark");

        w.command("unbookmark", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Removed bookmark: old-bookmark");
        assertThat(w.labels).doesNotContainKey("a1");
    }

    @Test
    void notFoundArmsWarnVerbatim() {
        var w = new W3Context();
        w.entries = List.of(messageEntry("u1", true, "only user"));
        new BookmarkExtension().register(w.ctx);

        w.command("bookmark", "x");
        w.command("unbookmark", "");

        assertThat(w.ui.notices())
            .extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("No assistant message to bookmark", "No bookmarked entry found");
        assertThat(w.ui.notices()).allSatisfy(n ->
            assertThat(n.type()).isEqualTo(NotifyType.WARNING));
    }

    @Test
    void lastAssistantEntrySkipsNonMessages() {
        var user = messageEntry("u", true, "q");
        var assistant = messageEntry("a", false, "r");
        assertThat(BookmarkExtension.lastAssistantEntry(List.of(user, assistant)))
            .isSameAs(assistant);
        assertThat(BookmarkExtension.lastAssistantEntry(List.of(user))).isNull();
    }
}
