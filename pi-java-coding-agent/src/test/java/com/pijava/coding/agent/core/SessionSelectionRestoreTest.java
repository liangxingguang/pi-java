package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.core.virtual.VirtualModelSelections;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSelectionRestoreTest {

    private static Entry.ModelChange modelChange(String provider, String model) {
        return new Entry.ModelChange("mc-" + provider + "-" + model, 0, null,
            Instant.EPOCH, provider, model);
    }

    private static Entry assistant(String provider, String model) {
        return new Entry.Message("msg-" + provider + "-" + model, 0, null, Instant.EPOCH,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi")),
                null, null, null, provider, model, null, null,
                null, null, null, null, null, null, null, null), false);
    }

    private static Entry assistantWithoutProvider() {
        return new Entry.Message("msg-bare", 0, null, Instant.EPOCH,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi"))), false);
    }

    @Test
    void aPhysicalChangeBeforeTheResponseLosesToTheRespondingModel() {
        var branch = List.of(modelChange("openai", "gpt-a"), assistant("anthropic", "claude-b"));

        var selection = VirtualModelSelections.getBranchSelection(branch,
            id -> true, id -> false);

        assertThat(selection).isEqualTo(new VirtualModelSelections.Selection(
            "anthropic", "claude-b"));
    }

    @Test
    void aRegisteredVirtualChangeHoldsOverThePhysicalResponse() {
        var branch = List.of(modelChange("router", "fast"), assistant("anthropic", "claude-b"));

        var selection = VirtualModelSelections.getBranchSelection(branch,
            id -> true, id -> ModelId.of("router", "fast").equals(id));

        assertThat(selection).isEqualTo(new VirtualModelSelections.Selection(
            "router", "fast"));
    }

    @Test
    void anUnregisteredVirtualChangeFallsBackToThePhysicalResponse() {
        var branch = List.of(modelChange("router", "gone"), assistant("anthropic", "claude-b"));

        var selection = VirtualModelSelections.getBranchSelection(branch,
            id -> false, id -> ModelId.of("router", "gone").equals(id));

        assertThat(selection).isEqualTo(new VirtualModelSelections.Selection(
            "anthropic", "claude-b"));
    }

    @Test
    void theLatestModelChangeWinsWithoutResponses() {
        var branch = List.<Entry>of(modelChange("openai", "gpt-a"),
            modelChange("anthropic", "claude-b"));

        var selection = VirtualModelSelections.getBranchSelection(branch,
            id -> true, id -> false);

        assertThat(selection).isEqualTo(new VirtualModelSelections.Selection(
            "anthropic", "claude-b"));
    }

    @Test
    void messagesWithoutModelFieldsAreSkipped() {
        var branch = List.of(modelChange("openai", "gpt-a"), assistantWithoutProvider());

        var selection = VirtualModelSelections.getBranchSelection(branch,
            id -> true, id -> false);

        assertThat(selection).isEqualTo(new VirtualModelSelections.Selection(
            "openai", "gpt-a"));
    }

    @Test
    void clampWalksUpThenDownTheExtendedScale() {
        var highOnly = List.of(ModelThinkingLevel.off(),
            ModelThinkingLevel.of(new ThinkingLevel.High()));
        var mediumDown = List.of(ModelThinkingLevel.off(),
            ModelThinkingLevel.of(new ThinkingLevel.Medium()));

        assertThat(SessionSelectionRestore.clamp(
            ModelThinkingLevel.of(new ThinkingLevel.XHigh()), highOnly).label())
            .isEqualTo("high");
        assertThat(SessionSelectionRestore.clamp(
            ModelThinkingLevel.of(new ThinkingLevel.XHigh()), mediumDown).label())
            .isEqualTo("medium");
        // low climbs to the next available level, high (pi walks up first).
        assertThat(SessionSelectionRestore.clamp(
            ModelThinkingLevel.of(new ThinkingLevel.Low()), highOnly).label())
            .isEqualTo("high");
    }
}
