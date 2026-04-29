package com.atlas.codesync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Fetches Java source files from a remote GitHub repo via the Contents API.
 * Public repos only at this stage — token wiring deferred to DD-001. The
 * Contents API has two content shapes per file: a {@code download_url} for
 * larger files (we GET it as raw text) and an inline base64-encoded
 * {@code content} field for smaller ones (we decode in place).
 *
 * Recursion: a {@code dir} entry triggers another Contents API call against
 * the dir's path. Non-Java files are filtered out at the listing level so
 * we don't waste GET calls fetching them.
 */
@Component
public class RepoFileFetcher {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public RepoFileFetcher(@Value("${atlas.code-sync.github-api-base:https://api.github.com}")
                           String githubApiBase) {
        this.http = RestClient.builder()
                .baseUrl(githubApiBase)
                .build();
    }

    public List<RepoFile> listJavaSourcesUnder(String owner, String repo, String relativePath) {
        List<RepoFile> out = new ArrayList<>();
        walk(owner, repo, relativePath, out);
        return out;
    }

    private void walk(String owner, String repo, String path, List<RepoFile> out) {
        List<Map<String, Object>> entries;
        // Manual URI build: RestClient's path-variable expansion percent-encodes
        // slashes, but the GitHub Contents API takes the relative path with
        // literal slashes. owner/repo/path are atlas-controlled (no untrusted
        // input), so plain concat is safe.
        String uri = "/repos/" + owner + "/" + repo + "/contents/" + path;
        try {
            entries = http.get()
                    .uri(uri)
                    .retrieve()
                    .body(LIST_OF_MAP);
        } catch (HttpClientErrorException.NotFound e) {
            return;
        }
        if (entries == null) return;
        for (Map<String, Object> entry : entries) {
            String type = asString(entry.get("type"));
            String entryPath = asString(entry.get("path"));
            String name = asString(entry.get("name"));
            if ("dir".equals(type)) {
                walk(owner, repo, entryPath, out);
            } else if ("file".equals(type) && name != null && name.endsWith(".java")) {
                out.add(loadFile(entry, entryPath));
            }
        }
    }

    private RepoFile loadFile(Map<String, Object> entry, String entryPath) {
        String encoding = asString(entry.get("encoding"));
        String inline = asString(entry.get("content"));
        if ("base64".equals(encoding) && inline != null && !inline.isBlank()) {
            // GitHub wraps base64 content with newlines every 60 chars; the
            // decoder in MIME mode tolerates them.
            byte[] decoded = Base64.getMimeDecoder().decode(inline);
            return new RepoFile(entryPath, new String(decoded, StandardCharsets.UTF_8));
        }
        String downloadUrl = asString(entry.get("download_url"));
        if (downloadUrl == null || downloadUrl.isBlank()) {
            // No content available; skip rather than fail. Real GitHub
            // responses always supply one of the two; defensive only.
            return new RepoFile(entryPath, "");
        }
        String body = RestClient.create()
                .get()
                .uri(downloadUrl)
                .retrieve()
                .body(String.class);
        return new RepoFile(entryPath, body == null ? "" : body);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    public record GitHubRepoCoords(String owner, String repo) {}

    /**
     * Parse a GitHub repo URL into its {@code (owner, repo)} pair.
     *
     * <p>Tolerated shapes (case-sensitive on the path segments):
     * {@code https://github.com/owner/repo},
     * {@code https://github.com/owner/repo.git},
     * {@code https://github.com/owner/repo/}.
     *
     * <p>Anything else throws {@link IllegalArgumentException}.
     */
    public static GitHubRepoCoords parseGitHubUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("repo URL is null");
        }
        String trimmed = url.trim();
        // Match http(s)://github.com/{owner}/{repo}[/.git]?[/]?
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$"
        ).matcher(trimmed);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a GitHub repo URL: " + url);
        }
        return new GitHubRepoCoords(m.group(1), m.group(2));
    }
}
