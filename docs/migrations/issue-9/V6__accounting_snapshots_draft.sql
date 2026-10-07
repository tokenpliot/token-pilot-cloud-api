-- REVIEW DRAFT ONLY. Outside classpath:db/migration; never copy into production without rehearsal.
-- Target MySQL >= 8.0.16, InnoDB. V6 is provisional (V5 is #10 ingestion idempotency). Existing V1-V5 remain unchanged.
-- Each DDL commits separately. Run preflight.sql, inspect collation/drift and take a restorable backup first.
-- Expansion only: nullable/default fields permit old binaries. Application writers are a separate rollout.

create table pricing_snapshots (
    id char(36) primary key,
    version varchar(128) not null,
    pricing_plan_id char(36) null,
    provider varchar(128) not null,
    model varchar(128) not null,
    currency char(3) not null,
    rate_unit_tokens bigint not null,
    snapshot_json json not null,
    checksum char(64) not null,
    actor_type varchar(30) not null,
    actor_id varchar(128) not null,
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_price_snapshot_version (provider, model, currency, version),
    constraint ck_price_snapshot_unit check (rate_unit_tokens > 0),
    constraint ck_price_snapshot_currency check (currency = 'USD')
) engine=InnoDB;
-- Snapshot JSON contains copied rates, effective interval, inclusive token semantics and algorithm version.
-- pricing_plan_id is provenance, not a mutable source of historical calculation; snapshots are append-only.

create table policy_snapshots (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) null,
    environment varchar(20) null,
    version varchar(128) not null,
    snapshot_json json not null,
    checksum char(64) not null,
    actor_type varchar(30) not null,
    actor_id varchar(128) not null,
    created_at datetime(6) not null default current_timestamp(6),
    -- Version allocated uniquely per organization, including organization-wide policies.
    unique key uk_policy_snapshot_org_version (organization_id, version),
    constraint ck_policy_snapshot_scope check (environment is null or project_id is not null)
) engine=InnoDB;

alter table pricing_plans
    add column rate_unit_tokens bigint null,
    add column created_by_type varchar(30) not null default 'UNKNOWN',
    add column created_by_id varchar(128) null,
    add constraint ck_pricing_plan_unit check (rate_unit_tokens is null or rate_unit_tokens > 0);
-- Never label legacy rates per-million without validating their source catalog.

alter table monthly_budget_settings
    add column currency char(3) not null default 'USD',
    add column policy_version varchar(128) null,
    add column updated_by_type varchar(30) not null default 'UNKNOWN',
    add column updated_by_id varchar(128) null;
-- NULL-normalized scope uniqueness and tenant FKs come after duplicate/orphan repair and writer rollout.

alter table project_api_keys
    add column scope_version varchar(50) not null default 'legacy-ingestion-v0',
    add column created_by_type varchar(30) not null default 'UNKNOWN',
    add column created_by_id varchar(128) null,
    add column revoked_at datetime(6) null,
    add column revoked_by_type varchar(30) null,
    add column revoked_by_id varchar(128) null;
-- scope_version is an audit label, NOT an authorization grant or a migration to member privileges.

alter table usage_events
    add column schema_version varchar(50) not null default 'legacy-v0',
    add column token_semantics varchar(30) not null default 'LEGACY_UNKNOWN',
    add column accounting_status varchar(30) not null default 'LEGACY_UNVERIFIED',
    add column call_status varchar(30) not null default 'LEGACY_UNKNOWN',
    add column settlement_state varchar(20) not null default 'UNSETTLED',
    add column actor_type varchar(30) not null default 'UNKNOWN',
    add column actor_id varchar(128) null,
    add column policy_snapshot_id char(36) null,
    add column normalized_usage_json json null,
    add column sdk_reported_cost_json json null,
    add column backfill_run_id varchar(128) null,
    add constraint ck_usage_token_semantics check (token_semantics in ('LEGACY_UNKNOWN', 'INCLUSIVE_V1')),
    add constraint ck_usage_accounting_status check (accounting_status in ('LEGACY_UNVERIFIED', 'PENDING', 'VERIFIED', 'QUARANTINED')),
    add constraint ck_usage_call_status check (call_status in ('SUCCEEDED', 'PROVIDER_FAILED', 'USAGE_UNKNOWN', 'BLOCKED_BY_CLIENT', 'LEGACY_UNKNOWN')),
    add constraint ck_usage_settlement_state check (settlement_state in ('SETTLED', 'UNSETTLED')),
    add constraint fk_usage_policy_snapshot foreign key (policy_snapshot_id) references policy_snapshots(id),
    add index idx_usage_accounting_review (accounting_status, created_at, id);
-- normalized_usage_json retains nullable cache/reasoning details; old NOT NULL token columns are projections.
-- A policy_snapshot_id must be verified against the event tenant by the writer before insertion.

create table usage_accounting_values (
    id char(36) primary key,
    usage_event_id char(36) not null,
    value_kind varchar(20) not null,
    revision int not null,
    pricing_status varchar(20) not null,
    amount decimal(18,6) null,
    currency char(3) not null,
    cost_source varchar(30) not null,
    pricing_snapshot_id char(36) null,
    evidence_reference varchar(255) null,
    reason_code varchar(50) not null,
    actor_type varchar(30) not null,
    actor_id varchar(128) not null,
    recorded_at datetime(6) not null default current_timestamp(6),
    unique key uk_accounting_event_kind_revision (usage_event_id, value_kind, revision),
    constraint fk_accounting_event foreign key (usage_event_id) references usage_events(id),
    constraint fk_accounting_price_snapshot foreign key (pricing_snapshot_id) references pricing_snapshots(id),
    constraint ck_accounting_kind check (value_kind in ('ESTIMATED', 'ACTUAL')),
    constraint ck_accounting_revision check (revision > 0),
    constraint ck_accounting_currency check (currency = 'USD'),
    constraint ck_accounting_value check (
        (pricing_status = 'UNPRICED' and amount is null and cost_source = 'NONE' and pricing_snapshot_id is null)
        or (pricing_status = 'PRICED' and amount is not null and amount >= 0 and (
            (cost_source = 'SERVER_CATALOG' and pricing_snapshot_id is not null)
            or (cost_source = 'PROVIDER_BILLING' and value_kind = 'ACTUAL' and evidence_reference is not null)
        ))
    )
) engine=InnoDB;
-- No UPDATE/DELETE in the new ledger writer. Grant INSERT/SELECT only on snapshot/accounting tables.
-- Authorize through usage_events tenant before reading; aggregate only latest ACTUAL revision per event.
