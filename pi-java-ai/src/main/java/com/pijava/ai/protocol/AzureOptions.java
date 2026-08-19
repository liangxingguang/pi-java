package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;

/**
 * Azure OpenAI Responses 专属选项（对齐 pi {@code AzureOpenAIResponsesOptions}）。
 *
 * <p>从 {@link ApiOptions#extra()} 读取，键名与 pi 对齐：{@code azureApiVersion} /
 * {@code azureBaseUrl} / {@code azureResourceName} / {@code azureDeploymentName}。</p>
 */
public record AzureOptions(
    /** 优先级最高；否则 AZURE_OPENAI_API_VERSION env；否则 "v1" */
    String apiVersion,
    /** 完整 baseUrl；否则 AZURE_OPENAI_BASE_URL env */
    String baseUrl,
    /** 资源名，用于拼默认 baseUrl；否则 AZURE_OPENAI_RESOURCE_NAME env */
    String resourceName,
    /** 部署名；否则查 AZURE_OPENAI_DEPLOYMENT_NAME_MAP；否则用 model.id */
    String deploymentName
) {
    /** pi: DEFAULT_AZURE_API_VERSION = "v1"（unified 路由的默认值） */
    static final String DEFAULT_API_VERSION = "v1";

    /** 从 {@link ApiOptions#extra()} 读取本协议选项。 */
    public static AzureOptions from(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        return new AzureOptions(
            stringOrNull(extra.get("azureApiVersion")),
            stringOrNull(extra.get("azureBaseUrl")),
            stringOrNull(extra.get("azureResourceName")),
            stringOrNull(extra.get("azureDeploymentName")));
    }

    /**
     * 解析部署名映射（pi {@code parseDeploymentNameMap}）：
     * {@code modelId=deploymentName} 逗号分隔，畸形条目跳过。
     */
    static Map<String, String> parseDeploymentNameMap(String raw) {
        var map = new LinkedHashMap<String, String>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (var entry : raw.split(",")) {
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                continue;
            }
            map.put(parts[0].trim(), parts[1].trim());
        }
        return map;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
