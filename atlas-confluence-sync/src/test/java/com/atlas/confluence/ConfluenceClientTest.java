package com.atlas.confluence;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String EMAIL = "test@example.com";
    private static final String TOKEN = "test-token-123";
    private static final String EXPECTED_BASIC =
            "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));

    private ConfluenceClient client;

    @BeforeEach
    void setUp() {
        client = new ConfluenceClient(wireMock.baseUrl(), EMAIL, TOKEN);
    }

    @Test
    void whenGettingSpaceByKey_thenReturnsIdFromResultsArray() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .withQueryParam("keys", equalTo("ATLAS"))
                .willReturn(okJson("""
                        {"results":[{"id":"589827","key":"ATLAS","name":"Atlas"}]}
                        """)));

        String spaceId = client.getSpaceIdByKey("ATLAS");

        assertThat(spaceId).isEqualTo("589827");
    }

    @Test
    void whenCallingApi_thenSendsBasicAuthAndJsonHeaders() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[{"id":"1","key":"ATLAS"}]}
                        """)));

        client.getSpaceIdByKey("ATLAS");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v2/spaces"))
                .withHeader("Authorization", equalTo(EXPECTED_BASIC))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void whenSpaceLookupReturnsEmptyResults_thenThrowsClearError() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/spaces"))
                .willReturn(okJson("""
                        {"results":[]}
                        """)));

        assertThatThrownBy(() -> client.getSpaceIdByKey("MISSING"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    void whenCreatingPage_thenPostsExpectedBodyAndReturnsNewId() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/pages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"123456","title":"billing-service","spaceId":"589827"}
                                """)));

        String pageId = client.createPage("589827", "billing-service", "<h2>Overview</h2>", null);

        assertThat(pageId).isEqualTo("123456");
        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/pages"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", equalTo(EXPECTED_BASIC))
                .withRequestBody(equalToJson("""
                        {
                          "spaceId": "589827",
                          "status": "current",
                          "title": "billing-service",
                          "body": {
                            "representation": "storage",
                            "value": "<h2>Overview</h2>"
                          }
                        }
                        """)));
    }

    @Test
    void whenUpdatingMissingPage_thenThrowsConfluencePageNotFoundException() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/STALE"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        assertThatThrownBy(() -> client.updatePage("STALE", "title", "<h2>body</h2>", null))
                .isInstanceOf(ConfluencePageNotFoundException.class)
                .hasMessageContaining("STALE");
    }

    @Test
    void whenUpdatingPage_thenFetchesCurrentVersionAndPutsIncrementedVersion() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/pages/123456"))
                .willReturn(okJson("""
                        {"id":"123456","title":"billing-service","version":{"number":3}}
                        """)));
        wireMock.stubFor(put(urlPathEqualTo("/api/v2/pages/123456"))
                .willReturn(okJson("""
                        {"id":"123456","title":"billing-service","version":{"number":4}}
                        """)));

        client.updatePage("123456", "billing-service", "<h2>Overview</h2> updated", null);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v2/pages/123456")));
        wireMock.verify(putRequestedFor(urlPathEqualTo("/api/v2/pages/123456"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.id", equalTo("123456")))
                .withRequestBody(matchingJsonPath("$.title", equalTo("billing-service")))
                .withRequestBody(matchingJsonPath("$.status", equalTo("current")))
                .withRequestBody(matchingJsonPath("$.body.representation", equalTo("storage")))
                .withRequestBody(matchingJsonPath("$.body.value", equalTo("<h2>Overview</h2> updated")))
                .withRequestBody(matchingJsonPath("$.version.number", equalTo("4"))));
    }

    @Test
    void whenDeletingPage_thenSendsDeleteAndAcceptsSuccess() {
        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/PAGE_ID"))
                .willReturn(aResponse().withStatus(204)));

        client.deletePage("PAGE_ID");

        wireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v2/pages/PAGE_ID"))
                .withHeader("Authorization", equalTo(EXPECTED_BASIC)));
    }

    @Test
    void whenDeletingAlreadyMissingPage_thenTreats404AsSuccess() {
        // Cleanup pass should not fail when someone has already deleted the
        // page manually in Confluence. End state — page is gone — is what matters.
        wireMock.stubFor(delete(urlPathEqualTo("/api/v2/pages/STALE"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        client.deletePage("STALE"); // should not throw

        wireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v2/pages/STALE")));
    }
}
