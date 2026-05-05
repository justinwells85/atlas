package com.atlas.confluence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@link ConfluenceWikiSink}: the sink wraps
 * {@link ConfluenceClient}, owns space-id resolution, and translates
 * the Confluence-specific not-found exception to the sink-agnostic
 * {@link WikiPageNotFoundException} so callers (the coordinator) can
 * stay sink-blind.
 */
@ExtendWith(MockitoExtension.class)
class ConfluenceWikiSinkTest {

    private static final String SPACE_KEY = "ATLAS";
    private static final String SPACE_ID = "589827";

    @Mock
    ConfluenceClient client;

    ConfluenceWikiSink sink;

    @BeforeEach
    void setUp() {
        sink = new ConfluenceWikiSink(client, SPACE_KEY);
    }

    @Test
    void whenAskedForName_thenReturnsConfluence() {
        assertThat(sink.name()).isEqualTo(ConfluenceWikiSink.NAME);
        assertThat(ConfluenceWikiSink.NAME).isEqualTo("confluence");
    }

    @Test
    void whenCreatePage_thenDelegatesToClientWithResolvedSpaceId() {
        when(client.getSpaceIdByKey(SPACE_KEY)).thenReturn(SPACE_ID);
        when(client.createPage(eq(SPACE_ID), eq("Service: foo"), eq("<p>body</p>"), eq("PARENT")))
                .thenReturn("CREATED_PAGE_ID");

        String ref = sink.createPage("Service: foo", "<p>body</p>", "PARENT");

        assertThat(ref).isEqualTo("CREATED_PAGE_ID");
        verify(client).getSpaceIdByKey(SPACE_KEY);
        verify(client).createPage(SPACE_ID, "Service: foo", "<p>body</p>", "PARENT");
    }

    @Test
    void whenCreatePageWithNullParent_thenPassesNullThroughForRootPage() {
        when(client.getSpaceIdByKey(SPACE_KEY)).thenReturn(SPACE_ID);
        when(client.createPage(eq(SPACE_ID), any(), any(), eq((String) null))).thenReturn("ROOT_ID");

        String ref = sink.createPage("Atlas — Landing", "<p>landing</p>", null);

        assertThat(ref).isEqualTo("ROOT_ID");
        verify(client).createPage(SPACE_ID, "Atlas — Landing", "<p>landing</p>", null);
    }

    @Test
    void whenUpdatePage_thenDelegatesToClient() {
        sink.updatePage("PAGE123", "Service: foo", "<p>updated</p>", "PARENT");

        verify(client).updatePage("PAGE123", "Service: foo", "<p>updated</p>", "PARENT");
    }

    @Test
    void whenUpdatePageThrowsConfluenceNotFound_thenTranslatesToWikiPageNotFound() {
        ConfluencePageNotFoundException original = new ConfluencePageNotFoundException("STALE_ID", new RuntimeException("404"));
        doThrow(original).when(client).updatePage(eq("STALE_ID"), any(), any(), any());

        assertThatThrownBy(() -> sink.updatePage("STALE_ID", "t", "b", "p"))
                .isInstanceOf(WikiPageNotFoundException.class)
                .hasMessageContaining("STALE_ID")
                .hasCause(original);
    }

    @Test
    void whenDeletePage_thenDelegatesToClient() {
        sink.deletePage("DOOMED_ID");

        verify(client).deletePage("DOOMED_ID");
    }

    @Test
    void whenFindPageByTitle_thenDelegatesWithResolvedSpaceId() {
        when(client.getSpaceIdByKey(SPACE_KEY)).thenReturn(SPACE_ID);
        when(client.findPageByTitle(SPACE_ID, "Atlas — Service Inventory"))
                .thenReturn(Optional.of("LANDING_ID"));

        Optional<String> result = sink.findPageByTitle("Atlas — Service Inventory");

        assertThat(result).contains("LANDING_ID");
        verify(client).findPageByTitle(SPACE_ID, "Atlas — Service Inventory");
    }

    @Test
    void whenFindPageByTitleReturnsEmpty_thenSinkAlsoReturnsEmpty() {
        when(client.getSpaceIdByKey(SPACE_KEY)).thenReturn(SPACE_ID);
        when(client.findPageByTitle(SPACE_ID, "Missing Title")).thenReturn(Optional.empty());

        assertThat(sink.findPageByTitle("Missing Title")).isEmpty();
    }

    @Test
    void whenSpaceIdResolvedOnce_thenCachedAcrossSubsequentCalls() {
        when(client.getSpaceIdByKey(SPACE_KEY)).thenReturn(SPACE_ID);
        when(client.findPageByTitle(eq(SPACE_ID), any())).thenReturn(Optional.empty());
        when(client.createPage(eq(SPACE_ID), any(), any(), any())).thenReturn("ID");

        sink.findPageByTitle("a");
        sink.findPageByTitle("b");
        sink.createPage("t", "b", null);

        InOrder ordered = inOrder(client);
        ordered.verify(client, times(1)).getSpaceIdByKey(SPACE_KEY);
        ordered.verify(client).findPageByTitle(SPACE_ID, "a");
        ordered.verify(client).findPageByTitle(SPACE_ID, "b");
        ordered.verify(client).createPage(SPACE_ID, "t", "b", null);
        verifyNoMoreInteractions(client);
    }
}
