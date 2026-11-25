package com.pijava.ai.catalog;

import java.util.Map;

/**
 * {@link ModelCompat} 的逐字段复制器（B156）。
 *
 * <p>重建点只写「本车道要改哪些组件」，其余组件由 {@link #from(ModelCompat)}
 * 一次拷齐。新增组件时只需同步本文件的 {@code from}/{@code build} 与
 * {@link ModelCompat}，重建点不再逐字段感知；setter 带名字，也避免相邻
 * 同类型参数按位置错位。</p>
 */
public final class ModelCompatBuilder {

    private boolean allowEmptySignature;
    private Boolean requiresReasoningContentOnAssistantMessages;
    private boolean supportsFinishReason;
    private boolean forceAdaptiveThinking;
    private Boolean supportsMidConvoSystemMessages;
    private Boolean supportsMidConvoToolAdditions;
    private Boolean supportsMidConvoToolChanges;
    private Boolean supportsAdditionalTools;
    private Boolean supportsToolSearch;
    private boolean supportsTemperature;
    private MaxTokensField maxTokensField;
    private Boolean supportsStore;
    private Boolean supportsDeveloperRole;
    private Boolean supportsStrictMode;
    private Boolean supportsLongCacheRetention;
    private Boolean supportsCacheControlOnTools;
    private ThinkingTokenBudgetField thinkingTokenBudgetField;
    private Boolean supportsThinkingTokenBudget;
    private Boolean supportsMaxOutputTokens;
    private ThinkingFormat thinkingFormat;
    private Map<String, ChatTemplateKwargValue> chatTemplateKwargs;
    private Map<String, ChatTemplateKwargValue> chatTemplateArgs;
    private Boolean supportsReasoningEffort;
    private CacheControlFormat cacheControlFormat;
    private Map<String, Object> openRouterRouting;
    private Boolean sendSessionAffinityHeaders;
    private SessionAffinityFormat sessionAffinityFormat;
    private boolean supportsStrictTools;
    private Boolean supportsOpenAIGrammarTools;
    private Boolean supportsEagerToolInputStreaming;
    private Boolean supportsExplicitPromptCacheMode;
    private Boolean requiresToolResultName;
    private Boolean requiresAssistantAfterToolResult;
    private Boolean requiresThinkingAsText;
    private Boolean supportsUsageInStreaming;
    private Boolean zaiToolStream;
    private Integer vllmPriority;
    private VercelGatewayRouting vercelGatewayRouting;
    private java.util.List<AllowedFallbackModel> allowedFallbackModels;
    private Boolean supportsMidConvoEffort;
    private Boolean requiresNonNullAssistantContent;
    private Boolean requiresToolsWithToolHistory;
    private PromptCacheKeyMode promptCacheKeyMode;

    private ModelCompatBuilder() {
    }

    static ModelCompatBuilder from(ModelCompat c) {
        var b = new ModelCompatBuilder();
        b.allowEmptySignature = c.allowEmptySignature();
        b.requiresReasoningContentOnAssistantMessages = c.requiresReasoningContentOnAssistantMessages();
        b.supportsFinishReason = c.supportsFinishReason();
        b.forceAdaptiveThinking = c.forceAdaptiveThinking();
        b.supportsMidConvoSystemMessages = c.supportsMidConvoSystemMessages();
        b.supportsMidConvoToolAdditions = c.supportsMidConvoToolAdditions();
        b.supportsMidConvoToolChanges = c.supportsMidConvoToolChanges();
        b.supportsAdditionalTools = c.supportsAdditionalTools();
        b.supportsToolSearch = c.supportsToolSearch();
        b.supportsTemperature = c.supportsTemperature();
        b.maxTokensField = c.maxTokensField();
        b.supportsStore = c.supportsStore();
        b.supportsDeveloperRole = c.supportsDeveloperRole();
        b.supportsStrictMode = c.supportsStrictMode();
        b.supportsLongCacheRetention = c.supportsLongCacheRetention();
        b.supportsCacheControlOnTools = c.supportsCacheControlOnTools();
        b.thinkingTokenBudgetField = c.thinkingTokenBudgetField();
        b.supportsThinkingTokenBudget = c.supportsThinkingTokenBudget();
        b.supportsMaxOutputTokens = c.supportsMaxOutputTokens();
        b.thinkingFormat = c.thinkingFormat();
        b.chatTemplateKwargs = c.chatTemplateKwargs();
        b.chatTemplateArgs = c.chatTemplateArgs();
        b.supportsReasoningEffort = c.supportsReasoningEffort();
        b.cacheControlFormat = c.cacheControlFormat();
        b.openRouterRouting = c.openRouterRouting();
        b.sendSessionAffinityHeaders = c.sendSessionAffinityHeaders();
        b.sessionAffinityFormat = c.sessionAffinityFormat();
        b.supportsStrictTools = c.supportsStrictTools();
        b.supportsOpenAIGrammarTools = c.supportsOpenAIGrammarTools();
        b.supportsEagerToolInputStreaming = c.supportsEagerToolInputStreaming();
        b.supportsExplicitPromptCacheMode = c.supportsExplicitPromptCacheMode();
        b.requiresToolResultName = c.requiresToolResultName();
        b.requiresAssistantAfterToolResult = c.requiresAssistantAfterToolResult();
        b.requiresThinkingAsText = c.requiresThinkingAsText();
        b.supportsUsageInStreaming = c.supportsUsageInStreaming();
        b.zaiToolStream = c.zaiToolStream();
        b.vllmPriority = c.vllmPriority();
        b.vercelGatewayRouting = c.vercelGatewayRouting();
        b.allowedFallbackModels = c.allowedFallbackModels();
        b.supportsMidConvoEffort = c.supportsMidConvoEffort();
        b.requiresNonNullAssistantContent = c.requiresNonNullAssistantContent();
        b.requiresToolsWithToolHistory = c.requiresToolsWithToolHistory();
        b.promptCacheKeyMode = c.promptCacheKeyMode();
        return b;
    }

    public ModelCompatBuilder allowEmptySignature(boolean value) {
        this.allowEmptySignature = value;
        return this;
    }

    public ModelCompatBuilder requiresReasoningContentOnAssistantMessages(Boolean value) {
        this.requiresReasoningContentOnAssistantMessages = value;
        return this;
    }

    public ModelCompatBuilder supportsFinishReason(boolean value) {
        this.supportsFinishReason = value;
        return this;
    }

    public ModelCompatBuilder forceAdaptiveThinking(boolean value) {
        this.forceAdaptiveThinking = value;
        return this;
    }

    public ModelCompatBuilder supportsMidConvoSystemMessages(Boolean value) {
        this.supportsMidConvoSystemMessages = value;
        return this;
    }

    public ModelCompatBuilder supportsMidConvoToolAdditions(Boolean value) {
        this.supportsMidConvoToolAdditions = value;
        return this;
    }

    public ModelCompatBuilder supportsMidConvoToolChanges(Boolean value) {
        this.supportsMidConvoToolChanges = value;
        return this;
    }

    public ModelCompatBuilder supportsAdditionalTools(Boolean value) {
        this.supportsAdditionalTools = value;
        return this;
    }

    public ModelCompatBuilder supportsToolSearch(Boolean value) {
        this.supportsToolSearch = value;
        return this;
    }

    public ModelCompatBuilder supportsTemperature(boolean value) {
        this.supportsTemperature = value;
        return this;
    }

    public ModelCompatBuilder maxTokensField(MaxTokensField value) {
        this.maxTokensField = value;
        return this;
    }

    public ModelCompatBuilder supportsStore(Boolean value) {
        this.supportsStore = value;
        return this;
    }

    public ModelCompatBuilder supportsDeveloperRole(Boolean value) {
        this.supportsDeveloperRole = value;
        return this;
    }

    public ModelCompatBuilder supportsStrictMode(Boolean value) {
        this.supportsStrictMode = value;
        return this;
    }

    public ModelCompatBuilder supportsLongCacheRetention(Boolean value) {
        this.supportsLongCacheRetention = value;
        return this;
    }

    public ModelCompatBuilder supportsCacheControlOnTools(Boolean value) {
        this.supportsCacheControlOnTools = value;
        return this;
    }

    public ModelCompatBuilder thinkingTokenBudgetField(ThinkingTokenBudgetField value) {
        this.thinkingTokenBudgetField = value;
        return this;
    }

    public ModelCompatBuilder supportsThinkingTokenBudget(Boolean value) {
        this.supportsThinkingTokenBudget = value;
        return this;
    }

    public ModelCompatBuilder supportsMaxOutputTokens(Boolean value) {
        this.supportsMaxOutputTokens = value;
        return this;
    }

    public ModelCompatBuilder thinkingFormat(ThinkingFormat value) {
        this.thinkingFormat = value;
        return this;
    }

    public ModelCompatBuilder chatTemplateKwargs(Map<String, ChatTemplateKwargValue> value) {
        this.chatTemplateKwargs = value;
        return this;
    }

    public ModelCompatBuilder chatTemplateArgs(Map<String, ChatTemplateKwargValue> value) {
        this.chatTemplateArgs = value;
        return this;
    }

    public ModelCompatBuilder supportsReasoningEffort(Boolean value) {
        this.supportsReasoningEffort = value;
        return this;
    }

    public ModelCompatBuilder cacheControlFormat(CacheControlFormat value) {
        this.cacheControlFormat = value;
        return this;
    }

    public ModelCompatBuilder openRouterRouting(Map<String, Object> value) {
        this.openRouterRouting = value;
        return this;
    }

    public ModelCompatBuilder sendSessionAffinityHeaders(Boolean value) {
        this.sendSessionAffinityHeaders = value;
        return this;
    }

    public ModelCompatBuilder sessionAffinityFormat(SessionAffinityFormat value) {
        this.sessionAffinityFormat = value;
        return this;
    }

    public ModelCompatBuilder supportsStrictTools(boolean value) {
        this.supportsStrictTools = value;
        return this;
    }

    public ModelCompatBuilder supportsOpenAIGrammarTools(Boolean value) {
        this.supportsOpenAIGrammarTools = value;
        return this;
    }

    public ModelCompatBuilder supportsEagerToolInputStreaming(Boolean value) {
        this.supportsEagerToolInputStreaming = value;
        return this;
    }

    public ModelCompatBuilder supportsExplicitPromptCacheMode(Boolean value) {
        this.supportsExplicitPromptCacheMode = value;
        return this;
    }

    public ModelCompatBuilder requiresToolResultName(Boolean value) {
        this.requiresToolResultName = value;
        return this;
    }

    public ModelCompatBuilder requiresAssistantAfterToolResult(Boolean value) {
        this.requiresAssistantAfterToolResult = value;
        return this;
    }

    public ModelCompatBuilder requiresThinkingAsText(Boolean value) {
        this.requiresThinkingAsText = value;
        return this;
    }

    public ModelCompatBuilder supportsUsageInStreaming(Boolean value) {
        this.supportsUsageInStreaming = value;
        return this;
    }

    public ModelCompatBuilder zaiToolStream(Boolean value) {
        this.zaiToolStream = value;
        return this;
    }

    public ModelCompatBuilder vllmPriority(Integer value) {
        this.vllmPriority = value;
        return this;
    }

    public ModelCompatBuilder vercelGatewayRouting(VercelGatewayRouting value) {
        this.vercelGatewayRouting = value;
        return this;
    }

    public ModelCompatBuilder allowedFallbackModels(java.util.List<AllowedFallbackModel> value) {
        this.allowedFallbackModels = value;
        return this;
    }

    public ModelCompatBuilder supportsMidConvoEffort(Boolean value) {
        this.supportsMidConvoEffort = value;
        return this;
    }

    public ModelCompatBuilder requiresNonNullAssistantContent(Boolean value) {
        this.requiresNonNullAssistantContent = value;
        return this;
    }

    public ModelCompatBuilder requiresToolsWithToolHistory(Boolean value) {
        this.requiresToolsWithToolHistory = value;
        return this;
    }

    public ModelCompatBuilder promptCacheKeyMode(PromptCacheKeyMode value) {
        this.promptCacheKeyMode = value;
        return this;
    }

    public ModelCompat build() {
        return new ModelCompat(
            allowEmptySignature,
            requiresReasoningContentOnAssistantMessages,
            supportsFinishReason,
            forceAdaptiveThinking,
            supportsMidConvoSystemMessages,
            supportsMidConvoToolAdditions,
            supportsMidConvoToolChanges,
            supportsAdditionalTools,
            supportsToolSearch,
            supportsTemperature,
            maxTokensField,
            supportsStore,
            supportsDeveloperRole,
            supportsStrictMode,
            supportsLongCacheRetention,
            supportsCacheControlOnTools,
            thinkingTokenBudgetField,
            supportsThinkingTokenBudget,
            supportsMaxOutputTokens,
            thinkingFormat,
            chatTemplateKwargs,
            chatTemplateArgs,
            supportsReasoningEffort,
            cacheControlFormat,
            openRouterRouting,
            sendSessionAffinityHeaders,
            sessionAffinityFormat,
            supportsStrictTools,
            supportsOpenAIGrammarTools,
            supportsEagerToolInputStreaming,
            supportsExplicitPromptCacheMode,
            requiresToolResultName,
            requiresAssistantAfterToolResult,
            requiresThinkingAsText,
            supportsUsageInStreaming,
            zaiToolStream,
            vllmPriority,
            vercelGatewayRouting,
            allowedFallbackModels,
            supportsMidConvoEffort,
            requiresNonNullAssistantContent,
            requiresToolsWithToolHistory,
            promptCacheKeyMode);
    }
}
