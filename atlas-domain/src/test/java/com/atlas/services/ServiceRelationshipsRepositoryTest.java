package com.atlas.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ServiceRelationshipsRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @Autowired
    ServiceRepository services;

    @Autowired
    ServiceRelationshipsRepository relationships;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void wipe() {
        // Clean both tables; service_dependencies cascades on services delete
        // but we want a deterministic empty starting state.
        jdbc.update("DELETE FROM service_dependencies");
        jdbc.update("DELETE FROM service_changes");
        jdbc.update("DELETE FROM services");
    }

    @Test
    void whenNoEdgesExist_thenFindAllReturnsEmpty() {
        assertThat(relationships.findAllServiceDependencies()).isEmpty();
    }

    @Test
    void whenEdgesExist_thenFindAllReturnsThemWithBothEndpointNames() {
        Service upstream = save("upstream-svc", "platform");
        Service downstream = save("downstream-svc", "platform");
        relationships.insertServiceDependency(upstream.getId(), downstream.getId(), "calls REST");

        List<ServiceDependencyEdge> edges = relationships.findAllServiceDependencies();

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamServiceName()).isEqualTo("upstream-svc");
        assertThat(edges.get(0).downstreamServiceName()).isEqualTo("downstream-svc");
        assertThat(edges.get(0).description()).isEqualTo("calls REST");
    }

    @Test
    void whenEitherEndpointIsSoftDeleted_thenEdgeIsExcluded() {
        Service a = save("active-a", "team");
        Service b = save("active-b", "team");
        Service c = save("doomed-c", "team");
        relationships.insertServiceDependency(a.getId(), b.getId(), "live edge");
        relationships.insertServiceDependency(a.getId(), c.getId(), "edge to doomed downstream");
        relationships.insertServiceDependency(c.getId(), b.getId(), "edge from doomed upstream");

        // Soft-delete c — both edges touching it should drop out.
        services.delete(c);

        List<ServiceDependencyEdge> edges = relationships.findAllServiceDependencies();

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).upstreamServiceName()).isEqualTo("active-a");
        assertThat(edges.get(0).downstreamServiceName()).isEqualTo("active-b");
    }

    @Test
    void whenMultipleEdges_thenResultIsSortedByUpstreamThenDownstream() {
        Service a = save("aaa-svc", "team");
        Service b = save("bbb-svc", "team");
        Service c = save("ccc-svc", "team");
        // Insert out of order to verify ordering at the SQL layer.
        relationships.insertServiceDependency(c.getId(), b.getId(), null);
        relationships.insertServiceDependency(a.getId(), c.getId(), null);
        relationships.insertServiceDependency(a.getId(), b.getId(), null);

        List<ServiceDependencyEdge> edges = relationships.findAllServiceDependencies();

        assertThat(edges).extracting(ServiceDependencyEdge::upstreamServiceName,
                        ServiceDependencyEdge::downstreamServiceName)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("aaa-svc", "bbb-svc"),
                        org.assertj.core.api.Assertions.tuple("aaa-svc", "ccc-svc"),
                        org.assertj.core.api.Assertions.tuple("ccc-svc", "bbb-svc"));
    }

    // ---- M4.5: external-deps live-view + stale-intake-apis -----------------

    @Test
    void findExternalDependenciesFor_excludesTombstonedObservationsAndExposesSource() {
        Service svc = save("composed-svc", "team");
        UUID intakeDepId = relationships.insertExternalDependency("Stripe", "https://stripe.com");
        relationships.insertServiceExternalDepObservation(
                svc.getId(), intakeDepId, "Payment processor", "intake");

        UUID pomDepId = relationships.insertExternalDependency("com.fasterxml.jackson.core:jackson-databind", null);
        relationships.insertServiceExternalDepObservation(
                svc.getId(), pomDepId, null, "pom-xml");

        // Tombstoned pom-source observation should not appear.
        UUID disappearedDepId = relationships.insertExternalDependency("org.gone:gone-artifact", null);
        relationships.insertServiceExternalDepObservation(
                svc.getId(), disappearedDepId, null, "pom-xml");
        relationships.writeServiceExternalDepTombstone(svc.getId(), disappearedDepId, "pom-xml");

        List<ExternalDependencyUsage> deps = relationships.findExternalDependenciesFor(svc.getId());

        assertThat(deps).extracting(ExternalDependencyUsage::name, ExternalDependencyUsage::source)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("Stripe", "intake"),
                        org.assertj.core.api.Assertions.tuple("com.fasterxml.jackson.core:jackson-databind", "pom-xml"));
        assertThat(deps).noneMatch(d -> d.name().equals("org.gone:gone-artifact"));
    }

    @Test
    void findExternalDependenciesFor_returnsLatestObservationPerSourceAfterMultipleRefreshes() {
        Service svc = save("repeated-svc", "team");
        UUID depId = relationships.insertExternalDependency("com.foo:bar", null);
        relationships.insertServiceExternalDepObservation(svc.getId(), depId, null, "pom-xml");
        relationships.insertServiceExternalDepObservation(svc.getId(), depId, null, "pom-xml");
        relationships.insertServiceExternalDepObservation(svc.getId(), depId, null, "pom-xml");

        List<ExternalDependencyUsage> deps = relationships.findExternalDependenciesFor(svc.getId());

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).source()).isEqualTo("pom-xml");
    }

    @Test
    void findStaleIntakeApis_returnsIntakeApisWithNoOpenapiCounterpart() {
        Service svc = save("stale-svc", "team");
        // Intake declared two endpoints. Code shipped /v1/orders openapi-source
        // but renamed /v1/legacy → /v1/legacy-renamed (so /v1/legacy is stale).
        relationships.insertApi(svc.getId(), "/v1/orders", "GET", null, "Order list", "intake");
        relationships.insertApi(svc.getId(), "/v1/legacy", "GET", null, "Legacy endpoint", "intake");
        relationships.insertApi(svc.getId(), "/v1/orders", "GET", null, "Orders (from spec)", "openapi");
        relationships.insertApi(svc.getId(), "/v1/legacy-renamed", "GET", null, "Renamed endpoint", "openapi");

        List<ApiSummary> stale = relationships.findStaleIntakeApis(svc.getId());

        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).method()).isEqualTo("GET");
        assertThat(stale.get(0).path()).isEqualTo("/v1/legacy");
        assertThat(stale.get(0).source()).isEqualTo("intake");
    }

    @Test
    void findStaleIntakeApis_emptyWhenNoIntakeApisExist() {
        Service svc = save("openapi-only", "team");
        relationships.insertApi(svc.getId(), "/v1/health", "GET", null, "ping", "openapi");

        assertThat(relationships.findStaleIntakeApis(svc.getId())).isEmpty();
    }

    @Test
    void findStaleIntakeApis_excludesAlreadyTombstonedIntakeRows() {
        Service svc = save("already-cleaned", "team");
        relationships.insertApi(svc.getId(), "/v1/old", "GET", null, "removed", "intake");
        relationships.writeApiTombstone(svc.getId(), "GET", "/v1/old", "intake", null);

        assertThat(relationships.findStaleIntakeApis(svc.getId())).isEmpty();
    }

    @Test
    void findStaleIntakeApis_doesNotIncludeIntakeRowsWithMatchingOpenapiObservation() {
        Service svc = save("aligned-svc", "team");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null, "Create order", "intake");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null, "Create order (spec)", "openapi");

        assertThat(relationships.findStaleIntakeApis(svc.getId())).isEmpty();
    }

    @Test
    void whenApiIsInsertedWithSnapshot_thenLiveViewReturnsIt() {
        Service svc = save("snap-svc", "team");
        String snapshot = "{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\"}}}}}";

        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null,
                "Create order", "openapi", "present", null, snapshot);

        List<ApiSummary> rows = relationships.findApisFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).openapiSnapshot()).isEqualTo(snapshot);
    }

    @Test
    void whenLatestObservationCarriesSnapshot_thenLiveViewSurfacesIt_andEarlierIsHidden() {
        Service svc = save("snap-evolve", "team");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null,
                "Create order", "openapi", "present", null, "{\"v\":1}");
        relationships.insertApi(svc.getId(), "/v1/orders", "POST", null,
                "Create order", "openapi", "present", null, "{\"v\":2}");

        List<ApiSummary> rows = relationships.findApisFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).openapiSnapshot()).isEqualTo("{\"v\":2}");
    }

    // ---- service_modules (Phase 5.6 M2) -----------------------------------

    @Test
    void whenModuleIsInserted_thenLiveViewReturnsItWithFullCoords() {
        Service svc = save("module-svc", "team");

        relationships.insertModule(svc.getId(),
                "billing-api", "",
                "com.example", "billing-api", "1.0.0", "jar",
                "21", "Spring Boot", "4.0.6",
                "[\"org.springframework.boot:spring-boot-starter-web\"]",
                "pom-xml");

        List<ServiceModule> rows = relationships.findModulesFor(svc.getId());
        assertThat(rows).hasSize(1);
        ServiceModule m = rows.get(0);
        assertThat(m.modulePath()).isEqualTo("billing-api");
        assertThat(m.parentPath()).isEqualTo("");
        assertThat(m.groupId()).isEqualTo("com.example");
        assertThat(m.artifactId()).isEqualTo("billing-api");
        assertThat(m.version()).isEqualTo("1.0.0");
        assertThat(m.packaging()).isEqualTo("jar");
        assertThat(m.languageVersion()).isEqualTo("21");
        assertThat(m.framework()).isEqualTo("Spring Boot");
        assertThat(m.frameworkVersion()).isEqualTo("4.0.6");
        assertThat(m.declaredDeps()).contains("spring-boot-starter-web");
        assertThat(m.source()).isEqualTo("pom-xml");
    }

    @Test
    void whenModuleHasMultipleObservations_thenLiveViewReturnsLatest() {
        Service svc = save("module-evolve", "team");

        relationships.insertModule(svc.getId(),
                "api", "",
                "com.example", "api", "1.0.0", "jar",
                "21", "Spring Boot", "4.0.6", "[]",
                "pom-xml");
        relationships.insertModule(svc.getId(),
                "api", "",
                "com.example", "api", "2.0.0", "jar",
                "21", "Spring Boot", "4.0.6", "[]",
                "pom-xml");

        List<ServiceModule> rows = relationships.findModulesFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).version()).isEqualTo("2.0.0");
    }

    @Test
    void whenModuleIsTombstoned_thenLiveViewExcludesIt_butStaleQuerySurfacesIt() {
        Service svc = save("module-tombstone", "team");

        UUID firstObs = relationships.insertModule(svc.getId(),
                "api", "",
                "com.example", "api", "1.0.0", "jar",
                "21", null, null, "[]", "pom-xml");
        relationships.setModuleConfluencePageId(firstObs, "PAGE_API_1");
        relationships.writeModuleTombstone(svc.getId(), "api", "pom-xml", "PAGE_API_1");

        // Live view: no longer present.
        assertThat(relationships.findModulesFor(svc.getId())).isEmpty();
        // Stale-pages query: surfaces the tombstone with its carried-forward page id.
        List<SoftDeletedModulePage> stale = relationships.findStaleModulePages();
        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).modulePath()).isEqualTo("api");
        assertThat(stale.get(0).confluencePageId()).isEqualTo("PAGE_API_1");
        assertThat(stale.get(0).serviceName()).isEqualTo("module-tombstone");
    }

    // ---- service_beans (Phase 5.6 M3) -----------------------------------

    @Test
    void whenBeanIsInserted_thenLiveViewReturnsItWithStereotypeAndMethods() {
        Service svc = save("bean-svc", "team");

        relationships.insertBean(svc.getId(), "", "com.example.web", "OrderController",
                "RestController", "Handles orders.",
                "[{\"name\":\"create\",\"signature\":\"Order create()\",\"javadocSummary\":\"Create.\"}]");

        java.util.List<ServiceBean> rows = relationships.findBeansFor(svc.getId());
        assertThat(rows).hasSize(1);
        ServiceBean b = rows.get(0);
        assertThat(b.packageName()).isEqualTo("com.example.web");
        assertThat(b.className()).isEqualTo("OrderController");
        assertThat(b.stereotype()).isEqualTo("RestController");
        assertThat(b.classJavadocSummary()).isEqualTo("Handles orders.");
        assertThat(b.publicMethods()).contains("Order create()");
        assertThat(b.source()).isEqualTo("source-tree");
    }

    @Test
    void whenBeanHasMultipleObservations_thenLiveViewReturnsLatest() {
        Service svc = save("bean-evolve", "team");

        relationships.insertBean(svc.getId(), "", "com.example", "OrderService",
                "Service", "v1 description", "[]");
        relationships.insertBean(svc.getId(), "", "com.example", "OrderService",
                "Service", "v2 description", "[]");

        java.util.List<ServiceBean> rows = relationships.findBeansFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).classJavadocSummary()).isEqualTo("v2 description");
    }

    @Test
    void whenBeanIsTombstoned_thenLiveViewExcludesIt() {
        Service svc = save("bean-tombstone", "team");

        relationships.insertBean(svc.getId(), "", "com.example", "OldService",
                "Service", null, "[]");
        relationships.writeBeanTombstone(svc.getId(), "", "com.example", "OldService", "source-tree");

        assertThat(relationships.findBeansFor(svc.getId())).isEmpty();
    }

    @Test
    void whenServiceBeansPageIdIsSet_thenServiceEntityCarriesIt() {
        Service svc = save("bean-page", "team");

        relationships.setServiceBeansPageId(svc.getId(), "BEANS_PAGE");

        Service reloaded = services.findById(svc.getId()).orElseThrow();
        assertThat(reloaded.getBeansPageId()).isEqualTo("BEANS_PAGE");
    }

    @Test
    void whenModuleConfluencePageIdIsSet_thenItIsReturnedAndCanBeCleared() {
        Service svc = save("module-page-id", "team");
        UUID obs = relationships.insertModule(svc.getId(),
                "", null,
                "com.example", "root", "1.0.0", "pom",
                null, null, null, "[]", "pom-xml");

        relationships.setModuleConfluencePageId(obs, "PAGE_ROOT");

        ServiceModule live = relationships.findModulesFor(svc.getId()).get(0);
        assertThat(live.confluencePageId()).isEqualTo("PAGE_ROOT");

        relationships.clearModuleConfluencePageId(obs);
        ServiceModule afterClear = relationships.findModulesFor(svc.getId()).get(0);
        assertThat(afterClear.confluencePageId()).isNull();
    }

    // ---- service_value_injections (Phase 5.9 M2) -------------------------

    @Test
    void whenValueInjectionIsInserted_thenLiveViewReturnsItWithKeyPathAndDefault() {
        Service svc = save("value-svc", "team");

        relationships.insertValueInjection(svc.getId(), "",
                "com.example.MyConfig", "apiKey", "field",
                "${atlas.api.key:fallback}", "atlas.api.key", "fallback");

        java.util.List<ServiceValueInjection> rows = relationships.findValueInjectionsFor(svc.getId());
        assertThat(rows).hasSize(1);
        ServiceValueInjection v = rows.get(0);
        assertThat(v.enclosingClass()).isEqualTo("com.example.MyConfig");
        assertThat(v.memberName()).isEqualTo("apiKey");
        assertThat(v.memberKind()).isEqualTo("field");
        assertThat(v.rawSpel()).isEqualTo("${atlas.api.key:fallback}");
        assertThat(v.keyPath()).isEqualTo("atlas.api.key");
        assertThat(v.defaultValue()).isEqualTo("fallback");
        assertThat(v.source()).isEqualTo("source-tree");
    }

    @Test
    void whenValueInjectionHasMultipleObservations_thenLiveViewReturnsLatest() {
        Service svc = save("value-evolve", "team");

        relationships.insertValueInjection(svc.getId(), "",
                "com.example.C", "url", "field", "${atlas.url}", "atlas.url", null);
        relationships.insertValueInjection(svc.getId(), "",
                "com.example.C", "url", "field", "${atlas.url:http://default}",
                "atlas.url", "http://default");

        java.util.List<ServiceValueInjection> rows = relationships.findValueInjectionsFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).defaultValue()).isEqualTo("http://default");
    }

    @Test
    void whenValueInjectionIsTombstoned_thenLiveViewExcludesIt() {
        Service svc = save("value-tombstone", "team");

        relationships.insertValueInjection(svc.getId(), "",
                "com.example.Old", "field", "field", "${k}", "k", null);
        relationships.writeValueInjectionTombstone(svc.getId(), "",
                "com.example.Old", "field", "field", "source-tree");

        assertThat(relationships.findValueInjectionsFor(svc.getId())).isEmpty();
    }

    @Test
    void whenSameClassHasFieldAndConstructorParamWithSameKey_thenBothAreLiveObservations() {
        // member_kind disambiguates: one class can have both an @Value field
        // and an @Value constructor parameter that happen to share a name.
        Service svc = save("value-multi-kind", "team");

        relationships.insertValueInjection(svc.getId(), "",
                "com.example.C", "url", "field", "${atlas.url}", "atlas.url", null);
        relationships.insertValueInjection(svc.getId(), "",
                "com.example.C", "url", "constructor-parameter",
                "${atlas.url}", "atlas.url", null);

        java.util.List<ServiceValueInjection> rows = relationships.findValueInjectionsFor(svc.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ServiceValueInjection::memberKind)
                .containsExactlyInAnyOrder("field", "constructor-parameter");
    }

    // ---- service_configuration_properties_types (Phase 5.9 M2) -----------

    @Test
    void whenConfigurationPropertiesTypeIsInserted_thenLiveViewReturnsItWithPrefixAndComponents() {
        Service svc = save("cfg-type-svc", "team");

        relationships.insertConfigurationPropertiesType(svc.getId(), "",
                "com.example.AtlasProperties", "atlas", "record",
                "[{\"name\":\"apiKey\",\"declaredType\":\"String\"}," +
                        "{\"name\":\"port\",\"declaredType\":\"int\"}]");

        java.util.List<ServiceConfigurationPropertiesType> rows =
                relationships.findConfigurationPropertiesTypesFor(svc.getId());
        assertThat(rows).hasSize(1);
        ServiceConfigurationPropertiesType t = rows.get(0);
        assertThat(t.enclosingClass()).isEqualTo("com.example.AtlasProperties");
        assertThat(t.prefix()).isEqualTo("atlas");
        assertThat(t.typeKind()).isEqualTo("record");
        assertThat(t.components()).contains("\"apiKey\"").contains("\"port\"");
        assertThat(t.source()).isEqualTo("source-tree");
    }

    @Test
    void whenConfigurationPropertiesTypeHasMultipleObservations_thenLiveViewReturnsLatest() {
        Service svc = save("cfg-type-evolve", "team");

        relationships.insertConfigurationPropertiesType(svc.getId(), "",
                "com.example.P", "old.prefix", "class", "[]");
        relationships.insertConfigurationPropertiesType(svc.getId(), "",
                "com.example.P", "new.prefix", "class", "[]");

        java.util.List<ServiceConfigurationPropertiesType> rows =
                relationships.findConfigurationPropertiesTypesFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).prefix()).isEqualTo("new.prefix");
    }

    @Test
    void whenConfigurationPropertiesTypeIsTombstoned_thenLiveViewExcludesIt() {
        Service svc = save("cfg-type-tombstone", "team");

        relationships.insertConfigurationPropertiesType(svc.getId(), "",
                "com.example.Old", "old", "record", "[]");
        relationships.writeConfigurationPropertiesTypeTombstone(svc.getId(), "",
                "com.example.Old", "source-tree");

        assertThat(relationships.findConfigurationPropertiesTypesFor(svc.getId())).isEmpty();
    }

    @Test
    void whenConfigurationPropertiesPrefixIsEmpty_thenItIsStoredAndReturnedAsEmptyString() {
        // @ConfigurationProperties with no prefix (annotation accepts none).
        Service svc = save("cfg-type-no-prefix", "team");

        relationships.insertConfigurationPropertiesType(svc.getId(), "",
                "com.example.Top", "", "class", "[]");

        java.util.List<ServiceConfigurationPropertiesType> rows =
                relationships.findConfigurationPropertiesTypesFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).prefix()).isEmpty();
    }

    // ---- service_enable_annotations (Phase 5.9 M3) -----------------------

    @Test
    void whenEnableAnnotationIsInserted_thenLiveViewReturnsItWithSimpleNameFqnAndJavadoc() {
        Service svc = save("enable-svc", "team");

        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.MyApp", "EnableScheduling",
                "org.springframework.scheduling.annotation.EnableScheduling",
                "Enables Spring's scheduled task execution capability.");

        java.util.List<ServiceEnableAnnotation> rows = relationships.findEnableAnnotationsFor(svc.getId());
        assertThat(rows).hasSize(1);
        ServiceEnableAnnotation a = rows.get(0);
        assertThat(a.enclosingClass()).isEqualTo("com.example.MyApp");
        assertThat(a.annotationSimpleName()).isEqualTo("EnableScheduling");
        assertThat(a.annotationFqn()).isEqualTo("org.springframework.scheduling.annotation.EnableScheduling");
        assertThat(a.javadocFirstSentence()).isEqualTo("Enables Spring's scheduled task execution capability.");
        assertThat(a.source()).isEqualTo("source-tree");
    }

    @Test
    void whenEnableAnnotationHasMultipleObservations_thenLiveViewReturnsLatest() {
        Service svc = save("enable-evolve", "team");

        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableX", "old.fqn.EnableX", null);
        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableX", "new.fqn.EnableX", "Activates X.");

        java.util.List<ServiceEnableAnnotation> rows = relationships.findEnableAnnotationsFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).annotationFqn()).isEqualTo("new.fqn.EnableX");
        assertThat(rows.get(0).javadocFirstSentence()).isEqualTo("Activates X.");
    }

    @Test
    void whenEnableAnnotationIsTombstoned_thenLiveViewExcludesIt() {
        Service svc = save("enable-tombstone", "team");

        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableOld", "old.EnableOld", null);
        relationships.writeEnableAnnotationTombstone(svc.getId(), "",
                "com.example.App", "EnableOld", "source-tree");

        assertThat(relationships.findEnableAnnotationsFor(svc.getId())).isEmpty();
    }

    @Test
    void whenSameClassHasMultipleEnableAnnotations_thenAllAreLiveObservations() {
        // A single @Configuration class typically activates several subsystems
        // via stacked @Enable* annotations.
        Service svc = save("enable-multi", "team");

        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableScheduling", "org.springframework.s.EnableScheduling", null);
        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableJpaRepositories", "org.springframework.d.j.EnableJpaRepositories", null);
        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableAsync", "org.springframework.s.a.EnableAsync", null);

        java.util.List<ServiceEnableAnnotation> rows = relationships.findEnableAnnotationsFor(svc.getId());
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ServiceEnableAnnotation::annotationSimpleName)
                .containsExactlyInAnyOrder("EnableScheduling", "EnableJpaRepositories", "EnableAsync");
    }

    @Test
    void whenEnableAnnotationJavadocIsNull_thenItIsStoredAndReturnedAsNull() {
        // Spring's built-in @Enable* annotations live in the Spring JAR and
        // are not reachable via same-module javadoc resolution; their rows
        // carry javadoc_first_sentence = NULL.
        Service svc = save("enable-no-javadoc", "team");

        relationships.insertEnableAnnotation(svc.getId(), "",
                "com.example.App", "EnableScheduling",
                "org.springframework.scheduling.annotation.EnableScheduling", null);

        java.util.List<ServiceEnableAnnotation> rows = relationships.findEnableAnnotationsFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).javadocFirstSentence()).isNull();
    }

    // ---- service_config_properties (Phase 5.9 M1) ------------------------

    @Test
    void whenConfigPropertyIsInserted_thenLiveViewReturnsItWithKeyValueProfileAndSource() {
        Service svc = save("config-svc", "team");

        relationships.insertConfigProperty(svc.getId(),
                "spring.datasource.url",
                "jdbc:postgresql://localhost/atlas",
                "application.properties",
                "default");

        java.util.List<ServiceConfigProperty> rows = relationships.findConfigPropertiesFor(svc.getId());
        assertThat(rows).hasSize(1);
        ServiceConfigProperty p = rows.get(0);
        assertThat(p.keyPath()).isEqualTo("spring.datasource.url");
        assertThat(p.value()).isEqualTo("jdbc:postgresql://localhost/atlas");
        assertThat(p.sourceFile()).isEqualTo("application.properties");
        assertThat(p.profile()).isEqualTo("default");
        assertThat(p.source()).isEqualTo("properties-file");
    }

    @Test
    void whenConfigPropertyHasMultipleObservations_thenLiveViewReturnsLatest() {
        Service svc = save("config-evolve", "team");

        relationships.insertConfigProperty(svc.getId(),
                "atlas.feature.flag", "false", "application.properties", "default");
        relationships.insertConfigProperty(svc.getId(),
                "atlas.feature.flag", "true", "application.properties", "default");

        java.util.List<ServiceConfigProperty> rows = relationships.findConfigPropertiesFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value()).isEqualTo("true");
    }

    @Test
    void whenConfigPropertyIsTombstoned_thenLiveViewExcludesIt() {
        Service svc = save("config-tombstone", "team");

        relationships.insertConfigProperty(svc.getId(),
                "atlas.removed.key", "v", "application.properties", "default");
        relationships.writeConfigPropertyTombstone(svc.getId(),
                "atlas.removed.key", "application.properties", "default", "properties-file");

        assertThat(relationships.findConfigPropertiesFor(svc.getId())).isEmpty();
    }

    @Test
    void whenSameKeyExistsAcrossProfiles_thenLiveViewReturnsBothObservations() {
        Service svc = save("config-profiles", "team");

        relationships.insertConfigProperty(svc.getId(),
                "atlas.api.url", "http://default", "application.properties", "default");
        relationships.insertConfigProperty(svc.getId(),
                "atlas.api.url", "http://prod", "application-prod.properties", "prod");

        java.util.List<ServiceConfigProperty> rows = relationships.findConfigPropertiesFor(svc.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ServiceConfigProperty::profile)
                .containsExactlyInAnyOrder("default", "prod");
    }

    @Test
    void whenConfigPropertyValueIsEmpty_thenItIsStoredAndReturnedAsEmptyString() {
        Service svc = save("config-empty", "team");

        relationships.insertConfigProperty(svc.getId(),
                "atlas.empty.key", "", "application.properties", "default");

        java.util.List<ServiceConfigProperty> rows = relationships.findConfigPropertiesFor(svc.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value()).isEmpty();
    }

    private Service save(String name, String ownerTeam) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(ownerTeam);
        s.setStatus(ServiceStatus.ACTIVE);
        return services.saveAndFlush(s);
    }
}
