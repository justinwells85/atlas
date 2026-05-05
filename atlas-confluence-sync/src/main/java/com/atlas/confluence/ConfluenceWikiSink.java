package com.atlas.confluence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link WikiSink} implementation backed by Confluence Cloud's v2 REST API.
 * Wraps {@link ConfluenceClient}, owns space-id resolution (cached lazily),
 * and translates {@link ConfluencePageNotFoundException} into the
 * sink-agnostic {@link WikiPageNotFoundException} so callers stay
 * sink-blind.
 *
 * Activates by default. Disable via
 * {@code atlas.wiki.sinks.confluence.enabled=false} when running an Atlas
 * instance against confidential code (no external egress allowed) — see
 * the Phase 5.8 plan and the work-confidentiality rule for rationale.
 */
@Component
@ConditionalOnProperty(
        prefix = "atlas.wiki.sinks.confluence",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ConfluenceWikiSink implements WikiSink {

    public static final String NAME = "confluence";

    private final ConfluenceClient client;
    private final String spaceKey;
    private volatile String spaceId;

    public ConfluenceWikiSink(
            ConfluenceClient client,
            @Value("${atlas.confluence.space-key}") String spaceKey) {
        this.client = client;
        this.spaceKey = spaceKey;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String createPage(String title, String body, String parentRef) {
        return client.createPage(resolveSpaceId(), title, body, parentRef);
    }

    @Override
    public void updatePage(String ref, String title, String body, String parentRef) {
        try {
            client.updatePage(ref, title, body, parentRef);
        } catch (ConfluencePageNotFoundException e) {
            throw new WikiPageNotFoundException(ref, e);
        }
    }

    @Override
    public void deletePage(String ref) {
        client.deletePage(ref);
    }

    @Override
    public Optional<String> findPageByTitle(String title) {
        return client.findPageByTitle(resolveSpaceId(), title);
    }

    private String resolveSpaceId() {
        String cached = spaceId;
        if (cached == null) {
            cached = client.getSpaceIdByKey(spaceKey);
            spaceId = cached;
        }
        return cached;
    }
}
