package com.pijava.evals.harness;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.AgentSession;

/**
 * 批 32（docs/44 §3.2）：真 harness 的输入选项 —— pi
 * {@code PiCodingAgentHarnessOptions}（{@code harness.ts:50-59}）对位。
 *
 * @param name                       variant name (used in verification messages)
 * @param provider                   provider name (explicit or {@code PI_PROVIDER})
 * @param model                      model id (explicit or {@code PI_MODEL})
 * @param thinking                   thinking level, default {@code "off"}
 * @param tools                      activated tool allowlist; {@code null} ⇒ 不限制
 * @param noTools                    disable all tools
 * @param customTools                tools registered through an inline extension
 * @param workspaceFiles             relative workspace fixture files
 * @param transformSystemPrompt      prompt transform ({@code without_docs})
 * @param expectedPiDocumentation    {@code null} ⇒ 不校验；true/false ⇒ 校验 {@code <docs>} 段
 * @param noSession                  ephemeral session (no artifact)
 * @param noExtensions               disable extension discovery
 * @param sessionArtifactDir         where {@code session.jsonl} is copied; {@code null} ⇒ 不落盘
 * @param providers                  provider registry; {@code null} ⇒ 内置默认表
 * @param sessionCustomizer          runs once on the assembled session (批 32 T12：
 *                                   扩展作者 eval 用它把装载工具的句柄接到会话)
 * @param output                     judged output producer (pi {@code options.output});
 *                                   {@code null} ⇒ 判分器看模型回复文本
 */
public record HarnessOptions(
    String name,
    String provider,
    String model,
    String thinking,
    List<String> tools,
    boolean noTools,
    List<AgentTool<?, ?>> customTools,
    Map<String, String> workspaceFiles,
    UnaryOperator<String> transformSystemPrompt,
    Boolean expectedPiDocumentation,
    boolean noSession,
    boolean noExtensions,
    Path sessionArtifactDir,
    ProviderRegistry providers,
    Consumer<AgentSession> sessionCustomizer,
    Function<OutputContext, Object> output
) {

    /** Defensive copies and defaults. */
    public HarnessOptions {
        name = name == null || name.isBlank() ? "pi-coding-agent" : name;
        thinking = thinking == null || thinking.isBlank() ? "off" : thinking;
        tools = tools == null ? null : List.copyOf(tools);
        customTools = customTools == null ? List.of() : List.copyOf(customTools);
        workspaceFiles = workspaceFiles == null ? Map.of() : Map.copyOf(workspaceFiles);
    }

    /**
     * @return empty options with defaults
     */
    public static HarnessOptions defaults() {
        return builder().build();
    }

    /** @return a fresh builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return a builder pre-populated with this instance's values
     */
    public Builder toBuilder() {
        return builder().name(name).provider(provider).model(model).thinking(thinking)
            .tools(tools).noTools(noTools).customTools(customTools)
            .workspaceFiles(workspaceFiles).transformSystemPrompt(transformSystemPrompt)
            .expectedPiDocumentation(expectedPiDocumentation).noSession(noSession)
            .noExtensions(noExtensions).sessionArtifactDir(sessionArtifactDir)
            .providers(providers).sessionCustomizer(sessionCustomizer).output(output);
    }

    /**
     * Inputs handed to {@link #output()}: pi
     * {@code options.output({response, session, systemPrompt, agentDir})}.
     *
     * @param response     last assistant text
     * @param session      assembled session
     * @param systemPrompt model-visible system prompt
     * @param agentDir     isolated agent dir
     */
    public record OutputContext(String response, AgentSession session, String systemPrompt,
                                Path agentDir) {
    }

    /** Fluent builder. */
    public static final class Builder {
        private String name;
        private String provider;
        private String model;
        private String thinking;
        private List<String> tools;
        private boolean noTools;
        private List<AgentTool<?, ?>> customTools = List.of();
        private Map<String, String> workspaceFiles = Map.of();
        private UnaryOperator<String> transformSystemPrompt;
        private Boolean expectedPiDocumentation;
        private boolean noSession;
        private boolean noExtensions;
        private Path sessionArtifactDir;
        private ProviderRegistry providers;
        private Consumer<AgentSession> sessionCustomizer;
        private Function<OutputContext, Object> output;

        private Builder() {
        }

        /** @param value variant name @return this */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @param value provider name @return this */
        public Builder provider(String value) {
            this.provider = value;
            return this;
        }

        /** @param value model id @return this */
        public Builder model(String value) {
            this.model = value;
            return this;
        }

        /** @param value thinking level @return this */
        public Builder thinking(String value) {
            this.thinking = value;
            return this;
        }

        /** @param value tool allowlist @return this */
        public Builder tools(List<String> value) {
            this.tools = value;
            return this;
        }

        /** @param value disable all tools @return this */
        public Builder noTools(boolean value) {
            this.noTools = value;
            return this;
        }

        /** @param value inline custom tools @return this */
        public Builder customTools(List<AgentTool<?, ?>> value) {
            this.customTools = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        /** @param value seeded workspace files @return this */
        public Builder workspaceFiles(Map<String, String> value) {
            this.workspaceFiles = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }

        /** @param value system-prompt transform @return this */
        public Builder transformSystemPrompt(UnaryOperator<String> value) {
            this.transformSystemPrompt = value;
            return this;
        }

        /** @param value expected docs presence @return this */
        public Builder expectedPiDocumentation(Boolean value) {
            this.expectedPiDocumentation = value;
            return this;
        }

        /** @param value ephemeral session @return this */
        public Builder noSession(boolean value) {
            this.noSession = value;
            return this;
        }

        /** @param value disable extension discovery @return this */
        public Builder noExtensions(boolean value) {
            this.noExtensions = value;
            return this;
        }

        /** @param value session artifact directory @return this */
        public Builder sessionArtifactDir(Path value) {
            this.sessionArtifactDir = value;
            return this;
        }

        /** @param value provider registry @return this */
        public Builder providers(ProviderRegistry value) {
            this.providers = value;
            return this;
        }

        /** @param value assembled-session hook @return this */
        public Builder sessionCustomizer(Consumer<AgentSession> value) {
            this.sessionCustomizer = value;
            return this;
        }

        /** @param value judged output producer @return this */
        public Builder output(Function<OutputContext, Object> value) {
            this.output = value;
            return this;
        }

        /** @return immutable options */
        public HarnessOptions build() {
            return new HarnessOptions(name, provider, model, thinking, tools, noTools,
                customTools, workspaceFiles, transformSystemPrompt,
                expectedPiDocumentation, noSession, noExtensions, sessionArtifactDir,
                providers, sessionCustomizer, output);
        }
    }
}
