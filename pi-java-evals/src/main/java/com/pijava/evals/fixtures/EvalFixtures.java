package com.pijava.evals.fixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * 批 32（docs/44 §8.4）：provider／model 文档 eval 的共享夹具 —— pi
 * {@code eval-fixtures.ts} 对位。
 *
 * <p>Java 的 read/write 工具只允许写 workspace，无法让模型直接编辑隔离 home 的
 * {@code models.json}；因此这里提供内联工具 {@code inspect_provider}：它按模型给出的
 * provider/api/model 把配置写入 {@code models.json}（隔离 home 内），再调用
 * {@link ConfiguredRuntimeInspector} 探针并把结果 JSON 返回给模型。模型仍须从文档里
 * 选出正确的协议名，文档 lift 语义不变；工具位差登记于 T13 台账（拟 D186）。</p>
 */
public final class EvalFixtures {

    /** 内联工具名。 */
    public static final String INSPECT_PROVIDER_TOOL = "inspect_provider";

    /** 模型作者 eval 的内联工具名。 */
    public static final String INSPECT_ADDED_MODEL_TOOL = "inspect_added_model";

    /** 模型作者 eval 的目标 provider。 */
    public static final String ADDED_MODEL_PROVIDER = "openai";

    /** 模型作者 eval 的目标模型 id。 */
    public static final String ADDED_MODEL_ID = "fixture-chat";

    /** 模型作者 eval 的模型显示名。 */
    public static final String ADDED_MODEL_NAME = "Fixture Chat";

    /** 模型作者 eval 的上下文窗口。 */
    public static final int ADDED_MODEL_CONTEXT_WINDOW = 32768;

    /** 模型作者 eval 的最大输出 token。 */
    public static final int ADDED_MODEL_MAX_TOKENS = 4096;

    /** 自定义流式车道的 provider id。 */
    public static final String CUSTOM_PROVIDER = "acme";

    /** OpenAI 兼容车道的 provider id。 */
    public static final String OPENAI_PROVIDER = "fixture-openai";

    /** 两条车道共用的模型 id。 */
    public static final String MODEL_ID = "fixture-chat";

    /** 模型显示名。 */
    public static final String MODEL_NAME = "Fixture Chat";

    /** 夹具内联凭证。 */
    public static final String API_KEY = "acme-fixture-key";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalFixtures() {
    }

    /** Lazy shared fixture server (虚拟线程执行器，进程退出即回收)。 */
    private static final class SharedServer {
        private static final AcmeServer INSTANCE = start();

        private static AcmeServer start() {
            try {
                return AcmeServer.start();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start the eval fixture server", e);
            }
        }
    }

    /**
     * Shared fixture server used by the live provider evals.
     *
     * @return running shared server
     */
    public static AcmeServer server() {
        return SharedServer.INSTANCE;
    }

    /**
     * Probe expectation shared by the provider evals.
     *
     * @return {@code {text, stopReason, inputTokens, outputTokens}}
     */
    public static Map<String, Object> probeExpectation() {
        return Map.of(
            "text", AcmeServer.REPLY_TEXT,
            "stopReason", "stop",
            "inputTokens", AcmeServer.INPUT_TOKENS,
            "outputTokens", AcmeServer.OUTPUT_TOKENS);
    }

    /**
     * Inline tool backed by the shared server and the current isolated home.
     *
     * @return inspect_provider tool
     */
    public static AgentTool<Map<String, Object>, Void> inspectProviderTool() {
        return inspectProviderTool(server(), ModelsJsonConfig::defaultPath);
    }

    /**
     * Inline tool with injectable server and {@code models.json} location (tests).
     *
     * @param server         fixture server
     * @param modelsJsonPath where the tool writes {@code models.json}
     * @return inspect_provider tool
     */
    public static AgentTool<Map<String, Object>, Void> inspectProviderTool(
            AcmeServer server, Supplier<Path> modelsJsonPath) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(modelsJsonPath, "modelsJsonPath");
        return new AgentTool<>() {
            @Override public String name() {
                return INSPECT_PROVIDER_TOOL;
            }

            @Override public String label() {
                return "inspect provider";
            }

            @Override public String promptSnippet() {
                return "Configure a provider in models.json and probe it";
            }

            @Override public String description() {
                return "Write the provider entry to models.json, send one 32-token probe"
                    + " request and return the probe result as JSON.";
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "provider", Map.of("type", "string",
                            "description", "provider id (the models.json providers key)"),
                        "api", Map.of("type", "string",
                            "description", "protocol wire name, e.g. pi-messages"
                                + " or openai-completions"),
                        "model", Map.of("type", "string", "description", "model id")
                    ),
                    "required", List.of("provider", "api", "model"));
            }

            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Sequential();
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                var provider = requireString(params, "provider");
                var api = requireString(params, "api");
                var model = requireString(params, "model");
                var modelsJson = modelsJsonPath.get();
                Files.createDirectories(Objects.requireNonNull(modelsJson.getParent(),
                    "models.json parent"));
                Files.writeString(modelsJson,
                    modelsJson(provider, api, baseUrlFor(server, api), model),
                    StandardCharsets.UTF_8);
                server.reset();
                var probe = ConfiguredRuntimeInspector.inspectProvider(modelsJson, provider,
                    model, ConfiguredRuntimeInspector.PROBE_MAX_TOKENS);
                assertValidRequest(server, laneFor(api), model);
                return ToolResult.success(probeJson(probe));
            }
        };
    }

    /**
     * Render the {@code models.json} fixture for one lane.
     *
     * @param provider provider id
     * @param api      protocol wire name
     * @param baseUrl  lane base URL
     * @param model    model id
     * @return models.json text
     */
    public static String modelsJson(String provider, String api, String baseUrl, String model) {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("api", api);
        definition.put("baseUrl", baseUrl);
        definition.put("apiKey", API_KEY);
        definition.put("models", List.of(Map.of(
            "id", model,
            "name", MODEL_NAME,
            "contextWindow", 32768,
            "maxTokens", 4096,
            "cost", Map.of("input", 0, "output", 0, "cacheRead", 0, "cacheWrite", 0))));
        try {
            return MAPPER.writeValueAsString(Map.of("providers", Map.of(provider, definition)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to render the models.json fixture", e);
        }
    }

    private static String baseUrlFor(AcmeServer server, String api) {
        return switch (laneFor(api)) {
            case AcmeServer.OPENAI_PATH -> server.openAiBaseUrl();
            default -> server.customBaseUrl();
        };
    }

    private static String laneFor(String api) {
        return switch (api) {
            case "openai-completions" -> AcmeServer.OPENAI_PATH;
            case "pi-messages" -> AcmeServer.MESSAGES_PATH;
            default -> throw new IllegalArgumentException(
                "unknown api: " + api + " (expected pi-messages or openai-completions)");
        };
    }

    private static void assertValidRequest(AcmeServer server, String lane, String model) {
        var requests = server.requests();
        if (requests.size() != 1) {
            throw new IllegalStateException(
                "fixture server expected one request but recorded " + requests.size());
        }
        var request = requests.get(0);
        if (!lane.equals(request.path())) {
            throw new IllegalStateException(
                "fixture server expected path " + lane + " but recorded " + request.path());
        }
        if (!request.body().contains("\"model\":\"" + model + "\"")) {
            throw new IllegalStateException(
                "fixture request body does not name model " + model);
        }
    }

    private static String probeJson(ConfiguredRuntimeInspector.ProviderProbe probe) {
        var result = new LinkedHashMap<String, Object>();
        result.put("text", probe.text());
        result.put("stopReason", probe.stopReason());
        result.put("inputTokens", probe.inputTokens());
        result.put("outputTokens", probe.outputTokens());
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the probe result", e);
        }
    }

    /**
     * 模型作者 eval 的期望观测（docs/44 §8.3）：新增模型字段＋目录计数。
     *
     * @return {@code {providerId, modelId, name, reasoning, text, toolUse, streaming,
     *         inputCost, outputCost, contextWindow, maxTokens, builtinModels,
     *         fileModels, totalModels}}
     */
    public static Map<String, Object> addedModelExpectation() {
        var builtin = ProviderCatalog.allModels().listModels().size();
        var expected = new LinkedHashMap<String, Object>();
        expected.put("providerId", ADDED_MODEL_PROVIDER);
        expected.put("modelId", ADDED_MODEL_ID);
        expected.put("name", ADDED_MODEL_NAME);
        expected.put("reasoning", true);
        expected.put("text", true);
        expected.put("toolUse", true);
        expected.put("streaming", true);
        expected.put("inputCost", 0.0);
        expected.put("outputCost", 0.0);
        expected.put("contextWindow", ADDED_MODEL_CONTEXT_WINDOW);
        expected.put("maxTokens", ADDED_MODEL_MAX_TOKENS);
        expected.put("builtinModels", builtin);
        expected.put("fileModels", 1);
        expected.put("totalModels", builtin + 1);
        return expected;
    }

    /**
     * Inline tool backed by the current isolated {@code models.json}.
     *
     * @return inspect_added_model tool
     */
    public static AgentTool<Map<String, Object>, Void> inspectAddedModelTool() {
        return inspectAddedModelTool(ModelsJsonConfig::defaultPath);
    }

    /**
     * Inline tool with an injectable {@code models.json} location (tests).
     *
     * @param modelsJsonPath where the tool writes {@code models.json}
     * @return inspect_added_model tool
     */
    public static AgentTool<Map<String, Object>, Void> inspectAddedModelTool(
            Supplier<Path> modelsJsonPath) {
        Objects.requireNonNull(modelsJsonPath, "modelsJsonPath");
        return new AgentTool<>() {
            @Override public String name() {
                return INSPECT_ADDED_MODEL_TOOL;
            }

            @Override public String label() {
                return "inspect added model";
            }

            @Override public String promptSnippet() {
                return "Add a model to models.json and report its effective fields";
            }

            @Override public String description() {
                return "Merge the model entry into models.json (keeping existing models),"
                    + " then return the effective runtime fields as JSON.";
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "provider", Map.of("type", "string",
                            "description", "provider id (the models.json providers key)"),
                        "model", Map.of("type", "string", "description", "model id")
                    ),
                    "required", List.of("provider", "model"));
            }

            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Sequential();
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                var provider = requireString(params, "provider");
                var model = requireString(params, "model");
                var modelsJson = modelsJsonPath.get();
                Files.createDirectories(Objects.requireNonNull(modelsJson.getParent(),
                    "models.json parent"));
                mergeAddedModel(modelsJson, provider, model);
                var added = ConfiguredRuntimeInspector.inspectAddedModel(
                    modelsJson, provider, model);
                return ToolResult.success(addedModelJson(added));
            }
        };
    }

    /**
     * 合并写入新增模型，保留既有 providers/models（docs/44 §8.3「不替换既有模型」）。
     */
    private static void mergeAddedModel(Path modelsJson, String provider, String model)
            throws IOException {
        ObjectNode root = Files.exists(modelsJson)
            ? readObjectNode(modelsJson)
            : MAPPER.createObjectNode();
        ObjectNode providers = root.get("providers") instanceof ObjectNode existing
            ? existing : root.putObject("providers");
        ObjectNode definition = providers.get(provider) instanceof ObjectNode existing
            ? existing : providers.putObject(provider);
        if (!definition.hasNonNull("api")) {
            definition.put("api", "openai-completions");
        }
        if (!definition.hasNonNull("baseUrl")) {
            definition.put("baseUrl", "http://127.0.0.1:1/openai/v1");
        }
        if (!definition.hasNonNull("apiKey")) {
            definition.put("apiKey", API_KEY);
        }
        ArrayNode models = definition.get("models") instanceof ArrayNode existing
            ? existing : definition.putArray("models");
        ObjectNode entry = findModel(models, model);
        if (entry == null) {
            entry = models.addObject();
        }
        entry.put("id", model);
        entry.put("name", ADDED_MODEL_NAME);
        entry.put("reasoning", true);
        entry.putArray("input").add("text");
        var cost = entry.putObject("cost");
        cost.put("input", 0);
        cost.put("output", 0);
        cost.put("cacheRead", 0);
        cost.put("cacheWrite", 0);
        entry.put("contextWindow", ADDED_MODEL_CONTEXT_WINDOW);
        entry.put("maxTokens", ADDED_MODEL_MAX_TOKENS);
        Files.writeString(modelsJson,
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
            StandardCharsets.UTF_8);
    }

    private static ObjectNode readObjectNode(Path path) throws IOException {
        JsonNode node = MAPPER.readTree(path.toFile());
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalStateException("models.json root must be an object: " + path);
    }

    private static ObjectNode findModel(ArrayNode models, String model) {
        for (var node : models) {
            if (node instanceof ObjectNode object && model.equals(object.path("id").asText())) {
                return object;
            }
        }
        return null;
    }

    private static String addedModelJson(ConfiguredRuntimeInspector.AddedModel added) {
        var result = new LinkedHashMap<String, Object>();
        result.put("providerId", added.providerId());
        result.put("modelId", added.modelId());
        result.put("name", added.name());
        result.put("reasoning", added.reasoning());
        result.put("text", added.text());
        result.put("toolUse", added.toolUse());
        result.put("streaming", added.streaming());
        result.put("inputCost", added.inputCost());
        result.put("outputCost", added.outputCost());
        result.put("contextWindow", added.contextWindow());
        result.put("maxTokens", added.maxTokens());
        result.put("builtinModels", added.builtinModels());
        result.put("fileModels", added.fileModels());
        result.put("totalModels", added.totalModels());
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the added model", e);
        }
    }

    private static String requireString(Map<String, Object> params, String key) {
        var value = params == null ? null : params.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("inspection tool requires a non-blank '" + key + "'");
        }
        return text;
    }
}
