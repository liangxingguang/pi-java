package com.pijava.ai.identity;

/**
 * Version used by the default detailed {@code User-Agent}.
 *
 * <p>pi {@code PI_AI_VERSION}（{@code packages/ai/src/utils/version.ts}）的对应物。
 * 值镜像根 {@code pom.xml} 的 {@code project.version}；与 pi 的
 * {@code sync-versions.js} 同理，改版本时同步本常量。</p>
 */
public final class PiAiVersion {

    /** Current pi-ai version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private PiAiVersion() {}
}
