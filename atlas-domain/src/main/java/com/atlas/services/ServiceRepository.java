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
}
