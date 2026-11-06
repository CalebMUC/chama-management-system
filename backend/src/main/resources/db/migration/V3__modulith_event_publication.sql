-- ============================================================
-- V3 — Spring Modulith event publication registry (transactional outbox)
--
-- Every event handed to an @ApplicationModuleListener is stored here in
-- the same transaction as the business change, and marked complete once
-- the listener succeeds. Incomplete rows are retried on restart, so a
-- crash between "contribution saved" and "SMS sent" loses nothing.
--
-- Copied verbatim from spring-modulith-events-jdbc 2.1.2
-- (schemas/v2/schema-postgresql.sql). Re-check it when upgrading Modulith.
-- ============================================================

CREATE TABLE IF NOT EXISTS event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
