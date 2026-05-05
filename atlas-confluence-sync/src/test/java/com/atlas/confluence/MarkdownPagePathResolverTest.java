package com.atlas.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownPagePathResolverTest {

    @Test
    void whenLandingTitle_thenReadmeAtVaultRoot() {
        assertThat(MarkdownPagePathResolver.pathFor("Atlas — Service Inventory"))
                .isEqualTo("README.md");
    }

    @Test
    void whenArchitectureMapTitle_thenSlugAtVaultRoot() {
        assertThat(MarkdownPagePathResolver.pathFor("Atlas — Architecture Map"))
                .isEqualTo("architecture-map.md");
    }

    @Test
    void whenInventoryTitles_thenInventoryPaths() {
        assertThat(MarkdownPagePathResolver.pathFor("Inventory: Data Stores"))
                .isEqualTo("inventory-data-stores.md");
        assertThat(MarkdownPagePathResolver.pathFor("Inventory: External Dependencies"))
                .isEqualTo("inventory-external-deps.md");
    }

    @Test
    void whenAboutTitle_thenAboutMd() {
        assertThat(MarkdownPagePathResolver.pathFor("About Atlas"))
                .isEqualTo("about.md");
    }

    @Test
    void whenServiceTitle_thenNestedUnderServicesFolderWithMatchingFilename() {
        assertThat(MarkdownPagePathResolver.pathFor("Service: atlas-intake"))
                .isEqualTo("services/atlas-intake/atlas-intake.md");
    }

    @Test
    void whenServiceNameHasSpacesOrPunct_thenSlugIsUsed() {
        // Real services are kebab-case but the resolver should not crash
        // on whitespace / mixed case from misconfigured intake.
        assertThat(MarkdownPagePathResolver.pathFor("Service: Order Service"))
                .isEqualTo("services/order-service/order-service.md");
        assertThat(MarkdownPagePathResolver.pathFor("Service: Foo!Bar"))
                .isEqualTo("services/foo-bar/foo-bar.md");
    }

    @Test
    void whenBeansTitle_thenBeansMdUnderService() {
        assertThat(MarkdownPagePathResolver.pathFor("atlas-intake — Beans"))
                .isEqualTo("services/atlas-intake/beans.md");
    }

    @Test
    void whenTestsTitle_thenTestsMdUnderService() {
        assertThat(MarkdownPagePathResolver.pathFor("atlas-intake — Tests"))
                .isEqualTo("services/atlas-intake/tests.md");
    }

    @Test
    void whenConfigurationTitle_thenConfigurationMdUnderService() {
        assertThat(MarkdownPagePathResolver.pathFor("atlas-intake — Configuration"))
                .isEqualTo("services/atlas-intake/configuration.md");
    }

    @Test
    void whenModuleTitle_thenModulesPathUnderService() {
        assertThat(MarkdownPagePathResolver.pathFor("billing-service — Module: billing-api"))
                .isEqualTo("services/billing-service/modules/billing-api.md");
    }

    @Test
    void whenRootModuleTitle_thenRootMdUnderModulesFolder() {
        // The Module renderer surfaces the root module as "(root)" in titles;
        // we map that to a literal "root.md" filename so the parens don't
        // leak into the filesystem.
        assertThat(MarkdownPagePathResolver.pathFor("svc — Module: (root)"))
                .isEqualTo("services/svc/modules/root.md");
    }

    @Test
    void whenEndpointTitle_thenEndpointsPathUnderService() {
        assertThat(MarkdownPagePathResolver.pathFor("atlas-intake — POST /api/intake/turn"))
                .isEqualTo("services/atlas-intake/endpoints/post-api-intake-turn.md");
    }

    @Test
    void whenEndpointPathHasCurlyBraces_thenSlugifiedClean() {
        assertThat(MarkdownPagePathResolver.pathFor("svc — GET /users/{id}/posts"))
                .isEqualTo("services/svc/endpoints/get-users-id-posts.md");
    }

    @Test
    void whenUnknownPattern_thenFallbackSlugAtRoot() {
        assertThat(MarkdownPagePathResolver.pathFor("Some Random Title"))
                .isEqualTo("some-random-title.md");
    }

    @Test
    void whenTitleIsNull_thenIllegalArgumentExceptionThrown() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> MarkdownPagePathResolver.pathFor(null));
    }

    // ---- wikiLinkTargetFor (Phase 5.8 M3) ---------------------------------

    @Test
    void whenWikiLinkTargetForServicePageRef_thenCollapsesToServiceSlug() {
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor(
                "services/atlas-intake/atlas-intake.md"))
                .isEqualTo("atlas-intake");
    }

    @Test
    void whenWikiLinkTargetForBeansOrTestsRef_thenFullPathWithoutExtension() {
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor(
                "services/atlas-intake/beans.md"))
                .isEqualTo("services/atlas-intake/beans");
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor(
                "services/atlas-intake/tests.md"))
                .isEqualTo("services/atlas-intake/tests");
    }

    @Test
    void whenWikiLinkTargetForModuleOrEndpointRef_thenFullPathWithoutExtension() {
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor(
                "services/atlas-intake/modules/api.md"))
                .isEqualTo("services/atlas-intake/modules/api");
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor(
                "services/atlas-intake/endpoints/post-api-intake-turn.md"))
                .isEqualTo("services/atlas-intake/endpoints/post-api-intake-turn");
    }

    @Test
    void whenWikiLinkTargetForRootLevelPages_thenStripsExtension() {
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor("README.md"))
                .isEqualTo("README");
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor("inventory-data-stores.md"))
                .isEqualTo("inventory-data-stores");
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor("about.md"))
                .isEqualTo("about");
    }

    @Test
    void whenWikiLinkTargetInputIsNullOrBlank_thenNull() {
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor(null)).isNull();
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor("")).isNull();
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor("   ")).isNull();
    }

    @Test
    void whenWikiLinkTargetWithoutMdSuffix_thenInputUsedAsIs() {
        // Defensive: a caller passing already-stripped form should round-trip cleanly.
        assertThat(MarkdownPagePathResolver.wikiLinkTargetFor("services/atlas-intake/beans"))
                .isEqualTo("services/atlas-intake/beans");
    }
}
