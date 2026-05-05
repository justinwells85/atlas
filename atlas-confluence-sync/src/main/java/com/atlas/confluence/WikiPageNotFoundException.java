package com.atlas.confluence;

/**
 * Sink-agnostic counterpart to {@link ConfluencePageNotFoundException}.
 * Thrown by {@link WikiSink#updatePage} when the referenced page is gone
 * from the underlying backend. The coordinator catches this and recovers
 * by creating a fresh page, regardless of which sink raised it.
 *
 * {@link ConfluenceWikiSink} translates {@link ConfluencePageNotFoundException}
 * (raised by the underlying HTTP client) into this type at the sink boundary
 * so that the coordinator stays sink-blind.
 */
public class WikiPageNotFoundException extends RuntimeException {

    private final String ref;

    public WikiPageNotFoundException(String ref, Throwable cause) {
        super("Wiki page " + ref + " not found", cause);
        this.ref = ref;
    }

    public String getRef() {
        return ref;
    }
}
