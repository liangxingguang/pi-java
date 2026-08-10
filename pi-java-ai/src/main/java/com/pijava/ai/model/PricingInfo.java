package com.pijava.ai.model;

/**
 * Pricing information for an LLM model.
 *
 * <p>All prices are in US dollars per million (1,000,000) tokens.
 * A value of {@code -1} means pricing data is not available for that dimension.</p>
 *
 * @param inputPrice  price per 1M input tokens, or -1 if unknown
 * @param outputPrice price per 1M output tokens, or -1 if unknown
 */
public record PricingInfo(double inputPrice, double outputPrice) {

    /** Sentinel indicating pricing is not available. */
    public static final PricingInfo UNKNOWN = new PricingInfo(-1, -1);

    /** Returns {@code true} if both input and output prices are known. */
    public boolean isKnown() {
        return inputPrice >= 0 && outputPrice >= 0;
    }
}
