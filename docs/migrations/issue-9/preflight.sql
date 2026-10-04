-- Read-only preflight; run on a production clone. A nonempty finding needs triage, never automatic reassignment.
select version() as mysql_version, @@character_set_database as db_charset, @@collation_database as db_collation;
select installed_rank, version, description, checksum, success from flyway_schema_history order by installed_rank;

-- Tenant ownership, orphans, environment scope.
select 'project_org_missing' as finding, p.id from projects p
left join organizations o on o.id = p.organization_id where o.id is null;
select 'environment_tenant_mismatch' as finding, e.id from project_environments e
left join projects p on p.id = e.project_id
where p.id is null or p.organization_id <> e.organization_id;
select 'key_tenant_or_environment_mismatch' as finding, k.id from project_api_keys k
left join projects p on p.id = k.project_id
where p.id is null or p.organization_id <> k.organization_id
   or k.environment = ''
   or (k.environment is not null and not exists (
       select 1 from project_environments e where e.organization_id = k.organization_id
       and e.project_id = k.project_id and e.environment = k.environment));
select 'usage_tenant_or_key_or_environment_mismatch' as finding, u.id from usage_events u
left join projects p on p.id = u.project_id
left join project_api_keys k on k.id = u.api_key_id
where p.id is null or p.organization_id <> u.organization_id
   or (u.api_key_id is not null and (k.id is null or k.project_id <> u.project_id
       or k.organization_id <> u.organization_id
       or (k.environment is not null and k.environment <> '' and k.environment <> u.environment)))
   or not exists (select 1 from project_environments e where e.project_id = u.project_id
       and e.organization_id = u.organization_id and e.environment = u.environment);
select 'budget_scope_mismatch' as finding, b.id from monthly_budget_settings b
left join projects p on p.id = b.project_id
left join organizations o on o.id = b.organization_id
where o.id is null or (b.project_id is not null and (p.id is null or p.organization_id <> b.organization_id))
   or (b.environment is not null and (b.project_id is null or b.environment = '' or not exists (
       select 1 from project_environments e where e.project_id = b.project_id
       and e.organization_id = b.organization_id and e.environment = b.environment)));
select organization_id, project_id, environment, count(*) as duplicate_count
from monthly_budget_settings group by organization_id, project_id, environment having count(*) > 1;

-- These are evidence gaps, not proof that a row can be corrected using the new inclusive semantics.
select id, prompt_tokens, completion_tokens, cached_prompt_tokens, reasoning_tokens, total_tokens
from usage_events where prompt_tokens < 0 or completion_tokens < 0 or cached_prompt_tokens < 0 or reasoning_tokens < 0
   or cached_prompt_tokens > prompt_tokens or reasoning_tokens > completion_tokens
   or cast(total_tokens as decimal(30,0)) <> cast(prompt_tokens as decimal(30,0)) + cast(completion_tokens as decimal(30,0));
select id from usage_events where total_cost_usd < 0 or prompt_cost_usd < 0 or completion_cost_usd < 0
    or reasoning_cost_usd < 0 or cached_prompt_cost_usd < 0 or request_id is null or request_id = '';
select u.id as usage_id, u.pricing_plan_id from usage_events u
left join pricing_plans p on p.id = u.pricing_plan_id
where u.pricing_plan_id is not null and (p.id is null or p.provider <> u.provider or p.model <> u.model
    or p.version <> u.pricing_version or p.currency <> 'USD');
select id, currency, effective_from, effective_to from pricing_plans
where currency <> 'USD' or prompt_rate < 0 or completion_rate < 0 or reasoning_rate < 0 or cached_prompt_rate < 0
   or (effective_to is not null and effective_to <= effective_from);
select a.id as first_plan, b.id as overlapping_plan from pricing_plans a join pricing_plans b
on a.id < b.id and a.provider = b.provider and a.model = b.model and a.currency = b.currency
and (b.effective_to is null or a.effective_from < b.effective_to)
and (a.effective_to is null or b.effective_from < a.effective_to);

-- Baseline for before/after reconciliation; save results outside the working DB.
select organization_id, project_id, environment, date(occurred_at) as usage_date,
       count(*) as event_count, sum(total_cost_usd) as legacy_cost_usd, sum(total_tokens) as legacy_tokens
from usage_events group by organization_id, project_id, environment, date(occurred_at);
