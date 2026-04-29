package com.atlas.codesync;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.TestScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates a code-sync refresh for one service: fetch the OpenAPI spec
 * named on {@code services.openapi_spec_url}, parse it, and upsert {@code apis}
 * rows tagged {@code source='openapi'}.
 *
 * Per the per-source provenance rule (V13 / plan 2026-04-29), this code path
 * never touches {@code apis} rows whose {@code source='intake'} — those belong
 * to the human interview and are out of bounds for code-sync. The audit log
 * records {@code changed_by='code-sync-openapi'} so the writer is visible in
 * the change history.
 */
@Component
public class CodeSyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CodeSyncCoordinator.class);
    private static final String SOURCE = "openapi";
    private static final String CHANGED_BY = "code-sync-openapi";
    private static final String TESTS_SOURCE = "tests";
    private static final String TESTS_CHANGED_BY = "code-sync-tests";

    private final ServiceRepository services;
    private final ServiceRelationshipsRepository relationships;
    private final OpenApiFetcher fetcher;
    private final OpenApiParser parser;
    private final RepoFileFetcher repoFetcher;
    private final JavaTestExtractor testExtractor;

    public CodeSyncCoordinator(ServiceRepository services,
                               ServiceRelationshipsRepository relationships,
                               OpenApiFetcher fetcher,
                               OpenApiParser parser,
                               RepoFileFetcher repoFetcher,
                               JavaTestExtractor testExtractor) {
        this.services = services;
        this.relationships = relationships;
        this.fetcher = fetcher;
        this.parser = parser;
        this.repoFetcher = repoFetcher;
        this.testExtractor = testExtractor;
    }

    /**
     * Refresh the test-scenario rows for one service from its GitHub repo.
     * Walks {@code {module_path}/src/test/java/**\/*.java}, extracts every
     * {@code @Test}-annotated method, and upserts {@code service_test_scenarios}
     * rows tagged {@code source='tests'}. Rows whose
     * {@code (package, class, method)} no longer appears in the code are
     * deleted; identical second runs are no-ops.
     *
     * <p>Atomic: fetch + parse complete before any DB mutation; the mutation
     * is wrapped in a transaction so a partial failure rolls back.
     *
     * @return counts of inserted/updated(=0)/deleted/skipped(=0).
     * @throws IllegalArgumentException if no service exists for the id, or
     *         the service's {@code repo_url} is non-null but not a GitHub URL.
     */
    @Transactional
    public CodeSyncResult refreshTests(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        String repoUrl = service.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return CodeSyncResult.empty();
        }
        RepoFileFetcher.GitHubRepoCoords coords = RepoFileFetcher.parseGitHubUrl(repoUrl);
        String testsPath = testsPathFor(service);

        // Fetch + parse first; only mutate the DB on success.
        List<RepoFile> sources = repoFetcher.listJavaSourcesUnder(coords.owner(), coords.repo(), testsPath);
        List<TestMethodRecord> fresh = new ArrayList<>();
        for (RepoFile f : sources) {
            try {
                fresh.addAll(testExtractor.extract(f.content()));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping unparseable test source {}: {}", f.path(), e.getMessage());
            }
        }

        // Live view: latest observation per (package, class, method) where
        // presence='present'. Append-only — we never UPDATE or DELETE rows;
        // disappearance is recorded as a tombstone observation.
        Map<String, TestScenario> live = new HashMap<>();
        for (TestScenario s : relationships.findTestScenariosFor(serviceId)) {
            live.put(scenarioKey(s.packageName(), s.className(), s.methodName()), s);
        }

        int created = 0;
        for (TestMethodRecord rec : fresh) {
            String k = scenarioKey(rec.packageName(), rec.className(), rec.methodName());
            if (live.remove(k) == null) {
                relationships.insertTestScenario(serviceId,
                        rec.packageName(), rec.className(), rec.methodName(),
                        TESTS_SOURCE);
                created++;
            }
            // Already in live view (and tests have no mutable content fields) → no-op.
        }

        // Anything left in `live` is a scenario the source code no longer
        // contains — append a tombstone.
        int deleted = live.size();
        for (TestScenario stale : live.values()) {
            relationships.writeTestScenarioTombstone(serviceId,
                    stale.packageName(), stale.className(), stale.methodName(),
                    TESTS_SOURCE);
        }

        if (created + deleted > 0) {
            relationships.insertServiceChange(serviceId, TESTS_CHANGED_BY, "updated",
                    "Tests refresh: created=" + created + " deleted=" + deleted);
        }

        return new CodeSyncResult(created, 0, deleted, 0);
    }

    private static String testsPathFor(Service service) {
        String module = service.getModulePath();
        if (module == null || module.isBlank()) {
            return "src/test/java";
        }
        String trimmed = module.endsWith("/") ? module.substring(0, module.length() - 1) : module;
        return trimmed + "/src/test/java";
    }

    private static String scenarioKey(String packageName, String className, String methodName) {
        return (packageName == null ? "" : packageName) + "|" + className + "|" + methodName;
    }

    /**
     * Refresh the OpenAPI-derived APIs for one service (M3.5: append-only).
     *
     * <p>Atomic: fetch + parse happen before any DB mutation, and the mutation
     * is wrapped in a transaction so a partial failure leaves the previous
     * observations intact rather than half-applied.
     *
     * <p>Per the project rule (persist as observed; updates decorate, never
     * overwrite), this method <b>only inserts</b>; it never UPDATEs or
     * DELETEs an apis row. Three insert paths:
     *
     * <ul>
     *   <li><b>created</b>: a fresh endpoint with no live observation in the
     *       openapi-source history → INSERT presence='present'.</li>
     *   <li><b>updated</b>: a fresh endpoint whose live observation has
     *       different content (description / auth method) → INSERT a new
     *       presence='present' observation, carrying forward the previous
     *       confluence_page_id so the existing endpoint page keeps tracking.</li>
     *   <li><b>deleted</b>: a key in the live openapi history that the spec
     *       no longer contains → INSERT a presence='absent' tombstone, also
     *       carrying the previous confluence_page_id so the cleanup pass
     *       can find it.</li>
     * </ul>
     *
     * <p>Endpoints whose {@code (method, path)} is already owned by an
     * {@code 'intake'}-source live observation are skipped, not decorated.
     * Intake stays authoritative for those keys until the user re-runs
     * intake. Skips don't write any row.
     *
     * <p>A refresh that observes nothing new produces no insert (the table
     * doesn't grow just to record sameness).
     *
     * @return counts of inserted/updated/deleted/skipped writes (all
     *         "deleted" writes are tombstones, not actual deletes).
     * @throws IllegalArgumentException if no service exists for the given id.
     */
    @Transactional
    public CodeSyncResult refreshOpenApi(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        String url = service.getOpenapiSpecUrl();
        if (url == null || url.isBlank()) {
            return CodeSyncResult.empty();
        }

        // Fetch + parse first; only mutate the DB on success so a 404 or
        // malformed spec leaves the existing observations untouched.
        String specText = fetcher.fetch(url);
        List<EndpointRecord> endpoints = parser.parse(specText);

        // Live view of openapi-source rows: latest observation per key with
        // presence='present'. Tombstoned keys aren't here, so re-appearance
        // of a previously-removed endpoint is naturally treated as "created".
        Map<String, ApiSummary> live = new HashMap<>();
        for (ApiSummary row : relationships.findApisBySource(serviceId, SOURCE)) {
            live.put(key(row.method(), row.path()), row);
        }
        // Intake-owned set: code-sync defers entirely on these keys.
        Set<String> intakeOwned = new HashSet<>();
        for (ApiSummary row : relationships.findApisBySource(serviceId, "intake")) {
            intakeOwned.add(key(row.method(), row.path()));
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (EndpointRecord ep : endpoints) {
            String k = key(ep.method(), ep.path());
            if (intakeOwned.contains(k)) {
                skipped++;
                continue;
            }
            ApiSummary current = live.remove(k);
            if (current == null) {
                relationships.insertApi(serviceId, ep.path(), ep.method(),
                        ep.authMethod(), ep.description(), SOURCE);
                created++;
            } else if (changed(current, ep)) {
                // Append a new observation; carry forward the page_id so the
                // existing per-endpoint Confluence page keeps being tracked.
                relationships.insertApi(serviceId, ep.path(), ep.method(),
                        ep.authMethod(), ep.description(), SOURCE,
                        "present", current.confluencePageId());
                updated++;
            }
            // No-op: live observation matches fresh — append-only avoids
            // recording sameness.
        }

        // Anything left in `live` is an openapi key the spec no longer contains:
        // append a tombstone, carrying forward the page_id so cleanup can find it.
        int deleted = live.size();
        for (ApiSummary stale : live.values()) {
            relationships.writeApiTombstone(serviceId, stale.method(), stale.path(),
                    SOURCE, stale.confluencePageId());
        }

        // Audit: only when something was actually written.
        if (created + updated + deleted > 0) {
            relationships.insertServiceChange(serviceId, CHANGED_BY, "updated",
                    "OpenAPI refresh: created=" + created
                            + " updated=" + updated + " deleted=" + deleted
                            + " skipped=" + skipped);
        }

        return new CodeSyncResult(created, updated, deleted, skipped);
    }

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private static boolean changed(ApiSummary current, EndpointRecord fresh) {
        return !equalsNullable(current.authMethod(), fresh.authMethod())
                || !equalsNullable(current.description(), fresh.description());
    }

    private static boolean equalsNullable(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }
}
