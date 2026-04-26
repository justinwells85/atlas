package com.atlas.services;

/**
 * Per ADR-009: Java enum at the application layer paired with the DB CHECK
 * constraint on {@code services.status}. Names lowercase to the DB values
 * ('active','deprecated','in_dev') via {@link ServiceStatusConverter}.
 */
public enum ServiceStatus {
    ACTIVE,
    DEPRECATED,
    IN_DEV
}
