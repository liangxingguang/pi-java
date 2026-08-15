package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Project-level trust decisions persisted under {@code ~/.pi-java/trust/}
 * (Phase 4 §12). A marker file named after the encoded cwd records a trusted
 * project; its content records the decision time. Untrusting removes it.
 */
public final class TrustManager {

    private final Path trustRoot;
    private String defaultTrust = "ask";

    /** Default root: {@code ~/.pi-java/trust/}. */
    public TrustManager(String defaultTrust) {
        this(defaultRoot(), defaultTrust);
    }

    public TrustManager(Path trustRoot, String defaultTrust) {
        this.trustRoot = trustRoot;
        if (defaultTrust != null) {
            this.defaultTrust = defaultTrust;
        }
    }

    /** The configured default trust ("ask" | "always" | "never"). */
    public String defaultTrust() {
        return defaultTrust;
    }

    /** Whether the given project directory is trusted. */
    public boolean isTrusted(Path projectDir) {
        if (Files.exists(marker(projectDir))) {
            return true;
        }
        return "always".equals(defaultTrust);
    }

    /** Record (or clear) a trust decision for a project directory. */
    public void trust(Path projectDir, boolean trusted) {
        var marker = marker(projectDir);
        try {
            if (trusted) {
                Files.createDirectories(trustRoot);
                Files.writeString(marker, "trusted " + Instant.now() + "\n",
                    StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(marker);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist trust decision for " + projectDir, e);
        }
    }

    /** Change the default trust policy. */
    public void setDefaultTrust(String trust) {
        this.defaultTrust = trust;
    }

    private Path marker(Path projectDir) {
        String encoded = sessionDirectoryName(
            projectDir.toAbsolutePath().normalize().toString());
        return trustRoot.resolve(encoded);
    }

    /** Same cwd encoding as the JSONL backend's session directory name. */
    private static String sessionDirectoryName(String cwd) {
        String normalized = cwd.replaceFirst("^[/\\\\]", "");
        return "--" + normalized.replaceAll("[/\\\\:]", "-") + "--";
    }

    private static Path defaultRoot() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".pi-java", "trust");
    }
}