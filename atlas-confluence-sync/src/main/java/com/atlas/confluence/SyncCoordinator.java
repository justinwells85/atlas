package com.atlas.confluence;

import com.atlas.services.ApiConsumer;
import com.atlas.services.ApiSummary;
import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.DataStoreInventoryRow;
import com.atlas.services.ExternalDependencyInventoryRow;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceBean;
import com.atlas.services.ServiceConfigProperty;
import com.atlas.services.ServiceConfigurationPropertiesType;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceEnableAnnotation;
import com.atlas.services.ServiceMetadata;
import com.atlas.services.ServiceModule;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceValueInjection;
import com.atlas.services.SoftDeletedApiPage;
import com.atlas.services.SoftDeletedModulePage;
import com.atlas.services.TestScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Orchestrates the DB → wiki sync. Loads each service plus its
 * relationship rows, renders a page body for the L1–L5 hierarchy, and
 * dispatches create / update / delete calls to every enabled
 * {@link WikiSink} bean — Confluence Cloud and the local Markdown vault
 * (Phase 5.8 M3) today.
 *
 * <p>Per-service exceptions are caught and reported in {@link SyncResult} —
 * one bad service does not abort the rest of {@code syncAll()}.
 *
 * <p>Layout (per docs/confluence-layout.md):
 * <ul>
 *   <li>Each service page is parented under a single landing page titled
 *       {@code atlas.confluence.landing-page-title} (default
 *       "Atlas — Service Inventory").</li>
 *   <li>Two inventory sub-pages ("Inventory: Data Stores", "Inventory:
 *       External Dependencies"), one "About Atlas" page, and an "Atlas —
 *       Architecture Map" page also parent under the landing page.</li>
 *   <li>Service page titles are prefixed with "Service: " so they're
 *       easy to scan in the space's sidebar.</li>
 *   <li>Cross-links: each service page links to its referenced peers
 *       (upstream/downstream/consumers) using the peers' wiki refs
 *       (Confluence URL or Obsidian WikiLink target depending on the
 *       sink), and back-links its database / external-dep references
 *       into the inventory pages.</li>
 *   <li>All well-known pages are regenerated on every sync.</li>
 * </ul>
 *
 * <p><b>Multi-sink architecture (Phase 5.8 M3).</b> The coordinator
 * iterates over every enabled {@link WikiSink}. For each page, it
 * constructs a sink-appropriate context (Confluence URLs vs. Obsidian
 * WikiLink targets), routes to the matching renderer (Confluence
 * {@code *PageRenderer} vs. Markdown {@code *MarkdownRenderer}), and
 * persists the returned ref to the corresponding column —
 * {@code confluence_page_id} for the Confluence sink,
 * {@code local_markdown_path} for the Markdown sink. Both columns
 * coexist on the same row and update independently. Cleanup paths walk
 * both columns and dispatch deletes per sink.
 *
 * <p>Sinks Atlas does not have a dedicated renderer for (e.g. test
 * doubles) receive the Confluence-format body — the Confluence path is
 * the default fallback for unknown sink names. Their refs are still
 * captured and written to the Confluence column when they happen to
 * be the only sink the coordinator sees, but a real third sink would
 * need its own renderer fork here.
 */
@Component
public class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);
    private static final int RECENT_CHANGES_LIMIT = 10;
    private static final String SERVICE_TITLE_PREFIX = "Service: ";
    private static final String DATA_STORE_INVENTORY_TITLE = "Inventory: Data Stores";
    private static final String EXTERNAL_DEP_INVENTORY_TITLE = "Inventory: External Dependencies";
    private static final String ABOUT_PAGE_TITLE = "About Atlas";
    private static final String ARCHITECTURE_MAP_TITLE = "Atlas — Architecture Map";

    private final ServiceRepository serviceRepository;
    private final ServiceRelationshipsRepository relationships;
    // Confluence renderers (existing).
    private final ServicePageRenderer renderer;
    private final ApiEndpointPageRenderer endpointRenderer;
    private final ModulePageRenderer modulePageRenderer;
    private final BeansPageRenderer beansPageRenderer;
    private final ConfigurationPageRenderer configurationPageRenderer;
    private final TestScenariosPageRenderer testScenariosRenderer;
    private final LandingPageRenderer landingRenderer;
    private final DataStoreInventoryRenderer dataStoreInventoryRenderer;
    private final ExternalDependencyInventoryRenderer externalDepInventoryRenderer;
    private final AboutPageRenderer aboutRenderer;
    private final ArchitectureMapRenderer architectureMapRenderer;
    // Markdown renderers (Phase 5.8 M2).
    private final ServiceMarkdownRenderer serviceMarkdownRenderer;
    private final ApiEndpointMarkdownRenderer endpointMarkdownRenderer;
    private final ModuleMarkdownRenderer moduleMarkdownRenderer;
    private final BeansMarkdownRenderer beansMarkdownRenderer;
    private final ConfigurationMarkdownRenderer configurationMarkdownRenderer;
    private final TestScenariosMarkdownRenderer testScenariosMarkdownRenderer;
    private final LandingMarkdownRenderer landingMarkdownRenderer;
    private final DataStoreInventoryMarkdownRenderer dataStoreInventoryMarkdownRenderer;
    private final ExternalDependencyInventoryMarkdownRenderer externalDepInventoryMarkdownRenderer;
    private final AboutMarkdownRenderer aboutMarkdownRenderer;
    private final ArchitectureMapMarkdownRenderer architectureMapMarkdownRenderer;

    private final List<WikiSink> sinks;
    private final String spaceKey;
    private final String baseUrl;
    private final String landingPageTitle;

    public SyncCoordinator(
            ServiceRepository serviceRepository,
            ServiceRelationshipsRepository relationships,
            ServicePageRenderer renderer,
            ApiEndpointPageRenderer endpointRenderer,
            ModulePageRenderer modulePageRenderer,
            BeansPageRenderer beansPageRenderer,
            ConfigurationPageRenderer configurationPageRenderer,
            TestScenariosPageRenderer testScenariosRenderer,
            LandingPageRenderer landingRenderer,
            DataStoreInventoryRenderer dataStoreInventoryRenderer,
            ExternalDependencyInventoryRenderer externalDepInventoryRenderer,
            AboutPageRenderer aboutRenderer,
            ArchitectureMapRenderer architectureMapRenderer,
            ServiceMarkdownRenderer serviceMarkdownRenderer,
            ApiEndpointMarkdownRenderer endpointMarkdownRenderer,
            ModuleMarkdownRenderer moduleMarkdownRenderer,
            BeansMarkdownRenderer beansMarkdownRenderer,
            ConfigurationMarkdownRenderer configurationMarkdownRenderer,
            TestScenariosMarkdownRenderer testScenariosMarkdownRenderer,
            LandingMarkdownRenderer landingMarkdownRenderer,
            DataStoreInventoryMarkdownRenderer dataStoreInventoryMarkdownRenderer,
            ExternalDependencyInventoryMarkdownRenderer externalDepInventoryMarkdownRenderer,
            AboutMarkdownRenderer aboutMarkdownRenderer,
            ArchitectureMapMarkdownRenderer architectureMapMarkdownRenderer,
            List<WikiSink> sinks,
            @Value("${atlas.confluence.space-key}") String spaceKey,
            @Value("${atlas.confluence.base-url}") String baseUrl,
            @Value("${atlas.confluence.landing-page-title:Atlas — Service Inventory}") String landingPageTitle) {
        this.serviceRepository = serviceRepository;
        this.relationships = relationships;
        this.renderer = renderer;
        this.endpointRenderer = endpointRenderer;
        this.modulePageRenderer = modulePageRenderer;
        this.beansPageRenderer = beansPageRenderer;
        this.configurationPageRenderer = configurationPageRenderer;
        this.testScenariosRenderer = testScenariosRenderer;
        this.landingRenderer = landingRenderer;
        this.dataStoreInventoryRenderer = dataStoreInventoryRenderer;
        this.externalDepInventoryRenderer = externalDepInventoryRenderer;
        this.aboutRenderer = aboutRenderer;
        this.architectureMapRenderer = architectureMapRenderer;
        this.serviceMarkdownRenderer = serviceMarkdownRenderer;
        this.endpointMarkdownRenderer = endpointMarkdownRenderer;
        this.moduleMarkdownRenderer = moduleMarkdownRenderer;
        this.beansMarkdownRenderer = beansMarkdownRenderer;
        this.configurationMarkdownRenderer = configurationMarkdownRenderer;
        this.testScenariosMarkdownRenderer = testScenariosMarkdownRenderer;
        this.landingMarkdownRenderer = landingMarkdownRenderer;
        this.dataStoreInventoryMarkdownRenderer = dataStoreInventoryMarkdownRenderer;
        this.externalDepInventoryMarkdownRenderer = externalDepInventoryMarkdownRenderer;
        this.aboutMarkdownRenderer = aboutMarkdownRenderer;
        this.architectureMapMarkdownRenderer = architectureMapMarkdownRenderer;
        this.sinks = List.copyOf(sinks);
        this.spaceKey = spaceKey;
        this.baseUrl = baseUrl;
        this.landingPageTitle = landingPageTitle;
    }

    /** Sync every service in the DB. Per-service errors are isolated. */
    public SyncResult syncAll() {
        if (sinks.isEmpty()) {
            log.info("No wiki sinks enabled; sync is a no-op.");
            return new SyncResult(0, 0, List.of());
        }

        // Cleanup pass first: any service rows soft-deleted since last sync
        // need their wiki pages removed. Independent of the active service
        // list — runs even when there are no live services.
        cleanupDeletedServices();
        cleanupDeletedApiPages();
        cleanupDeletedModulePages();

        List<Service> services = serviceRepository.findAll();
        if (services.isEmpty()) {
            // No live services — skip the rest of the wiki calls.
            return new SyncResult(0, 0, List.of());
        }

        WellKnownPages pages = ensureWellKnownPages(services);

        int successes = 0;
        List<SyncFailure> failures = new ArrayList<>();
        for (Service s : services) {
            try {
                syncOneInternal(s, pages, services);
                successes++;
            } catch (Exception e) {
                log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
                failures.add(new SyncFailure(s.getId(), s.getName(), e.getMessage()));
            }
        }

        // Re-read so the well-known refresh sees the newly populated refs.
        List<Service> reloaded = serviceRepository.findAll();
        refreshWellKnownPages(pages, reloaded);
        return new SyncResult(successes, failures.size(), failures);
    }

    /** Sync one service by ID. */
    public SyncResult syncOne(UUID serviceId) {
        if (sinks.isEmpty()) {
            log.info("No wiki sinks enabled; sync is a no-op.");
            return new SyncResult(0, 0, List.of());
        }
        Service s = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No service with id " + serviceId));
        try {
            List<Service> allServices = serviceRepository.findAll();
            WellKnownPages pages = ensureWellKnownPages(allServices);
            syncOneInternal(s, pages, allServices);
            List<Service> reloaded = serviceRepository.findAll();
            refreshWellKnownPages(pages, reloaded);
            return new SyncResult(1, 0, List.of());
        } catch (Exception e) {
            log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
            return new SyncResult(0, 1, List.of(new SyncFailure(s.getId(), s.getName(), e.getMessage())));
        }
    }

    // ---- Cleanup paths ----------------------------------------------------

    /**
     * For every soft-deleted service that still carries a wiki ref on at
     * least one sink, delete the page on each sink that has a ref and null
     * the corresponding column. Per-row errors are logged and skipped —
     * one stuck page does not block other cleanups.
     */
    private void cleanupDeletedServices() {
        // Pass 1: Confluence-side stale refs.
        for (Service s : serviceRepository.findSoftDeletedWithConfluencePage()) {
            String ref = s.getConfluencePageId();
            try {
                deleteOnSinkByName(ConfluenceWikiSink.NAME, ref);
                serviceRepository.clearConfluencePageId(s.getId());
                log.info("Cleaned up Confluence page {} for soft-deleted service {} ({})",
                        ref, s.getName(), s.getId());
            } catch (Exception e) {
                log.warn("Cleanup of Confluence page {} for soft-deleted service {} ({}) failed: {}",
                        ref, s.getName(), s.getId(), e.getMessage());
            }
        }
        // Pass 2: Markdown-side stale refs (Phase 5.8 M3).
        for (Service s : serviceRepository.findSoftDeletedWithLocalMarkdownPath()) {
            String ref = s.getLocalMarkdownPath();
            try {
                deleteOnSinkByName(LocalMarkdownWikiSink.NAME, ref);
                serviceRepository.clearLocalMarkdownPath(s.getId());
                log.info("Cleaned up Markdown page {} for soft-deleted service {} ({})",
                        ref, s.getName(), s.getId());
            } catch (Exception e) {
                log.warn("Cleanup of Markdown page {} for soft-deleted service {} ({}) failed: {}",
                        ref, s.getName(), s.getId(), e.getMessage());
            }
        }
    }

    /**
     * For every "stale" endpoint page — a tombstone that is the latest
     * observation for its key and still carries a non-null ref on at least
     * one sink — delete the page per sink and null the corresponding column.
     */
    private void cleanupDeletedApiPages() {
        for (SoftDeletedApiPage orphan : relationships.findStaleApiPages()) {
            if (orphan.confluencePageId() != null) {
                try {
                    deleteOnSinkByName(ConfluenceWikiSink.NAME, orphan.confluencePageId());
                    relationships.clearApiConfluencePageId(orphan.apiId());
                    log.info("Cleaned up Confluence endpoint page {} for tombstoned api {} {} on service {}",
                            orphan.confluencePageId(), orphan.method(), orphan.path(), orphan.serviceName());
                } catch (Exception e) {
                    log.warn("Cleanup of Confluence endpoint page {} for {} {} on {} failed: {}",
                            orphan.confluencePageId(), orphan.method(), orphan.path(),
                            orphan.serviceName(), e.getMessage());
                }
            }
            if (orphan.localMarkdownPath() != null) {
                try {
                    deleteOnSinkByName(LocalMarkdownWikiSink.NAME, orphan.localMarkdownPath());
                    relationships.clearApiLocalMarkdownPath(orphan.apiId());
                    log.info("Cleaned up Markdown endpoint page {} for tombstoned api {} {} on service {}",
                            orphan.localMarkdownPath(), orphan.method(), orphan.path(), orphan.serviceName());
                } catch (Exception e) {
                    log.warn("Cleanup of Markdown endpoint page {} for {} {} on {} failed: {}",
                            orphan.localMarkdownPath(), orphan.method(), orphan.path(),
                            orphan.serviceName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Mirror of {@link #cleanupDeletedApiPages()} at the L4 module grain.
     */
    private void cleanupDeletedModulePages() {
        for (SoftDeletedModulePage orphan : relationships.findStaleModulePages()) {
            if (orphan.confluencePageId() != null) {
                try {
                    deleteOnSinkByName(ConfluenceWikiSink.NAME, orphan.confluencePageId());
                    relationships.clearModuleConfluencePageId(orphan.moduleObservationId());
                    log.info("Cleaned up Confluence module page {} for tombstoned module {} on service {}",
                            orphan.confluencePageId(), orphan.modulePath(), orphan.serviceName());
                } catch (Exception e) {
                    log.warn("Cleanup of Confluence module page {} for {} on {} failed: {}",
                            orphan.confluencePageId(), orphan.modulePath(),
                            orphan.serviceName(), e.getMessage());
                }
            }
            if (orphan.localMarkdownPath() != null) {
                try {
                    deleteOnSinkByName(LocalMarkdownWikiSink.NAME, orphan.localMarkdownPath());
                    relationships.clearModuleLocalMarkdownPath(orphan.moduleObservationId());
                    log.info("Cleaned up Markdown module page {} for tombstoned module {} on service {}",
                            orphan.localMarkdownPath(), orphan.modulePath(), orphan.serviceName());
                } catch (Exception e) {
                    log.warn("Cleanup of Markdown module page {} for {} on {} failed: {}",
                            orphan.localMarkdownPath(), orphan.modulePath(),
                            orphan.serviceName(), e.getMessage());
                }
            }
        }
    }

    // ---- Per-service sync -------------------------------------------------

    private void syncOneInternal(Service service, WellKnownPages pages, List<Service> allServices) {
        // Pass 1: create-or-update the L2 service page so child pages have a
        // parent ref to nest under. Section 8 "Internals" will render with
        // whatever cross-link state currently exists (often empty on first
        // sync of a fresh service).
        renderAndUpsertServicePage(service, pages, allServices);

        // Per-endpoint pages: each api row gets its own page parented under
        // the service page just persisted. Per-endpoint failures are caught
        // and logged so one bad endpoint doesn't block the others.
        syncEndpointPages(service);
        syncModulePages(service);
        syncTestsPage(service);
        syncBeansPage(service);
        syncConfigurationPage(service);

        // Pass 2: re-render the L2 service page so Section 8 "Internals"
        // picks up the refs assigned by the child syncs above.
        renderAndUpsertServicePage(service, pages, allServices);
    }

    private void renderAndUpsertServicePage(Service service, WellKnownPages pages, List<Service> allServices) {
        String title = SERVICE_TITLE_PREFIX + service.getName();
        List<SinkPlan> plans = new ArrayList<>(sinks.size());
        for (WikiSink sink : sinks) {
            plans.add(planServicePage(sink, service, pages, allServices));
        }
        applyUpserts(plans, title);
        service.setLastSyncedToConfluence(OffsetDateTime.now());
        serviceRepository.save(service);
    }

    private SinkPlan planServicePage(WikiSink sink, Service service, WellKnownPages pages, List<Service> allServices) {
        ServicePageContext ctx = buildServiceContext(sink, service, allServices, pages.inventoryUrlsFor(sink));
        String body;
        String existingRef;
        Consumer<String> persist;
        switch (routeOf(sink)) {
            case MARKDOWN -> {
                body = serviceMarkdownRenderer.render(ctx);
                existingRef = service.getLocalMarkdownPath();
                persist = freshRef -> {
                    if (!Objects.equals(freshRef, service.getLocalMarkdownPath())) {
                        service.setLocalMarkdownPath(freshRef);
                    }
                };
            }
            case CONFLUENCE -> {
                body = renderer.render(ctx);
                existingRef = service.getConfluencePageId();
                persist = freshRef -> {
                    if (!Objects.equals(freshRef, service.getConfluencePageId())) {
                        service.setConfluencePageId(freshRef);
                    }
                };
            }
            default -> {
                body = renderer.render(ctx);
                existingRef = null;
                persist = NO_OP_PERSIST;
            }
        }
        return new SinkPlan(sink, existingRef, body, pages.landingRefFor(sink), persist);
    }

    private void syncTestsPage(Service service) {
        try {
            List<TestScenario> scenarios = relationships.findTestScenariosFor(service.getId());
            String title = TestScenariosPageRenderer.pageTitle(service);
            List<SinkPlan> plans = new ArrayList<>(sinks.size());
            for (WikiSink sink : sinks) {
                TestScenariosPageContext ctx = new TestScenariosPageContext(
                        service, scenarios, serviceCrossLinkTargetFor(sink, service));
                String body;
                String existingRef;
                Consumer<String> persist;
                switch (routeOf(sink)) {
                    case MARKDOWN -> {
                        body = testScenariosMarkdownRenderer.render(ctx);
                        existingRef = service.getTestsMarkdownPath();
                        persist = freshRef -> {
                            if (!Objects.equals(freshRef, service.getTestsMarkdownPath())) {
                                relationships.setServiceTestsMarkdownPath(service.getId(), freshRef);
                                service.setTestsMarkdownPath(freshRef);
                            }
                        };
                    }
                    case CONFLUENCE -> {
                        body = testScenariosRenderer.render(ctx);
                        existingRef = service.getTestsPageId();
                        persist = freshRef -> {
                            if (!Objects.equals(freshRef, service.getTestsPageId())) {
                                relationships.setServiceTestsPageId(service.getId(), freshRef);
                                service.setTestsPageId(freshRef);
                            }
                        };
                    }
                    default -> {
                        body = testScenariosRenderer.render(ctx);
                        existingRef = null;
                        persist = NO_OP_PERSIST;
                    }
                }
                plans.add(new SinkPlan(sink, existingRef, body, serviceParentRefFor(sink, service), persist));
            }
            applyUpserts(plans, title);
        } catch (Exception e) {
            log.warn("Tests-page sync failed for service {}: {}", service.getName(), e.getMessage());
        }
    }

    private void syncBeansPage(Service service) {
        try {
            List<ServiceBean> beans = relationships.findBeansFor(service.getId());
            String title = BeansPageRenderer.pageTitle(service);
            List<SinkPlan> plans = new ArrayList<>(sinks.size());
            for (WikiSink sink : sinks) {
                BeansPageContext ctx = new BeansPageContext(
                        service, beans, serviceCrossLinkTargetFor(sink, service));
                String body;
                String existingRef;
                Consumer<String> persist;
                switch (routeOf(sink)) {
                    case MARKDOWN -> {
                        body = beansMarkdownRenderer.render(ctx);
                        existingRef = service.getBeansMarkdownPath();
                        persist = freshRef -> {
                            if (!Objects.equals(freshRef, service.getBeansMarkdownPath())) {
                                relationships.setServiceBeansMarkdownPath(service.getId(), freshRef);
                                service.setBeansMarkdownPath(freshRef);
                            }
                        };
                    }
                    case CONFLUENCE -> {
                        body = beansPageRenderer.render(ctx);
                        existingRef = service.getBeansPageId();
                        persist = freshRef -> {
                            if (!Objects.equals(freshRef, service.getBeansPageId())) {
                                relationships.setServiceBeansPageId(service.getId(), freshRef);
                                service.setBeansPageId(freshRef);
                            }
                        };
                    }
                    default -> {
                        body = beansPageRenderer.render(ctx);
                        existingRef = null;
                        persist = NO_OP_PERSIST;
                    }
                }
                plans.add(new SinkPlan(sink, existingRef, body, serviceParentRefFor(sink, service), persist));
            }
            applyUpserts(plans, title);
        } catch (Exception e) {
            log.warn("Beans-page sync failed for service {}: {}", service.getName(), e.getMessage());
        }
    }

    private void syncConfigurationPage(Service service) {
        try {
            List<ServiceConfigProperty> properties = relationships.findConfigPropertiesFor(service.getId());
            List<ServiceValueInjection> injections = relationships.findValueInjectionsFor(service.getId());
            List<ServiceConfigurationPropertiesType> configTypes =
                    relationships.findConfigurationPropertiesTypesFor(service.getId());
            List<ServiceEnableAnnotation> enables = relationships.findEnableAnnotationsFor(service.getId());
            String title = ConfigurationPageRenderer.pageTitle(service);
            List<SinkPlan> plans = new ArrayList<>(sinks.size());
            for (WikiSink sink : sinks) {
                ConfigurationPageContext ctx = new ConfigurationPageContext(
                        service, properties, injections, configTypes, enables,
                        serviceCrossLinkTargetFor(sink, service));
                String body;
                String existingRef;
                Consumer<String> persist;
                switch (routeOf(sink)) {
                    case MARKDOWN -> {
                        body = configurationMarkdownRenderer.render(ctx);
                        existingRef = service.getConfigurationMarkdownPath();
                        persist = freshRef -> {
                            if (!Objects.equals(freshRef, service.getConfigurationMarkdownPath())) {
                                relationships.setServiceConfigurationMarkdownPath(service.getId(), freshRef);
                                service.setConfigurationMarkdownPath(freshRef);
                            }
                        };
                    }
                    case CONFLUENCE -> {
                        body = configurationPageRenderer.render(ctx);
                        existingRef = service.getConfigurationPageId();
                        persist = freshRef -> {
                            if (!Objects.equals(freshRef, service.getConfigurationPageId())) {
                                relationships.setServiceConfigurationPageId(service.getId(), freshRef);
                                service.setConfigurationPageId(freshRef);
                            }
                        };
                    }
                    default -> {
                        body = configurationPageRenderer.render(ctx);
                        existingRef = null;
                        persist = NO_OP_PERSIST;
                    }
                }
                plans.add(new SinkPlan(sink, existingRef, body, serviceParentRefFor(sink, service), persist));
            }
            applyUpserts(plans, title);
        } catch (Exception e) {
            log.warn("Configuration-page sync failed for service {}: {}", service.getName(), e.getMessage());
        }
    }

    private void syncModulePages(Service service) {
        List<ServiceModule> modules = relationships.findModulesFor(service.getId());
        if (modules.isEmpty()) return;

        // Two-pass: pass 1 ensures every module has a ref on every sink
        // (creates new pages with empty cross-link maps); pass 2 re-renders
        // each page so parent/child links resolve to refs from the now-
        // populated maps.
        for (ServiceModule m : modules) {
            try {
                ensureModulePageExists(service, m, modules);
            } catch (Exception e) {
                log.warn("Module-page create failed for module {} on service {}: {}",
                        m.modulePath(), service.getName(), e.getMessage());
            }
        }
        // Re-read so newly-created refs are visible.
        modules = relationships.findModulesFor(service.getId());
        Map<String, Map<String, String>> moduleRefsBySink = buildModuleRefsBySink(modules);
        for (ServiceModule m : modules) {
            try {
                renderAndUpdateModulePage(service, m, modules, moduleRefsBySink);
            } catch (Exception e) {
                log.warn("Module-page update failed for module {} on service {}: {}",
                        m.modulePath(), service.getName(), e.getMessage());
            }
        }
    }

    private void ensureModulePageExists(Service service, ServiceModule m, List<ServiceModule> allModules) {
        String title = ModulePageRenderer.pageTitle(service, m);
        List<SinkPlan> plans = new ArrayList<>(sinks.size());
        for (WikiSink sink : sinks) {
            String existingRef = moduleRefFor(sink, m);
            if (existingRef != null && !existingRef.isBlank()) continue; // already exists for this sink
            ModulePageContext ctx = new ModulePageContext(
                    service, m, allModules, Map.of(), serviceCrossLinkTargetFor(sink, service));
            String body = LocalMarkdownWikiSink.NAME.equals(sink.name())
                    ? moduleMarkdownRenderer.render(ctx)
                    : modulePageRenderer.render(ctx);
            Consumer<String> persist = persistModuleRefFor(sink, m);
            plans.add(new SinkPlan(sink, null, body, serviceParentRefFor(sink, service), persist));
        }
        if (!plans.isEmpty()) {
            applyUpserts(plans, title);
        }
    }

    private void renderAndUpdateModulePage(Service service, ServiceModule m, List<ServiceModule> allModules,
                                           Map<String, Map<String, String>> moduleRefsBySink) {
        String title = ModulePageRenderer.pageTitle(service, m);
        List<SinkPlan> plans = new ArrayList<>(sinks.size());
        for (WikiSink sink : sinks) {
            String existingRef = moduleRefFor(sink, m);
            if (existingRef == null || existingRef.isBlank()) continue; // create path handled in pass 1
            Map<String, String> moduleRefs = moduleRefsBySink.getOrDefault(sink.name(), Map.of());
            ModulePageContext ctx = new ModulePageContext(
                    service, m, allModules, moduleRefs, serviceCrossLinkTargetFor(sink, service));
            String body = LocalMarkdownWikiSink.NAME.equals(sink.name())
                    ? moduleMarkdownRenderer.render(ctx)
                    : modulePageRenderer.render(ctx);
            Consumer<String> persist = persistModuleRefFor(sink, m);
            plans.add(new SinkPlan(sink, existingRef, body, serviceParentRefFor(sink, service), persist));
        }
        if (!plans.isEmpty()) {
            applyUpserts(plans, title);
        }
    }

    private void syncEndpointPages(Service service) {
        for (ApiSummary api : relationships.findApisFor(service.getId())) {
            try {
                syncOneEndpoint(service, api);
            } catch (Exception e) {
                log.warn("Endpoint-page sync failed for {} {} on service {}: {}",
                        api.method(), api.path(), service.getName(), e.getMessage());
            }
        }
    }

    private void syncOneEndpoint(Service service, ApiSummary api) {
        String title = ApiEndpointPageRenderer.pageTitle(service, api);
        List<SinkPlan> plans = new ArrayList<>(sinks.size());
        for (WikiSink sink : sinks) {
            ApiEndpointPageContext ctx = new ApiEndpointPageContext(
                    service, api, serviceCrossLinkTargetFor(sink, service));
            String body;
            String existingRef;
            Consumer<String> persist;
            switch (routeOf(sink)) {
                case MARKDOWN -> {
                    body = endpointMarkdownRenderer.render(ctx);
                    existingRef = api.localMarkdownPath();
                    persist = freshRef -> {
                        if (!Objects.equals(freshRef, api.localMarkdownPath())) {
                            relationships.setApiLocalMarkdownPath(api.id(), freshRef);
                        }
                    };
                }
                case CONFLUENCE -> {
                    body = endpointRenderer.render(ctx);
                    existingRef = api.confluencePageId();
                    persist = freshRef -> {
                        if (!Objects.equals(freshRef, api.confluencePageId())) {
                            relationships.setApiConfluencePageId(api.id(), freshRef);
                        }
                    };
                }
                default -> {
                    body = endpointRenderer.render(ctx);
                    existingRef = null;
                    persist = NO_OP_PERSIST;
                }
            }
            plans.add(new SinkPlan(sink, existingRef, body, serviceParentRefFor(sink, service), persist));
        }
        applyUpserts(plans, title);
    }

    // ---- Per-sink context construction ------------------------------------

    private ServicePageContext buildServiceContext(WikiSink sink, Service service, List<Service> allServices,
                                                   InventoryPageUrls inventoryUrls) {
        UUID id = service.getId();
        Map<UUID, String> servicePageRefs = buildServicePageRefsFor(sink, allServices);
        List<ApiSummary> apis = relationships.findApisFor(id);
        List<ApiPresentation> apiPresentations = new ArrayList<>(apis.size());
        for (ApiSummary api : apis) {
            List<ApiConsumer> consumers = relationships.findApiConsumersFor(api.id());
            String endpointRef = endpointCrossLinkTargetFor(sink, api);
            apiPresentations.add(new ApiPresentation(api, consumers, endpointRef));
        }
        List<ServiceDependencyEdge> upstream = relationships.findUpstreamDependenciesOf(id);
        List<ServiceDependencyEdge> downstream = relationships.findDownstreamDependenciesOf(id);
        List<DatabaseUsage> dbs = relationships.findDatabasesFor(id);
        List<ExternalDependencyUsage> exts = relationships.findExternalDependenciesFor(id);
        List<ServiceMetadata> metadata = relationships.findServiceMetadataFor(id);
        List<ChangeEntry> changes = relationships.findRecentChangesFor(id, RECENT_CHANGES_LIMIT);

        List<ServiceModule> modules = relationships.findModulesFor(id);
        Map<String, String> modulePageRefs = new HashMap<>();
        for (ServiceModule m : modules) {
            String ref = moduleCrossLinkTargetFor(sink, m);
            if (ref != null && !ref.isBlank()) {
                modulePageRefs.put(m.modulePath(), ref);
            }
        }
        String beansRef = beansCrossLinkTargetFor(sink, service);
        String testsRef = testsCrossLinkTargetFor(sink, service);
        String configurationRef = configurationCrossLinkTargetFor(sink, service);

        return new ServicePageContext(service, apiPresentations, upstream, downstream, dbs, exts, metadata, changes,
                servicePageRefs, inventoryUrls,
                modules, modulePageRefs, beansRef, testsRef, configurationRef);
    }

    private Map<UUID, String> buildServicePageRefsFor(WikiSink sink, List<Service> services) {
        Map<UUID, String> map = new HashMap<>();
        for (Service s : services) {
            String ref = serviceCrossLinkTargetFor(sink, s);
            if (ref != null && !ref.isBlank()) {
                map.put(s.getId(), ref);
            }
        }
        return map;
    }

    private Map<String, Map<String, String>> buildModuleRefsBySink(List<ServiceModule> modules) {
        Map<String, Map<String, String>> bySink = new HashMap<>();
        for (WikiSink sink : sinks) {
            Map<String, String> refs = new HashMap<>();
            for (ServiceModule m : modules) {
                String ref = moduleCrossLinkTargetFor(sink, m);
                if (ref != null && !ref.isBlank()) {
                    refs.put(m.modulePath(), ref);
                }
            }
            bySink.put(sink.name(), refs);
        }
        return bySink;
    }

    /** Cross-link target for a service's L2 page on this sink. */
    private String serviceCrossLinkTargetFor(WikiSink sink, Service s) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return MarkdownPagePathResolver.wikiLinkTargetFor(s.getLocalMarkdownPath());
        }
        return pageUrlForOptional(s.getConfluencePageId());
    }

    private String beansCrossLinkTargetFor(WikiSink sink, Service s) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return MarkdownPagePathResolver.wikiLinkTargetFor(s.getBeansMarkdownPath());
        }
        return pageUrlForOptional(s.getBeansPageId());
    }

    private String testsCrossLinkTargetFor(WikiSink sink, Service s) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return MarkdownPagePathResolver.wikiLinkTargetFor(s.getTestsMarkdownPath());
        }
        return pageUrlForOptional(s.getTestsPageId());
    }

    private String configurationCrossLinkTargetFor(WikiSink sink, Service s) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return MarkdownPagePathResolver.wikiLinkTargetFor(s.getConfigurationMarkdownPath());
        }
        return pageUrlForOptional(s.getConfigurationPageId());
    }

    private String moduleCrossLinkTargetFor(WikiSink sink, ServiceModule m) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return MarkdownPagePathResolver.wikiLinkTargetFor(m.localMarkdownPath());
        }
        return pageUrlForOptional(m.confluencePageId());
    }

    private String endpointCrossLinkTargetFor(WikiSink sink, ApiSummary api) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return MarkdownPagePathResolver.wikiLinkTargetFor(api.localMarkdownPath());
        }
        return pageUrlForOptional(api.confluencePageId());
    }

    /**
     * Existing ref for a child module page on this sink (used to pick
     * create vs. update path). Returns {@code null} for sinks Atlas does
     * not have a dedicated renderer for, so they always go through the
     * create path.
     */
    private String moduleRefFor(WikiSink sink, ServiceModule m) {
        return switch (routeOf(sink)) {
            case MARKDOWN -> m.localMarkdownPath();
            case CONFLUENCE -> m.confluencePageId();
            case OTHER -> null;
        };
    }

    private Consumer<String> persistModuleRefFor(WikiSink sink, ServiceModule m) {
        return switch (routeOf(sink)) {
            case MARKDOWN -> freshRef -> {
                if (!Objects.equals(freshRef, m.localMarkdownPath())) {
                    relationships.setModuleLocalMarkdownPath(m.id(), freshRef);
                }
            };
            case CONFLUENCE -> freshRef -> {
                if (!Objects.equals(freshRef, m.confluencePageId())) {
                    relationships.setModuleConfluencePageId(m.id(), freshRef);
                }
            };
            case OTHER -> NO_OP_PERSIST;
        };
    }

    /** Parent ref for a child page nested under the L2 service page on this sink. */
    private String serviceParentRefFor(WikiSink sink, Service service) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return service.getLocalMarkdownPath();
        }
        return service.getConfluencePageId();
    }

    private String pageUrlForOptional(String pageId) {
        return (pageId == null || pageId.isBlank()) ? null : pageUrlFor(pageId);
    }

    private String pageUrlFor(String pageId) {
        // Confluence Cloud page URL pattern: {baseUrl}/spaces/{KEY}/pages/{ID}
        // baseUrl already includes /wiki (e.g., https://example.atlassian.net/wiki).
        if (pageId == null) return null;
        return baseUrl + "/spaces/" + spaceKey + "/pages/" + pageId;
    }

    // ---- Well-known pages -------------------------------------------------

    /**
     * Resolve the five "well-known" pages that belong in every Atlas
     * vault — landing, data-store inventory, external-dep inventory, About,
     * Architecture Map — looking each up by title on each enabled sink and
     * creating with a placeholder body if missing. Captures per-sink refs
     * threaded into the per-service rendering pass.
     */
    private WellKnownPages ensureWellKnownPages(List<Service> services) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, String> landingBySink = new LinkedHashMap<>();
        Map<String, String> dsInvBySink = new LinkedHashMap<>();
        Map<String, String> edInvBySink = new LinkedHashMap<>();
        Map<String, String> aboutBySink = new LinkedHashMap<>();
        Map<String, String> archMapBySink = new LinkedHashMap<>();

        for (WikiSink sink : sinks) {
            String landingRef = findOrCreateOnSink(sink, landingPageTitle,
                    renderLandingFor(sink, services, Map.of(), now), null);
            landingBySink.put(sink.name(), landingRef);

            dsInvBySink.put(sink.name(), findOrCreateOnSink(sink, DATA_STORE_INVENTORY_TITLE,
                    renderDataStoreInventoryFor(sink, List.of(), Map.of(), now), landingRef));
            edInvBySink.put(sink.name(), findOrCreateOnSink(sink, EXTERNAL_DEP_INVENTORY_TITLE,
                    renderExternalDepInventoryFor(sink, List.of(), Map.of(), now), landingRef));
            aboutBySink.put(sink.name(), findOrCreateOnSink(sink, ABOUT_PAGE_TITLE,
                    renderAboutFor(sink, now), landingRef));
            archMapBySink.put(sink.name(), findOrCreateOnSink(sink, ARCHITECTURE_MAP_TITLE,
                    renderArchitectureMapFor(sink, services, List.of(), now), landingRef));
        }

        Map<String, InventoryPageUrls> inventoryUrlsBySink = new LinkedHashMap<>();
        for (WikiSink sink : sinks) {
            String dsRef = dsInvBySink.get(sink.name());
            String edRef = edInvBySink.get(sink.name());
            inventoryUrlsBySink.put(sink.name(), inventoryUrlsFromRefs(sink, dsRef, edRef));
        }
        return new WellKnownPages(landingBySink, dsInvBySink, edInvBySink, aboutBySink, archMapBySink, inventoryUrlsBySink);
    }

    private InventoryPageUrls inventoryUrlsFromRefs(WikiSink sink, String dsRef, String edRef) {
        if (LocalMarkdownWikiSink.NAME.equals(sink.name())) {
            return new InventoryPageUrls(
                    MarkdownPagePathResolver.wikiLinkTargetFor(dsRef),
                    MarkdownPagePathResolver.wikiLinkTargetFor(edRef));
        }
        return new InventoryPageUrls(pageUrlForOptional(dsRef), pageUrlForOptional(edRef));
    }

    /**
     * After services sync, refresh each well-known page on every sink with
     * current data. Each is best-effort — failures log a warning but don't
     * fail the overall sync.
     */
    private void refreshWellKnownPages(WellKnownPages pages, List<Service> services) {
        OffsetDateTime now = OffsetDateTime.now();
        for (WikiSink sink : sinks) {
            Map<UUID, String> servicePageRefs = buildServicePageRefsFor(sink, services);
            try {
                upsertOnSink(sink,
                        pages.landingRefFor(sink), landingPageTitle,
                        renderLandingFor(sink, services, servicePageRefs, now), null,
                        freshRef -> {
                            if (freshRef != null) pages.landingRefBySink().put(sink.name(), freshRef);
                        });
            } catch (Exception e) {
                log.warn("Landing page refresh failed on sink {}: {}", sink.name(), e.getMessage());
            }
            String landingRef = pages.landingRefFor(sink);
            try {
                upsertOnSink(sink,
                        pages.dsInvRefBySink().get(sink.name()), DATA_STORE_INVENTORY_TITLE,
                        renderDataStoreInventoryFor(sink, relationships.findAllDataStoresWithUsages(),
                                servicePageRefs, now),
                        landingRef,
                        freshRef -> {
                            if (freshRef != null) pages.dsInvRefBySink().put(sink.name(), freshRef);
                        });
            } catch (Exception e) {
                log.warn("Data-store inventory refresh failed on sink {}: {}", sink.name(), e.getMessage());
            }
            try {
                upsertOnSink(sink,
                        pages.edInvRefBySink().get(sink.name()), EXTERNAL_DEP_INVENTORY_TITLE,
                        renderExternalDepInventoryFor(sink, relationships.findAllExternalDependenciesWithUsages(),
                                servicePageRefs, now),
                        landingRef,
                        freshRef -> {
                            if (freshRef != null) pages.edInvRefBySink().put(sink.name(), freshRef);
                        });
            } catch (Exception e) {
                log.warn("External-dependency inventory refresh failed on sink {}: {}", sink.name(), e.getMessage());
            }
            try {
                upsertOnSink(sink,
                        pages.aboutRefBySink().get(sink.name()), ABOUT_PAGE_TITLE,
                        renderAboutFor(sink, now),
                        landingRef,
                        freshRef -> {
                            if (freshRef != null) pages.aboutRefBySink().put(sink.name(), freshRef);
                        });
            } catch (Exception e) {
                log.warn("About page refresh failed on sink {}: {}", sink.name(), e.getMessage());
            }
            try {
                upsertOnSink(sink,
                        pages.archMapRefBySink().get(sink.name()), ARCHITECTURE_MAP_TITLE,
                        renderArchitectureMapFor(sink, services, relationships.findAllServiceDependencies(), now),
                        landingRef,
                        freshRef -> {
                            if (freshRef != null) pages.archMapRefBySink().put(sink.name(), freshRef);
                        });
            } catch (Exception e) {
                log.warn("Architecture map page refresh failed on sink {}: {}", sink.name(), e.getMessage());
            }
        }
    }

    private String renderLandingFor(WikiSink sink, List<Service> services,
                                    Map<UUID, String> servicePageRefs, OffsetDateTime now) {
        return LocalMarkdownWikiSink.NAME.equals(sink.name())
                ? landingMarkdownRenderer.render(services, servicePageRefs, now)
                : landingRenderer.render(services, servicePageRefs, now);
    }

    private String renderDataStoreInventoryFor(WikiSink sink, List<DataStoreInventoryRow> rows,
                                               Map<UUID, String> servicePageRefs, OffsetDateTime now) {
        return LocalMarkdownWikiSink.NAME.equals(sink.name())
                ? dataStoreInventoryMarkdownRenderer.render(rows, servicePageRefs, now)
                : dataStoreInventoryRenderer.render(rows, servicePageRefs, now);
    }

    private String renderExternalDepInventoryFor(WikiSink sink, List<ExternalDependencyInventoryRow> rows,
                                                 Map<UUID, String> servicePageRefs, OffsetDateTime now) {
        return LocalMarkdownWikiSink.NAME.equals(sink.name())
                ? externalDepInventoryMarkdownRenderer.render(rows, servicePageRefs, now)
                : externalDepInventoryRenderer.render(rows, servicePageRefs, now);
    }

    private String renderAboutFor(WikiSink sink, OffsetDateTime now) {
        return LocalMarkdownWikiSink.NAME.equals(sink.name())
                ? aboutMarkdownRenderer.render(now)
                : aboutRenderer.render(now);
    }

    private String renderArchitectureMapFor(WikiSink sink, List<Service> services,
                                            List<ServiceDependencyEdge> edges, OffsetDateTime now) {
        return LocalMarkdownWikiSink.NAME.equals(sink.name())
                ? architectureMapMarkdownRenderer.render(services, edges, now)
                : architectureMapRenderer.render(services, edges, now);
    }

    // ---- Sink fan-out primitives ------------------------------------------

    /**
     * Apply a list of {@link SinkPlan} as upserts. Each plan represents the
     * rendered body + persistence callback for one sink. {@link
     * WikiPageNotFoundException} on the update path triggers a recovery
     * create with the fresh ref persisted via the plan's callback.
     *
     * <p>Failures propagate to the caller — the L2 service-page path lets
     * them bubble to {@code syncAll}'s per-service try/catch (counted as a
     * service failure), while child-page methods ({@link #syncBeansPage},
     * {@link #syncTestsPage}, etc.) wrap the call in their own try/catch
     * to log a warning without failing the whole service.
     */
    private void applyUpserts(List<SinkPlan> plans, String title) {
        for (SinkPlan plan : plans) {
            String fresh = upsertOneOnSink(plan.sink(), plan.existingRef(), title,
                    plan.body(), plan.parentRef());
            plan.persistFreshRef().accept(fresh);
        }
    }

    private String upsertOneOnSink(WikiSink sink, String existingRef, String title,
                                   String body, String parentRef) {
        if (existingRef == null || existingRef.isBlank()) {
            return sink.createPage(title, body, parentRef);
        }
        try {
            sink.updatePage(existingRef, title, body, parentRef);
            return existingRef;
        } catch (WikiPageNotFoundException e) {
            log.info("Wiki page {} for '{}' on sink '{}' no longer exists; creating fresh page.",
                    existingRef, title, sink.name());
            return sink.createPage(title, body, parentRef);
        }
    }

    /** Single-sink upsert that funnels the fresh ref into a callback (used by the well-known refresh path). */
    private void upsertOnSink(WikiSink sink, String existingRef, String title, String body,
                              String parentRef, Consumer<String> persistFreshRef) {
        String fresh = upsertOneOnSink(sink, existingRef, title, body, parentRef);
        persistFreshRef.accept(fresh);
    }

    private String findOrCreateOnSink(WikiSink sink, String title, String body, String parentRef) {
        Optional<String> existing = sink.findPageByTitle(title);
        return existing.orElseGet(() -> {
            log.info("Creating well-known page '{}' on sink '{}'", title, sink.name());
            return sink.createPage(title, body, parentRef);
        });
    }

    private void deleteOnSinkByName(String sinkName, String ref) {
        if (ref == null || ref.isBlank()) return;
        for (WikiSink sink : sinks) {
            if (sinkName.equals(sink.name())) {
                sink.deletePage(ref);
                return;
            }
        }
        log.debug("No sink named '{}' is enabled; skipping delete of '{}'", sinkName, ref);
    }

    // ---- Internal data shapes ---------------------------------------------

    /**
     * Per-page, per-sink rendering+persistence plan. Built once per page
     * × sink at the call site; consumed by {@link #applyUpserts(List, String)}.
     * The {@code persistFreshRef} callback writes the sink's returned ref
     * to the appropriate column ({@code confluence_page_id} for the
     * Confluence sink, {@code local_markdown_path} for the Markdown sink,
     * no-op for sinks Atlas does not have a dedicated renderer for).
     */
    private record SinkPlan(
            WikiSink sink,
            String existingRef,
            String body,
            String parentRef,
            Consumer<String> persistFreshRef) {
    }

    /**
     * Route classification for the per-sink dispatch in the plan builders.
     * {@link #OTHER} sinks (e.g. test doubles) participate in the create /
     * update / delete fan-out — receiving the Confluence-format body — but
     * no DB column is reserved for their refs, so their persistence
     * callback is a no-op and they are never treated as already having an
     * existing ref (so they always go through the create path).
     */
    private enum SinkRoute { CONFLUENCE, MARKDOWN, OTHER }

    private SinkRoute routeOf(WikiSink sink) {
        String name = sink.name();
        if (LocalMarkdownWikiSink.NAME.equals(name)) return SinkRoute.MARKDOWN;
        if (ConfluenceWikiSink.NAME.equals(name)) return SinkRoute.CONFLUENCE;
        return SinkRoute.OTHER;
    }

    private static final Consumer<String> NO_OP_PERSIST = freshRef -> {};

    /**
     * Per-sink refs of the five well-known pages plus the per-sink
     * inventory back-link record threaded into per-service rendering. Each
     * {@code Map<String, String>} is keyed by {@link WikiSink#name()}.
     * Internal to the coordinator.
     */
    private record WellKnownPages(
            Map<String, String> landingRefBySink,
            Map<String, String> dsInvRefBySink,
            Map<String, String> edInvRefBySink,
            Map<String, String> aboutRefBySink,
            Map<String, String> archMapRefBySink,
            Map<String, InventoryPageUrls> inventoryUrlsBySink) {

        String landingRefFor(WikiSink sink) {
            return landingRefBySink.get(sink.name());
        }

        InventoryPageUrls inventoryUrlsFor(WikiSink sink) {
            return inventoryUrlsBySink.getOrDefault(sink.name(), InventoryPageUrls.empty());
        }
    }
}
