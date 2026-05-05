package com.atlas.codesync;

import com.atlas.services.ApiSummary;
import com.atlas.services.Service;
import com.atlas.services.ServiceBean;
import com.atlas.services.ServiceConfigProperty;
import com.atlas.services.ServiceMetadata;
import com.atlas.services.ServiceModule;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.TestScenario;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String POM_SOURCE = "pom-xml";
    private static final String POM_CHANGED_BY = "code-sync-pom";
    private static final String STALE_INTAKE_CHANGED_BY = "code-sync-stale-intake-cleanup";
    private static final String BEANS_SOURCE = "source-tree";
    private static final String BEANS_CHANGED_BY = "code-sync-beans";
    private static final String CONFIG_SOURCE = "properties-file";
    private static final String CONFIG_CHANGED_BY = "code-sync-configuration";

    private final ServiceRepository services;
    private final ServiceRelationshipsRepository relationships;
    private final OpenApiFetcher fetcher;
    private final OpenApiParser parser;
    private final RepoFileFetcher repoFetcher;
    private final JavaTestExtractor testExtractor;
    private final JavaBeanExtractor beanExtractor;
    private final PomParser pomParser;
    private final PropertiesFileParser propertiesParser;
    private final String orgGroupPrefix;

    public CodeSyncCoordinator(ServiceRepository services,
                               ServiceRelationshipsRepository relationships,
                               OpenApiFetcher fetcher,
                               OpenApiParser parser,
                               RepoFileFetcher repoFetcher,
                               JavaTestExtractor testExtractor,
                               JavaBeanExtractor beanExtractor,
                               PomParser pomParser,
                               PropertiesFileParser propertiesParser,
                               @Value("${atlas.code-sync.org-group-prefix:com.atlas}") String orgGroupPrefix) {
        this.services = services;
        this.relationships = relationships;
        this.fetcher = fetcher;
        this.parser = parser;
        this.repoFetcher = repoFetcher;
        this.testExtractor = testExtractor;
        this.beanExtractor = beanExtractor;
        this.pomParser = pomParser;
        this.propertiesParser = propertiesParser;
        this.orgGroupPrefix = orgGroupPrefix;
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
     * Refresh pom-derived metadata + external-dep observations for one
     * service (M4). Append-only: every observation is an INSERT;
     * disappearance is recorded as a tombstone.
     *
     * <p>Metadata keys observed: {@code language}, {@code language_version}
     * (from {@code java.version} property), {@code framework} +
     * {@code framework_version} (when the parent is
     * {@code spring-boot-starter-parent}), {@code build_tool}
     * ({@code Maven}). Each is written as a {@code source='pom-xml'} row in
     * {@code service_metadata}.
     *
     * <p>External dependencies: each declared {@code <dependency>} whose
     * groupId is NOT under {@code atlas.code-sync.org-group-prefix} (default
     * {@code com.atlas}) becomes an {@code external_dependencies} row (one
     * per groupId:artifactId pair, deduped) and a corresponding
     * {@code service_external_deps} observation tagged
     * {@code source='pom-xml'}.
     *
     * @return counts of inserted (created), unchanged-and-skipped (skipped),
     *         and tombstoned (deleted) writes. Updates are appended as new
     *         observations and so count toward {@code created}.
     */
    @Transactional
    public CodeSyncResult refreshPom(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        String repoUrl = service.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return CodeSyncResult.empty();
        }
        RepoFileFetcher.GitHubRepoCoords coords = RepoFileFetcher.parseGitHubUrl(repoUrl);
        String pomPath = pomPathFor(service);
        Optional<RepoFile> pomFile = repoFetcher.fetchFile(coords.owner(), coords.repo(), pomPath);
        if (pomFile.isEmpty()) {
            return CodeSyncResult.empty();
        }
        PomFacts rootFacts = pomParser.parse(pomFile.get().content());

        // Walk the <modules> tree starting from the root pom. Each visited
        // module observation is added to `walk` keyed by its module_path
        // (relative to the service root). Failures on individual sub-poms
        // (404, malformed) are skipped with a log; the rest of the tree
        // still gets observed.
        Map<String, ModuleObservation> walk = new LinkedHashMap<>();
        walkModuleTree(coords.owner(), coords.repo(), service, "", null, rootFacts, walk);

        int created = 0;
        int deleted = 0;

        // ----- service_metadata: append-only diff (root pom only — service-level
        // metadata stays sourced from the root pom; sub-module overrides live
        // on service_modules and are surfaced on the L4 module pages).
        Map<String, String> freshMetadata = freshMetadataFromFacts(rootFacts);
        Map<String, ServiceMetadata> liveMetadata = new HashMap<>();
        for (ServiceMetadata m : relationships.findServiceMetadataFor(serviceId)) {
            if (POM_SOURCE.equals(m.source())) {
                liveMetadata.put(m.key(), m);
            }
        }
        for (Map.Entry<String, String> e : freshMetadata.entrySet()) {
            ServiceMetadata current = liveMetadata.remove(e.getKey());
            if (current == null || !equalsNullable(current.value(), e.getValue())) {
                relationships.insertServiceMetadata(serviceId, e.getKey(), e.getValue(), POM_SOURCE);
                created++;
            }
        }
        for (ServiceMetadata stale : liveMetadata.values()) {
            relationships.writeServiceMetadataTombstone(serviceId, stale.key(), POM_SOURCE);
            deleted++;
        }

        // ----- service_modules: append-only diff against the walked tree.
        Map<String, ServiceModule> liveModules = new HashMap<>();
        for (ServiceModule sm : relationships.findModulesFor(serviceId)) {
            if (POM_SOURCE.equals(sm.source())) {
                liveModules.put(sm.modulePath(), sm);
            }
        }
        for (ModuleObservation obs : walk.values()) {
            ServiceModule current = liveModules.remove(obs.modulePath);
            if (current == null) {
                relationships.insertModule(serviceId, obs.modulePath, obs.parentPath,
                        obs.groupId, obs.artifactId, obs.version, obs.packaging,
                        obs.languageVersion, obs.framework, obs.frameworkVersion,
                        obs.declaredDeps, POM_SOURCE);
                created++;
            } else if (moduleChanged(current, obs)) {
                relationships.insertModule(serviceId, obs.modulePath, obs.parentPath,
                        obs.groupId, obs.artifactId, obs.version, obs.packaging,
                        obs.languageVersion, obs.framework, obs.frameworkVersion,
                        obs.declaredDeps, POM_SOURCE,
                        "present", current.confluencePageId());
                created++;
            }
            // No-op: live module matches fresh.
        }
        for (ServiceModule stale : liveModules.values()) {
            relationships.writeModuleTombstone(serviceId, stale.modulePath(), POM_SOURCE,
                    stale.confluencePageId());
            deleted++;
        }

        // ----- external dependencies: append-only diff (root-pom-only path).
        // A multi-module service that wants service-level deps to reflect
        // every sub-module's declarations should union from the tree — listed
        // as a Phase 5.6 polish carry-over in the M2 reflection. For Atlas's
        // dogfood (each service is a leaf), root-only is the complete set.
        Set<UUID> livePomExtDepIds = new HashSet<>(
                relationships.findLiveExternalDepsForService(serviceId, POM_SOURCE));

        Map<String, UUID> resolvedDepIds = new LinkedHashMap<>(); // groupId:artifactId -> ext_dep id
        for (DependencyCoords dep : rootFacts.dependencies()) {
            if (dep.groupId() == null || dep.artifactId() == null) continue;
            if (orgGroupPrefix != null && !orgGroupPrefix.isBlank()
                    && dep.groupId().startsWith(orgGroupPrefix)) continue;
            String depKey = dep.groupId() + ":" + dep.artifactId();
            UUID extDepId = relationships.findExternalDependencyIdByName(depKey)
                    .orElseGet(() -> relationships.insertExternalDependency(depKey, null));
            resolvedDepIds.put(depKey, extDepId);
        }
        for (UUID extDepId : resolvedDepIds.values()) {
            if (!livePomExtDepIds.remove(extDepId)) {
                relationships.insertServiceExternalDepObservation(serviceId, extDepId,
                        null, POM_SOURCE);
                created++;
            }
        }
        for (UUID staleExtDepId : livePomExtDepIds) {
            relationships.writeServiceExternalDepTombstone(serviceId, staleExtDepId, POM_SOURCE);
            deleted++;
        }

        if (created + deleted > 0) {
            relationships.insertServiceChange(serviceId, POM_CHANGED_BY, "updated",
                    "pom.xml refresh: created=" + created + " deleted=" + deleted);
        }
        return new CodeSyncResult(created, 0, deleted, 0);
    }

    /**
     * Walk the Maven module tree rooted at the given parsed pom. The root
     * call passes the already-parsed {@code rootFacts} and an empty
     * {@code currentPath}; recursive calls fetch each child pom from
     * GitHub and recurse into its modules.
     *
     * <p>{@code currentPath} is the module's path RELATIVE to the service
     * root (i.e. relative to {@code services.module_path}). Empty string
     * for the root module of the service.
     */
    private void walkModuleTree(String owner, String repo, Service service,
                                String currentPath, String parentPath,
                                PomFacts facts, Map<String, ModuleObservation> walk) {
        walk.put(currentPath, new ModuleObservation(
                currentPath,
                parentPath,
                facts.groupId(),
                facts.artifactId(),
                facts.version(),
                facts.packaging(),
                facts.languageVersion(),
                facts.framework(),
                facts.frameworkVersion(),
                serializeDeps(facts.dependencies())));
        if (facts.modules() == null || facts.modules().isEmpty()) return;
        for (String submodule : facts.modules()) {
            String childPath = joinModulePath(currentPath, submodule);
            String childPomPath = joinPath(joinPath(serviceModulePathPrefix(service), childPath), "pom.xml");
            try {
                Optional<RepoFile> childFile = repoFetcher.fetchFile(owner, repo, childPomPath);
                if (childFile.isEmpty()) {
                    log.warn("Sub-module pom not found: {} (parent: {})", childPomPath, currentPath);
                    continue;
                }
                PomFacts childFacts = pomParser.parse(childFile.get().content());
                walkModuleTree(owner, repo, service, childPath, currentPath, childFacts, walk);
            } catch (RuntimeException e) {
                log.warn("Skipping unparseable sub-module pom {}: {}", childPomPath, e.getMessage());
            }
        }
    }

    private static String joinModulePath(String parent, String child) {
        if (parent == null || parent.isEmpty()) return child;
        return parent + "/" + child;
    }

    private static String joinPath(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (a.endsWith("/")) return a + b;
        return a + "/" + b;
    }

    /**
     * Path prefix to prepend to a module's relative path to fetch its pom from
     * the repo. For services rooted at the repo root ({@code services.module_path}
     * null/blank), this is empty. For services rooted in a sub-directory
     * (e.g. {@code "services/billing"}), this prefix carries that.
     */
    private static String serviceModulePathPrefix(Service service) {
        String module = service.getModulePath();
        if (module == null || module.isBlank()) return "";
        return module.endsWith("/") ? module.substring(0, module.length() - 1) : module;
    }

    /**
     * Serialize a list of declared deps to a JSON array of {@code "groupId:artifactId"}
     * strings for storage in {@code service_modules.declared_deps}. Returns
     * {@code "[]"} when there are no deps; that's distinguishable from null
     * (no observation captured).
     */
    private static String serializeDeps(List<DependencyCoords> deps) {
        List<String> coords = new ArrayList<>(deps.size());
        for (DependencyCoords d : deps) {
            if (d.groupId() == null || d.artifactId() == null) continue;
            coords.add(d.groupId() + ":" + d.artifactId());
        }
        try {
            return DEPS_MAPPER.writeValueAsString(coords);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static final ObjectMapper DEPS_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static boolean moduleChanged(ServiceModule current, ModuleObservation fresh) {
        return !equalsNullable(current.parentPath(), fresh.parentPath)
                || !equalsNullable(current.groupId(), fresh.groupId)
                || !equalsNullable(current.artifactId(), fresh.artifactId)
                || !equalsNullable(current.version(), fresh.version)
                || !equalsNullable(current.packaging(), fresh.packaging)
                || !equalsNullable(current.languageVersion(), fresh.languageVersion)
                || !equalsNullable(current.framework(), fresh.framework)
                || !equalsNullable(current.frameworkVersion(), fresh.frameworkVersion)
                || !equalsNullable(current.declaredDeps(), fresh.declaredDeps);
    }

    /**
     * Internal carrier for one module observation built during the walk —
     * keeps {@link #refreshPom} out of the business of constructing the
     * many-arg {@code insertModule} call inline.
     */
    private record ModuleObservation(
            String modulePath,
            String parentPath,
            String groupId,
            String artifactId,
            String version,
            String packaging,
            String languageVersion,
            String framework,
            String frameworkVersion,
            String declaredDeps) {
    }

    /**
     * Refresh the Spring-stereotype bean rows for one service from its
     * GitHub repo (Phase 5.6 M3 — L5). Walks {@code {module_path}/src/main/java}
     * recursively, runs {@link JavaBeanExtractor} on every {@code .java}
     * source, and upserts {@code service_beans} rows append-only — the
     * same model used for {@code service_test_scenarios} (M3 of code-driven
     * docs) and {@code service_modules} (Phase 5.6 M2).
     *
     * <p>Stereotype scope: narrow Spring set only — see {@link JavaBeanExtractor}
     * for the list. The page is regenerated on every sync, so widening
     * the scope later costs no migration.
     *
     * <p>Atomic: fetch + parse complete before any DB mutation; the
     * mutation is wrapped in a transaction so a partial failure rolls back.
     *
     * @return counts of inserted (created) and tombstoned (deleted) writes.
     */
    @Transactional
    public CodeSyncResult refreshBeans(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        String repoUrl = service.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return CodeSyncResult.empty();
        }
        RepoFileFetcher.GitHubRepoCoords coords = RepoFileFetcher.parseGitHubUrl(repoUrl);
        String mainPath = mainSourcesPathFor(service);
        String modulePathTag = service.getModulePath() == null ? "" : service.getModulePath();

        List<RepoFile> sources = repoFetcher.listJavaSourcesUnder(coords.owner(), coords.repo(), mainPath);
        List<BeanRecord> fresh = new ArrayList<>();
        for (RepoFile f : sources) {
            try {
                fresh.addAll(beanExtractor.extract(f.content()));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping unparseable source {}: {}", f.path(), e.getMessage());
            }
        }

        // Live view: latest observation per (module_path, package, class) where present.
        Map<String, ServiceBean> live = new HashMap<>();
        for (ServiceBean b : relationships.findBeansFor(serviceId)) {
            live.put(beanKey(b.modulePath(), b.packageName(), b.className()), b);
        }

        int created = 0;
        for (BeanRecord rec : fresh) {
            String publicMethodsJson = serializeMethods(rec.publicMethods());
            String k = beanKey(modulePathTag, rec.packageName(), rec.className());
            ServiceBean current = live.remove(k);
            if (current == null) {
                relationships.insertBean(serviceId, modulePathTag, rec.packageName(),
                        rec.className(), rec.stereotype(),
                        rec.classJavadocSummary(), publicMethodsJson);
                created++;
            } else if (beanChanged(current, rec, publicMethodsJson)) {
                relationships.insertBean(serviceId, modulePathTag, rec.packageName(),
                        rec.className(), rec.stereotype(),
                        rec.classJavadocSummary(), publicMethodsJson);
                created++;
            }
        }
        int deleted = live.size();
        for (ServiceBean stale : live.values()) {
            relationships.writeBeanTombstone(serviceId, stale.modulePath(),
                    stale.packageName(), stale.className(), BEANS_SOURCE);
        }

        if (created + deleted > 0) {
            relationships.insertServiceChange(serviceId, BEANS_CHANGED_BY, "updated",
                    "Beans refresh: created=" + created + " deleted=" + deleted);
        }
        return new CodeSyncResult(created, 0, deleted, 0);
    }

    private static String mainSourcesPathFor(Service service) {
        String module = service.getModulePath();
        if (module == null || module.isBlank()) return "src/main/java";
        String trimmed = module.endsWith("/") ? module.substring(0, module.length() - 1) : module;
        return trimmed + "/src/main/java";
    }

    private static String beanKey(String modulePath, String packageName, String className) {
        return (modulePath == null ? "" : modulePath) + "|"
                + (packageName == null ? "" : packageName) + "|"
                + className;
    }

    private static boolean beanChanged(ServiceBean current, BeanRecord fresh, String freshMethodsJson) {
        return !equalsNullable(current.stereotype(), fresh.stereotype())
                || !equalsNullable(current.classJavadocSummary(), fresh.classJavadocSummary())
                || !equalsNullable(current.publicMethods(), freshMethodsJson);
    }

    /**
     * Serialize the extractor's method records to a JSON array string for
     * persistence in {@code service_beans.public_methods}. Returns
     * {@code "[]"} when there are no methods so the column is never NULL
     * for live observations.
     */
    private static String serializeMethods(List<BeanRecord.MethodRecord> methods) {
        try {
            return BEANS_MAPPER.writeValueAsString(methods);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static final ObjectMapper BEANS_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static String pomPathFor(Service service) {
        String module = service.getModulePath();
        if (module == null || module.isBlank()) return "pom.xml";
        String trimmed = module.endsWith("/") ? module.substring(0, module.length() - 1) : module;
        return trimmed + "/pom.xml";
    }

    /**
     * Refresh the property-key observations for one service from the
     * Spring Boot configuration files in its repo (Phase 5.9 M1). Walks
     * {@code {module_path}/src/main/resources/} non-recursively, parses every
     * {@code application*.{properties,yml,yaml}} file via
     * {@link PropertiesFileParser}, and upserts {@code service_config_properties}
     * rows tagged {@code source='properties-file'}. Append-only — disappeared
     * keys are tombstoned.
     *
     * <p>Atomic: fetch + parse complete before any DB mutation; the mutation
     * is wrapped in a transaction so a partial failure rolls back.
     *
     * <p>Limits documented at the plan level (DD-016 / DD-017):
     * {@code spring.config.import} chained imports are not followed; relaxed
     * binding aliases are not normalised.
     *
     * @return counts of inserted (created) and tombstoned (deleted) writes.
     */
    @Transactional
    public CodeSyncResult refreshConfiguration(UUID serviceId) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        String repoUrl = service.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return CodeSyncResult.empty();
        }
        RepoFileFetcher.GitHubRepoCoords coords = RepoFileFetcher.parseGitHubUrl(repoUrl);
        String resourcesPath = resourcesPathFor(service);

        // Fetch + parse first; only mutate the DB on success.
        List<RepoFile> files = repoFetcher.listFilesIn(coords.owner(), coords.repo(), resourcesPath);
        List<ParsedConfigEntry> fresh = new ArrayList<>();
        for (RepoFile f : files) {
            String filename = filenameOf(f.path());
            for (PropertyEntry entry : propertiesParser.parse(f.content(), filename)) {
                fresh.add(new ParsedConfigEntry(
                        entry.keyPath(), entry.value(), entry.profile(), filename));
            }
        }

        // Live view: latest observation per (key_path, profile, source_file)
        // where presence='present'. Append-only diff vs `fresh`.
        Map<String, ServiceConfigProperty> live = new HashMap<>();
        for (ServiceConfigProperty p : relationships.findConfigPropertiesFor(serviceId)) {
            if (CONFIG_SOURCE.equals(p.source())) {
                live.put(configKey(p.keyPath(), p.profile(), p.sourceFile()), p);
            }
        }

        int created = 0;
        for (ParsedConfigEntry e : fresh) {
            String k = configKey(e.keyPath(), e.profile(), e.sourceFile());
            ServiceConfigProperty current = live.remove(k);
            if (current == null) {
                relationships.insertConfigProperty(serviceId, e.keyPath(), e.value(),
                        e.sourceFile(), e.profile());
                created++;
            } else if (!equalsNullable(current.value(), e.value())) {
                relationships.insertConfigProperty(serviceId, e.keyPath(), e.value(),
                        e.sourceFile(), e.profile());
                created++;
            }
        }
        int deleted = live.size();
        for (ServiceConfigProperty stale : live.values()) {
            relationships.writeConfigPropertyTombstone(serviceId,
                    stale.keyPath(), stale.sourceFile(), stale.profile(), CONFIG_SOURCE);
        }

        if (created + deleted > 0) {
            relationships.insertServiceChange(serviceId, CONFIG_CHANGED_BY, "updated",
                    "Configuration refresh: created=" + created + " deleted=" + deleted);
        }
        return new CodeSyncResult(created, 0, deleted, 0);
    }

    private static String resourcesPathFor(Service service) {
        String module = service.getModulePath();
        if (module == null || module.isBlank()) return "src/main/resources";
        String trimmed = module.endsWith("/") ? module.substring(0, module.length() - 1) : module;
        return trimmed + "/src/main/resources";
    }

    private static String filenameOf(String repoPath) {
        if (repoPath == null) return "";
        int slash = repoPath.lastIndexOf('/');
        return slash < 0 ? repoPath : repoPath.substring(slash + 1);
    }

    private static String configKey(String keyPath, String profile, String sourceFile) {
        return keyPath + "|" + profile + "|" + sourceFile;
    }

    private record ParsedConfigEntry(String keyPath, String value, String profile, String sourceFile) {}

    private static Map<String, String> freshMetadataFromFacts(PomFacts facts) {
        Map<String, String> out = new LinkedHashMap<>();
        if (facts.language() != null) out.put("language", facts.language());
        if (facts.languageVersion() != null) out.put("language_version", facts.languageVersion());
        if (facts.framework() != null) out.put("framework", facts.framework());
        if (facts.frameworkVersion() != null) out.put("framework_version", facts.frameworkVersion());
        if (facts.buildTool() != null) out.put("build_tool", facts.buildTool());
        return out;
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
                        ep.authMethod(), ep.description(), SOURCE,
                        "present", null, ep.openapiSnapshot());
                created++;
            } else if (changed(current, ep)) {
                // Append a new observation; carry forward the page_id so the
                // existing per-endpoint Confluence page keeps being tracked.
                relationships.insertApi(serviceId, ep.path(), ep.method(),
                        ep.authMethod(), ep.description(), SOURCE,
                        "present", current.confluencePageId(), ep.openapiSnapshot());
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

    /**
     * Tombstone intake-source api observations whose {@code (method, path)}
     * has no live openapi-source counterpart on the same service (M4.5
     * cleanup mechanism). Opt-in per service: callers invoke this explicitly
     * via {@code POST /api/code-sync/tombstone-stale-intake-apis/{serviceId}}
     * after running an OpenAPI refresh that confirms the current set of
     * endpoints. Not auto-fired during {@link #refreshOpenApi} — the M1
     * intake-skip rule remains the default for cautious teams.
     *
     * <p>The tombstone preserves {@code source='intake'} so provenance stays
     * honest: this is recording that an intake observation is no longer
     * present in any code-sync evidence, not converting it to a different
     * source. Audit row written with
     * {@code changed_by='code-sync-stale-intake-cleanup'}.
     *
     * @return counts: {@code deleted} = number of stale intake rows tombstoned;
     *         the other fields are zero.
     */
    @Transactional
    public CodeSyncResult tombstoneStaleIntakeApis(UUID serviceId) {
        services.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        List<ApiSummary> stale = relationships.findStaleIntakeApis(serviceId);
        if (stale.isEmpty()) {
            return CodeSyncResult.empty();
        }
        for (ApiSummary api : stale) {
            relationships.writeApiTombstone(serviceId, api.method(), api.path(),
                    "intake", api.confluencePageId());
        }
        relationships.insertServiceChange(serviceId, STALE_INTAKE_CHANGED_BY, "updated",
                "Stale-intake cleanup: tombstoned=" + stale.size());
        return new CodeSyncResult(0, 0, stale.size(), 0);
    }

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private static boolean changed(ApiSummary current, EndpointRecord fresh) {
        return !equalsNullable(current.authMethod(), fresh.authMethod())
                || !equalsNullable(current.description(), fresh.description())
                || !equalsNullable(current.openapiSnapshot(), fresh.openapiSnapshot());
    }

    private static boolean equalsNullable(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }
}
