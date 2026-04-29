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

        Map<String, TestScenario> existing = new HashMap<>();
        for (TestScenario s : relationships.findTestScenariosFor(serviceId)) {
            existing.put(scenarioKey(s.packageName(), s.className(), s.methodName()), s);
        }

        int created = 0;
        for (TestMethodRecord rec : fresh) {
            String k = scenarioKey(rec.packageName(), rec.className(), rec.methodName());
            if (existing.remove(k) == null) {
                relationships.insertTestScenario(serviceId,
                        rec.packageName(), rec.className(), rec.methodName(),
                        TESTS_SOURCE);
                created++;
            }
        }

        int deleted = existing.size();
        for (TestScenario stale : existing.values()) {
            relationships.deleteTestScenario(stale.id());
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
     * Refresh the OpenAPI-derived APIs for one service.
     *
     * <p>Atomic: fetch + parse happen before any DB mutation, and the mutation is
     * wrapped in a transaction so a partial failure (constraint violation, DB
     * blip) leaves the previous state intact rather than half-applied.
     *
     * <p>Endpoints whose {@code (method, path)} is already owned by an
     * {@code 'intake'}-source row are <b>skipped</b>, not overwritten. Intake's
     * row stays authoritative until the user re-runs intake or removes the
     * endpoint there — the unique constraint on
     * {@code (service_id, method, path)} forbids a parallel openapi row anyway.
     * The skip is reflected in {@link CodeSyncResult#skipped()}.
     *
     * @return counts of inserted/updated/deleted/skipped rows.
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
        // malformed spec leaves the existing rows untouched.
        String specText = fetcher.fetch(url);
        List<EndpointRecord> endpoints = parser.parse(specText);

        // Build a lookup of existing openapi-source rows keyed by (METHOD, path).
        // Intake-source rows are loaded into a separate "do not touch" set so
        // the loop knows to skip endpoints intake has already covered.
        Map<String, ApiSummary> existing = new HashMap<>();
        for (ApiSummary row : relationships.findApisBySource(serviceId, SOURCE)) {
            existing.put(key(row.method(), row.path()), row);
        }
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
            ApiSummary current = existing.remove(k);
            if (current == null) {
                relationships.insertApi(serviceId, ep.path(), ep.method(),
                        ep.authMethod(), ep.description(), SOURCE);
                created++;
            } else if (changed(current, ep)) {
                relationships.updateApi(current.id(), ep.authMethod(), ep.description());
                updated++;
            }
        }

        // Anything left in `existing` is an openapi row no longer in the spec — delete it.
        int deleted = existing.size();
        for (ApiSummary stale : existing.values()) {
            relationships.deleteApi(stale.id());
        }

        // Audit a single row summarising the refresh — the change-detail tally lives in
        // the response, not in the audit summary, to keep the audit table compact.
        // Skipped endpoints don't count as a mutation, so a refresh that only skips is
        // effectively a no-op and produces no audit row.
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
