-- #15: additive rollout. Widen request_id to the contract's Identifier length and add the decision record table.
-- Rollback: set token-pilot.decision.enabled=false. Do not shrink request_id back (rows longer than 100 characters would fail).

-- The control-plane contract allows requestId up to 128 characters. Restate the full column definition from V7
-- (character set / collation / not null) so MODIFY cannot change them. Both lengths need a 2-byte length prefix.
alter table policy_request_contexts
    modify column request_id varchar(128) character set utf8mb4 collate utf8mb4_bin not null;

-- Body fingerprint of the decision request that first bound this context. A concurrent request with the same requestId
-- but another body (e.g. another model) would otherwise be evaluated against the first request's pricing snapshot
-- before either decision row exists. Null for contexts bound without a decision body.
alter table policy_request_contexts
    add column request_fingerprint char(64) null;

-- One row per decision. The key columns use the same char(36) type as projects and policy_request_contexts
-- (no explicit character set) so the foreign keys below match.
create table decision_records (
    id char(36) primary key,                       -- decisionId is "dec-" + id
    organization_id char(36) not null,
    project_id char(36) not null,
    api_key_id char(36) not null,                  -- audit only; no FK, like policy_request_contexts.actor_id
    policy_request_context_id char(36) not null,   -- binds the policy and pricing snapshots; one per (org, project, env, requestId)
    idempotency_key varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    request_fingerprint char(64) not null,         -- SHA-256 of the canonical request body, without the Idempotency-Key header
    client_mode varchar(10) not null,              -- audit only; the server never changes the client's mode
    outcome varchar(30) not null,
    algorithm_version varchar(30) not null,
    evaluated_at datetime(6) not null,
    evaluation_input_json json not null,           -- reproducible inputs: tokens, per-scope monthly spend, month window
    response_json json not null,                   -- data returned the first time; replayed for the same key and body
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_decision_idempotency (project_id, idempotency_key),
    unique key uk_decision_context (policy_request_context_id),
    key ix_decision_project_created (organization_id, project_id, created_at),
    constraint fk_decision_project foreign key (project_id) references projects(id),
    constraint fk_decision_context foreign key (policy_request_context_id) references policy_request_contexts(id),
    constraint ck_decision_outcome check (outcome in ('ALLOW', 'DENY_POLICY', 'DENY_BUDGET', 'REQUIRE_APPROVAL', 'INDETERMINATE')),
    constraint ck_decision_client_mode check (client_mode in ('OBSERVE', 'ADVISORY', 'ENFORCE')),
    constraint ck_decision_key_length check (char_length(idempotency_key) between 8 and 128)
) engine=InnoDB;
-- Insert/select only. Never update or delete rows.
