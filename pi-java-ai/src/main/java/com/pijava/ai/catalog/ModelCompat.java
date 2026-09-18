package com.pijava.ai.catalog;

/**
 * Per-model provider compatibility flags (pi {@code Model.compat}, {@code types.ts:713-714}).
 *
 * <p>Only flags pi-java actually **consults** are carried here — one so far. pi's own interface
 * has eight ({@code forceAdaptiveThinking}, {@code supportsStrictTools}, {@code deferredToolsMode},
 * …); each gets added when its consumer is ported, never speculatively
 * (docs/31 §8.34.4 决策 2). The field is a typed record rather than a {@code Map} for the same
 * reason: pi's compat is a per-provider typed interface, and a map would push type errors to the
 * read site.</p>
 *
 * @param allowEmptySignature pi {@code compat.allowEmptySignature}. When {@code true}, a thinking
 *        block that has **no** signature is replayed as a {@code thinking} block carrying
 *        {@code signature:""} instead of being downgraded to plain text — some
 *        Anthropic-compatible providers emit and accept empty signatures. Absent ≡ {@code false}
 *        (pi normalizes with {@code ?? false}, {@code anthropic-messages.ts:193}), so this is a
 *        **two-state** flag, not tri-state: {@code undefined} and {@code false} are
 *        indistinguishable in behavior (docs/31 §8.34.4 决策 3).
 */
public record ModelCompat(boolean allowEmptySignature) {

    /** The default for models that declare nothing (pi: {@code undefined ?? false}). */
    public static final ModelCompat NONE = new ModelCompat(false);

    /** Flags with {@code allowEmptySignature} set. */
    public static ModelCompat of(boolean allowEmptySignature) {
        return new ModelCompat(allowEmptySignature);
    }
}
