package com.atlas.services;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {
    boolean existsByName(String name);

    List<Service> findByNameContainingIgnoreCaseOrderByName(String query, Pageable pageable);
}
