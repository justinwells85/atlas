package com.atlas.services;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {
    boolean existsByName(String name);

    Optional<Service> findByName(String name);

    List<Service> findByNameContainingIgnoreCaseOrderByName(String query, Pageable pageable);

    /**
     * Find soft-deleted services that still have a Confluence page —
     * candidates for the sync agent's cleanup pass. Native query so the
     * entity-level {@code @SQLRestriction} (which hides soft-deleted rows)
     * does not filter them out.
     */
    @Query(value = "SELECT * FROM services " +
            "WHERE deleted_at IS NOT NULL AND confluence_page_id IS NOT NULL",
            nativeQuery = true)
    List<Service> findSoftDeletedWithConfluencePage();

    /**
     * Null out {@code confluence_page_id} on a soft-deleted row after its
     * Confluence page has been deleted. Native UPDATE so the
     * {@code @SQLRestriction} filter on the entity does not block the row
     * from being found.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE services SET confluence_page_id = NULL WHERE id = :id",
            nativeQuery = true)
    void clearConfluencePageId(@Param("id") UUID id);

    /**
     * Look up a service by name *including* soft-deleted rows — bypasses the
     * entity-level {@code @SQLRestriction} filter. Used by intake to detect
     * the "name held by a soft-deleted row" case so it can reactivate the
     * existing UUID instead of failing the UNIQUE constraint at INSERT time.
     */
    @Query(value = "SELECT * FROM services WHERE name = :name", nativeQuery = true)
    Optional<Service> findByNameIncludingDeleted(@Param("name") String name);

    /**
     * Look up a service by ID *including* soft-deleted rows. Used by
     * {@code delete_service} to read back the freshly-stamped {@code deleted_at}
     * for the response shape — the standard {@code findById} would return
     * empty since {@code @SQLRestriction} hides soft-deleted rows.
     */
    @Query(value = "SELECT * FROM services WHERE id = :id", nativeQuery = true)
    Optional<Service> findByIdIncludingDeleted(@Param("id") UUID id);

    /**
     * Reactivate a soft-deleted service: clear {@code deleted_at} so the
     * row is visible again to JPA queries, and clear the Confluence-sync
     * state so the next sync produces a fresh page. Caller is expected to
     * follow up with field updates via the JPA entity (now visible) and
     * {@code save()}.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE services SET deleted_at = NULL, " +
            "confluence_page_id = NULL, last_synced_to_confluence = NULL " +
            "WHERE id = :id",
            nativeQuery = true)
    void reactivate(@Param("id") UUID id);
}
