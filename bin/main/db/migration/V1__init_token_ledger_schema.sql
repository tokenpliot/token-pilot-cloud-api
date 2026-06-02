create table organizations (
    id char(36) primary key,
    name varchar(100) not null,
    plan varchar(30) not null,
    default_currency varchar(10) not null default 'USD',
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6)
) engine=InnoDB;

create table organization_members (
    id char(36) primary key,
    organization_id char(36) not null,
    user_id char(36) not null,
    role varchar(30) not null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    unique key uk_organization_members_org_user (organization_id, user_id)
) engine=InnoDB;

create table projects (
    id char(36) primary key,
    organization_id char(36) not null,
    project_key varchar(50) not null,
    name varchar(100) not null,
    status varchar(20) not null,
    default_model varchar(100) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    unique key uk_projects_org_project_key (organization_id, project_key)
) engine=InnoDB;

create table project_environments (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) not null,
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_project_environments_project_env (project_id, environment)
) engine=InnoDB;

create table project_api_keys (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) null,
    name varchar(100) not null,
    key_prefix varchar(30) not null,
    key_hash varchar(255) not null,
    status varchar(20) not null,
    last_used_at datetime(6) null,
    expires_at datetime(6) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6)
) engine=InnoDB;

create table pricing_catalogs (
    id char(36) primary key,
    catalog_key varchar(50) not null,
    version varchar(50) not null,
    format varchar(20) not null default 'yaml',
    checksum varchar(128) not null,
    is_active tinyint(1) not null default 0,
    generated_yaml longtext not null,
    published_at datetime(6) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    unique key uk_pricing_catalogs_catalog_version (catalog_key, version)
) engine=InnoDB;

create table pricing_plans (
    id char(36) primary key,
    catalog_id char(36) null,
    provider varchar(50) not null,
    model varchar(100) not null,
    currency varchar(10) not null,
    prompt_rate decimal(18,6) not null,
    completion_rate decimal(18,6) not null,
    reasoning_rate decimal(18,6) null,
    cached_prompt_rate decimal(18,6) null,
    version varchar(50) not null,
    effective_from datetime(6) not null,
    effective_to datetime(6) null,
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_pricing_plans_provider_model_version (provider, model, version)
) engine=InnoDB;

create table usage_events (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    api_key_id char(36) null,
    environment varchar(20) not null,
    request_id varchar(100) null,
    provider varchar(50) not null,
    model varchar(100) not null,
    prompt_tokens bigint not null default 0,
    completion_tokens bigint not null default 0,
    reasoning_tokens bigint not null default 0,
    cached_prompt_tokens bigint not null default 0,
    total_tokens bigint not null default 0,
    prompt_cost_usd decimal(18,6) not null default 0,
    completion_cost_usd decimal(18,6) not null default 0,
    reasoning_cost_usd decimal(18,6) not null default 0,
    cached_prompt_cost_usd decimal(18,6) not null default 0,
    total_cost_usd decimal(18,6) not null default 0,
    pricing_plan_id char(36) null,
    pricing_version varchar(50) not null,
    source_type varchar(30) not null default 'sdk',
    metadata_json json null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null default current_timestamp(6),
    unique key uk_usage_events_project_env_request (project_id, environment, request_id)
) engine=InnoDB;

create table daily_usage_aggregates (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) not null,
    environment varchar(20) not null,
    usage_date date not null,
    provider varchar(50) null,
    model varchar(100) null,
    total_cost_usd decimal(18,6) not null default 0,
    total_tokens bigint not null default 0,
    prompt_cost_usd decimal(18,6) not null default 0,
    completion_cost_usd decimal(18,6) not null default 0,
    reasoning_cost_usd decimal(18,6) not null default 0,
    cached_prompt_cost_usd decimal(18,6) not null default 0,
    prompt_tokens bigint not null default 0,
    completion_tokens bigint not null default 0,
    reasoning_tokens bigint not null default 0,
    cached_prompt_tokens bigint not null default 0,
    top_model varchar(100) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    unique key uk_daily_usage_aggregates_scope (project_id, environment, usage_date, provider, model)
) engine=InnoDB;

create table monthly_budget_settings (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) null,
    environment varchar(20) null,
    limit_usd decimal(18,6) not null,
    threshold_50_enabled tinyint(1) not null default 1,
    threshold_80_enabled tinyint(1) not null default 1,
    threshold_100_enabled tinyint(1) not null default 1,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6)
) engine=InnoDB;

create table monthly_budget_snapshots (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) null,
    environment varchar(20) null,
    budget_month char(7) not null,
    limit_usd decimal(18,6) not null,
    spent_usd decimal(18,6) not null default 0,
    remaining_usd decimal(18,6) not null default 0,
    usage_percent decimal(5,2) not null default 0,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    unique key uk_monthly_budget_snapshots_scope (organization_id, project_id, environment, budget_month)
) engine=InnoDB;

create table operational_events (
    id char(36) primary key,
    organization_id char(36) not null,
    project_id char(36) null,
    environment varchar(20) null,
    event_type varchar(20) not null,
    event_code varchar(50) not null,
    message text not null,
    metadata_json json null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null default current_timestamp(6)
) engine=InnoDB;

create index idx_projects_org on projects (organization_id);
create index idx_project_env_project on project_environments (project_id);
create index idx_project_api_keys_project on project_api_keys (project_id, environment);
create index idx_pricing_catalogs_active on pricing_catalogs (catalog_key, is_active, published_at desc);
create index idx_pricing_plans_lookup on pricing_plans (provider, model, effective_from desc);
create index idx_usage_events_project_time on usage_events (project_id, environment, occurred_at desc);
create index idx_usage_events_org_time on usage_events (organization_id, occurred_at desc);
create index idx_usage_events_provider_model on usage_events (provider, model, occurred_at desc);
create index idx_daily_usage_project_date on daily_usage_aggregates (project_id, environment, usage_date desc);
create index idx_budget_settings_scope on monthly_budget_settings (organization_id, project_id, environment);
create index idx_budget_snapshots_scope on monthly_budget_snapshots (organization_id, project_id, environment, budget_month);
create index idx_operational_events_scope on operational_events (project_id, environment, occurred_at desc);
