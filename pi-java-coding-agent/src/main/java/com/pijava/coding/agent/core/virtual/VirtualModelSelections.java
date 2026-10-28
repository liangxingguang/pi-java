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
 * <p>{@code getBranchSelection} 不落：它的唯一消费者是 pi 的 resume 选型恢复
 * （sdk.ts:217-231），java 的 resume 不从分支恢复模型（存量方言）——无消费者
 * 不接线（B65 先例），随未来「resume 接分支选型」批一并落地（docs/04 挂账）。</p>
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
