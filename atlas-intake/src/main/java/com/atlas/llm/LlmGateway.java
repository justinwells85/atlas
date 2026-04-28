package com.atlas.llm;

/**
 * Provider-neutral seam for LLM calls. atlas-intake writes against this
 * interface; provider-specific concerns (auth, request envelope, response
 * shape) live inside implementations.
 *
 * Today: {@link AnthropicLlmGateway} (direct Anthropic SDK).
 * Future: {@link InternalLlmGateway} (org-internal LLM API gateway,
 * implementation deferred — see {@code docs/deferred-decisions.md} DD-012).
 *
 * Selection at boot is property-driven: {@code atlas.llm.provider=anthropic}
 * (default) or {@code atlas.llm.provider=internal-gateway}.
 *
 * Per ADR-013, the interface is intentionally use-case-shaped (single
 * {@code complete(prompt)} method) rather than mirroring any provider's
 * native message API. The intake interview's only LLM-driven turn is
 * description clarification, which is a single-prompt-in / text-out call.
 * If new use cases arrive, add a new method here and implement it in both
 * providers — do not leak provider-specific message shapes through.
 */
public interface LlmGateway {

    /**
     * Send a free-form prompt to the configured LLM provider and return the
     * provider's text response. Implementations are expected to throw on
     * provider-side errors (auth failure, rate limit, network) so the caller
     * can decide how to recover.
     */
    String complete(String prompt);
}
