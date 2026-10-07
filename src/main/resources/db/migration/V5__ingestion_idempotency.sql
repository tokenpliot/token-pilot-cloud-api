-- Ingestion idempotency (#10). Expansion only: nullable columns keep old writers working.
-- event_id: optional client event id; the idempotency key is (project_id, environment, coalesce(event_id, request_id)).
-- payload_fingerprint: SHA-256 hex of the canonical payload (see PayloadFingerprint). NULL on legacy rows.
alter table usage_events
    add column event_id varchar(100) null,
    add column payload_fingerprint char(64) null,
    add unique key uk_usage_events_project_env_event (project_id, environment, event_id);
