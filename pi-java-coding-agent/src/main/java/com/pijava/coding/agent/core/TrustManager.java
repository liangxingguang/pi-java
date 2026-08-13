package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-level trust decisions (Phase 3 design §12.4).
 *
 * <p>Phase 3 keeps trust in memory (the {@code /trust} slash command records
 * decisions for the process lifetime). Persistent trust markers in
 * {@code ~/.pi-java/trust/} arrive with session persistence in Phase 4.</p>
 */
public final class TrustManager {

    private final Map<Path, Boolean> decisions = new ConcurrentHashMap<>();
    private String defaultTrust = "ask";

    public TrustManager(String defaultTrust) {
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
        var decision = decisions.get(projectDir.toAbsolutePath().normalize());
        if (decision != null) {
            return decision;
        }
        return "always".equals(defaultTrust);
    }

    /** Record a trust decision for the current process. */
    public void trust(Path projectDir, boolean trusted) {
        decisions.put(projectDir.toAbsolutePath().normalize(), trusted);
    }

    /** Change the default trust policy. */
    public void setDefaultTrust(String trust) {
        this.defaultTrust = trust;
    }
}
