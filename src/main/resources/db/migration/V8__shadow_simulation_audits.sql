-- #16: independent, append-only pilot evidence. No customer policy activation or usage mutation.
create table shadow_audits (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    kind varchar(20) not null,
    environment_key varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    key_hash varchar(64) not null,
    fingerprint varchar(64) not null,
    parent_run_id char(36) null,
    revision bigint null,
    policy_denial boolean not null default false,
    server_unavailable boolean not null default false,
    customer_override boolean not null default false,
    actor_id varchar(128) not null,
    created_at datetime(6) not null,
    payload_json json not null,
    unique key uk_shadow_idempotency (organization_id, project_id, kind, key_hash),
    unique key uk_shadow_rollout_revision (organization_id, project_id, kind, environment_key, revision),
    key idx_shadow_project_time (organization_id, project_id, kind, created_at),
    key idx_shadow_run_events (parent_run_id, created_at),
    constraint fk_shadow_project foreign key (project_id) references projects(id),
    constraint fk_shadow_parent foreign key (parent_run_id) references shadow_audits(id),
    constraint ck_shadow_kind check (
        (kind = 'RUN' and parent_run_id is null and revision is null)
        or (kind = 'EXECUTION' and parent_run_id is not null and revision is null)
        or (kind = 'ROLLOUT' and parent_run_id is null and revision is not null and revision > 0)
    )
) engine=InnoDB;
-- Restrict runtime writes to INSERT/SELECT. Retain records on application rollback.
