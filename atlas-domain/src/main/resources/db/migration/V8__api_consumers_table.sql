-- V8: Which services call which APIs.
-- Supports the "Consumers of this API" section of the Confluence template.

CREATE TABLE api_consumers (
    id                      UUID PRIMARY KEY,
    api_id                  UUID NOT NULL REFERENCES apis(id) ON DELETE CASCADE,
    consumer_service_id     UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    description             TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT api_consumers_pair_unique UNIQUE (api_id, consumer_service_id)
);

CREATE INDEX idx_api_consumers_api      ON api_consumers (api_id);
CREATE INDEX idx_api_consumers_consumer ON api_consumers (consumer_service_id);
