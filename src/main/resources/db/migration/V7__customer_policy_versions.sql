-- #14: additive rollout; no implicit activation/backfill of legacy organization-wide budgets.
create table customer_policies (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment_key varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    current_version bigint not null,
    unique key uk_customer_policy_scope (organization_id, project_id, environment_key),
    constraint fk_customer_policy_project foreign key (project_id) references projects(id),
    constraint ck_customer_policy_version check (current_version > 0)
) engine=InnoDB;

-- Nullable additions preserve V6 snapshots and existing usage_events references.
alter table policy_snapshots
    add column policy_id char(36) null,
    add column revision bigint null,
    add column change_type varchar(30) null,
    add column reason varchar(500) null,
    add column restored_from_version bigint null,
    add constraint fk_policy_snapshot_policy foreign key (policy_id) references customer_policies(id),
    add unique key uk_policy_snapshot_revision (policy_id, revision),
    add constraint ck_policy_snapshot_revision check (
        (policy_id is null and revision is null) or
        (policy_id is not null and project_id is not null and revision is not null and revision > 0
         and change_type is not null and change_type in ('CREATE', 'REPLACE', 'IMPORT_LEGACY', 'ROLLBACK')
         and reason is not null and char_length(trim(reason)) > 0)
    ),
    add constraint ck_policy_snapshot_restore check (
        (change_type = 'ROLLBACK' and restored_from_version is not null and restored_from_version > 0 and restored_from_version < revision)
        or (change_type <> 'ROLLBACK' and restored_from_version is null)
        or (change_type is null and restored_from_version is null)
    );

create table policy_request_contexts (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) character set utf8mb4 collate utf8mb4_bin not null,
    request_id varchar(100) character set utf8mb4 collate utf8mb4_bin not null,
    project_policy_snapshot_id char(36) null,
    environment_policy_snapshot_id char(36) null,
    pricing_snapshot_id char(36) null,
    actor_type varchar(30) not null,
    actor_id varchar(128) not null,
    created_at datetime(6) not null,
    unique key uk_policy_request_scope (organization_id, project_id, environment, request_id),
    constraint fk_policy_context_project foreign key (project_id) references projects(id),
    constraint fk_policy_context_project_snapshot foreign key (project_policy_snapshot_id) references policy_snapshots(id),
    constraint fk_policy_context_environment_snapshot foreign key (environment_policy_snapshot_id) references policy_snapshots(id),
    constraint fk_policy_context_price_snapshot foreign key (pricing_snapshot_id) references pricing_snapshots(id)
) engine=InnoDB;
-- Snapshot/context rows are insert/select only. Never delete old revisions on rollback.
-- Memberships already exist in V1. Provision verified memberships explicitly; do not grant every user admin.
