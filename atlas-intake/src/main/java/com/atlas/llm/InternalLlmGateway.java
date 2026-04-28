package com.atlas.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub {@link LlmGateway} for the org's internal LLM API. Wires through when
 * {@code atlas.llm.provider=internal-gateway} is set, but the actual call
 * implementation is deferred — see {@code docs/deferred-decisions.md} DD-012.
 *
 * Behavior is fail-on-call (not fail-at-boot): the application starts
 * cleanly under this provider so the toggle wiring can be exercised and
 * tested, but every actual {@link #complete} call throws with a clear
 * pointer to the deferred-decision entry. Once the production team has the
 * org's gateway spec, replace this stub's body.
 */
@Component
@ConditionalOnProperty(prefix = "atlas.llm", name = "provider",
        havingValue = "internal-gateway")
public class InternalLlmGateway implements LlmGateway {

    @Override
    public String complete(String prompt) {
        throw new UnsupportedOperationException(
                "atlas.llm.provider=internal-gateway is configured but not yet implemented. " +
                        "See docs/deferred-decisions.md DD-012 for status. " +
                        "Set atlas.llm.provider=anthropic to use the current Anthropic-direct implementation.");
    }
}
