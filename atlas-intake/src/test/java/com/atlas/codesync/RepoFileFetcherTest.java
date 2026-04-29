package com.atlas.codesync;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level tests for {@link RepoFileFetcher}: pure HTTP behaviour against a
 * stubbed GitHub Contents API, plus the URL-parsing helper. Public repos
 * only — token wiring deferred to DD-001.
 */
class RepoFileFetcherTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private RepoFileFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new RepoFileFetcher(wireMock.baseUrl());
    }

    @Test
    void whenGitHubHttpsUrl_thenOwnerAndRepoAreExtracted() {
        RepoFileFetcher.GitHubRepoCoords c =
                RepoFileFetcher.parseGitHubUrl("https://github.com/justinwells85/atlas");
        assertThat(c.owner()).isEqualTo("justinwells85");
        assertThat(c.repo()).isEqualTo("atlas");
    }

    @Test
    void whenGitHubUrlHasGitSuffix_thenItIsStripped() {
        RepoFileFetcher.GitHubRepoCoords c =
                RepoFileFetcher.parseGitHubUrl("https://github.com/foo/bar.git");
        assertThat(c.repo()).isEqualTo("bar");
    }

    @Test
    void whenGitHubUrlHasTrailingSlash_thenItIsTolerated() {
        RepoFileFetcher.GitHubRepoCoords c =
                RepoFileFetcher.parseGitHubUrl("https://github.com/foo/bar/");
        assertThat(c.repo()).isEqualTo("bar");
    }

    @Test
    void whenUrlIsNotGitHub_thenParseThrows() {
        assertThatThrownBy(() -> RepoFileFetcher.parseGitHubUrl("https://gitlab.com/foo/bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenDirContainsTwoJavaFiles_thenBothAreFetchedWithContent() {
        String javaA = "package com.example; class A {}";
        String javaB = "package com.example; class B {}";
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/src/test/java"))
                .willReturn(okJson("""
                        [
                          {"type":"file","name":"A.java","path":"src/test/java/A.java","download_url":"%s/raw/o/r/A.java"},
                          {"type":"file","name":"B.java","path":"src/test/java/B.java","download_url":"%s/raw/o/r/B.java"}
                        ]
                        """.formatted(wireMock.baseUrl(), wireMock.baseUrl()))));
        wireMock.stubFor(get(urlPathEqualTo("/raw/o/r/A.java"))
                .willReturn(aResponse().withStatus(200).withBody(javaA)));
        wireMock.stubFor(get(urlPathEqualTo("/raw/o/r/B.java"))
                .willReturn(aResponse().withStatus(200).withBody(javaB)));

        List<RepoFile> files = fetcher.listJavaSourcesUnder("o", "r", "src/test/java");

        assertThat(files).extracting(RepoFile::path)
                .containsExactlyInAnyOrder("src/test/java/A.java", "src/test/java/B.java");
        assertThat(files).extracting(RepoFile::content)
                .containsExactlyInAnyOrder(javaA, javaB);
    }

    @Test
    void whenDirHasSubdirs_thenItRecursesIntoThem() {
        String aJava = "package com.example.a; class A {}";
        String bJava = "package com.example.a.b; class B {}";
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/src/test/java"))
                .willReturn(okJson("""
                        [
                          {"type":"file","name":"A.java","path":"src/test/java/A.java","download_url":"%s/raw/A.java"},
                          {"type":"dir","name":"sub","path":"src/test/java/sub"}
                        ]
                        """.formatted(wireMock.baseUrl()))));
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/src/test/java/sub"))
                .willReturn(okJson("""
                        [{"type":"file","name":"B.java","path":"src/test/java/sub/B.java","download_url":"%s/raw/B.java"}]
                        """.formatted(wireMock.baseUrl()))));
        wireMock.stubFor(get(urlPathEqualTo("/raw/A.java"))
                .willReturn(aResponse().withStatus(200).withBody(aJava)));
        wireMock.stubFor(get(urlPathEqualTo("/raw/B.java"))
                .willReturn(aResponse().withStatus(200).withBody(bJava)));

        List<RepoFile> files = fetcher.listJavaSourcesUnder("o", "r", "src/test/java");

        assertThat(files).extracting(RepoFile::path)
                .containsExactlyInAnyOrder("src/test/java/A.java", "src/test/java/sub/B.java");
    }

    @Test
    void whenDirContainsNonJavaFiles_thenTheyAreSkipped() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/src/test"))
                .willReturn(okJson("""
                        [
                          {"type":"file","name":"README.md","path":"src/test/README.md","download_url":"%s/raw/README.md"},
                          {"type":"file","name":"OnlyJava.java","path":"src/test/OnlyJava.java","download_url":"%s/raw/OnlyJava.java"}
                        ]
                        """.formatted(wireMock.baseUrl(), wireMock.baseUrl()))));
        wireMock.stubFor(get(urlPathEqualTo("/raw/OnlyJava.java"))
                .willReturn(aResponse().withStatus(200).withBody("class OnlyJava {}")));

        List<RepoFile> files = fetcher.listJavaSourcesUnder("o", "r", "src/test");

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).endsWith("OnlyJava.java");
    }

    @Test
    void whenContentsApi404s_thenReturnsEmptyList() {
        // src/test/java doesn't exist on this repo — common for newly-scaffolded
        // services. A 404 should be treated as "no tests yet", not as an error.
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/src/test/java"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(fetcher.listJavaSourcesUnder("o", "r", "src/test/java")).isEmpty();
    }

    @Test
    void whenContentsApiReturnsBase64InsteadOfDownloadUrl_thenItIsDecoded() {
        // Smaller files come back base64-inline rather than via download_url.
        // The fetcher should handle both shapes.
        String code = "package com.example; class C {}";
        String b64 = Base64.getEncoder().encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMock.stubFor(get(urlPathEqualTo("/repos/o/r/contents/src"))
                .willReturn(okJson("""
                        [{"type":"file","name":"C.java","path":"src/C.java","encoding":"base64","content":"%s"}]
                        """.formatted(b64))));

        List<RepoFile> files = fetcher.listJavaSourcesUnder("o", "r", "src");

        assertThat(files).hasSize(1);
        assertThat(files.get(0).content()).isEqualTo(code);
    }
}
