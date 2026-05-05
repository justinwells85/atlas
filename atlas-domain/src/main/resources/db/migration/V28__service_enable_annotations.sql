-- V28: @Enable-prefixed annotation use-sites (Phase 5.9 M3).
--
-- One row per observation of an @Enable*-named annotation on a class
-- annotated with @Configuration or @SpringBootApplication. Append-only
-- from day one, mirroring V22 / V23 / V26 / V27. Composite identity:
--   (service_id, module_path, enclosing_class, annotation_simple_name)
-- A class can carry multiple @Enable* annotations; each is its own row.
--
-- The filter is lexical, not semantic: any annotation whose simple name
-- starts with "Enable" is captured, even org-internal annotations that
-- aren't true Spring meta-annotations. Atlas does not follow meta-
-- annotations or compute the actual subsystem activation graph; it
-- surfaces what the source declares as a starting point for an ownership
-- reader.
--
-- annotation_fqn is the resolved fully-qualified name (from the source
-- file's import statements). Falls back to annotation_simple_name when
-- the annotation is not imported (e.g. wildcard import, same-package usage).
--
-- javadoc_first_sentence is captured opportunistically when the
-- annotation's source file is reachable via the same-module source-tree
-- walk (M3 limit — cross-module same-repo resolution is deferred to
-- DD-018). NULL when the annotation lives outside the walked module
-- (Spring's built-in @Enable* annotations always fall here).

CREATE TABLE service_enable_annotations (
    id                       UUID PRIMARY KEY,
    service_id               UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    module_path              VARCHAR(191) NOT NULL,
    enclosing_class          VARCHAR(191) NOT NULL,
    annotation_simple_name   VARCHAR(191) NOT NULL,
    annotation_fqn           VARCHAR(255) NOT NULL,
    javadoc_first_sentence   TEXT,
    source                   VARCHAR(16) NOT NULL DEFAULT 'source-tree'
                             CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests',
                                                'source-tree', 'properties-file')),
    presence                 VARCHAR(8) NOT NULL DEFAULT 'present'
                             CHECK (presence IN ('present', 'absent')),
    observed_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_enable_annotations_service ON service_enable_annotations (service_id);
