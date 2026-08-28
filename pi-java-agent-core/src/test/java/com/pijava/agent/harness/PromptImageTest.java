package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptImageTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", partial),
            new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            DriveMode.MANUAL, null, java.util.Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    private static void drive(AgentHarness h) {
        var action = h.peekAction();
        while (action != null) { action = h.executeAction(action); }
    }

    private static List<ContentBlock> lastUserContent(AgentHarness h) {
        var msgs = h.snapshot("default").transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .toList();
        return ((Entry.Message) msgs.get(msgs.size() - 1)).message().content();
    }

    @Test
    void runWithImagesBuildsTextThenImageContent() {
        var h = harness();
        h.run("look", List.of(new PromptImage("image/png", "aGk=")));
        var content = lastUserContent(h);
        assertThat(content).hasSize(2);
        assertThat(content.get(0)).isInstanceOf(ContentBlock.TextContent.class);
        var img = (ContentBlock.ImageContent) content.get(1);
        assertThat(img.mediaType()).isEqualTo("image/png");
        assertThat(img.data()).isEqualTo("aGk=");
    }

    @Test
    void steerWithImagesCarriesImagesIntoInjectedEntry() {
        var h = harness();
        h.run("first");
        drive(h);
        h.steer("default", "with pic", List.of(new PromptImage("image/jpeg", "eg==")));
        var action = h.peekAction("default"); // idle + steer queued → new run
        assertThat(action).isNotNull();
        var a = action;
        while (a != null) { a = h.executeAction("default", a); }
        var content = lastUserContent(h);
        assertThat(content.get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(content).anyMatch(b -> b instanceof ContentBlock.ImageContent
            && "image/jpeg".equals(((ContentBlock.ImageContent) b).mediaType()));
    }

    @Test
    void invalidPromptImageRejected() {
        assertThatThrownBy(() -> new PromptImage("text/plain", "aGk="))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptImage("image/png", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullImagesListTreatedAsEmpty() {
        var h = harness();
        h.run("plain", (List<PromptImage>) null);
        assertThat(lastUserContent(h)).hasSize(1);
    }

    @Test
    void plainTextOverloadUnchanged() {
        var h = harness();
        h.run("plain");
        assertThat(lastUserContent(h)).hasSize(1);
    }
}
