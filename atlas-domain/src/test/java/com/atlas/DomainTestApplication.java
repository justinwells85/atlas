package com.atlas;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Spring Boot application for atlas-domain. Lets schema and
 * repository tests boot a Spring context (Hibernate, Flyway, DataSource)
 * without requiring a real consumer module on the test classpath.
 */
@SpringBootApplication
public class DomainTestApplication {
}
