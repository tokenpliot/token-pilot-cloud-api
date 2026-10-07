-- MANUAL REVIEW DRAFT, run after V5 on a clone first. Not a Flyway automatic data migration.
-- Set @issue9_run_id, @issue9_cutoff_utc, @issue9_lower_id (exclusive), @issue9_upper_id (inclusive)
-- to an approved run manifest in this session. UUID ordering is NOT chronological.
-- Unset variables cause no writes; there are intentionally no example production defaults here.
-- Record row count and PK watermark externally after each committed small batch.
start transaction;
update usage_events
set sdk_reported_cost_json = json_object(
        'provenance', 'legacy-v0-unverified',
        'promptCostUsd', cast(prompt_cost_usd as char),
        'completionCostUsd', cast(completion_cost_usd as char),
        'reasoningCostUsd', cast(reasoning_cost_usd as char),
        'cachedPromptCostUsd', cast(cached_prompt_cost_usd as char),
        'totalCostUsd', cast(total_cost_usd as char),
        'pricingVersion', pricing_version,
        'sourceType', source_type),
    backfill_run_id = @issue9_run_id
where @issue9_run_id is not null and @issue9_run_id <> ''
  and created_at <= @issue9_cutoff_utc
  and id > @issue9_lower_id and id <= @issue9_upper_id
  and schema_version = 'legacy-v0' and accounting_status = 'LEGACY_UNVERIFIED'
  and sdk_reported_cost_json is null and backfill_run_id is null;
select row_count() as copied_evidence_count;
commit;
-- Do NOT infer call success, actor identity, rate units, or actual/priced from a legacy non-null cost.
-- Verification creates new immutable snapshots/accounting revisions using external evidence.
-- Rollback is application feature rollback; evidence remains for audit, not DELETE or DROP.
