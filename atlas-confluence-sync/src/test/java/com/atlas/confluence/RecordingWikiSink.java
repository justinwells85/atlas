package com.atlas.confluence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test double for {@link WikiSink} that records every call. Used by the
 * multi-sink fan-out tests to assert that a non-Confluence sink, registered
 * alongside the real {@link ConfluenceWikiSink}, receives the same logical
 * sequence of operations the Confluence sink does.
 */
class RecordingWikiSink implements WikiSink {

    private final String name;
    private final List<Created> createPageCalls = new ArrayList<>();
    private final List<Updated> updatePageCalls = new ArrayList<>();
    private final List<String> deletePageCalls = new ArrayList<>();
    private final List<String> findByTitleCalls = new ArrayList<>();
    private int idCounter = 1;

    RecordingWikiSink(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String createPage(String title, String body, String parentRef) {
        String ref = name + "-page-" + (idCounter++);
        createPageCalls.add(new Created(title, body, parentRef, ref));
        return ref;
    }

    @Override
    public void updatePage(String ref, String title, String body, String parentRef) {
        updatePageCalls.add(new Updated(ref, title, body, parentRef));
    }

    @Override
    public void deletePage(String ref) {
        deletePageCalls.add(ref);
    }

    @Override
    public Optional<String> findPageByTitle(String title) {
        findByTitleCalls.add(title);
        // Returning empty forces the orchestrator to create well-known pages.
        return Optional.empty();
    }

    List<Created> createPageCalls() {
        return List.copyOf(createPageCalls);
    }

    List<String> createdTitles() {
        return createPageCalls.stream().map(Created::title).toList();
    }

    List<Updated> updatePageCalls() {
        return List.copyOf(updatePageCalls);
    }

    List<String> deletePageCalls() {
        return List.copyOf(deletePageCalls);
    }

    List<String> findByTitleCalls() {
        return List.copyOf(findByTitleCalls);
    }

    record Created(String title, String body, String parentRef, String returnedRef) {}

    record Updated(String ref, String title, String body, String parentRef) {}
}
