package com.pijava.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * pi-java web 网关 token（Phase 7 Stage D）—— WS 网关鉴权。
 *
 * <p>token 来源优先级：环境变量 {@code PI_JAVA_WEB_TOKEN} &gt;
 * {@code ~/.pi-java/web/gateway-token} 文件。两者都未配置时首启自动生成并落盘，
 * 保证网关默认受保护（对齐 pivot-ui gateway-token 思路）。</p>
 *
 * <p>浏览器 WebSocket API 无法设置自定义 header，token 通过 WS URL 查询参数
 * {@code ?token=...} 携带（{@code PiWebServer} 在 {@code onOpen} 校验）。</p>
 */
public final class GatewayToken {

    /** 环境变量名。 */
    public static final String ENV = "PI_JAVA_WEB_TOKEN";

    /** 默认 token 文件：{@code ~/.pi-java/web/gateway-token}。 */
    public static final Path DEFAULT_FILE = Path.of(
        System.getProperty("user.home"), ".pi-java", "web", "gateway-token");

    private static final SecureRandom RANDOM = new SecureRandom();

    private GatewayToken() {}

    /** 已配置的 token（env &gt; file）；未配置返回 {@link Optional#empty()}。 */
    public static Optional<String> configured() {
        Optional<String> env = fromEnv();
        return env.isPresent() ? env : fromFile(DEFAULT_FILE);
    }

    private static Optional<String> fromEnv() {
        String env = System.getenv(ENV);
        return env != null && !env.isBlank() ? Optional.of(env.trim()) : Optional.empty();
    }

    /** 从 token 文件读取（首行非空）。包私有便于测试。 */
    static Optional<String> fromFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String line = Files.readString(file, StandardCharsets.UTF_8).trim();
            return line.isBlank() ? Optional.empty() : Optional.of(line);
        } catch (Exception ignored) {
            // 文件不可读视为未配置
            return Optional.empty();
        }
    }

    /** 解析应强制校验的 token；未配置时自动生成并落盘（首启调用）。 */
    public static String resolve() {
        return configured().orElseGet(GatewayToken::generateAndPersist);
    }

    /** 生成一个随机 token（128-bit hex）。 */
    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 常数时间 token 比较（防时序侧信道）。 */
    public static boolean matches(String expected, String presented) {
        if (expected == null || expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String generateAndPersist() {
        String token = generate();
        try {
            Files.createDirectories(DEFAULT_FILE.getParent());
            Files.writeString(DEFAULT_FILE, token + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 落盘失败不影响本次运行；token 仍生效（仅本次进程内）
        }
        return token;
    }
}
