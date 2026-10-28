package com.pijava.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.pkg.SourceResolver;

/**
 * pi {@code examples/extensions/provider-payload.ts:1-18} 移植（docs/40 P14）——
 * before_provider_request／after_provider_response 落
 * {@code <cwd>/.pi-java/provider-payload.log}；请求臂 JSON 美化（2 空格缩进）＋双换行；
 * 响应臂 {@code [status] {headers}} 紧凑形＋双换行。
 *
 * <p>序列化走 {@link JsJson}（F2 取证：Jackson pretty 与 {@code JSON.stringify(x,null,2)}
 * 有空容器/键序/数字形字节差）。pi 的 {@code appendFileSync} 不建目录——目录缺失抛错
 * 进扩展错误通道（逐字忠实，不补 mkdir）。</p>
 */
public final class ProviderPayloadExtension implements PiExtension {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "provider-payload";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.BEFORE_PROVIDER_REQUEST, (event, c) -> {
            append(logFile(c), JsJson.stringify(MAPPER.valueToTree(event.payload()), 2)
                + "\n\n");
            // Optional: replace the payload instead of only logging it.
            return null;
        });

        ctx.on(ExtensionEventSpec.AFTER_PROVIDER_RESPONSE, (event, c) -> {
            append(logFile(c), "[" + event.status() + "] "
                + JsJson.stringify(MAPPER.valueToTree(event.headers())) + "\n\n");
            return null;
        });
    }

    private static Path logFile(ExtensionContext ctx) {
        return Path.of(ctx.cwd(), SourceResolver.CONFIG_DIR_NAME, "provider-payload.log");
    }

    /** pi {@code appendFileSync(..., "utf8")} 对位（不建目录）。 */
    private static void append(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
