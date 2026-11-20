-- #16 depends on #15 V8 decision_records. Append-only pilot evidence; no policy activation.
create table shadow_audits (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    kind varchar(20) not null,
    environment_key varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    key_hash varchar(64) not null,
    fingerprint varchar(64) not null,
    revision bigint null,
    actor_id varchar(128) not null,
    created_at datetime(6) not null,
    payload_json json not null,
    unique key uk_shadow_idempotency (organization_id, project_id, kind, key_hash),
    unique key uk_shadow_rollout_revision (organization_id, project_id, kind, environment_key, revision),
    key idx_shadow_project_time (organization_id, project_id, kind, created_at),
    constraint fk_shadow_project foreign key (project_id) references projects(id),
    constraint ck_shadow_kind check (
        (kind = 'RUN' and revision is null)
        or (kind = 'ROLLOUT' and revision is not null and revision > 0)
    )
) engine=InnoDB;

-- Customer-reported execution, separate from the immutable server decision and monetary ledger.
-- A null decision_record_id is allowed only for an unavailable/indeterminate customer observation.
create table decision_execution_events (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    request_id varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    decision_record_id char(36) null,
    key_hash varchar(64) not null,
    fingerprint varchar(64) not null,
    reported_decision varchar(30) not null,
    actual_execution varchar(20) not null,
    customer_override varchar(20) not null,
    occurred_at datetime(6) not null,
    recorded_at datetime(6) not null,
    payload_json json not null,
    unique key uk_execution_idempotency (project_id, key_hash),
    key ix_execution_scope_time (organization_id, project_id, environment, occurred_at),
    key ix_execution_decision (decision_record_id, recorded_at),
    constraint fk_execution_project foreign key (project_id) references projects(id),
    constraint fk_execution_decision foreign key (decision_record_id) references decision_records(id),
    constraint ck_execution_outcome check (reported_decision in
        ('ALLOW','DENY_POLICY','DENY_BUDGET','REQUIRE_APPROVAL','INDETERMINATE','CONTROL_PLANE_UNAVAILABLE')),
    constraint ck_execution_actual check (actual_execution in ('PROCEEDED','BLOCKED','FAILED','UNKNOWN')),
    constraint ck_execution_override check (customer_override in ('NONE','ALLOW','BLOCK')),
    constraint ck_execution_unbound check (decision_record_id is not null
        or reported_decision in ('INDETERMINATE','CONTROL_PLANE_UNAVAILABLE'))
) engine=InnoDB;
-- Restrict runtime writes to INSERT/SELECT. Retain records on application rollback.
