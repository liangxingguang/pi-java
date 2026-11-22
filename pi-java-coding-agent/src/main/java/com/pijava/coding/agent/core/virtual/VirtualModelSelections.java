package com.pijava.coding.agent.core.virtual;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.Json;

/**
 * Branch-scan helpers over session entries — pi {@code virtual-models.ts} 的
 * {@code findLatestResponse}/{@code getVirtualModelState} 对位。
 *
 * <p>L5 W4 起 {@link #getBranchSelection} 落地，消费者是 resume 选型恢复
 * （{@link com.pijava.coding.agent.core.SessionSelectionRestore}，pi sdk.ts:217-231）。</p>
 */
public final class VirtualModelSelections {

    private VirtualModelSelections() {}

    /**
     * pi {@code findLatestResponse}（:110-118 逐字）：从后往前找第一条非
     * error/aborted 的 assistant 消息。
     */
    public static Message.AssistantMessage findLatestResponse(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage assistant
                    && assistant.stopReason() != StopReason.ERROR
                    && assistant.stopReason() != StopReason.ABORTED) {
                return assistant;
            }
        }
        return null;
    }

    /** The model selection a session branch records (pi {@code {provider, modelId}}). */
    public record Selection(String provider, String modelId) {}

    /**
     * The model selection a session branch records (pi {@code getBranchSelection}).
     *
     * <p>A virtual {@code model_change} holds until the next {@code model_change},
     * because responses name the physical models it routed to. Otherwise the latest
     * physical response wins. A virtual model that is no longer registered does not
     * hold, so the selection falls back to the physical model that answered last.</p>
     *
     * @param branch       tree-root-to-leaf entry path
     * @param modelExists  whether the catalog/runtime knows the model
     * @param isVirtual    whether the model is a registered virtual model
     */
    public static Selection getBranchSelection(java.util.List<Entry> branch,
                                               java.util.function.Predicate<com.pijava.ai.model.ModelId<?>> modelExists,
                                               java.util.function.Predicate<com.pijava.ai.model.ModelId<?>> isVirtual) {
        for (var i = branch.size() - 1; i >= 0; i--) {
            var entry = branch.get(i);
            if (entry instanceof Entry.ModelChange change) {
                return new Selection(change.provider(), change.modelId());
            }
            if (entry instanceof Entry.Message message
                    && message.message() instanceof Message.AssistantMessage assistant
                    && assistant.provider() != null && assistant.model() != null
                    && !isVirtual.test(com.pijava.ai.model.ModelId.of(
                        assistant.provider(), assistant.model()))) {
                var response = new Selection(assistant.provider(), assistant.model());
                var change = findLastModelChange(branch, i);
                if (change != null) {
                    var changeId = com.pijava.ai.model.ModelId.of(
                        change.provider(), change.modelId());
                    if (modelExists.test(changeId) && isVirtual.test(changeId)) {
                        return change;
                    }
                }
                return response;
            }
        }
        return null;
    }

    private static Selection findLastModelChange(java.util.List<Entry> branch, int before) {
        for (var i = before - 1; i >= 0; i--) {
            if (branch.get(i) instanceof Entry.ModelChange change) {
                return new Selection(change.provider(), change.modelId());
            }
        }
        return null;
    }

    /**
     * pi {@code getVirtualModelState}（:159-167 逐字）：分支上从后往前找
     * {@code {provider, modelId}} 匹配的 {@code pi.virtual-model-state} 自定义条目，
     * 返回其 {@code state}——路由态天然跟随 session 树分叉与压缩。
     *
     * @param branch 树根到叶的 entry 路径
     */
    public static JsonNode getVirtualModelState(List<Entry> branch, String provider,
                                                String modelId) {
        for (int i = branch.size() - 1; i >= 0; i--) {
            if (!(branch.get(i) instanceof Entry.Custom custom)
                    || !VirtualModelRegistry.STATE_ENTRY.equals(custom.customType())
                    || custom.data() == null) {
                continue;
            }
            var data = custom.data();
            if (provider.equals(data.get("provider")) && modelId.equals(data.get("modelId"))) {
                var state = data.get("state");
                return state == null ? null : Json.mapper().valueToTree(state);
            }
        }
        return null;
    }

    /** 路由态条目的 data 形状（pi {@code VirtualModelStateData}，:35-39）。 */
    public static Map<String, Object> stateData(String provider, String modelId,
                                                JsonNode state) {
        return Map.of("provider", provider, "modelId", modelId,
            "state", Json.mapper().convertValue(state, Object.class));
    }
}
