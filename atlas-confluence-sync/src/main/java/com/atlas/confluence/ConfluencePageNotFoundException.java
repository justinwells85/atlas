package com.atlas.confluence;

/**
 * Thrown by {@link ConfluenceClient#updatePage} when the page no longer exists
 * in Confluence (HTTP 404). The {@link SyncCoordinator} catches this and
 * recovers by creating a fresh page, overwriting the stale page ID.
 */
public class ConfluencePageNotFoundException extends RuntimeException {

    private final String pageId;

    public ConfluencePageNotFoundException(String pageId, Throwable cause) {
        super("Confluence page " + pageId + " not found", cause);
        this.pageId = pageId;
    }

    public String getPageId() {
        return pageId;
    }
}
