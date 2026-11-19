package com.pijava.examples.rpc;

import java.nio.file.Path;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ResourcePaths;

/**
 * pi {@code examples/extensions/dynamic-resources/index.ts} 移植（docs/56 §2.4，入册）——
 * pi 的 {@code resources_discover} 事件在 Java 收敛为 {@link #sessionStartResources()}，
 * 返回 SKILL.md / dynamic.md / dynamic.json 三路径。
 */
public final class DynamicResourcesExtension implements PiExtension {

    /** 资源文件所在目录（jar/classes 下的 dynamic-resources）。 */
    public static Path resourceDir() {
        var url = DynamicResourcesExtension.class.getResource("/dynamic-resources/SKILL.md");
        if (url == null || !url.getProtocol().equals("file")) {
            return Path.of("src", "main", "resources", "dynamic-resources");
        }
        try {
            return Path.of(url.toURI()).getParent();
        } catch (Exception ignored) {
            return Path.of("src", "main", "resources", "dynamic-resources");
        }
    }

    @Override
    public String name() {
        return "dynamic-resources";
    }

    @Override
    public void register(ExtensionContext ctx) {
        // 纯资源贡献扩展：pi 侧仅 resources_discover，无 command/tool/event
    }

    @Override
    public ResourcePaths sessionStartResources() {
        var base = resourceDir();
        return new ResourcePaths(
            java.util.List.of(base.resolve("SKILL.md")),
            java.util.List.of(base.resolve("dynamic.md")),
            java.util.List.of(base.resolve("dynamic.json")));
    }
}
