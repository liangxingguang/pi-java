package com.pijava.ai.protocol;

import java.util.regex.Pattern;

/**
 * Google thought-signature gate (pi {@code google-shared.ts:145-160}):
 * a signature survives replay only coming from the same provider+model and
 * only when it is syntactically base64.
 *
 * <p>The wire field is TYPE_BYTES: the SDK exchanges base64 strings on the
 * wire for {@code byte[]} in Java. Signatures from other models are opaque
 * tokens this model cannot decrypt, so they must not be sent.</p>
 */
final class ThoughtSignatures {

    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");

    private ThoughtSignatures() {
    }

    /**
     * Resolve a signature for replay.
     *
     * @param sameProviderAndModel whether the carrying message originated
     *        from the target provider and model id (R6: provider+model only)
     * @param signature            candidate signature (base64 string)
     * @return the signature when both gates pass; {@code null} otherwise
     */
    static String resolve(boolean sameProviderAndModel, String signature) {
        return sameProviderAndModel && isValid(signature) ? signature : null;
    }

    /** pi {@code isValidThoughtSignature}: truthy, length divisible by 4, base64 alphabet. */
    static boolean isValid(String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        return signature.length() % 4 == 0 && BASE64.matcher(signature).matches();
    }
}
